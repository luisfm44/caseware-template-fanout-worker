package io.github.luisfm44.caseware.worker.durable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import io.github.luisfm44.caseware.worker.durable.AttemptTransport.Outcome;
import io.github.luisfm44.caseware.worker.durable.PostgresJobQueue.Claim;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres-worker")
final class DurableWorkerRunner implements ApplicationRunner {

    private static final System.Logger LOGGER = System.getLogger(DurableWorkerRunner.class.getName());

    private final Environment environment;

    DurableWorkerRunner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        int capacity = environment.getRequiredProperty("worker.capacity", Integer.class);
        var claimTtl = milliseconds("worker.lease-ms", 2_000);
        var idleDelay = milliseconds("worker.idle-ms", 250);
        var timeout = milliseconds("worker.http-timeout-ms", 1_000);
        var downstreamOrigin = URI.create(environment.getRequiredProperty("FANOUT_DOWNSTREAM_URL"));
        var jobs = new PostgresJobQueue(dataSource(), capacity, claimTtl);

        try (var client = HttpClient.newBuilder().connectTimeout(timeout).build()) {
            consume(jobs, new HttpAttemptTransport(client, downstreamOrigin, timeout), idleDelay);
        }
    }

    private PGSimpleDataSource dataSource() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(environment.getProperty("FANOUT_DB_URL", "jdbc:postgresql://127.0.0.1:55432/fanout"));
        dataSource.setUser(environment.getProperty("FANOUT_DB_USER", "fanout"));
        dataSource.setPassword(environment.getRequiredProperty("FANOUT_DB_PASSWORD"));
        dataSource.setConnectTimeout(5);
        dataSource.setSocketTimeout(10);
        return dataSource;
    }

    private Duration milliseconds(String property, long fallback) {
        long milliseconds = environment.getProperty(property, Long.class, fallback);
        if (milliseconds <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return Duration.ofMillis(milliseconds);
    }

    static void consume(PostgresJobQueue jobs, AttemptTransport transport, Duration idleDelay) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var claim = jobs.claimReady();
                if (claim.isPresent()) {
                    process(jobs, transport, claim.get());
                } else {
                    Thread.sleep(idleDelay);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static void process(PostgresJobQueue jobs, AttemptTransport transport, Claim claim)
            throws InterruptedException {
        var attempt = claim.attemptId();

        AttemptTransport.Reply reply;
        try {
            reply = claim.reconciling()
                    ? transport.resolve(claim.operationId(), attempt)
                    : transport.submit(claim.operationId(), attempt);
        } catch (IOException unavailable) {
            if (claim.pollCount() == 0) {
                LOGGER.log(System.Logger.Level.WARNING, "Downstream outcome is uncertain; will reconcile", unavailable);
            }
            jobs.defer(claim, backoff(claim.pollCount()));
            return;
        }

        if (reply.outcome() == Outcome.PENDING) {
            jobs.defer(claim, delay(claim.pollCount(), reply.retryAfter()));
        } else {
            jobs.settle(claim, attempt, reply.outcome(), delay(claim.submissions() - 1, reply.retryAfter()));
        }
    }

    private static Duration delay(int previousAttempts, Duration retryAfter) {
        var backoff = backoff(previousAttempts);
        return retryAfter.compareTo(backoff) > 0 ? retryAfter : backoff;
    }

    private static Duration backoff(int previousAttempts) {
        return Duration.ofMillis(100L << Math.min(previousAttempts, 5));
    }
}

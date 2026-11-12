package io.github.luisfm44.caseware.worker.bootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import io.github.luisfm44.caseware.worker.adapter.http.HttpAttemptTransport;
import io.github.luisfm44.caseware.worker.adapter.postgres.PostgresJobQueue;
import io.github.luisfm44.caseware.worker.execution.service.DurableWorker;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres-worker")
final class DurableWorkerRunner implements ApplicationRunner {

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
        // A recovered attempt gets the full HTTP deadline plus the configured claim margin to record the result.
        var jobs = new PostgresJobQueue(dataSource(), capacity, claimTtl, timeout.plus(claimTtl));

        try (var client = HttpClient.newBuilder().connectTimeout(timeout).build()) {
            new DurableWorker(jobs, new HttpAttemptTransport(client, downstreamOrigin, timeout), idleDelay).consume();
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
}

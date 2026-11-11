package io.github.luisfm44.caseware.worker.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.github.luisfm44.caseware.worker.DownstreamClient;
import io.github.luisfm44.caseware.worker.OperationId;
import io.github.luisfm44.caseware.worker.retry.DownstreamFailure.Reason;
import io.github.luisfm44.caseware.worker.retry.DownstreamTransport.Outcome;
import io.github.luisfm44.caseware.worker.retry.DownstreamTransport.Reply;
import io.github.luisfm44.caseware.worker.retry.DownstreamTransport.Resolution;

public final class RetryingDownstreamClient implements DownstreamClient {

    private static final System.Logger LOGGER = System.getLogger(RetryingDownstreamClient.class.getName());
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 100;

    private final DownstreamTransport transport;
    private final Function<Duration, CompletionStage<Void>> delay;

    public RetryingDownstreamClient(DownstreamTransport transport) {
        this(transport, duration -> CompletableFuture.runAsync(() -> {},
                CompletableFuture.delayedExecutor(duration.toNanos(), TimeUnit.NANOSECONDS)));
    }

    RetryingDownstreamClient(DownstreamTransport transport, Function<Duration, CompletionStage<Void>> delay) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    @Override
    public CompletionStage<Void> start(OperationId operationId) {
        return submit(Objects.requireNonNull(operationId, "operationId"), 1);
    }

    private CompletionStage<Void> submit(OperationId operationId, int attempt) {
        CompletionStage<Reply> response;
        try {
            response = Objects.requireNonNull(transport.submit(operationId), "submission response");
        } catch (RuntimeException failure) {
            return reconcile(operationId, attempt);
        }

        return response.handle((reply, failure) -> {
            if (failure != null || reply == null) {
                return reconcile(operationId, attempt);
            }
            return switch (reply.outcome()) {
                case APPLIED -> CompletableFuture.<Void>completedFuture(null);
                case THROTTLED, TRANSIENT_FAILURE -> retry(operationId, attempt, reply.outcome(), reply.retryAfter());
                case UNCONFIRMED -> reconcile(operationId, attempt);
                case REJECTED -> terminalFailure(Reason.REJECTED, attempt, Outcome.REJECTED);
            };
        }).thenCompose(Function.identity());
    }

    private CompletionStage<Void> reconcile(OperationId operationId, int attempt) {
        return authoritativeResolution(operationId).thenCompose(resolution -> switch (resolution) {
            case APPLIED -> CompletableFuture.completedFuture(null);
            case NOT_APPLIED -> retry(operationId, attempt, Outcome.UNCONFIRMED, Duration.ZERO);
            case REJECTED -> terminalFailure(Reason.REJECTED, attempt, Outcome.REJECTED);
        });
    }

    private CompletionStage<Resolution> authoritativeResolution(OperationId operationId) {
        try {
            return transport.reconcile(operationId).handle((resolution, failure) -> {
                if (failure != null || resolution == null) {
                    return unknownResolution();
                }
                return CompletableFuture.completedFuture(resolution);
            }).thenCompose(Function.identity());
        } catch (RuntimeException failure) {
            return unknownResolution();
        }
    }

    private static CompletableFuture<Resolution> unknownResolution() {
        // A broken status adapter cannot certify that the submission is safe to release.
        LOGGER.log(System.Logger.Level.WARNING, "Downstream status lookup returned no authoritative outcome");
        return new CompletableFuture<>();
    }

    private CompletionStage<Void> retry(OperationId operationId, int attempt, Outcome outcome, Duration retryAfter) {
        if (attempt >= MAX_ATTEMPTS) {
            return terminalFailure(Reason.RETRY_LIMIT, attempt, outcome);
        }

        long maximumMillis = INITIAL_BACKOFF_MILLIS << (attempt - 1);
        var backoff = Duration.ofMillis(ThreadLocalRandom.current().nextLong(maximumMillis + 1));
        var pause = backoff.compareTo(retryAfter) < 0 ? retryAfter : backoff;
        return delay.apply(pause).thenCompose(ignored -> submit(operationId, attempt + 1));
    }

    private static CompletionStage<Void> terminalFailure(Reason reason, int attempts, Outcome lastOutcome) {
        return CompletableFuture.failedFuture(new DownstreamFailure(reason, attempts, lastOutcome));
    }
}

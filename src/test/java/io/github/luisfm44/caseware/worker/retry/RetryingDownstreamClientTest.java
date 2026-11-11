package io.github.luisfm44.caseware.worker.retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;

import io.github.luisfm44.caseware.worker.AdmissionController;
import io.github.luisfm44.caseware.worker.FanoutWorker;
import io.github.luisfm44.caseware.worker.InMemoryAdmissionController;
import io.github.luisfm44.caseware.worker.OperationId;
import io.github.luisfm44.caseware.worker.Permit;
import io.github.luisfm44.caseware.worker.retry.DownstreamTransport.Outcome;
import io.github.luisfm44.caseware.worker.retry.DownstreamTransport.Reply;
import io.github.luisfm44.caseware.worker.retry.DownstreamTransport.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryingDownstreamClientTest {

    private static final OperationId OPERATION = new OperationId("firm-1", "engagement-1", "release-7", "revision-1");
    private static final OperationId NEXT = new OperationId("firm-1", "engagement-2", "release-7", "revision-1");

    private final InMemoryAdmissionController admission = new InMemoryAdmissionController(1);
    private final ScriptedTransport transport = new ScriptedTransport();
    private final List<Duration> delays = new CopyOnWriteArrayList<>();
    private final FanoutWorker worker = workerUsing(admission);

    @Test
    void reconcilesTheLostReplyWithoutApplyingTheOperationAgain() throws Exception {
        Map<OperationId, Integer> effects = new ConcurrentHashMap<>();
        transport.submissions.add(id -> {
            effects.merge(id, 1, Integer::sum);
            return completedFuture(new Reply(Outcome.UNCONFIRMED));
        });
        transport.reconciliations.add(id -> completedFuture(
                effects.containsKey(id) ? Resolution.APPLIED : Resolution.NOT_APPLIED));

        worker.tryStart(OPERATION).orElseThrow().get(5, SECONDS);

        assertThat(effects).containsExactlyEntriesOf(Map.of(OPERATION, 1));
        assertThat(transport.submitted).containsExactly(OPERATION);
        assertThat(transport.reconciled).containsExactly(OPERATION);
        assertThat(delays).isEmpty();
    }

    @Test
    void callerTimeoutKeepsTheSlotUntilAuthoritativeResolutionArrives() throws Exception {
        var resolution = new CompletableFuture<Resolution>();
        var lookupStarted = new CompletableFuture<Void>();
        var permitReleased = new CompletableFuture<Void>();
        var observingWorker = workerUsing(id -> admission.tryAcquire(id).map(permit -> new Permit() {
            @Override
            public OperationId operationId() {
                return permit.operationId();
            }

            @Override
            public void close() {
                permit.close();
                permitReleased.complete(null);
            }
        }));
        transport.submissions.add(id -> failedFuture(new TimeoutException("Dispatch reply timed out")));
        transport.reconciliations.add(id -> {
            lookupStarted.complete(null);
            return resolution;
        });

        var caller = observingWorker.tryStart(OPERATION).orElseThrow();
        lookupStarted.get(5, SECONDS);
        caller.orTimeout(0, NANOSECONDS);
        assertInstanceOf(TimeoutException.class, failureCause(caller));

        assertThat(permitReleased).isNotDone();
        assertThat(worker.tryStart(NEXT)).isEmpty();
        assertThat(transport.submitted).containsExactly(OPERATION);
        assertThat(transport.reconciled).containsExactly(OPERATION);

        resolution.complete(Resolution.APPLIED);
        permitReleased.get(5, SECONDS);
        admission.tryAcquire(NEXT).orElseThrow().close();
    }

    @Test
    void retriesTheSameKeyAfterTheTimeoutIsConfirmedToHaveNoEffect() throws Exception {
        transport.submissions.add(id -> failedFuture(new TimeoutException("Dispatch reply timed out")));
        transport.reconciliations.add(id -> completedFuture(Resolution.NOT_APPLIED));
        transport.submissions.add(id -> completedFuture(new Reply(Outcome.APPLIED)));

        worker.tryStart(OPERATION).orElseThrow().get(5, SECONDS);

        assertThat(transport.submitted).containsExactly(OPERATION, OPERATION);
        assertThat(transport.reconciled).containsExactly(OPERATION);
        assertThat(delays).hasSize(1);
    }

    @Test
    void exhaustsRetriesWhenEveryTimeoutIsConfirmedToHaveNoEffect() {
        for (int attempt = 0; attempt < 3; attempt++) {
            transport.submissions.add(id -> failedFuture(new TimeoutException("Dispatch reply timed out")));
            transport.reconciliations.add(id -> completedFuture(Resolution.NOT_APPLIED));
        }

        var failure = assertInstanceOf(DownstreamFailure.class,
                failureCause(worker.tryStart(OPERATION).orElseThrow()));

        assertThat(failure.reason()).isEqualTo(DownstreamFailure.Reason.RETRY_LIMIT);
        assertThat(failure.attempts()).isEqualTo(3);
        assertThat(failure.lastOutcome()).isEqualTo(Outcome.UNCONFIRMED);
        assertThat(transport.submitted).containsExactly(OPERATION, OPERATION, OPERATION);
        assertThat(transport.reconciled).containsExactly(OPERATION, OPERATION, OPERATION);
        assertThat(delays).hasSize(2);
        admission.tryAcquire(NEXT).orElseThrow().close();
    }

    @Test
    void honorsRetryAfterAndStopsAfterThreeThrottledSubmissions() throws Exception {
        var retryAfter = Duration.ofSeconds(30);
        for (int attempt = 0; attempt < 3; attempt++) {
            transport.submissions.add(id -> completedFuture(new Reply(Outcome.THROTTLED, retryAfter)));
        }

        var failure = assertInstanceOf(DownstreamFailure.class,
                failureCause(worker.tryStart(OPERATION).orElseThrow()));

        assertThat(failure.reason()).isEqualTo(DownstreamFailure.Reason.RETRY_LIMIT);
        assertThat(failure.attempts()).isEqualTo(3);
        assertThat(failure.lastOutcome()).isEqualTo(Outcome.THROTTLED);
        assertThat(transport.submitted).containsExactly(OPERATION, OPERATION, OPERATION);
        assertThat(transport.reconciled).isEmpty();
        assertThat(delays).hasSize(2).allSatisfy(delay -> assertThat(delay).isGreaterThanOrEqualTo(retryAfter));
        admission.tryAcquire(NEXT).orElseThrow().close();
    }

    @Test
    void surfacesPermanentRejectionWithoutRetrying() throws Exception {
        transport.submissions.add(id -> completedFuture(new Reply(Outcome.REJECTED)));

        var failure = assertInstanceOf(DownstreamFailure.class,
                failureCause(worker.tryStart(OPERATION).orElseThrow()));

        assertThat(failure.reason()).isEqualTo(DownstreamFailure.Reason.REJECTED);
        assertThat(failure.attempts()).isEqualTo(1);
        assertThat(failure.lastOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(transport.submitted).containsExactly(OPERATION);
        assertThat(transport.reconciled).isEmpty();
        assertThat(delays).isEmpty();
        admission.tryAcquire(NEXT).orElseThrow().close();
    }

    @Test
    void retriesAConfirmedTransientFailureUsingTheDefaultScheduler() throws Exception {
        var scheduledWorker = new FanoutWorker(admission, new RetryingDownstreamClient(transport));
        transport.submissions.add(id -> completedFuture(new Reply(Outcome.TRANSIENT_FAILURE)));
        transport.submissions.add(id -> completedFuture(new Reply(Outcome.APPLIED)));

        scheduledWorker.tryStart(OPERATION).orElseThrow().get(5, SECONDS);

        assertThat(transport.submitted).containsExactly(OPERATION, OPERATION);
        assertThat(transport.reconciled).isEmpty();
        admission.tryAcquire(NEXT).orElseThrow().close();
    }

    @ParameterizedTest
    @MethodSource("unusableSubmissions")
    void reconcilesSubmissionsWithoutAUsableReply(Function<OperationId, CompletionStage<Reply>> submit)
            throws Exception {
        transport.submissions.add(submit);
        transport.reconciliations.add(id -> completedFuture(Resolution.APPLIED));

        worker.tryStart(OPERATION).orElseThrow().get(5, SECONDS);

        assertThat(transport.submitted).containsExactly(OPERATION);
        assertThat(transport.reconciled).containsExactly(OPERATION);
        assertThat(delays).isEmpty();
        admission.tryAcquire(NEXT).orElseThrow().close();
    }

    static Stream<Function<OperationId, CompletionStage<Reply>>> unusableSubmissions() {
        return Stream.of(
                id -> {
                    throw new IllegalStateException("Transport threw before providing a reply stage");
                },
                id -> null,
                id -> completedFuture(null));
    }

    @Test
    void surfacesARejectionConfirmedByStatusWithoutResubmitting() {
        transport.submissions.add(id -> completedFuture(new Reply(Outcome.UNCONFIRMED)));
        transport.reconciliations.add(id -> completedFuture(Resolution.REJECTED));

        var failure = assertInstanceOf(DownstreamFailure.class,
                failureCause(worker.tryStart(OPERATION).orElseThrow()));

        assertThat(failure.reason()).isEqualTo(DownstreamFailure.Reason.REJECTED);
        assertThat(failure.attempts()).isEqualTo(1);
        assertThat(failure.lastOutcome()).isEqualTo(Outcome.REJECTED);
        assertThat(transport.submitted).containsExactly(OPERATION);
        assertThat(transport.reconciled).containsExactly(OPERATION);
        assertThat(delays).isEmpty();
        admission.tryAcquire(NEXT).orElseThrow().close();
    }

    @ParameterizedTest
    @MethodSource("nonAuthoritativeLookups")
    void keepsThePermitWhenTheStatusAdapterCannotConfirmAnOutcome(
            Function<OperationId, CompletionStage<Resolution>> lookup) {
        transport.submissions.add(id -> completedFuture(new Reply(Outcome.UNCONFIRMED)));
        transport.reconciliations.add(lookup);

        var result = worker.tryStart(OPERATION).orElseThrow();

        assertThat(result).isNotDone();
        assertThat(worker.tryStart(NEXT)).isEmpty();
        assertThat(transport.submitted).containsExactly(OPERATION);
        assertThat(transport.reconciled).containsExactly(OPERATION);
        assertThat(delays).isEmpty();
    }

    static Stream<Function<OperationId, CompletionStage<Resolution>>> nonAuthoritativeLookups() {
        return Stream.of(
                id -> failedFuture(new IllegalStateException("Status temporarily unavailable")),
                id -> completedFuture(null),
                id -> {
                    throw new IllegalStateException("Status lookup could not start");
                });
    }

    @Test
    void rejectsNegativeRetryAfter() {
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> new Reply(Outcome.THROTTLED, Duration.ofMillis(-1))))
                .hasMessage("retryAfter must be non-negative");
    }

    private FanoutWorker workerUsing(AdmissionController controller) {
        return new FanoutWorker(controller, new RetryingDownstreamClient(transport, duration -> {
            delays.add(duration);
            return completedFuture(null);
        }));
    }

    private static Throwable failureCause(CompletableFuture<Void> result) {
        return assertThrows(ExecutionException.class, () -> result.get(5, SECONDS)).getCause();
    }

    private static final class ScriptedTransport implements DownstreamTransport {
        private final List<OperationId> submitted = new CopyOnWriteArrayList<>();
        private final List<OperationId> reconciled = new CopyOnWriteArrayList<>();
        private final Queue<Function<OperationId, CompletionStage<Reply>>> submissions = new ConcurrentLinkedQueue<>();
        private final Queue<Function<OperationId, CompletionStage<Resolution>>> reconciliations =
                new ConcurrentLinkedQueue<>();

        @Override
        public CompletionStage<Reply> submit(OperationId id) {
            submitted.add(id);
            return submissions.remove().apply(id);
        }

        @Override
        public CompletionStage<Resolution> reconcile(OperationId id) {
            reconciled.add(id);
            return reconciliations.remove().apply(id);
        }
    }
}

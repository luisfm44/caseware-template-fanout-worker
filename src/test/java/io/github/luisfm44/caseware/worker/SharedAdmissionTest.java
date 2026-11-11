package io.github.luisfm44.caseware.worker;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedAdmissionTest {

    private final AdmissionController admission = new InMemoryAdmissionController(3);
    private final FakeDownstreamClient downstream = new FakeDownstreamClient();
    private final FanoutWorker firstWorker = new FanoutWorker(admission, downstream);
    private final FanoutWorker secondWorker = new FanoutWorker(admission, downstream);

    @Test
    void twoWorkersCompeteForTheSameLastSlot() throws Exception {
        var first = firstWorker.tryStart(operation("1")).orElseThrow();
        var second = secondWorker.tryStart(operation("2")).orElseThrow();

        var startTogether = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var thirdAttempt = executor.submit(() -> {
                startTogether.await(5, SECONDS);
                return firstWorker.tryStart(operation("3"));
            });
            var fourthAttempt = executor.submit(() -> {
                startTogether.await(5, SECONDS);
                return secondWorker.tryStart(operation("4"));
            });
            var third = thirdAttempt.get(5, SECONDS);
            var fourth = fourthAttempt.get(5, SECONDS);

            assertNotEquals(third.isPresent(), fourth.isPresent(), "Exactly one slot was available");
            assertEquals(3, downstream.inFlight());
            assertEquals(3, downstream.peak());

            downstream.complete(operation("1"));
            first.get(5, SECONDS);

            // Retry the rejected operation on the same worker with the same identity.
            var admittedThird = third.orElseGet(() -> firstWorker.tryStart(operation("3")).orElseThrow());
            var admittedFourth = fourth.orElseGet(() -> secondWorker.tryStart(operation("4")).orElseThrow());

            assertEquals(3, downstream.inFlight());
            assertEquals(3, downstream.peak());
            downstream.complete(operation("2"));
            downstream.complete(operation("3"));
            downstream.complete(operation("4"));
            CompletableFuture.allOf(second, admittedThird, admittedFourth).get(5, SECONDS);
            assertEquals(0, downstream.inFlight());
        }
    }

    @Test
    void callerTimeoutKeepsTheSlotUntilTheDownstreamFinishes() throws Exception {
        var timedOut = firstWorker.tryStart(operation("1")).orElseThrow();
        var second = secondWorker.tryStart(operation("2")).orElseThrow();
        var third = firstWorker.tryStart(operation("3")).orElseThrow();

        timedOut.orTimeout(0, NANOSECONDS);
        var failure = assertThrows(ExecutionException.class, () -> timedOut.get(5, SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());

        assertEquals(3, downstream.inFlight());
        assertTrue(firstWorker.tryStart(operation("4")).isEmpty());
        assertTrue(secondWorker.tryStart(operation("4")).isEmpty());

        downstream.complete(operation("1"));
        var fourth = secondWorker.tryStart(operation("4")).orElseThrow();

        assertEquals(3, downstream.inFlight());
        assertEquals(3, downstream.peak());
        downstream.complete(operation("2"));
        downstream.complete(operation("3"));
        downstream.complete(operation("4"));
        CompletableFuture.allOf(second, third, fourth).get(5, SECONDS);
        assertEquals(0, downstream.inFlight());
    }

    @Test
    void failedDispatchAndConfirmedTerminalFailureReleaseCapacity() throws Exception {
        var oneSlot = new InMemoryAdmissionController(1);
        var operation = operation("1");
        var dispatchFailure = new IllegalStateException("Dispatch rejected");
        var rejectingWorker = new FanoutWorker(oneSlot, id -> {
            throw dispatchFailure;
        });

        assertSame(dispatchFailure, assertThrows(IllegalStateException.class,
                () -> rejectingWorker.tryStart(operation)));

        var terminal = new CompletableFuture<Void>();
        var worker = new FanoutWorker(oneSlot, id -> terminal.minimalCompletionStage());
        var result = worker.tryStart(operation).orElseThrow();
        assertTrue(oneSlot.tryAcquire(operation("2")).isEmpty());

        var terminalFailure = new IllegalStateException("Execution failed");
        terminal.completeExceptionally(terminalFailure);
        assertSame(terminalFailure, assertThrows(ExecutionException.class,
                () -> result.get(5, SECONDS)).getCause());
        oneSlot.tryAcquire(operation("2")).orElseThrow().close();
    }

    @Test
    void closingTheSamePermitTwiceOnlyReturnsOneSlot() {
        var oneSlot = new InMemoryAdmissionController(1);
        var permit = oneSlot.tryAcquire(operation("1")).orElseThrow();
        permit.close();

        try (var otherPermit = oneSlot.tryAcquire(operation("2")).orElseThrow()) {
            permit.close();
            assertTrue(oneSlot.tryAcquire(operation("3")).isEmpty());
        }
    }

    private static OperationId operation(String engagement) {
        return new OperationId("firm-1", engagement, "release-7", "revision-1");
    }
}

package io.github.luisfm44.caseware.worker.execution.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport.Outcome;

/** Durable execution state and capacity reservations for the worker. */
public interface JobQueue {

    /** Reclaims a due attempt or reserves capacity and claims a fresh one. */
    Optional<Claim> claimReady();

    /** Releases the claim for a later poll while retaining the attempt and its reservation. */
    void defer(Claim claim, Duration delay);

    /**
     * Records a confirmed outcome and releases its reservation if the claim token and attempt still match.
     * Returns false when the claim or attempt is no longer current. Lease expiry alone does not prevent settlement.
     */
    boolean settle(Claim claim, UUID attemptId, Outcome outcome, Duration nextDelay);

    record Claim(OperationId operationId, UUID token, UUID attemptId, int submissions, int pollCount,
            boolean reconciling) {}
}

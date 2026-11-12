package io.github.luisfm44.caseware.worker.execution.port;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport.Outcome;

/** Durable execution state and capacity reservations for the worker. */
public interface JobQueue {

    /** Reclaims a due attempt first, with fresh admission as fallback. */
    default Optional<Claim> claimReady() {
        return claimReady(ClaimPreference.RECONCILIATION);
    }

    /** Tries the preferred kind of claim first, falling back to the other kind when unavailable. */
    Optional<Claim> claimReady(ClaimPreference preference);

    /**
     * Releases the claim for a later poll while retaining the attempt and its reservation.
     * Returns false when the claim token or attempt has changed. Lease expiry alone does not prevent deferral.
     */
    boolean defer(Claim claim, Duration delay);

    /**
     * Records a confirmed outcome and releases its reservation if the claim token and attempt still match.
     * Returns false when the claim or attempt is no longer current. Lease expiry alone does not prevent settlement.
     */
    boolean settle(Claim claim, UUID attemptId, Outcome outcome, Duration nextDelay);

    /** Connectivity was interrupted and the last operation might have committed. */
    final class Unavailable extends IllegalStateException {
        public Unavailable(Throwable cause) {
            super("Lost connectivity to the durable job queue", cause);
        }
    }

    enum ClaimPreference {
        RECONCILIATION,
        ADMISSION
    }

    record Claim(OperationId operationId, UUID token, UUID attemptId, int submissions, int pollCount,
            boolean reconciling) {}
}

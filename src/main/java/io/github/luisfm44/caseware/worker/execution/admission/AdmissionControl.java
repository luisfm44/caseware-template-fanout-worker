package io.github.luisfm44.caseware.worker.execution.admission;

import java.util.Optional;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;

/** The workers sharing a downstream capacity pool use the same admission authority. */
public interface AdmissionControl {

    /** Atomically reserves one slot, or returns empty immediately when the pool is full. */
    Optional<Permit> tryAcquire(OperationId operationId);
}

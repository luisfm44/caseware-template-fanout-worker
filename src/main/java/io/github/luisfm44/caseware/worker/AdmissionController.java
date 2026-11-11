package io.github.luisfm44.caseware.worker;

import java.util.Optional;

/** The workers sharing a downstream capacity pool use the same admission authority. */
public interface AdmissionController {

    /** Atomically reserves one slot, or returns empty immediately when the pool is full. */
    Optional<Permit> tryAcquire(OperationId operationId);
}

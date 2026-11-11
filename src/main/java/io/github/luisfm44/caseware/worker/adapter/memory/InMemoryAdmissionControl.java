package io.github.luisfm44.caseware.worker.adapter.memory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.luisfm44.caseware.worker.execution.admission.AdmissionControl;
import io.github.luisfm44.caseware.worker.execution.admission.Permit;
import io.github.luisfm44.caseware.worker.execution.model.OperationId;

/** Shares downstream capacity between workers in one JVM. */
public final class InMemoryAdmissionControl implements AdmissionControl {

    private final Semaphore slots;

    public InMemoryAdmissionControl(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        slots = new Semaphore(capacity);
    }

    @Override
    public Optional<Permit> tryAcquire(OperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        if (!slots.tryAcquire()) {
            return Optional.empty();
        }
        return Optional.of(new Reservation(operationId));
    }

    private final class Reservation implements Permit {

        private final OperationId operationId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(OperationId operationId) {
            this.operationId = operationId;
        }

        @Override
        public OperationId operationId() {
            return operationId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                slots.release();
            }
        }
    }
}

package io.github.luisfm44.caseware.worker.execution.service;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.github.luisfm44.caseware.worker.execution.admission.AdmissionControl;
import io.github.luisfm44.caseware.worker.execution.admission.Permit;
import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.DownstreamClient;

public final class FanoutWorker {

    private final AdmissionControl admission;
    private final DownstreamClient downstream;

    public FanoutWorker(AdmissionControl admission, DownstreamClient downstream) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    /**
     * Returns empty when capacity is unavailable. The caller may time out or cancel
     * its own future independently of the downstream execution and its reservation.
     */
    public Optional<CompletableFuture<Void>> tryStart(OperationId operationId) {
        var reservation = admission.tryAcquire(operationId);
        if (reservation.isEmpty()) {
            return Optional.empty();
        }

        Permit permit = reservation.get();
        CompletionStage<Void> terminal;
        try {
            terminal = downstream.start(permit.operationId());
        } catch (RuntimeException | Error failure) {
            permit.close();
            throw failure;
        }

        var completion = terminal.whenComplete((result, failure) -> permit.close());
        return Optional.of(completion.toCompletableFuture().copy());
    }
}

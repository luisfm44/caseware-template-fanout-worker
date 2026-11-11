package io.github.luisfm44.caseware.worker;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class FanoutWorker {

    private final AdmissionController admission;
    private final DownstreamClient downstream;

    public FanoutWorker(AdmissionController admission, DownstreamClient downstream) {
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

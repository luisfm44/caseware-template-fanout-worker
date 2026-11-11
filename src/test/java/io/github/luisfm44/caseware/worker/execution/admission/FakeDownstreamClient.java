package io.github.luisfm44.caseware.worker.execution.admission;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.DownstreamClient;

final class FakeDownstreamClient implements DownstreamClient {

    private final Map<OperationId, CompletableFuture<Void>> active = new HashMap<>();
    private int peak;

    @Override
    public synchronized CompletionStage<Void> start(OperationId operationId) {
        var completion = new CompletableFuture<Void>();
        if (active.putIfAbsent(operationId, completion) != null) {
            throw new IllegalStateException("Operation is already active: " + operationId);
        }
        peak = Math.max(peak, active.size());
        return completion.minimalCompletionStage();
    }

    void complete(OperationId operationId) {
        CompletableFuture<Void> completion;
        synchronized (this) {
            completion = active.remove(operationId);
        }
        if (completion == null) {
            throw new IllegalStateException("Operation is not active: " + operationId);
        }
        completion.complete(null);
    }

    synchronized int inFlight() {
        return active.size();
    }

    synchronized int peak() {
        return peak;
    }
}

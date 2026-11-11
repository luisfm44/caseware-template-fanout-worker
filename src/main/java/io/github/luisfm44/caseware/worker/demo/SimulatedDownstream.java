package io.github.luisfm44.caseware.worker.demo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.DownstreamTransport;


/** A single-threaded downstream controlled by the demo runner. */
final class SimulatedDownstream implements DownstreamTransport {

    private final OperationId lostReply;
    private final Map<OperationId, CompletableFuture<Reply>> inFlight = new LinkedHashMap<>();
    private final Map<OperationId, Integer> effects = new HashMap<>();
    private int peakInFlight;
    private int dispatchAttempts;
    private int reconciliations;

    SimulatedDownstream(OperationId lostReply) {
        this.lostReply = Objects.requireNonNull(lostReply, "lostReply");
    }

    @Override
    public CompletionStage<Reply> submit(OperationId operationId) {
        dispatchAttempts++;
        if (effects.containsKey(operationId)) {
            return CompletableFuture.completedFuture(new Reply(Outcome.APPLIED));
        }

        var reply = inFlight.computeIfAbsent(operationId, ignored -> new CompletableFuture<>());
        peakInFlight = Math.max(peakInFlight, inFlight.size());
        return reply.minimalCompletionStage();
    }

    @Override
    public CompletionStage<Resolution> reconcile(OperationId operationId) {
        reconciliations++;
        if (effects.containsKey(operationId)) {
            return CompletableFuture.completedFuture(Resolution.APPLIED);
        }

        var reply = inFlight.get(operationId);
        if (reply != null) {
            // This simulator settles a pending reply only after recording its effect.
            return reply.handle((result, failure) -> Resolution.APPLIED);
        }
        return CompletableFuture.completedFuture(Resolution.NOT_APPLIED);
    }

    void completeBatch() {
        for (var operationId : List.copyOf(inFlight.keySet())) {
            var reply = inFlight.remove(operationId);
            effects.merge(operationId, 1, Integer::sum);
            if (lostReply.equals(operationId)) {
                reply.completeExceptionally(new TimeoutException("Simulated lost reply after the effect was applied"));
            } else {
                reply.complete(new Reply(Outcome.APPLIED));
            }
        }
    }

    int peakInFlight() {
        return peakInFlight;
    }

    int duplicateEffects() {
        return appliedEffects() - effects.size();
    }

    int dispatchAttempts() {
        return dispatchAttempts;
    }

    int reconciliations() {
        return reconciliations;
    }

    int appliedEffects() {
        return effects.values().stream().mapToInt(Integer::intValue).sum();
    }
}

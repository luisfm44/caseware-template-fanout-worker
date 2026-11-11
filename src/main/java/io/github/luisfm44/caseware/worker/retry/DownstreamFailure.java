package io.github.luisfm44.caseware.worker.retry;

import io.github.luisfm44.caseware.worker.retry.DownstreamTransport.Outcome;

/** A confirmed terminal failure for an operation. */
public final class DownstreamFailure extends RuntimeException {

    public enum Reason {
        REJECTED,
        RETRY_LIMIT
    }

    private final Reason reason;
    private final int attempts;
    private final Outcome lastOutcome;

    DownstreamFailure(Reason reason, int attempts, Outcome lastOutcome) {
        super("Downstream " + reason + " after " + attempts + " attempt(s). Last reply: " + lastOutcome);
        this.reason = reason;
        this.attempts = attempts;
        this.lastOutcome = lastOutcome;
    }

    public Reason reason() {
        return reason;
    }

    public int attempts() {
        return attempts;
    }

    public Outcome lastOutcome() {
        return lastOutcome;
    }
}

package io.github.luisfm44.caseware.worker.execution.port;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;


/** Transport operations below the worker's terminal completion contract. */
public interface DownstreamTransport {

    /**
     * Uses the operation ID as the downstream idempotency key on every submission.
     * A failed response stage leaves the submission outcome unconfirmed.
     */
    CompletionStage<Reply> submit(OperationId operationId);

    /**
     * Resolves an ambiguous submission by its operation ID. The adapter owns recovery from
     * lookup failures and keeps this stage pending while status is unavailable or execution
     * is still running. A missing record alone is insufficient for NOT_APPLIED if the
     * earlier submission can still arrive and execute.
     */
    CompletionStage<Resolution> reconcile(OperationId operationId);

    enum Outcome {
        APPLIED,
        THROTTLED,
        TRANSIENT_FAILURE,
        UNCONFIRMED,
        REJECTED
    }

    /**
     * THROTTLED and TRANSIENT_FAILURE certify that the submission has no effect and can no
     * longer execute. Use UNCONFIRMED if either point is uncertain. REJECTED is permanent.
     */
    record Reply(Outcome outcome, Duration retryAfter) {

        public Reply(Outcome outcome) {
            this(outcome, Duration.ZERO);
        }

        public Reply {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must be non-negative");
            }
        }
    }

    enum Resolution {
        APPLIED,
        /** The earlier submission had no effect and can no longer execute. */
        NOT_APPLIED,
        REJECTED
    }
}

package io.github.luisfm44.caseware.worker.execution.port;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;

/**
 * The downstream deduplicates effects by operation and can fence individual dispatch attempts.
 * An authoritative no-effect reply means the named attempt has had no effect and can never
 * execute, including if its request reaches the downstream after reconciliation.
 */
public interface AttemptTransport {

    Reply submit(OperationId operationId, UUID attemptId) throws IOException, InterruptedException;

    /**
     * Resolves the named attempt and may fence it against a late submission. A missing record
     * alone is inconclusive. Return PENDING while the downstream cannot certify the outcome.
     */
    Reply resolve(OperationId operationId, UUID attemptId) throws IOException, InterruptedException;

    enum Outcome {
        APPLIED, REJECTED, NOT_APPLIED, PENDING
    }

    record Reply(Outcome outcome, Duration retryAfter) {}
}

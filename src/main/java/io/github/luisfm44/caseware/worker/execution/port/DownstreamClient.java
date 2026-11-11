package io.github.luisfm44.caseware.worker.execution.port;

import java.util.concurrent.CompletionStage;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;

public interface DownstreamClient {

    /**
     * Dispatches an operation under a reserved slot. The returned stage settles only when the
     * downstream confirms that execution has ended or that the operation was never started.
     * Exceptional completion has the same terminal guarantee. A transport timeout after
     * possible dispatch requires reconciliation by operation ID before settling the stage.
     * A synchronous exception guarantees that no work was started by this call.
     */
    CompletionStage<Void> start(OperationId operationId);
}

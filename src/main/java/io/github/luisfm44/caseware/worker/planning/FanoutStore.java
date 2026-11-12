package io.github.luisfm44.caseware.worker.planning;

import java.util.List;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;

public interface FanoutStore {

    Checkpoint checkpoint(Publication publication);

    /**
     * Records the jobs, snapshot ID and next cursor in the same transaction. The first page
     * pins the snapshot. Existing jobs keep their identity and state. Returns false if
     * the expected checkpoint is no longer current.
     */
    boolean commitPage(Publication publication, Checkpoint expected, List<OperationId> jobs,
            String nextCursor, String snapshotId);

    record Checkpoint(long pagesCommitted, String nextCursor, boolean complete, String snapshotId) {}
}

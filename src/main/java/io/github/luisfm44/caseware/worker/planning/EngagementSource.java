package io.github.luisfm44.caseware.worker.planning;

import java.util.List;

public interface EngagementSource {

    /**
     * Reads up to limit eligible engagements. A first read uses a null snapshot ID and cursor
     * and returns the selected snapshot ID. Subsequent reads use that ID and its cursor.
     * Membership, revisions, ordering and cursor meanings stay stable within the snapshot
     * across process restarts. Throw SnapshotUnavailableException if it cannot be read.
     */
    Page fetch(Publication publication, String snapshotId, String cursor, int limit);

    record Engagement(String engagementId, String engagementRevision) {}

    /** A null next cursor marks the final page, which may be empty. */
    record Page(List<Engagement> engagements, String nextCursor, String snapshotId) {

        public Page {
            engagements = List.copyOf(engagements);
            if (snapshotId == null || snapshotId.isBlank()) {
                throw new IllegalArgumentException("snapshotId must be non-blank");
            }
        }
    }

    /** Planning cannot continue with the pinned source population. */
    final class SnapshotUnavailableException extends IllegalStateException {

        public SnapshotUnavailableException(String message) {
            super(message);
        }
    }
}

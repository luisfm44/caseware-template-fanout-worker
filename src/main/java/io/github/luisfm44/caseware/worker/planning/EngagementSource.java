package io.github.luisfm44.caseware.worker.planning;

import java.util.List;

public interface EngagementSource {

    /**
     * Reads up to limit eligible engagements. A null cursor starts the publication.
     * Membership, revisions, ordering and cursor meanings stay stable for that publication
     * across process restarts, so an uncommitted page can be read again.
     */
    Page fetch(Publication publication, String cursor, int limit);

    record Engagement(String engagementId, String engagementRevision) {}

    /** A null next cursor marks the final page, which may be empty. */
    record Page(List<Engagement> engagements, String nextCursor) {

        public Page {
            engagements = List.copyOf(engagements);
        }
    }
}

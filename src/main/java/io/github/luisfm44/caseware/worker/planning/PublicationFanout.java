package io.github.luisfm44.caseware.worker.planning;

import java.util.Objects;

import io.github.luisfm44.caseware.worker.OperationId;

/** Converts each bounded page of a publication into durable worker jobs. */
public final class PublicationFanout {

    private final EngagementSource source;
    private final FanoutStore store;
    private final int pageSize;

    public PublicationFanout(EngagementSource source, FanoutStore store, int pageSize) {
        this.source = Objects.requireNonNull(source, "source");
        this.store = Objects.requireNonNull(store, "store");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.pageSize = pageSize;
    }

    public void plan(Publication publication) {
        while (planNextPage(publication)) {
            // Each iteration starts from the last committed checkpoint.
        }
    }

    /** Commits one page, or returns false if planning is already complete. */
    public boolean planNextPage(Publication publication) {
        Objects.requireNonNull(publication, "publication");
        while (true) {
            var checkpoint = store.checkpoint(publication);
            if (checkpoint.complete()) {
                return false;
            }

            var cursor = checkpoint.nextCursor();
            var page = source.fetch(publication, cursor, pageSize);
            if (page.engagements().size() > pageSize) {
                throw new IllegalStateException("Engagement source exceeded the page size");
            }
            if (page.nextCursor() != null && page.nextCursor().equals(cursor)) {
                throw new IllegalStateException("Engagement source returned the current cursor");
            }

            var jobs = page.engagements().stream()
                    .map(engagement -> new OperationId(publication.firmId(), engagement.engagementId(),
                            publication.publicationId(), engagement.engagementRevision()))
                    .toList();
            if (store.commitPage(publication, checkpoint, jobs, page.nextCursor())) {
                return true;
            }
        }
    }
}

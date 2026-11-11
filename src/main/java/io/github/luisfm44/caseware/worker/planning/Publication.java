package io.github.luisfm44.caseware.worker.planning;

/** Identifies a publication within a firm. */
public record Publication(String firmId, String publicationId) {

    public Publication {
        if (firmId == null || firmId.isBlank() || publicationId == null || publicationId.isBlank()) {
            throw new IllegalArgumentException("firmId and publicationId must be non-blank");
        }
    }
}

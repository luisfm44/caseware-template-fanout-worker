package io.github.luisfm44.caseware.worker;

/** Identifies one engagement revision for a publication, across workers and attempts. */
public record OperationId(
        String firmId, String engagementId, String publicationId, String engagementRevision) {

    public OperationId {
        requireNonBlank(firmId, "firmId");
        requireNonBlank(engagementId, "engagementId");
        requireNonBlank(publicationId, "publicationId");
        requireNonBlank(engagementRevision, "engagementRevision");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}

package io.github.luisfm44.caseware.regional;

import java.util.Optional;
import java.util.UUID;

interface EngagementManager {

    Optional<Engagement> find(String firm, String engagement);

    boolean declined(String firm, String engagement, String baseline, String target);

    void rememberOffer(UUID receiptId, OfferReceipt offer);

    /** Compares the receipt to the current file and catalog and records or replays the decline atomically. */
    boolean declineOffer(String firm, String engagement, UUID receiptId);

    /** Compares the receipt to the current file and catalog and admits or replays the application atomically. */
    Optional<ApplyDecision> applyOffer(String firm, String engagement, UUID receiptId);

    Optional<ApplyDecision> application(String firm, String engagement, String decisionId);

    record Engagement(String product, String market, String confirmedBaseline, String revision) {}

    record OfferReceipt(String firm, String engagementId, Engagement engagement, String catalogRevision, String target) {}

    record ApplyDecision(String decisionId, ApplyStatus status) {}

    enum ApplyStatus {
        PENDING,
        FAILED,
        APPLIED
    }
}

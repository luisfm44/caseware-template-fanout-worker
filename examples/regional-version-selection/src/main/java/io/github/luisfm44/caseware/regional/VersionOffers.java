package io.github.luisfm44.caseware.regional;

import java.util.UUID;

import io.github.luisfm44.caseware.regional.EngagementManager.ApplyDecision;
import io.github.luisfm44.caseware.regional.EngagementManager.Engagement;
import io.github.luisfm44.caseware.regional.EngagementManager.OfferReceipt;
import io.github.luisfm44.caseware.regional.RegionalCatalog.CatalogSnapshot;
import io.github.luisfm44.caseware.regional.RegionalCatalog.UpgradePath;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
final class VersionOffers {

    private final RegionalCatalog catalog;
    private final EngagementManager engagements;

    VersionOffers(RegionalCatalog catalog, EngagementManager engagements) {
        this.catalog = catalog;
        this.engagements = engagements;
    }

    Offer offer(String firm, String engagementId) {
        var engagement = requireEngagement(firm, engagementId);
        var snapshot = catalog.current(engagement.product(), engagement.market());
        var baseline = engagement.confirmedBaseline();
        var target = eligibleTarget(snapshot, baseline);
        if (target == null || engagements.declined(firm, engagementId, baseline, target)) {
            return new Offer(snapshot.revision(), baseline, null, null, null);
        }

        var receiptId = UUID.randomUUID();
        engagements.rememberOffer(receiptId, new OfferReceipt(firm, engagementId, engagement, snapshot.revision(), target));
        return new Offer(snapshot.revision(), baseline, target, new SummaryPair(baseline, target), receiptId);
    }

    void decline(String firm, String engagementId, UUID receiptId) {
        requireEngagement(firm, engagementId);
        if (!engagements.declineOffer(firm, engagementId, requireReceiptId(receiptId))) {
            throw unavailableOffer();
        }
    }

    ApplyDecision apply(String firm, String engagementId, UUID receiptId) {
        requireEngagement(firm, engagementId);
        return engagements.applyOffer(firm, engagementId, requireReceiptId(receiptId))
                .orElseThrow(VersionOffers::unavailableOffer);
    }

    ApplyDecision application(String firm, String engagementId, String decisionId) {
        return engagements.application(firm, engagementId, decisionId).orElseThrow(VersionOffers::notFound);
    }

    private Engagement requireEngagement(String firm, String engagementId) {
        return engagements.find(firm, engagementId).orElseThrow(VersionOffers::notFound);
    }

    private static UUID requireReceiptId(UUID receiptId) {
        if (receiptId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An offer receipt is required");
        }
        return receiptId;
    }

    static String eligibleTarget(CatalogSnapshot catalog, String baseline) {
        if (baseline == null || catalog.designatedTargets().size() != 1) {
            return null;
        }

        var designated = catalog.designatedTargets().getFirst();
        var target = catalog.withdrawn().contains(designated) ? catalog.replacements().get(designated) : designated;
        if (target == null || target.equals(baseline) || catalog.withdrawn().contains(target)) {
            return null;
        }
        return catalog.approvedPaths().contains(new UpgradePath(baseline, target)) ? target : null;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static ResponseStatusException unavailableOffer() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The offer receipt cannot be used for this decision");
    }

    record Offer(String catalogRevision, String confirmedBaseline, String target, SummaryPair summary, UUID receiptId) {}

    record SummaryPair(String baseline, String target) {}
}

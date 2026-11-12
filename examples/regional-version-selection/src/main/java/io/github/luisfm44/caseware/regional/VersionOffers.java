package io.github.luisfm44.caseware.regional;

import io.github.luisfm44.caseware.regional.EngagementManager.ApplyDecision;
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
        var engagement = engagements.find(firm, engagementId).orElseThrow(VersionOffers::notFound);
        var snapshot = catalog.current(engagement.product(), engagement.market());
        var baseline = engagement.confirmedBaseline();
        var target = eligibleTarget(snapshot, baseline);
        if (target == null || engagements.declined(firm, engagementId, baseline, target)) {
            return new Offer(snapshot.revision(), baseline, null, null);
        }
        return new Offer(snapshot.revision(), baseline, target, new SummaryPair(baseline, target));
    }

    void decline(String firm, String engagementId, String target) {
        var offer = requireOffer(firm, engagementId, target);
        engagements.decline(firm, engagementId, offer.confirmedBaseline(), offer.target());
    }

    ApplyDecision apply(String firm, String engagementId, String target) {
        var offer = requireOffer(firm, engagementId, target);
        return engagements.requestApply(firm, engagementId, offer.confirmedBaseline(), offer.target());
    }

    ApplyDecision application(String firm, String engagementId, String decisionId) {
        return engagements.application(firm, engagementId, decisionId).orElseThrow(VersionOffers::notFound);
    }

    private Offer requireOffer(String firm, String engagementId, String requestedTarget) {
        var offer = offer(firm, engagementId);
        if (offer.target() == null || !offer.target().equals(requestedTarget)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The requested release is not currently offered");
        }
        return offer;
    }

    private static String eligibleTarget(CatalogSnapshot catalog, String baseline) {
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

    record Offer(String catalogRevision, String confirmedBaseline, String target, SummaryPair summary) {}

    record SummaryPair(String baseline, String target) {}
}

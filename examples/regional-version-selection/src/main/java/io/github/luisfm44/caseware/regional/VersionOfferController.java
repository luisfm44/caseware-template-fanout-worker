package io.github.luisfm44.caseware.regional;

import java.security.Principal;
import java.util.UUID;

import io.github.luisfm44.caseware.regional.EngagementManager.ApplyDecision;
import io.github.luisfm44.caseware.regional.VersionOffers.Offer;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/engagements/{engagementId}")
final class VersionOfferController {

    private final VersionOffers offers;

    VersionOfferController(VersionOffers offers) {
        this.offers = offers;
    }

    @GetMapping("/offer")
    Offer offer(Principal principal, @PathVariable String engagementId) {
        return offers.offer(principal.getName(), engagementId);
    }

    @PostMapping("/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(Principal principal, @PathVariable String engagementId, @RequestBody DecisionRequest request) {
        offers.decline(principal.getName(), engagementId, request.receiptId());
    }

    @PostMapping("/apply")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApplyDecision apply(Principal principal, @PathVariable String engagementId, @RequestBody DecisionRequest request) {
        return offers.apply(principal.getName(), engagementId, request.receiptId());
    }

    @GetMapping("/applications/{decisionId}")
    ApplyDecision application(Principal principal, @PathVariable String engagementId, @PathVariable String decisionId) {
        return offers.application(principal.getName(), engagementId, decisionId);
    }

    record DecisionRequest(UUID receiptId) {}
}

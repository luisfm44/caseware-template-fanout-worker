package io.github.luisfm44.caseware.regional;

import java.util.Optional;

interface EngagementManager {

    Optional<Engagement> find(String firm, String engagement);

    boolean declined(String firm, String engagement, String baseline, String target);

    void decline(String firm, String engagement, String baseline, String target);

    ApplyDecision requestApply(String firm, String engagement, String baseline, String target);

    Optional<ApplyDecision> application(String firm, String engagement, String decisionId);

    record Engagement(String product, String market, String confirmedBaseline) {}

    record ApplyDecision(String decisionId, ApplyStatus status) {}

    enum ApplyStatus {
        PENDING,
        FAILED,
        APPLIED
    }
}

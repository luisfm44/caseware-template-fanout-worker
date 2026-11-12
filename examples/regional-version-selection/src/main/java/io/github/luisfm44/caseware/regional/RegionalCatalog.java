package io.github.luisfm44.caseware.regional;

import java.util.List;
import java.util.Map;
import java.util.Set;

interface RegionalCatalog {

    CatalogSnapshot current(String product, String market);

    record UpgradePath(String baseline, String target) {}

    record CatalogSnapshot(String revision, List<String> designatedTargets, Set<UpgradePath> approvedPaths,
            Set<String> withdrawn, Map<String, String> replacements) {

        public CatalogSnapshot {
            designatedTargets = List.copyOf(designatedTargets);
            approvedPaths = Set.copyOf(approvedPaths);
            withdrawn = Set.copyOf(withdrawn);
            replacements = Map.copyOf(replacements);
        }
    }
}

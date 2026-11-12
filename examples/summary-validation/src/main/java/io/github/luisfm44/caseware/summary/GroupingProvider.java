package io.github.luisfm44.caseware.summary;

import java.util.List;

import io.github.luisfm44.caseware.summary.SummaryPolicy.Sentence;

interface GroupingProvider {

    /** Returns a proposed grouping of the verified change IDs. */
    String group(List<Sentence> verifiedRules);

    final class Unavailable extends RuntimeException {

        public Unavailable(String reason) {
            super(reason);
        }
    }
}

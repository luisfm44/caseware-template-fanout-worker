package io.github.luisfm44.caseware.summary;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.luisfm44.caseware.summary.SummaryPolicy.Sentence;
import io.github.luisfm44.caseware.summary.TemplateCatalog.Artifact;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Service
final class SummaryService {

    private final TemplateCatalog catalog;
    private final GroupingProvider grouping;
    private final ObjectReader templateReader;
    private final SummaryPolicy policy;

    private final Map<Comparison, Promotion> promotions = new HashMap<>();
    private final Map<String, ArchivedSummary> archive = new HashMap<>();
    private final Map<String, View> views = new HashMap<>();

    SummaryService(TemplateCatalog catalog, GroupingProvider grouping, ObjectMapper mapper) {
        this.catalog = catalog;
        this.grouping = grouping;
        this.templateReader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.policy = new SummaryPolicy(mapper);
    }

    Generation generate(String baselineRevision, String targetRevision, String locale) {
        if (!"en-CA".equals(locale) && !"fr-CA".equals(locale)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported locale");
        }

        var baseline = requireApproved(baselineRevision);
        var target = requireApproved(targetRevision);
        var baselineBytes = baseline.bytes();
        var targetBytes = target.bytes();
        if (!sha256(baselineBytes).equals(baseline.approvedSha256())
                || !sha256(targetBytes).equals(target.approvedSha256())) {
            return new Generation(Status.HASH_MISMATCH, null, null);
        }

        var comparison = new Comparison(baseline.approvedSha256(), target.approvedSha256(), locale);
        Optional<List<Sentence>> rules;
        try {
            var before = templateReader.readTree(baselineBytes);
            var after = templateReader.readTree(targetBytes);
            if (!before.isObject() || !after.isObject()) {
                return new Generation(Status.INVALID_TEMPLATE, null, null);
            }
            rules = policy.rules(policy.changes(before, after), locale);
        } catch (JacksonException invalidTemplate) {
            return new Generation(Status.INVALID_TEMPLATE, null, null);
        }
        if (rules.isEmpty()) {
            return new Generation(Status.EDITOR_REVIEW, null, null);
        }

        var verified = rules.orElseThrow();
        if (verified.isEmpty()) {
            return useRules(comparison, verified, null);
        }

        try {
            var grouped = policy.grouped(grouping.group(verified), verified);
            if (grouped.isPresent()) {
                return promoteGrouped(comparison, grouped.orElseThrow());
            }
            return useRules(comparison, verified, "INVALID");
        } catch (GroupingProvider.Unavailable unavailable) {
            return useRules(comparison, verified, unavailable.getMessage());
        }
    }

    private Artifact requireApproved(String revision) {
        if (revision == null || revision.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Approved revision is required");
        }
        return catalog.approved(revision).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private synchronized Generation promoteGrouped(Comparison comparison, List<Sentence> sentences) {
        var artifact = archive(comparison, sentences);
        promotions.put(comparison, new Promotion(Status.GROUPED, artifact));
        return new Generation(Status.GROUPED, "VALID", artifact);
    }

    private synchronized Generation useRules(Comparison comparison, List<Sentence> sentences, String groupingOutcome) {
        var current = promotions.computeIfAbsent(comparison,
                key -> new Promotion(Status.RULES, archive(key, sentences)));
        return new Generation(current.mode(), groupingOutcome, current.artifact());
    }

    private ArchivedSummary archive(Comparison comparison, List<Sentence> sentences) {
        var version = "summary-" + (archive.size() + 1);
        var artifact = new ArchivedSummary(version, comparison.baselineHash(), comparison.targetHash(),
                comparison.locale(), List.copyOf(sentences));
        archive.put(version, artifact);
        return artifact;
    }

    synchronized View recordView(String version) {
        var artifact = archive.get(version);
        if (artifact == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        var receipt = new View("receipt-" + (views.size() + 1), artifact);
        views.put(receipt.receiptId(), receipt);
        return receipt;
    }

    synchronized View view(String receiptId) {
        var receipt = views.get(receiptId);
        if (receipt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return receipt;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
    }

    private record Comparison(String baselineHash, String targetHash, String locale) {}

    private record Promotion(Status mode, ArchivedSummary artifact) {}

    enum Status {
        GROUPED,
        RULES,
        EDITOR_REVIEW,
        HASH_MISMATCH,
        INVALID_TEMPLATE
    }

    record Generation(Status status, String groupingOutcome, ArchivedSummary artifact) {}

    record ArchivedSummary(String version, String baselineHash, String targetHash, String locale,
            List<Sentence> sentences) {}

    record View(String receiptId, ArchivedSummary artifact) {}
}

package io.github.luisfm44.caseware.summary;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import io.github.luisfm44.caseware.summary.SummaryPolicy.Sentence;
import io.github.luisfm44.caseware.summary.TemplateCatalog.Artifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {SummaryMockApplication.class, SummaryValidationHttpTest.Fixtures.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1"
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SummaryValidationHttpTest {

    private static final String V4 = "v4";
    private static final String V5 = "v5";
    private static final String V7 = "v7";
    private static final String V8 = "v8";
    private static final String EN = "en-CA";
    private static final String FR = "fr-CA";
    private static final String BASE = """
            {"inventory":{"signoffRequired":false}}
            """;
    private static final String INTERMEDIATE = """
            {"inventory":{"signoffRequired":false,"temporaryNote":"removed before v7"}}
            """;
    private static final String TARGET = """
            {"inventory":{"signoffRequired":true,"exceptionReason":{"type":"string"}}}
            """;
    private static final String LATER_TARGET = """
            {"inventory":{"signoffRequired":true}}
            """;
    private static final String UNSUPPORTED_TARGET = """
            {"inventory":{"signoffRequired":true,"exceptionReason":{"type":"string"},"retentionYears":7}}
            """;
    private static final String COMPLETE = """
            {"groups":[{"topic":"inventory","ids":["C17","C18"]}]}
            """;
    private static final String MISSING = """
            {"groups":[{"topic":"inventory","ids":["C17"]}]}
            """;
    private static final String MALFORMED = "{invalid";
    private static final String FRENCH_SIGNOFF = "La validation de l’inventaire est désormais obligatoire.";
    private static final String FRENCH_EXCEPTION = "L’inventaire comporte désormais un champ pour le motif d’une exception.";

    @LocalServerPort
    private int port;

    @Autowired
    private FixtureCatalog catalog;

    @Autowired
    private FixtureGrouping grouping;

    private HttpClient client;

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        catalog.approve(V4, BASE);
        catalog.approve(V5, INTERMEDIATE);
        catalog.approve(V7, TARGET);
        fixture("Approved v4 baseline SHA-256 " + sha256(bytes(BASE)));
        fixture("Approved v5 has a temporary field. Approved v7 removes it. v7 SHA-256 " + sha256(bytes(TARGET)));
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void groupsOnlyVerifiedNetChangesFromExactArtifacts() throws Exception {
        grouping.use(COMPLETE);

        var result = summary(EN, 200);

        assertThat(string(result, "$.status")).isEqualTo("GROUPED");
        assertThat(string(result, "$.groupingOutcome")).isEqualTo("VALID");
        assertThat(catalog.reads()).containsExactly(V4, V7);
        assertThat(grouping.receivedChangeIds()).containsExactly("C17", "C18");
        assertThat(result.<List<String>>read("$.artifact.sentences[*].topic")).containsExactly("inventory", "inventory");
        assertVerifiedArtifact(result, "$.artifact", EN);
        assertThat(result.<List<String>>read("$.artifact.sentences[*].text")).containsExactly(
                "Inventory sign-off is now required.", "Inventory now has a field for the reason for an exception.");
    }

    @ParameterizedTest
    @ValueSource(strings = {V4, V7})
    void blocksChangedBytesAtEitherApprovedHash(String revision) throws Exception {
        var changed = (revision.equals(V4) ? BASE : TARGET).strip();
        catalog.replaceBytes(revision, changed);
        grouping.use(COMPLETE);
        fixture("Changed bytes for " + revision + " have SHA-256 " + sha256(bytes(changed))
                + ". The approved hash stays unchanged.");

        var result = summary(EN, 422);

        assertThat(string(result, "$.status")).isEqualTo("HASH_MISMATCH");
        assertThat(result.read("$.artifact", Object.class)).isNull();
        assertThat(grouping.calls()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidGroupingCandidates")
    void rejectsInvalidGroupingAndPublishesOnlyCompleteRules(String caseName, String candidate) throws Exception {
        grouping.use(candidate);
        fixture("Grouping fixture: " + caseName);

        var result = summary(EN, 200);

        assertThat(string(result, "$.status")).isEqualTo("RULES");
        assertThat(string(result, "$.groupingOutcome")).isEqualTo("INVALID");
        assertThat(grouping.calls()).isEqualTo(1);
        assertVerifiedArtifact(result, "$.artifact", EN);
        assertThat(result.<List<String>>read("$.artifact.sentences[*].text")).containsExactly(
                "Inventory sign-off is now required.", "Inventory now has a field for the reason for an exception.");
    }

    static Stream<Arguments> invalidGroupingCandidates() {
        return Stream.of(
                Arguments.of("omitted change", MISSING),
                Arguments.of("duplicate change", """
                        {"groups":[{"topic":"inventory","ids":["C17","C18"]},{"topic":"review","ids":["C17"]}]}
                        """),
                Arguments.of("fabricated change", """
                        {"groups":[{"topic":"inventory","ids":["C17","C18","C99"]}]}
                        """),
                Arguments.of("disallowed topic", """
                        {"groups":[{"topic":"exceptions","ids":["C17","C18"]}]}
                        """),
                Arguments.of("extra prose", """
                        {"groups":[{"topic":"inventory","ids":["C17","C18"],"text":"The reason is mandatory."}]}
                        """),
                Arguments.of("trailing content", COMPLETE + "{\"prose\":\"The reason is mandatory.\"}"),
                Arguments.of("invalid JSON", MALFORMED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT", "INPUT_LIMIT", "BUDGET"})
    void preservesAnExistingVerifiedRulesArtifactWhenGroupingIsUnavailable(String reason) throws Exception {
        grouping.use(MISSING);
        var previous = summary(EN, 200);
        assertThat(string(previous, "$.status")).isEqualTo("RULES");
        assertVerifiedArtifact(previous, "$.artifact", EN);

        grouping.unavailable(reason);
        fixture("Grouping provider reports " + reason);
        var result = summary(EN, 200);

        assertThat(string(result, "$.status")).isEqualTo("RULES");
        assertThat(string(result, "$.groupingOutcome")).isEqualTo(reason);
        assertThat(result.read("$.artifact", Object.class)).isEqualTo(previous.read("$.artifact", Object.class));
    }

    @Test
    void sendsAnUnsupportedMaterialEditForEditorialReviewWithoutPublishing() throws Exception {
        catalog.approve(V7, UNSUPPORTED_TARGET);
        grouping.use(COMPLETE);
        fixture("v7 includes a material retention edit with no approved wording");

        var result = summary(EN, 200);

        assertThat(string(result, "$.status")).isEqualTo("EDITOR_REVIEW");
        assertThat(result.read("$.artifact", Object.class)).isNull();
        assertThat(grouping.calls()).isZero();
    }

    @Test
    void keepsTheLocalizedArtifactPinnedToTheOriginalViewReceipt() throws Exception {
        grouping.use("""
                {"groups":[{"topic":"review","ids":["C17"]},{"topic":"exceptions","ids":["C18"]}]}
                """);
        var original = summary(FR, 200);
        assertThat(string(original, "$.status")).isEqualTo("GROUPED");
        assertVerifiedArtifact(original, "$.artifact", FR);
        assertThat(original.<List<String>>read("$.artifact.sentences[*].text"))
                .containsExactly(FRENCH_SIGNOFF, FRENCH_EXCEPTION);

        var originalVersion = string(original, "$.artifact.version");
        var view = post("/summaries/" + originalVersion + "/views", null, "", 201);
        var receiptId = string(view, "$.receiptId");
        assertThat(receiptId).isNotBlank();
        assertThat(view.read("$.artifact", Object.class)).isEqualTo(original.read("$.artifact", Object.class));

        catalog.approve(V8, LATER_TARGET);
        grouping.use(MISSING);
        fixture("Approved v8 removes the exception-reason field, leaving only C17. v8 SHA-256 "
                + sha256(bytes(LATER_TARGET)));
        var newer = summary(V8, FR, 200);
        assertThat(string(newer, "$.status")).isEqualTo("GROUPED");
        assertThat(string(newer, "$.artifact.version")).isNotEqualTo(originalVersion);
        assertThat(string(newer, "$.artifact.targetHash")).isEqualTo(sha256(bytes(LATER_TARGET)));
        assertThat(newer.<List<String>>read("$.artifact.sentences[*].change.id")).containsExactly("C17");

        grouping.use(MALFORMED);
        var rejected = summary(V8, FR, 200);
        assertThat(string(rejected, "$.status")).isEqualTo("GROUPED");
        assertThat(string(rejected, "$.groupingOutcome")).isEqualTo("INVALID");
        assertThat(rejected.read("$.artifact", Object.class)).isEqualTo(newer.read("$.artifact", Object.class));

        var receipt = request("GET", "/views/" + receiptId, null, "", 200);
        assertThat(string(receipt, "$.receiptId")).isEqualTo(receiptId);
        assertThat(receipt.read("$.artifact", Object.class)).isEqualTo(original.read("$.artifact", Object.class));
        assertVerifiedArtifact(receipt, "$.artifact", FR);
    }

    private static void assertVerifiedArtifact(ReadContext result, String path, String locale) {
        assertThat(string(result, path + ".version")).isNotBlank();
        assertThat(string(result, path + ".baselineHash")).isEqualTo(sha256(bytes(BASE)));
        assertThat(string(result, path + ".targetHash")).isEqualTo(sha256(bytes(TARGET)));
        assertThat(string(result, path + ".locale")).isEqualTo(locale);
        assertThat(result.<List<String>>read(path + ".sentences[*].change.id")).containsExactly("C17", "C18");
        assertThat(result.<List<String>>read(path + ".sentences[*].change.path"))
                .containsExactly("/inventory/signoffRequired", "/inventory/exceptionReason");
        assertThat(result.<List<String>>read(path + ".sentences[*].change.operation"))
                .containsExactly("REPLACED", "ADDED");
        assertThat(result.read(path + ".sentences[0].change.before", Boolean.class)).isFalse();
        assertThat(result.read(path + ".sentences[0].change.after", Boolean.class)).isTrue();
        assertThat(result.read(path + ".sentences[1].change.before", Object.class)).isNull();
        assertThat(result.<Map<String, String>>read(path + ".sentences[1].change.after")).isEqualTo(Map.of("type", "string"));
    }

    private ReadContext summary(String locale, int status) throws IOException, InterruptedException {
        return summary(V7, locale, status);
    }

    private ReadContext summary(String target, String locale, int status) throws IOException, InterruptedException {
        return post("/summaries", """
                {"baselineRevision":"%s","targetRevision":"%s","locale":"%s"}
                """.formatted(V4, target, locale), target, status);
    }

    private ReadContext post(String path, String body, String target, int status) throws IOException, InterruptedException {
        return request("POST", path, body, target, status);
    }

    private ReadContext request(String method, String path, String body, String target, int status)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        var request = builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        System.out.printf("CASEWARE_HTTP\t%s\t%s\t%s\t%s\t%d\t%s%n", method, path, "summary-client", target,
                response.statusCode(), Base64.getEncoder().encodeToString(bytes(response.body())));
        assertThat(response.statusCode()).withFailMessage("Expected HTTP %s, got %s with body %s",
                status, response.statusCode(), response.body()).isEqualTo(status);
        return JsonPath.parse(response.body());
    }

    private static void fixture(String action) {
        System.out.println("CASEWARE_FIXTURE\t" + action);
    }

    private static String string(ReadContext result, String path) {
        return result.read(path, String.class);
    }

    private static byte[] bytes(String contents) {
        return contents.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        FixtureCatalog catalog() {
            return new FixtureCatalog();
        }

        @Bean
        FixtureGrouping grouping() {
            return new FixtureGrouping();
        }
    }

    static final class FixtureCatalog implements TemplateCatalog {

        private final Map<String, Artifact> artifacts = new HashMap<>();
        private final List<String> reads = new ArrayList<>();

        synchronized void approve(String revision, String contents) {
            var data = bytes(contents);
            artifacts.put(revision, new Artifact(revision, sha256(data), data));
        }

        synchronized void replaceBytes(String revision, String contents) {
            var previous = artifacts.get(revision);
            artifacts.put(revision, new Artifact(revision, previous.approvedSha256(), bytes(contents)));
        }

        @Override
        public synchronized Optional<Artifact> approved(String revision) {
            reads.add(revision);
            return Optional.ofNullable(artifacts.get(revision));
        }

        synchronized List<String> reads() {
            return List.copyOf(reads);
        }
    }

    static final class FixtureGrouping implements GroupingProvider {

        private String candidate;
        private String unavailableReason;
        private int calls;
        private List<String> receivedChangeIds = List.of();

        synchronized void use(String response) {
            candidate = response;
            unavailableReason = null;
        }

        synchronized void unavailable(String reason) {
            unavailableReason = reason;
        }

        @Override
        public synchronized String group(List<Sentence> verifiedRules) {
            calls++;
            receivedChangeIds = verifiedRules.stream().map(sentence -> sentence.change().id()).toList();
            if (unavailableReason != null) {
                throw new Unavailable(unavailableReason);
            }
            return candidate;
        }

        synchronized int calls() {
            return calls;
        }

        synchronized List<String> receivedChangeIds() {
            return receivedChangeIds;
        }
    }
}

package io.github.luisfm44.caseware.regional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import io.github.luisfm44.caseware.regional.EngagementManager.ApplyDecision;
import io.github.luisfm44.caseware.regional.EngagementManager.ApplyStatus;
import io.github.luisfm44.caseware.regional.EngagementManager.Engagement;
import io.github.luisfm44.caseware.regional.RegionalCatalog.CatalogSnapshot;
import io.github.luisfm44.caseware.regional.RegionalCatalog.UpgradePath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.config.Customizer.withDefaults;

@SpringBootTest(
        classes = {RegionalApiApplication.class, RegionalVersionSelectionHttpTest.Fixtures.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1"
)
class RegionalVersionSelectionHttpTest {

    private static final String FIRM_A = "firm-a";
    private static final String FIRM_B = "firm-b";
    private static final String FILE_A = "file-a";
    private static final String FILE_B = "file-b";
    private static final String OTHER_FIRM_FILE = "other-firm-file";
    private static final String V3 = "release-v3";
    private static final String V4 = "release-v4";
    private static final String V5 = "release-v5";
    private static final String V6 = "release-v6";
    private static final String V7 = "release-v7";
    private static final String V8 = "release-v8";
    private static final String FIXTURE_PASSWORD = "regional-test-only";
    private static final String PRODUCT = "audit";
    private static final String MARKET = "ca";

    @LocalServerPort
    private int port;

    @Autowired
    private FixtureCatalog catalog;

    @Autowired
    private FixtureManager manager;

    @BeforeEach
    void reset() {
        setCatalog(active("catalog-v7", V7, new UpgradePath(V4, V7)));
        manager.reset();
        manager.put(FIRM_A, FILE_A, V4);
        manager.put(FIRM_A, FILE_B, V4);
        manager.put(FIRM_B, OTHER_FIRM_FILE, V4);
        fixture("Base inicial %s para %s/%s, %s/%s y %s/%s".formatted(
                V4, FIRM_A, FILE_A, FIRM_A, FILE_B, FIRM_B, OTHER_FIRM_FILE));
    }

    @Test
    void offersAnApprovedTargetAndTheExactCumulativeSummaryPair() throws Exception {
        var response = getOffer(FIRM_A, FILE_A);
        assertThat(response.read("$.catalogRevision", String.class)).isEqualTo("catalog-v7");
        assertOffer(response, V4, V7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing path", "ambiguous targets"})
    void withholdsAnOfferWithoutOneApprovedTarget(String caseName) throws Exception {
        setCatalog(caseName.equals("missing path")
                ? active("catalog-unapproved", V7, new UpgradePath(V5, V7))
                : new CatalogSnapshot("catalog-ambiguous", List.of(V6, V7),
                        Set.of(new UpgradePath(V4, V6), new UpgradePath(V4, V7)), Set.of(), Map.of()));

        assertWithheld(getOffer(FIRM_A, FILE_A), V4);
    }

    @Test
    void exactDeclineIsScopedToTheFileAndConfirmedBaseline() throws Exception {
        setCatalog(active("catalog-v5", V5, new UpgradePath(V4, V5), new UpgradePath(V3, V5)));
        assertOffer(getOffer(FIRM_A, FILE_A), V4, V5);
        assertThat(post(FIRM_A, filePath(FILE_A) + "/decline", V5).statusCode()).isEqualTo(204);

        assertWithheld(getOffer(FIRM_A, FILE_A), V4);
        assertOffer(getOffer(FIRM_A, FILE_B), V4, V5);

        setBaseline(FIRM_A, FILE_A, V3);
        assertOffer(getOffer(FIRM_A, FILE_A), V3, V5);
    }

    @Test
    void anExactDeclineDoesNotSuppressALaterCompatibleTarget() throws Exception {
        setCatalog(active("catalog-v5", V5, new UpgradePath(V4, V5)));
        assertThat(post(FIRM_A, filePath(FILE_A) + "/decline", V5).statusCode()).isEqualTo(204);

        setCatalog(active("catalog-v7", V7, new UpgradePath(V4, V7)));
        assertOffer(getOffer(FIRM_A, FILE_A), V4, V7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "incompatible", "compatible"})
    void handlesWithdrawalWithoutRewritingTheConfirmedBaseline(String replacement) throws Exception {
        setBaseline(FIRM_A, FILE_B, V7);
        var replacements = replacement.equals("none") ? Map.<String, String>of() : Map.of(V7, V8);
        var paths = replacement.equals("compatible")
                ? Set.of(new UpgradePath(V4, V7), new UpgradePath(V4, V8))
                : Set.of(new UpgradePath(V4, V7), new UpgradePath(V5, V8));
        setCatalog(new CatalogSnapshot("catalog-withdrawn", List.of(V7), paths, Set.of(V7), replacements));

        var offer = getOffer(FIRM_A, FILE_A);
        if (replacement.equals("compatible")) {
            assertOffer(offer, V4, V8);
        } else {
            assertWithheld(offer, V4);
        }
        assertWithheld(getOffer(FIRM_A, FILE_B), V7);
    }

    @Test
    void holdsTheOfferAndSummaryWhenTheManagerHasNoConfirmedBaseline() throws Exception {
        setBaseline(FIRM_A, FILE_A, null);
        assertWithheld(getOffer(FIRM_A, FILE_A), null);
    }

    @ParameterizedTest
    @EnumSource(value = ApplyStatus.class, names = {"FAILED", "APPLIED"})
    void baselineAdvancesOnlyAfterTheManagerConfirmsSuccess(ApplyStatus outcome) throws Exception {
        var application = json(post(FIRM_A, filePath(FILE_A) + "/apply", V7), 202);
        var decisionId = application.read("$.decisionId", String.class);
        var applicationPath = filePath(FILE_A) + "/applications/" + decisionId;

        assertThat(application.read("$.status", String.class)).isEqualTo("PENDING");
        assertThat(json(get(FIRM_A, applicationPath), 200).read("$.status", String.class)).isEqualTo("PENDING");
        assertThat(getOffer(FIRM_A, FILE_A).read("$.confirmedBaseline", String.class)).isEqualTo(V4);

        manager.complete(decisionId, outcome);
        fixture("El engagement manager de prueba confirma %s para %s".formatted(outcome, decisionId));
        assertThat(json(get(FIRM_A, applicationPath), 200).read("$.status", String.class)).isEqualTo(outcome.name());
        var updated = getOffer(FIRM_A, FILE_A);
        if (outcome == ApplyStatus.APPLIED) {
            assertWithheld(updated, V7);
        } else {
            assertThat(updated.read("$.confirmedBaseline", String.class)).isEqualTo(V4);
        }
    }

    @Test
    void rejectsAStaleTargetBeforeCallingTheManager() throws Exception {
        assertOffer(getOffer(FIRM_A, FILE_A), V4, V7);
        setCatalog(active("catalog-v8", V8, new UpgradePath(V4, V8)));

        assertThat(post(FIRM_A, filePath(FILE_A) + "/apply", V7).statusCode()).isEqualTo(409);
        assertThat(post(FIRM_A, filePath(FILE_A) + "/decline", V7).statusCode()).isEqualTo(409);
        assertThat(manager.decisionCount()).isZero();
        assertThat(manager.declined(FIRM_A, FILE_A, V4, V7)).isFalse();
        assertOffer(getOffer(FIRM_A, FILE_A), V4, V8);
    }

    @Test
    void usesTheAuthenticatedFirmAndKeepsAnotherFirmsFileOutOfReach() throws Exception {
        assertThat(get(null, filePath(FILE_A) + "/offer").statusCode()).isEqualTo(401);
        assertThat(get(FIRM_A, filePath(OTHER_FIRM_FILE) + "/offer").statusCode()).isEqualTo(404);
        assertThat(post(FIRM_A, filePath(OTHER_FIRM_FILE) + "/apply", V7).statusCode()).isEqualTo(404);
        assertThat(manager.decisionCount()).isZero();
        assertOffer(getOffer(FIRM_B, OTHER_FIRM_FILE), V4, V7);
    }

    private static CatalogSnapshot active(String revision, String target, UpgradePath... paths) {
        return new CatalogSnapshot(revision, List.of(target), Set.of(paths), Set.of(), Map.of());
    }

    private void setCatalog(CatalogSnapshot snapshot) {
        catalog.snapshot = snapshot;
        var paths = snapshot.approvedPaths().stream()
                .map(path -> path.baseline() + " → " + path.target()).sorted().toList();
        fixture("Catálogo %s · destinos %s · rutas aprobadas %s · retiradas %s · reemplazos %s".formatted(
                snapshot.revision(), snapshot.designatedTargets(), paths, snapshot.withdrawn(), snapshot.replacements()));
    }

    private void setBaseline(String firm, String engagement, String baseline) {
        manager.put(firm, engagement, baseline);
        fixture("El engagement manager de prueba deja %s/%s %s".formatted(firm, engagement,
                baseline == null ? "sin base confirmada" : "con base confirmada " + baseline));
    }

    private static void fixture(String action) {
        System.out.println("CASEWARE_FIXTURE\t" + action);
    }

    private static void assertOffer(ReadContext response, String baseline, String target) {
        assertThat(response.read("$.confirmedBaseline", String.class)).isEqualTo(baseline);
        assertThat(response.read("$.target", String.class)).isEqualTo(target);
        assertThat(response.read("$.summary.baseline", String.class)).isEqualTo(baseline);
        assertThat(response.read("$.summary.target", String.class)).isEqualTo(target);
    }

    private static void assertWithheld(ReadContext response, String baseline) {
        assertThat(response.read("$.confirmedBaseline", String.class)).isEqualTo(baseline);
        assertThat(response.read("$.target", String.class)).isNull();
        assertThat(response.read("$.summary", Object.class)).isNull();
    }

    private ReadContext getOffer(String firm, String engagement) throws Exception {
        return json(get(firm, filePath(engagement) + "/offer"), 200);
    }

    private static ReadContext json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).withFailMessage("Expected HTTP %s, got %s with body %s",
                status, response.statusCode(), response.body()).isEqualTo(status);
        return JsonPath.parse(response.body());
    }

    private static String filePath(String engagement) {
        return "/engagements/" + engagement;
    }

    private HttpResponse<String> get(String firm, String path) throws IOException, InterruptedException {
        return send(request(firm, path).GET().build(), firm, "");
    }

    private HttpResponse<String> post(String firm, String path, String target) throws IOException, InterruptedException {
        return send(request(firm, path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"target\":\"" + target + "\"}"))
                .build(), firm, target);
    }

    private HttpRequest.Builder request(String firm, String path) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (firm != null) {
            var credentials = (firm + ":" + FIXTURE_PASSWORD).getBytes(StandardCharsets.UTF_8);
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials));
        }
        return builder;
    }

    private static HttpResponse<String> send(HttpRequest request, String firm, String target)
            throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var body = Base64.getEncoder().encodeToString(response.body().getBytes(StandardCharsets.UTF_8));
            System.out.printf("CASEWARE_HTTP\t%s\t%s\t%s\t%s\t%d\t%s%n",
                    request.method(), request.uri().getRawPath(), firm == null ? "anonymous" : firm,
                    target, response.statusCode(), body);
            return response;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        FixtureCatalog catalog() {
            return new FixtureCatalog();
        }

        @Bean
        FixtureManager manager() {
            return new FixtureManager();
        }

        @Bean
        InMemoryUserDetailsManager fixtureUsers() {
            return new InMemoryUserDetailsManager(
                    User.withUsername(FIRM_A).password("{noop}" + FIXTURE_PASSWORD).roles("AUDITOR").build(),
                    User.withUsername(FIRM_B).password("{noop}" + FIXTURE_PASSWORD).roles("AUDITOR").build());
        }

        @Bean
        SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
            // This fixture accepts explicit Basic credentials over loopback and uses no cookies.
            return http.authorizeHttpRequests(registry -> registry.anyRequest().authenticated())
                    .httpBasic(withDefaults())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.disable())
                    .build();
        }
    }

    static final class FixtureCatalog implements RegionalCatalog {
        private volatile CatalogSnapshot snapshot;

        @Override
        public CatalogSnapshot current(String product, String market) {
            assertThat(product).isEqualTo(PRODUCT);
            assertThat(market).isEqualTo(MARKET);
            return snapshot;
        }
    }

    static final class FixtureManager implements EngagementManager {
        private final Map<FileKey, Engagement> engagements = new HashMap<>();
        private final Set<DeclineKey> declines = new HashSet<>();
        private final Map<String, StoredDecision> decisions = new HashMap<>();

        synchronized void reset() {
            engagements.clear();
            declines.clear();
            decisions.clear();
        }

        synchronized void put(String firm, String engagement, String baseline) {
            engagements.put(new FileKey(firm, engagement), new Engagement(PRODUCT, MARKET, baseline));
        }

        @Override
        public synchronized Optional<Engagement> find(String firm, String engagement) {
            return Optional.ofNullable(engagements.get(new FileKey(firm, engagement)));
        }

        @Override
        public synchronized boolean declined(String firm, String engagement, String baseline, String target) {
            return declines.contains(new DeclineKey(new FileKey(firm, engagement), baseline, target));
        }

        @Override
        public synchronized void decline(String firm, String engagement, String baseline, String target) {
            declines.add(new DeclineKey(new FileKey(firm, engagement), baseline, target));
        }

        @Override
        public synchronized ApplyDecision requestApply(String firm, String engagement, String baseline, String target) {
            var key = new FileKey(firm, engagement);
            assertThat(engagements.get(key).confirmedBaseline()).isEqualTo(baseline);
            var id = "decision-" + (decisions.size() + 1);
            var result = new ApplyDecision(id, ApplyStatus.PENDING);
            decisions.put(id, new StoredDecision(key, target, result));
            return result;
        }

        @Override
        public synchronized Optional<ApplyDecision> application(String firm, String engagement, String decisionId) {
            return Optional.ofNullable(decisions.get(decisionId))
                    .filter(record -> record.file().equals(new FileKey(firm, engagement)))
                    .map(StoredDecision::decision);
        }

        synchronized void complete(String decisionId, ApplyStatus status) {
            var original = decisions.get(decisionId);
            assertThat(original.decision().status()).isEqualTo(ApplyStatus.PENDING);
            if (status == ApplyStatus.APPLIED) {
                put(original.file().firm(), original.file().engagement(), original.target());
            }
            decisions.put(decisionId,
                    new StoredDecision(original.file(), original.target(), new ApplyDecision(decisionId, status)));
        }

        synchronized int decisionCount() {
            return decisions.size();
        }

        private record FileKey(String firm, String engagement) {}
        private record DeclineKey(FileKey file, String baseline, String target) {}
        private record StoredDecision(FileKey file, String target, ApplyDecision decision) {}
    }
}

package io.github.luisfm44.caseware.connected;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import io.github.luisfm44.caseware.regional.RegionalApiApplication;
import io.github.luisfm44.caseware.regional.RegionalReferenceFixtures;
import io.github.luisfm44.caseware.regional.RegionalReferenceFixtures.OfferCatalog;
import io.github.luisfm44.caseware.summary.SummaryMockApplication;
import io.github.luisfm44.caseware.summary.SummaryReferenceFixtures;
import io.github.luisfm44.caseware.summary.SummaryReferenceFixtures.Assets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static io.github.luisfm44.caseware.regional.RegionalReferenceFixtures.FILE;
import static io.github.luisfm44.caseware.regional.RegionalReferenceFixtures.FIRM;
import static io.github.luisfm44.caseware.regional.RegionalReferenceFixtures.PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

class ConnectedReferenceApisHttpTest {

    private static final String BASELINE_HASH = "652c349f3528e2187cecc2510adb89a55e91d06bd4bf7d66db3a67b0b396a7e1";
    private static final String TARGET_HASH = "7b471eeb143fd741a3ff5f7112b067f915a31319dd3ecb7f30df318551755335";
    private static final String LATER_TARGET_HASH = "220510fbc58aea4b53d38c4bbee436145cebccc2933f4fefead7da19e250837a";
    private static final String LOCALE = "fr-CA";
    private static final String BASIC_AUTH = "Basic " + encode(FIRM + ":" + PASSWORD);

    private final ObjectMapper mapper = new ObjectMapper();
    private ConfigurableApplicationContext regional;
    private ConfigurableApplicationContext summary;
    private HttpClient client;
    private OfferCatalog catalog;
    private Assets assets;

    @BeforeEach
    void startReferenceApis() {
        regional = start(RegionalApiApplication.class, RegionalReferenceFixtures.class);
        // The regional dependency adds security auto-configuration to the test classpath.
        // Keep the summary context's configuration consistent with its standalone module.
        summary = start(SummaryMockApplication.class, SummaryReferenceFixtures.class,
                "--spring.autoconfigure.exclude="
                        + "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration,"
                        + "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        catalog = regional.getBean(OfferCatalog.class);
        assets = summary.getBean(Assets.class);
    }

    @AfterEach
    void stopReferenceApis() {
        try (var regionalContext = regional; var summaryContext = summary; var testClient = client) {
            // Close the client first, then both independent application contexts.
        }
    }

    @Test
    void followsTheOfferedPairAndKeepsTheOriginalFrenchReceipt() throws Exception {
        fixture("Approve v4 → v7. The approved v5 fixture includes a temporary field removed before v7.");
        catalog.setTarget("v7", true);
        var firstOffer = getOffer();
        assertPair(firstOffer, "v7");

        var original = json(requestOfferedSummary(firstOffer).orElseThrow(), 200);
        assertThat(original.read("$.status", String.class)).isEqualTo("GROUPED");
        assertThat(original.read("$.artifact.baselineHash", String.class)).isEqualTo(BASELINE_HASH);
        assertThat(original.read("$.artifact.targetHash", String.class)).isEqualTo(TARGET_HASH);
        assertThat(original.read("$.artifact.locale", String.class)).isEqualTo(LOCALE);
        assertThat(original.<List<String>>read("$.artifact.sentences[*].change.id")).containsExactly("C17", "C18");
        assertThat(original.<List<String>>read("$.artifact.sentences[*].change.path"))
                .containsExactly("/inventory/signoffRequired", "/inventory/exceptionReason");
        assertThat(original.read("$.artifact.sentences[0].change.before", Boolean.class)).isFalse();
        assertThat(original.read("$.artifact.sentences[0].change.after", Boolean.class)).isTrue();
        assertThat(original.read("$.artifact.sentences[1].change.before", Object.class)).isNull();
        assertThat(original.<Map<String, String>>read("$.artifact.sentences[1].change.after"))
                .containsExactlyEntriesOf(Map.of("type", "string"));
        assertThat(original.<List<String>>read("$.artifact.sentences[*].text")).containsExactly(
                "La validation de l’inventaire est désormais obligatoire.",
                "L’inventaire comporte désormais un champ pour le motif d’une exception.");

        var version = original.read("$.artifact.version", String.class);
        var view = json(send(summary, "summary", "POST", "/summaries/" + version + "/views", null, ""), 201);
        var receiptId = view.read("$.receiptId", String.class);
        assertThat(receiptId).isNotBlank();
        assertThat(view.read("$.artifact", Object.class)).isEqualTo(original.read("$.artifact", Object.class));

        fixture("Publish v8 as the active target. The confirmed engagement baseline stays at v4.");
        catalog.setTarget("v8", true);
        var laterOffer = getOffer();
        assertPair(laterOffer, "v8");
        var later = json(requestOfferedSummary(laterOffer).orElseThrow(), 200);
        assertThat(later.read("$.status", String.class)).isEqualTo("GROUPED");
        assertThat(later.read("$.artifact.baselineHash", String.class)).isEqualTo(BASELINE_HASH);
        assertThat(later.read("$.artifact.targetHash", String.class)).isEqualTo(LATER_TARGET_HASH);
        assertThat(later.read("$.artifact.version", String.class)).isNotEqualTo(version);
        assertThat(later.<List<String>>read("$.artifact.sentences[*].change.id")).containsExactly("C17");

        var pinned = json(send(summary, "summary", "GET", "/views/" + receiptId, null, ""), 200);
        assertThat(pinned.read("$.receiptId", String.class)).isEqualTo(receiptId);
        assertThat(pinned.read("$.artifact", Object.class)).isEqualTo(original.read("$.artifact", Object.class));
        assertThat(assets.reads()).containsExactly("v4", "v7", "v4", "v8");
        assertThat(assets.summaryRequests()).isEqualTo(2);
    }

    @Test
    void doesNotRequestASummaryForAWithheldOffer() throws Exception {
        fixture("The catalog designates v7 without approving the upgrade path from v4.");
        catalog.setTarget("v7", false);
        var offer = getOffer();
        assertThat(offer.read("$.confirmedBaseline", String.class)).isEqualTo("v4");
        assertThat(offer.read("$.target", Object.class)).isNull();
        assertThat(offer.read("$.receiptId", Object.class)).isNull();
        assertThat(requestOfferedSummary(offer)).isEmpty();
        assertThat(assets.summaryRequests()).isZero();
        assertThat(assets.reads()).isEmpty();
        fixture("The summary server received zero generation requests.");
    }

    @Test
    void rejectsTamperedBytesForTheOfferedTarget() throws Exception {
        fixture("Approve v4 → v7, then change the stored v7 bytes while retaining the approved hash.");
        catalog.setTarget("v7", true);
        assets.replaceApprovedBytes("v7");
        var offer = getOffer();
        assertPair(offer, "v7");

        var rejected = json(requestOfferedSummary(offer).orElseThrow(), 422);
        assertThat(rejected.read("$.status", String.class)).isEqualTo("HASH_MISMATCH");
        assertThat(rejected.read("$.artifact", Object.class)).isNull();
        assertThat(assets.reads()).containsExactly("v4", "v7");
        assertThat(assets.summaryRequests()).isEqualTo(1);
        assertThat(assets.groupingCalls()).isZero();
    }

    private ReadContext getOffer() throws IOException, InterruptedException {
        return json(send(regional, "regional", "GET", "/engagements/" + FILE + "/offer", null, ""), 200);
    }

    private Optional<HttpResponse<String>> requestOfferedSummary(ReadContext offer)
            throws IOException, InterruptedException {
        Map<String, String> pair = offer.read("$.summary");
        if (pair == null) {
            return Optional.empty();
        }

        var request = new SummaryRequest(pair.get("baseline"), pair.get("target"), LOCALE);
        fixture("The test client forwards the offered pair " + request.baselineRevision() + " → "
                + request.targetRevision() + " with locale " + request.locale() + ".");
        return Optional.of(send(summary, "summary", "POST", "/summaries",
                mapper.writeValueAsString(request), request.targetRevision()));
    }

    private static void assertPair(ReadContext offer, String target) {
        assertThat(offer.read("$.summary.baseline", String.class)).isEqualTo("v4");
        assertThat(offer.read("$.summary.target", String.class)).isEqualTo(target);
        assertThat(offer.read("$.receiptId", String.class)).isNotBlank();
    }

    private static ReadContext json(HttpResponse<String> response, int expectedStatus) {
        assertThat(response.statusCode()).as("HTTP status for %s", response.uri()).isEqualTo(expectedStatus);
        return JsonPath.parse(response.body());
    }

    private HttpResponse<String> send(ConfigurableApplicationContext context, String service, String method,
            String path, String body, String target) throws IOException, InterruptedException {
        var port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json");
        if (context == regional) {
            request.header("Authorization", BASIC_AUTH);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        var response = client.send(request.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        System.out.printf("CASEWARE_HTTP\t%s\t%s\t%s\t%s\t%d\t%s%n",
                method, path, service + " · " + FIRM, target, response.statusCode(), encode(response.body()));
        return response;
    }

    private static ConfigurableApplicationContext start(Class<?> app, Class<?> fixtures, String... overrides) {
        var arguments = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0",
                "--spring.main.banner-mode=off"));
        arguments.addAll(List.of(overrides));
        return new SpringApplicationBuilder(app, fixtures).run(arguments.toArray(String[]::new));
    }

    private static void fixture(String action) {
        System.out.printf("CASEWARE_FIXTURE\t%s%n", action);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record SummaryRequest(String baselineRevision, String targetRevision, String locale) {}
}

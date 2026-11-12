package io.github.luisfm44.caseware.summary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.Filter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class SummaryReferenceFixtures {

    private static final String BASELINE = """
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

    @Bean
    Assets assets() {
        return new Assets();
    }

    @Bean
    GroupingProvider groupingProvider(Assets assets) {
        return rules -> {
            assets.groupingCalls.incrementAndGet();
            return rules.size() == 1
                    ? """
                      {"groups":[{"topic":"review","ids":["C17"]}]}
                      """
                    : """
                      {"groups":[{"topic":"review","ids":["C17"]},{"topic":"exceptions","ids":["C18"]}]}
                      """;
        };
    }

    @Bean
    FilterRegistrationBean<Filter> summaryRequestCounter(Assets assets) {
        Filter filter = (request, response, chain) -> {
            assets.summaryRequests.incrementAndGet();
            chain.doFilter(request, response);
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/summaries");
        return registration;
    }

    public static final class Assets implements TemplateCatalog {
        private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>(Map.of(
                "v4", approved("v4", BASELINE),
                "v5", approved("v5", INTERMEDIATE),
                "v7", approved("v7", TARGET),
                "v8", approved("v8", LATER_TARGET)));
        private final List<String> reads = new CopyOnWriteArrayList<>();
        private final AtomicInteger summaryRequests = new AtomicInteger();
        private final AtomicInteger groupingCalls = new AtomicInteger();

        @Override
        public Optional<Artifact> approved(String revision) {
            reads.add(revision);
            return Optional.ofNullable(artifacts.get(revision));
        }

        public void replaceApprovedBytes(String revision) {
            var original = artifacts.get(revision);
            var changed = new String(original.bytes(), StandardCharsets.UTF_8).strip();
            artifacts.put(revision, new Artifact(revision, original.approvedSha256(),
                    changed.getBytes(StandardCharsets.UTF_8)));
        }

        public List<String> reads() {
            return List.copyOf(reads);
        }

        public int summaryRequests() {
            return summaryRequests.get();
        }

        public int groupingCalls() {
            return groupingCalls.get();
        }

        private static Artifact approved(String revision, String template) {
            var bytes = template.getBytes(StandardCharsets.UTF_8);
            try {
                var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                return new Artifact(revision, hash, bytes);
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}

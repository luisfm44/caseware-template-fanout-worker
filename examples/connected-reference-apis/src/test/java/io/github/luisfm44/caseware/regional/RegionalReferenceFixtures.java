package io.github.luisfm44.caseware.regional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.luisfm44.caseware.regional.EngagementManager.Engagement;
import io.github.luisfm44.caseware.regional.RegionalCatalog.CatalogSnapshot;
import io.github.luisfm44.caseware.regional.RegionalCatalog.UpgradePath;
import org.mockito.MockMakers;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.security.config.Customizer.withDefaults;

@TestConfiguration(proxyBeanMethods = false)
public class RegionalReferenceFixtures {

    public static final String FIRM = "firm-a";
    public static final String FILE = "file-a";
    public static final String PASSWORD = "local-fixture";
    public static final String BASELINE = "v4";

    @Bean
    OfferCatalog offerCatalog() {
        return new OfferCatalog();
    }

    @Bean
    EngagementManager engagementManager() {
        var manager = mock(EngagementManager.class, withSettings().mockMaker(MockMakers.PROXY));
        when(manager.find(FIRM, FILE)).thenReturn(Optional.of(new Engagement("audit", "ca", BASELINE, "revision-1")));
        return manager;
    }

    @Bean
    InMemoryUserDetailsManager fixtureUser() {
        return new InMemoryUserDetailsManager(
                User.withUsername(FIRM).password("{noop}" + PASSWORD).roles("AUDITOR").build());
    }

    @Bean
    SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(routes -> routes.anyRequest().authenticated())
                .httpBasic(withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    public static final class OfferCatalog implements RegionalCatalog {
        private volatile CatalogSnapshot snapshot;

        public void setTarget(String target, boolean approvedPath) {
            var paths = approvedPath ? Set.of(new UpgradePath(BASELINE, target)) : Set.<UpgradePath>of();
            snapshot = new CatalogSnapshot("catalog-" + target, List.of(target), paths, Set.of(), Map.of());
        }

        @Override
        public CatalogSnapshot current(String product, String market) {
            if (!"audit".equals(product) || !"ca".equals(market)) {
                throw new AssertionError("Unexpected catalog selection " + product + "/" + market);
            }
            return snapshot;
        }
    }
}

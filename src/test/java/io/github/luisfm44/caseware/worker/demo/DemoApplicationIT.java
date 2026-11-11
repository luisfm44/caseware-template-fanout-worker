package io.github.luisfm44.caseware.worker.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import io.github.luisfm44.caseware.worker.TemplateFanoutWorkerApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.skyscreamer.jsonassert.JSONAssert;

import static org.assertj.core.api.Assertions.assertThat;

class DemoApplicationIT {

    @TempDir
    Path output;

    @Test
    void publishesTheDemoReportFromTheApplicationEntryPointAndRemovesItsSchema() throws Exception {
        var before = demoSchemas();
        var report = output.resolve("reports/fanout-demo.json");

        TemplateFanoutWorkerApplication.main(new String[] {
            "--spring.profiles.active=demo", "--spring.main.banner-mode=off", "--demo.report=" + report
        });

        JSONAssert.assertEquals("""
                {
                  "submitted": 7,
                  "completed": 7,
                  "duplicateEffects": 0,
                  "limit": 3,
                  "peakInFlight": 3,
                  "plannedPages": 4,
                  "appliedEffects": 7,
                  "dispatchAttempts": 7,
                  "reconciliations": 1
                }
                """, Files.readString(report), true);
        assertThat(demoSchemas()).containsExactlyElementsOf(before);
    }

    private static List<String> demoSchemas() throws SQLException {
        var environment = System.getenv();
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(environment.getOrDefault("FANOUT_DB_URL", "jdbc:postgresql://127.0.0.1:55432/fanout"));
        dataSource.setUser(environment.getOrDefault("FANOUT_DB_USER", "fanout"));
        dataSource.setPassword(environment.get("FANOUT_DB_PASSWORD"));
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT nspname FROM pg_namespace
                        WHERE starts_with(nspname, 'fanout_demo_') ORDER BY nspname
                        """)) {
            var schemas = new ArrayList<String>();
            while (rows.next()) {
                schemas.add(rows.getString(1));
            }
            return schemas;
        }
    }
}

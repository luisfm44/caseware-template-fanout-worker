package io.github.luisfm44.caseware.worker.adapter.postgres;

import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

/** Applies the versioned SQL schema before using the planning store or durable job queue. */
public final class FlywaySchema {

    private FlywaySchema() {}

    public static void migrate(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        final String schema;
        try (var connection = dataSource.getConnection()) {
            schema = connection.getSchema();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot determine PostgreSQL schema for Flyway", failure);
        }
        if (schema == null || schema.isBlank()) {
            throw new IllegalStateException("Configure an existing PostgreSQL currentSchema before Flyway migration");
        }
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(false)
                .baselineOnMigrate(false)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }
}

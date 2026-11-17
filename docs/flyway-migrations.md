# PostgreSQL schema lifecycle (Flyway)

The Java 25 worker uses Flyway versioned SQL migrations instead of issuing DDL from planning and queue constructors.

## Fresh schema

1. Provision an **empty** PostgreSQL schema for the worker pool.
2. Supply `FANOUT_DB_URL` with the correct `currentSchema`, plus `FANOUT_DB_USER` and `FANOUT_DB_PASSWORD`.
3. Start the planner/demo or a `postgres-worker`. `FlywaySchema.migrate` applies pending migrations before the runtime uses the tables. The schema must already exist: `createSchemas(false)`.
4. Inspect `<schema>.flyway_schema_history` to verify the current migration version. Starting another worker on the same schema validates existing migrations and applies only new ones.

The migrations are:
- `V1__create_planning_tables.sql`: `fanout_checkpoint` and `fanout_job`, key and foreign key.
- `V2__add_durable_job_execution.sql`: durable state, leases, attempts, constraints and indexes.
- `V3__add_shared_admission_and_firm_rotation.sql`: shared capacity table and firm-rotation index.

The per-schema `fanout_capacity` **row** is a runtime value: it is initialized by `PostgresJobQueue` with the requested capacity and validated against subsequent workers. Flyway creates the table, not that capacity reservation. All worker replicas in a pool must use the same schema, capacity, and downstream origin.

## Existing database adoption — do not run blind

**Do not run fresh-schema V1–V3 migrations against a preexisting worker schema without an audited transition plan.** `baselineOnMigrate(false)` deliberately stops migration against non-empty schemas that have no `flyway_schema_history` record.

Before adopting an existing schema:
1. Back up the schema and snapshot its data, including unconfirmed `SUBMITTED` attempts.
2. Check its real tables, columns, indexes, checks, constraints and capacity values against the final V3 definition; older planning-only schemas have no execution columns.
3. Identify the exact version represented by the existing schema, and test an explicit baseline or transition migration in a restored copy.
4. Only after evidence confirms equivalence, baseline with that exact version using an approved administrative process, then run validation and future migrations. Do not automatically baseline at V3 merely because the tables exist.
5. Roll out schema changes before starting new worker replicas; review cross-version compatibility for rolling deployment.

Never run `flyway clean` against a schema with worker data. Do not delete tables or existing attempts to unblock migration.

## Local development and CI

The root tests create isolated, empty test schemas and validate the SQL migrations against PostgreSQL 18. The demo creates a disposable schema that it drops after reporting. Tests for Java concurrency and crash recovery still operate over the same PostgreSQL job ledger.

```sh
export FANOUT_DB_PASSWORD='your-local-password'
docker compose up -d --wait
./mvnw -Ppostgres-integration,coverage -DfailIfNoTests=true verify
```

When the repository is on a macOS external drive, keep build output outside that volume and do not use an empty build directory:

```sh
./mvnw -Dfanout.build.directory=/private/tmp/caseware-fanout-target \
  -Ppostgres-integration,coverage -DfailIfNoTests=true verify
```

For deployed systems, consider applying Flyway as a dedicated deployment step before starting workers rather than invoking it from each runtime constructor. That is a follow-up deployment hardening step.

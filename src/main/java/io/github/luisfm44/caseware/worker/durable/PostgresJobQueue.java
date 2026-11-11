package io.github.luisfm44.caseware.worker.durable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import io.github.luisfm44.caseware.worker.OperationId;
import io.github.luisfm44.caseware.worker.durable.AttemptTransport.Outcome;

/** Durable claims and downstream reservations for one capacity pool per PostgreSQL schema. */
public final class PostgresJobQueue {

    private static final int MAX_SUBMISSIONS = 3;
    private static final String CURRENT_CLAIM = """
            WHERE firm_id = ? AND publication_id = ? AND engagement_id = ? AND engagement_revision = ?
              AND claim_token = ? AND claim_until > clock_timestamp()
            """;

    private final DataSource dataSource;
    private final long claimMillis;

    public PostgresJobQueue(DataSource dataSource, int capacity, Duration claimTtl) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.claimMillis = claimTtl.toMillis();
        if (capacity <= 0 || claimMillis <= 0) {
            throw new IllegalArgumentException("capacity and claim duration must be positive");
        }

        transaction(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(6975431162778140921)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS fanout_capacity (
                            singleton BOOLEAN PRIMARY KEY CHECK (singleton),
                            capacity INTEGER NOT NULL CHECK (capacity > 0)
                        )
                        """);
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO fanout_capacity (singleton, capacity) VALUES (TRUE, ?)
                    ON CONFLICT (singleton) DO NOTHING
                    """)) {
                insert.setInt(1, capacity);
                if (insert.executeUpdate() == 1) {
                    initializeExecutionColumns(connection);
                }
            }
            if (lockCapacity(connection) != capacity) {
                throw new IllegalArgumentException("worker capacity differs from the capacity configured for this schema");
            }
            return null;
        });
    }

    private static void initializeExecutionColumns(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE fanout_job
                        ADD COLUMN execution_state TEXT NOT NULL DEFAULT 'READY',
                        ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        ADD COLUMN claim_token UUID,
                        ADD COLUMN claim_until TIMESTAMPTZ,
                        ADD COLUMN attempt_id UUID,
                        ADD COLUMN authorized_at TIMESTAMPTZ,
                        ADD COLUMN submissions INTEGER NOT NULL DEFAULT 0,
                        ADD COLUMN poll_count INTEGER NOT NULL DEFAULT 0,
                        ADD CHECK (execution_state IN ('READY', 'SUBMITTED', 'APPLIED', 'REJECTED', 'RETRY_LIMIT')),
                        ADD CHECK ((execution_state = 'SUBMITTED') = (attempt_id IS NOT NULL)),
                        ADD CHECK ((attempt_id IS NULL) = (authorized_at IS NULL)),
                        ADD CHECK ((claim_token IS NULL) = (claim_until IS NULL))
                    """);
            statement.execute("""
                    CREATE INDEX fanout_job_ready ON fanout_job
                        (execution_state DESC, available_at, firm_id, publication_id, engagement_id, engagement_revision)
                    WHERE execution_state IN ('READY', 'SUBMITTED')
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX fanout_job_reservations ON fanout_job (attempt_id)
                    WHERE attempt_id IS NOT NULL
                    """);
        }
    }

    /** Prioritizes due reconciliation over fresh work so a full pool can recover its slots. */
    public Optional<Claim> claimReady() {
        return query(connection -> {
            try (var statement = connection.prepareStatement("""
                    WITH candidate AS (
                        SELECT firm_id, publication_id, engagement_id, engagement_revision
                        FROM fanout_job
                        WHERE execution_state IN ('READY', 'SUBMITTED')
                          AND available_at <= statement_timestamp()
                          AND (claim_until IS NULL OR claim_until <= statement_timestamp())
                        ORDER BY execution_state DESC, available_at, firm_id, publication_id, engagement_id, engagement_revision
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                    )
                    UPDATE fanout_job AS job
                    SET claim_token = ?, claim_until = clock_timestamp() + (? * INTERVAL '1 millisecond')
                    FROM candidate
                    WHERE (job.firm_id, job.publication_id, job.engagement_id, job.engagement_revision) =
                          (candidate.firm_id, candidate.publication_id, candidate.engagement_id, candidate.engagement_revision)
                    RETURNING job.firm_id, job.engagement_id, job.publication_id, job.engagement_revision,
                              job.claim_token, job.attempt_id, job.submissions, job.poll_count
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setLong(2, claimMillis);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(readClaim(rows)) : Optional.empty();
                }
            }
        });
    }

    /** Reserves capacity and records an authorized submission in the same transaction. */
    public Optional<UUID> authorizeSubmission(Claim claim) {
        return transaction(connection -> {
            int capacity = lockCapacity(connection);
            try (var statement = connection.prepareStatement("""
                    UPDATE fanout_job
                    SET execution_state = 'SUBMITTED', attempt_id = ?,
                        authorized_at = clock_timestamp(), submissions = submissions + 1
                    """ + CURRENT_CLAIM + """
                      AND execution_state = 'READY' AND submissions < ?
                      AND (SELECT count(*) FROM fanout_job WHERE attempt_id IS NOT NULL) < ?
                    """)) {
                var attemptId = UUID.randomUUID();
                statement.setObject(1, attemptId);
                bindClaim(statement, 2, claim);
                statement.setInt(7, MAX_SUBMISSIONS);
                statement.setInt(8, capacity);
                return statement.executeUpdate() == 1 ? Optional.of(attemptId) : Optional.empty();
            }
        });
    }

    /** Releases the claim for a later poll, retaining any uncertain attempt and its reservation. */
    public void defer(Claim claim, Duration delay) {
        query(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE fanout_job
                    SET claim_token = NULL, claim_until = NULL,
                        available_at = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                        poll_count = CASE WHEN attempt_id IS NULL THEN 0 ELSE LEAST(poll_count + 1, 30) END
                    """ + CURRENT_CLAIM)) {
                statement.setLong(1, delay.toMillis());
                bindClaim(statement, 2, claim);
                return statement.executeUpdate();
            }
        });
    }

    /** Persists the confirmed outcome and returns the slot with one atomic row update. */
    public void settle(Claim claim, UUID attemptId, Outcome outcome, Duration nextDelay) {
        if (outcome == Outcome.PENDING) {
            throw new IllegalArgumentException("an uncertain outcome cannot release a reservation");
        }
        query(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE fanout_job
                    SET execution_state = CASE
                            WHEN ? <> 'NOT_APPLIED' THEN ?
                            WHEN submissions >= ? THEN 'RETRY_LIMIT'
                            ELSE 'READY'
                        END,
                        available_at = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                        attempt_id = NULL, authorized_at = NULL, claim_token = NULL, claim_until = NULL, poll_count = 0
                    """ + CURRENT_CLAIM + """
                      AND attempt_id = ?
                    """)) {
                statement.setString(1, outcome.name());
                statement.setString(2, outcome.name());
                statement.setInt(3, MAX_SUBMISSIONS);
                statement.setLong(4, nextDelay.toMillis());
                bindClaim(statement, 5, claim);
                statement.setObject(10, attemptId);
                return statement.executeUpdate();
            }
        });
    }

    private static Claim readClaim(ResultSet row) throws SQLException {
        var operation = new OperationId(row.getString(1), row.getString(2), row.getString(3), row.getString(4));
        return new Claim(operation, row.getObject(5, UUID.class), row.getObject(6, UUID.class),
                row.getInt(7), row.getInt(8));
    }

    private static int lockCapacity(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT capacity FROM fanout_capacity WHERE singleton FOR UPDATE")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void bindClaim(PreparedStatement statement, int start, Claim claim) throws SQLException {
        var operation = claim.operationId();
        statement.setString(start, operation.firmId());
        statement.setString(start + 1, operation.publicationId());
        statement.setString(start + 2, operation.engagementId());
        statement.setString(start + 3, operation.engagementRevision());
        statement.setObject(start + 4, claim.token());
    }

    private <T> T query(SqlOperation<T> operation) {
        try (var connection = dataSource.getConnection()) {
            return operation.run(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not access the durable job queue", failure);
        }
    }

    private <T> T transaction(SqlOperation<T> operation) {
        return query(connection -> {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                var result = operation.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        });
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run(Connection connection) throws SQLException;
    }

    public record Claim(OperationId operationId, UUID token, UUID attemptId, int submissions, int pollCount) {}
}

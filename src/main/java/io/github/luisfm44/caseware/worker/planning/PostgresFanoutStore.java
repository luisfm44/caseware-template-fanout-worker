package io.github.luisfm44.caseware.worker.planning;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

import io.github.luisfm44.caseware.worker.OperationId;

public final class PostgresFanoutStore implements FanoutStore {

    private static final Checkpoint INITIAL = new Checkpoint(0, null, false);

    private final DataSource dataSource;

    public PostgresFanoutStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        initializeTables();
    }

    private void initializeTables() {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS fanout_checkpoint (
                        firm_id TEXT NOT NULL,
                        publication_id TEXT NOT NULL,
                        pages_committed BIGINT NOT NULL CHECK (pages_committed > 0),
                        next_cursor TEXT,
                        complete BOOLEAN NOT NULL,
                        PRIMARY KEY (firm_id, publication_id),
                        CHECK (complete = (next_cursor IS NULL))
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS fanout_job (
                        firm_id TEXT NOT NULL,
                        publication_id TEXT NOT NULL,
                        engagement_id TEXT NOT NULL,
                        engagement_revision TEXT NOT NULL,
                        PRIMARY KEY (firm_id, publication_id, engagement_id, engagement_revision),
                        FOREIGN KEY (firm_id, publication_id) REFERENCES fanout_checkpoint
                    )
                    """);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not initialize fan-out tables", failure);
        }
    }

    @Override
    public Checkpoint checkpoint(Publication publication) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        SELECT pages_committed, next_cursor, complete
                        FROM fanout_checkpoint
                        WHERE firm_id = ? AND publication_id = ?
                        """)) {
            bindPublication(statement, 1, publication);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    return new Checkpoint(rows.getLong(1), rows.getString(2), rows.getBoolean(3));
                }
                return INITIAL;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read fan-out checkpoint", failure);
        }
    }

    @Override
    public boolean commitPage(Publication publication, Checkpoint expected, List<OperationId> jobs, String nextCursor) {
        if (expected.complete()) {
            return false;
        }

        var pageJobs = List.copyOf(jobs);
        for (var job : pageJobs) {
            if (!job.firmId().equals(publication.firmId()) || !job.publicationId().equals(publication.publicationId())) {
                throw new IllegalArgumentException("Every job must belong to the publication being planned");
            }
        }

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!advanceCheckpoint(connection, publication, expected, nextCursor)) {
                    connection.rollback();
                    return false;
                }

                insertJobs(connection, pageJobs);
                connection.commit();
                return true;
            } catch (SQLException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not commit fan-out page", failure);
        }
    }

    private static boolean advanceCheckpoint(
            Connection connection, Publication publication, Checkpoint expected, String nextCursor)
            throws SQLException {
        if (expected.equals(INITIAL)) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO fanout_checkpoint (firm_id, publication_id, pages_committed, next_cursor, complete)
                    VALUES (?, ?, 1, ?, ?)
                    ON CONFLICT (firm_id, publication_id) DO NOTHING
                    """)) {
                bindPublication(statement, 1, publication);
                statement.setString(3, nextCursor);
                statement.setBoolean(4, nextCursor == null);
                return statement.executeUpdate() == 1;
            }
        }

        try (var statement = connection.prepareStatement("""
                UPDATE fanout_checkpoint
                SET pages_committed = pages_committed + 1, next_cursor = ?, complete = ?
                WHERE firm_id = ? AND publication_id = ?
                  AND pages_committed = ? AND next_cursor IS NOT DISTINCT FROM ? AND NOT complete
                """)) {
            statement.setString(1, nextCursor);
            statement.setBoolean(2, nextCursor == null);
            bindPublication(statement, 3, publication);
            statement.setLong(5, expected.pagesCommitted());
            statement.setString(6, expected.nextCursor());
            return statement.executeUpdate() == 1;
        }
    }

    private static void insertJobs(Connection connection, List<OperationId> jobs) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO fanout_job (firm_id, publication_id, engagement_id, engagement_revision)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (firm_id, publication_id, engagement_id, engagement_revision) DO NOTHING
                """)) {
            for (var job : jobs) {
                statement.setString(1, job.firmId());
                statement.setString(2, job.publicationId());
                statement.setString(3, job.engagementId());
                statement.setString(4, job.engagementRevision());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void bindPublication(PreparedStatement statement, int firstIndex, Publication publication)
            throws SQLException {
        statement.setString(firstIndex, publication.firmId());
        statement.setString(firstIndex + 1, publication.publicationId());
    }
}

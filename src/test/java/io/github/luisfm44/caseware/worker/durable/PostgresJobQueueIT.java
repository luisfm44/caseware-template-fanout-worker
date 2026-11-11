package io.github.luisfm44.caseware.worker.durable;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import io.github.luisfm44.caseware.worker.OperationId;
import io.github.luisfm44.caseware.worker.durable.AttemptTransport.Outcome;
import io.github.luisfm44.caseware.worker.planning.PostgresFanoutStore;
import io.github.luisfm44.caseware.worker.planning.Publication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresJobQueueIT {

    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final Duration NOW = Duration.ZERO;
    private static final Publication PUBLICATION = new Publication("firm", "publication");
    private static final OperationId FIRST = operation("first");
    private static final OperationId SECOND = operation("second");

    private final PGSimpleDataSource database = dataSource();
    private final String schema = "fanout_queue_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void createSchema() throws SQLException {
        execute("CREATE SCHEMA " + schema);
        database.setCurrentSchema(schema);
    }

    @AfterEach
    void dropSchema() throws SQLException {
        execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void recoversAnAuthorizedAttemptAndExhaustsTheBudgetAcrossClaims() throws SQLException {
        seed(FIRST);

        for (int number = 1; number <= 3; number++) {
            var original = new PostgresJobQueue(database, 1, LEASE);
            var claim = original.claimReady().orElseThrow();
            var attempt = original.authorizeSubmission(claim).orElseThrow();
            assertThat(original.authorizeSubmission(claim)).isEmpty();
            assertThatThrownBy(() -> original.settle(claim, attempt, Outcome.PENDING, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            expireFirstClaim();

            var restarted = new PostgresJobQueue(database, 1, LEASE);
            var recovered = restarted.claimReady().orElseThrow();
            assertThat(recovered.operationId()).isEqualTo(FIRST);
            assertThat(recovered.attemptId()).isEqualTo(attempt);
            assertThat(recovered.submissions()).isEqualTo(number);
            assertThat(recovered.token()).isNotEqualTo(claim.token());

            original.settle(claim, attempt, Outcome.APPLIED, NOW);
            assertThat(state(FIRST)).isEqualTo("SUBMITTED");
            restarted.settle(recovered, attempt, Outcome.NOT_APPLIED, NOW);
        }

        assertThat(state(FIRST)).isEqualTo("RETRY_LIMIT");
        assertThat(new PostgresJobQueue(database, 1, LEASE).claimReady()).isEmpty();
    }

    @Test
    void concurrentAuthorizationsCannotBothTakeTheLastSlot() throws Exception {
        seed(FIRST, SECOND);
        var firstWorker = new PostgresJobQueue(database, 1, LEASE);
        var secondWorker = new PostgresJobQueue(database, 1, LEASE);
        var first = firstWorker.claimReady().orElseThrow();
        var second = secondWorker.claimReady().orElseThrow();
        var start = new CyclicBarrier(2);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstAuthorization = threads.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return firstWorker.authorizeSubmission(first);
            });
            var secondAuthorization = threads.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return secondWorker.authorizeSubmission(second);
            });
            var firstAttempt = firstAuthorization.get(5, TimeUnit.SECONDS);
            var secondAttempt = secondAuthorization.get(5, TimeUnit.SECONDS);
            assertThat(firstAttempt.isPresent()).isNotEqualTo(secondAttempt.isPresent());
        }

        assertThat(List.of(state(FIRST), state(SECOND))).containsExactlyInAnyOrder("READY", "SUBMITTED");
    }

    @Test
    void completionAndSlotReleaseRollBackTogetherAndRecoverAfterRestart() throws SQLException {
        seed(FIRST, SECOND);
        var firstWorker = new PostgresJobQueue(database, 1, LEASE);
        var first = firstWorker.claimReady().orElseThrow();
        var attempt = firstWorker.authorizeSubmission(first).orElseThrow();

        execute("ALTER TABLE fanout_job ADD CONSTRAINT fail_terminal_write CHECK (execution_state <> 'APPLIED')");
        assertThatThrownBy(() -> firstWorker.settle(first, attempt, Outcome.APPLIED, NOW))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(state(FIRST)).isEqualTo("SUBMITTED");

        var secondWorker = new PostgresJobQueue(database, 1, LEASE);
        var second = secondWorker.claimReady().orElseThrow();
        assertThat(second.operationId()).isEqualTo(SECOND);
        assertThat(secondWorker.authorizeSubmission(second)).isEmpty();
        secondWorker.defer(second, NOW);
        execute("ALTER TABLE fanout_job DROP CONSTRAINT fail_terminal_write");
        expireFirstClaim();

        var recovered = secondWorker.claimReady().orElseThrow();
        assertThat(recovered.attemptId()).isEqualTo(attempt);
        secondWorker.settle(recovered, attempt, Outcome.APPLIED, NOW);
        assertThat(state(FIRST)).isEqualTo("APPLIED");

        var thirdWorker = new PostgresJobQueue(database, 1, LEASE);
        var next = thirdWorker.claimReady().orElseThrow();
        assertThat(next.operationId()).isEqualTo(SECOND);
        var nextAttempt = thirdWorker.authorizeSubmission(next).orElseThrow();
        firstWorker.settle(first, attempt, Outcome.REJECTED, NOW);
        assertThat(state(FIRST)).isEqualTo("APPLIED");
        thirdWorker.settle(next, nextAttempt, Outcome.REJECTED, NOW);
        assertThat(state(SECOND)).isEqualTo("REJECTED");
        assertThat(thirdWorker.claimReady()).isEmpty();
    }

    @Test
    void refusesInvalidOrConflictingConfiguration() {
        seed(FIRST);
        assertThatThrownBy(() -> new PostgresJobQueue(database, 0, LEASE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresJobQueue(database, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        new PostgresJobQueue(database, 1, LEASE);
        assertThatThrownBy(() -> new PostgresJobQueue(database, 2, LEASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity differs");
    }

    @Test
    void reportsTheOriginalFailureIfRollbackAlsoFails() throws SQLException {
        var connection = mock(Connection.class);
        var source = mock(DataSource.class);
        var writeFailure = new SQLException("could not prepare schema");
        var rollbackFailure = new SQLException("lost database connection");
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenThrow(writeFailure);
        doThrow(rollbackFailure).when(connection).rollback();

        assertThatThrownBy(() -> new PostgresJobQueue(source, 1, LEASE))
                .hasCause(writeFailure);
        assertThat(writeFailure.getSuppressed()).containsExactly(rollbackFailure);
    }

    private void seed(OperationId... jobs) {
        var planning = new PostgresFanoutStore(database);
        assertThat(planning.commitPage(PUBLICATION, planning.checkpoint(PUBLICATION), List.of(jobs), null)).isTrue();
    }

    private void expireFirstClaim() throws SQLException {
        execute("UPDATE fanout_job SET claim_until = clock_timestamp() - INTERVAL '1 second' WHERE engagement_id = 'first'");
    }

    private String state(OperationId job) throws SQLException {
        try (var connection = database.getConnection();
                var query = connection.prepareStatement("SELECT execution_state FROM fanout_job WHERE engagement_id = ?")) {
            query.setString(1, job.engagementId());
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static PGSimpleDataSource dataSource() {
        var source = new PGSimpleDataSource();
        var environment = System.getenv();
        source.setURL(environment.getOrDefault("FANOUT_DB_URL", "jdbc:postgresql://127.0.0.1:55432/fanout"));
        source.setUser(environment.getOrDefault("FANOUT_DB_USER", "fanout"));
        source.setPassword(environment.get("FANOUT_DB_PASSWORD"));
        return source;
    }

    private static OperationId operation(String engagement) {
        return new OperationId(PUBLICATION.firmId(), engagement, PUBLICATION.publicationId(), "revision");
    }
}

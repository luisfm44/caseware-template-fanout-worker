package io.github.luisfm44.caseware.worker.adapter.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport.Outcome;
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
            var attempt = claim.attemptId();
            assertThat(claim.reconciling()).isFalse();
            assertThat(original.claimReady()).isEmpty();
            assertThatThrownBy(() -> original.settle(claim, attempt, Outcome.PENDING, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            expireFirstClaim();

            var restarted = new PostgresJobQueue(database, 1, LEASE);
            var recovered = restarted.claimReady().orElseThrow();
            assertThat(recovered.operationId()).isEqualTo(FIRST);
            assertThat(recovered.reconciling()).isTrue();
            assertThat(recovered.attemptId()).isEqualTo(attempt);
            assertThat(recovered.submissions()).isEqualTo(number);
            assertThat(recovered.token()).isNotEqualTo(claim.token());

            assertThat(original.settle(claim, attempt, Outcome.APPLIED, NOW)).isFalse();
            assertThat(state(FIRST)).isEqualTo("SUBMITTED");
            assertThat(restarted.settle(recovered, attempt, Outcome.NOT_APPLIED, NOW)).isTrue();
        }

        assertThat(state(FIRST)).isEqualTo("RETRY_LIMIT");
        assertThat(new PostgresJobQueue(database, 1, LEASE).claimReady()).isEmpty();
    }

    @Test
    void concurrentClaimsCannotBothTakeTheLastSlot() throws Exception {
        seed(FIRST, SECOND);
        var firstWorker = new PostgresJobQueue(database, 1, LEASE);
        var secondWorker = new PostgresJobQueue(database, 1, LEASE);
        var start = new CyclicBarrier(2);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstAuthorization = threads.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return firstWorker.claimReady();
            });
            var secondAuthorization = threads.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return secondWorker.claimReady();
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
        var attempt = first.attemptId();

        execute("ALTER TABLE fanout_job ADD CONSTRAINT fail_terminal_write CHECK (execution_state <> 'APPLIED')");
        assertThatThrownBy(() -> firstWorker.settle(first, attempt, Outcome.APPLIED, NOW))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(state(FIRST)).isEqualTo("SUBMITTED");

        var secondWorker = new PostgresJobQueue(database, 1, LEASE);
        assertThat(secondWorker.claimReady()).isEmpty();
        execute("ALTER TABLE fanout_job DROP CONSTRAINT fail_terminal_write");
        expireFirstClaim();

        var recovered = secondWorker.claimReady().orElseThrow();
        assertThat(recovered.attemptId()).isEqualTo(attempt);
        secondWorker.settle(recovered, attempt, Outcome.APPLIED, NOW);
        assertThat(state(FIRST)).isEqualTo("APPLIED");

        var thirdWorker = new PostgresJobQueue(database, 1, LEASE);
        var next = thirdWorker.claimReady().orElseThrow();
        assertThat(next.operationId()).isEqualTo(SECOND);
        var nextAttempt = next.attemptId();
        firstWorker.settle(first, attempt, Outcome.REJECTED, NOW);
        assertThat(state(FIRST)).isEqualTo("APPLIED");
        thirdWorker.settle(next, nextAttempt, Outcome.REJECTED, NOW);
        assertThat(state(SECOND)).isEqualTo("REJECTED");
        assertThat(thirdWorker.claimReady()).isEmpty();
    }

    @Test
    void rotatesAcrossReadyFirmsAndSkipsAnIneligibleRetry() throws SQLException {
        var a = new Publication("firm-a", "release");
        var b = new Publication("firm-b", "release");
        var c = new Publication("firm-c", "release");
        var a1 = operation(a, "a1");
        var a2 = operation(a, "a2");
        var b1 = operation(b, "b1");
        var c1 = operation(c, "c1");

        new PostgresFanoutStore(database);
        var queue = new PostgresJobQueue(database, 1, LEASE);
        assertThat(queue.claimReady()).isEmpty();
        seed(a, a1, a2);
        seed(b, b1);
        seed(c, c1);
        var first = queue.claimReady().orElseThrow();
        assertThat(first.operationId()).isEqualTo(a1);
        queue.settle(first, first.attemptId(), Outcome.NOT_APPLIED, Duration.ofHours(1));

        var restarted = new PostgresJobQueue(database, 1, LEASE);
        for (var expected : List.of(b1, c1, a2)) {
            var claim = restarted.claimReady().orElseThrow();
            assertThat(claim.operationId()).isEqualTo(expected);
            restarted.settle(claim, claim.attemptId(), Outcome.APPLIED, NOW);
        }

        assertThat(restarted.claimReady()).isEmpty();
        execute("UPDATE fanout_job SET available_at = clock_timestamp() WHERE engagement_id = 'a1'");
        var retry = restarted.claimReady().orElseThrow();
        assertThat(retry.operationId()).isEqualTo(a1);
        assertThat(retry.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(retry.submissions()).isEqualTo(2);
    }

    @Test
    void upgradesAnExistingPoolWithoutLosingItsReservedAttempt() throws SQLException {
        seed(FIRST, SECOND);
        var original = new PostgresJobQueue(database, 1, LEASE);
        var claim = original.claimReady().orElseThrow();
        execute("DROP INDEX fanout_job_ready_by_firm");
        execute("ALTER TABLE fanout_capacity DROP COLUMN last_firm_id");

        var upgraded = new PostgresJobQueue(database, 1, LEASE);
        assertThat(upgraded.claimReady()).isEmpty();
        expireFirstClaim();

        var recovery = upgraded.claimReady().orElseThrow();
        assertThat(recovery.attemptId()).isEqualTo(claim.attemptId());
        assertThat(recovery.submissions()).isEqualTo(1);
        assertThat(recovery.reconciling()).isTrue();
        upgraded.settle(recovery, recovery.attemptId(), Outcome.APPLIED, NOW);
        assertThat(upgraded.claimReady().orElseThrow().operationId()).isEqualTo(SECOND);
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
        seed(PUBLICATION, jobs);
    }

    private void seed(Publication publication, OperationId... jobs) {
        var planning = new PostgresFanoutStore(database);
        assertThat(planning.commitPage(publication, planning.checkpoint(publication), List.of(jobs), null,
                "queue-test-snapshot")).isTrue();
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
        return operation(PUBLICATION, engagement);
    }

    private static OperationId operation(Publication publication, String engagement) {
        return new OperationId(publication.firmId(), engagement, publication.publicationId(), "revision");
    }
}

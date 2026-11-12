package io.github.luisfm44.caseware.worker.planning;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import io.github.luisfm44.caseware.worker.adapter.postgres.PostgresFanoutStore;
import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.planning.EngagementSource.Engagement;
import io.github.luisfm44.caseware.worker.planning.EngagementSource.Page;
import io.github.luisfm44.caseware.worker.planning.FanoutStore.Checkpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicationFanoutIT {

    private static final Publication PUBLICATION = new Publication("firm-a", "publication-42");
    private static final String SNAPSHOT_ID = "engagements-v1";
    private static final int PAGE_SIZE = 2;
    private static final List<Engagement> ELIGIBLE = List.of(
            new Engagement("engagement-1", "revision-1"),
            new Engagement("engagement-2", "revision-7"),
            new Engagement("engagement-3", "revision-1"),
            new Engagement("engagement-4", "revision-2"),
            new Engagement("engagement-5", "revision-3"));

    private String schema;

    @BeforeEach
    void createSchema() throws SQLException {
        var testSchema = "fanout_test_" + UUID.randomUUID().toString().replace("-", "");
        execute(dataSource(), "CREATE SCHEMA " + testSchema);
        schema = testSchema;
    }

    @AfterEach
    void dropSchema() throws SQLException {
        if (schema != null) {
            execute(dataSource(), "DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void resumesAfterTheFirstPageAndReplayingDoesNotCreateMoreJobs() throws SQLException {
        var dataSource = dataSource();
        var firstStore = new PostgresFanoutStore(dataSource);
        var initialRequests = new ArrayList<String>();
        var firstPlanner = new PublicationFanout(source(initialRequests), firstStore, PAGE_SIZE);

        assertThat(firstPlanner.planNextPage(PUBLICATION)).isTrue();
        assertThat(initialRequests).containsExactly((String) null);
        assertThat(firstStore.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(1, "after-2", false, SNAPSHOT_ID));
        assertThat(storedJobs(dataSource)).containsExactlyInAnyOrderElementsOf(expectedJobs().subList(0, 2));

        var reopenedDataSource = dataSource();
        var reopenedStore = new PostgresFanoutStore(reopenedDataSource);
        var resumedRequests = new ArrayList<String>();
        var resumedPlanner = new PublicationFanout(source(resumedRequests), reopenedStore, PAGE_SIZE);
        resumedPlanner.plan(PUBLICATION);

        assertThat(resumedRequests).containsExactly("after-2", "after-4");
        assertThat(reopenedStore.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(3, null, true, SNAPSHOT_ID));
        assertThat(storedJobs(reopenedDataSource)).containsExactlyInAnyOrderElementsOf(expectedJobs());

        resumedRequests.clear();
        resumedPlanner.plan(PUBLICATION);
        assertThat(resumedRequests).isEmpty();
        assertThat(reopenedStore.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(3, null, true, SNAPSHOT_ID));
        assertThat(storedJobs(reopenedDataSource)).containsExactlyInAnyOrderElementsOf(expectedJobs());
    }

    @Test
    void rollsBackJobsAndCheckpointIfAPageCannotBeRecorded() throws SQLException {
        var dataSource = dataSource();
        var store = new PostgresFanoutStore(dataSource);
        var requestedCursors = new ArrayList<String>();
        var planner = new PublicationFanout(source(requestedCursors), store, PAGE_SIZE);

        assertThat(planner.planNextPage(PUBLICATION)).isTrue();
        execute(dataSource, """
                ALTER TABLE fanout_job ADD CONSTRAINT reject_fourth_engagement
                    CHECK (engagement_id <> 'engagement-4')
                """);

        assertThatThrownBy(() -> planner.planNextPage(PUBLICATION))
                .hasRootCauseInstanceOf(SQLException.class);

        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(1, "after-2", false, SNAPSHOT_ID));
        assertThat(storedJobs(dataSource)).containsExactlyInAnyOrderElementsOf(expectedJobs().subList(0, 2));

        execute(dataSource, "ALTER TABLE fanout_job DROP CONSTRAINT reject_fourth_engagement");
        planner.plan(PUBLICATION);

        assertThat(requestedCursors).containsExactly(null, "after-2", "after-2", "after-4");
        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(3, null, true, SNAPSHOT_ID));
        assertThat(storedJobs(dataSource)).containsExactlyInAnyOrderElementsOf(expectedJobs());
    }

    @Test
    void rejectsAStalePageWithoutChangingItsJobsOrCursor() throws SQLException {
        var dataSource = dataSource();
        var firstStore = new PostgresFanoutStore(dataSource);
        var otherStore = new PostgresFanoutStore(dataSource());
        var staleCheckpoint = otherStore.checkpoint(PUBLICATION);
        var committedJob = expectedJobs().get(0);
        var staleJob = expectedJobs().get(1);

        assertThat(firstStore.commitPage(PUBLICATION, staleCheckpoint, List.of(committedJob), "after-1", SNAPSHOT_ID))
                .isTrue();
        assertThat(otherStore.commitPage(PUBLICATION, staleCheckpoint, List.of(staleJob), "after-2", SNAPSHOT_ID))
                .isFalse();

        assertThat(otherStore.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(1, "after-1", false, SNAPSHOT_ID));
        assertThat(storedJobs(dataSource)).containsExactly(committedJob);

        var previous = otherStore.checkpoint(PUBLICATION);
        assertThat(firstStore.commitPage(PUBLICATION, previous, List.of(staleJob), "after-2", SNAPSHOT_ID)).isTrue();
        assertThat(otherStore.commitPage(PUBLICATION, previous, List.of(expectedJobs().get(2)), null, SNAPSHOT_ID)).isFalse();

        assertThat(otherStore.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(2, "after-2", false, SNAPSHOT_ID));
        assertThat(storedJobs(dataSource)).containsExactlyInAnyOrder(committedJob, staleJob);
    }

    @Test
    void advancesOnlyACheckpointForTheOriginalSnapshot() {
        var store = new PostgresFanoutStore(dataSource());
        var initial = store.checkpoint(PUBLICATION);
        var firstJob = expectedJobs().get(0);
        var nextJobs = List.of(expectedJobs().get(1));

        for (String invalidSnapshot : new String[] {null, "  "}) {
            assertThatThrownBy(() -> store.commitPage(PUBLICATION, initial, List.of(firstJob), "after-1",
                    invalidSnapshot))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("snapshotId must be non-blank");
        }
        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(initial);
        assertThat(store.listJobs(PUBLICATION, 10)).isEmpty();
        assertThat(store.commitPage(PUBLICATION, initial, List.of(firstJob), "after-1", SNAPSHOT_ID)).isTrue();

        var committed = store.checkpoint(PUBLICATION);
        var otherSnapshot = "engagements-v2";
        assertThatThrownBy(() -> store.commitPage(PUBLICATION, committed, nextJobs, null, otherSnapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A committed checkpoint requires its original snapshot");

        var differentCheckpoint = new Checkpoint(committed.pagesCommitted(), committed.nextCursor(), false,
                otherSnapshot);
        assertThat(store.commitPage(PUBLICATION, differentCheckpoint, nextJobs, null, otherSnapshot)).isFalse();
        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(committed);
        assertThat(store.listJobs(PUBLICATION, 10)).containsExactly(firstJob);
    }

    @Test
    void resumesTheWinningSnapshotAfterAnotherPlannerCommitsFirst() throws SQLException {
        var store = new PostgresFanoutStore(dataSource());
        var competingPlanner = new PublicationFanout(source(new ArrayList<>()), store, PAGE_SIZE);
        var requestedCursors = new ArrayList<String>();
        var requestedSnapshots = new ArrayList<String>();
        var pages = source(requestedCursors);
        EngagementSource sourceWithConcurrentCommit = (publication, snapshotId, cursor, limit) -> {
            requestedSnapshots.add(snapshotId);
            if (cursor == null) {
                requestedCursors.add(null);
                assertThat(competingPlanner.planNextPage(publication)).isTrue();
                return new Page(List.of(new Engagement("losing-engagement", "revision-1")),
                        "losing-cursor", "engagements-v2");
            }
            return pages.fetch(publication, snapshotId, cursor, limit);
        };

        new PublicationFanout(sourceWithConcurrentCommit, store, PAGE_SIZE).plan(PUBLICATION);

        assertThat(requestedSnapshots).containsExactly(null, SNAPSHOT_ID, SNAPSHOT_ID);
        assertThat(requestedCursors).containsExactly(null, "after-2", "after-4");
        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(3, null, true, SNAPSHOT_ID));
        assertThat(store.listJobs(PUBLICATION, 10)).containsExactlyElementsOf(expectedJobs());
    }

    @Test
    void rejectsOversizedPagesBeforeRecordingAnyJobs() throws SQLException {
        var store = new PostgresFanoutStore(dataSource());
        EngagementSource source = (publication, snapshotId, cursor, limit) -> new Page(ELIGIBLE, null, SNAPSHOT_ID);
        var planner = new PublicationFanout(source, store, PAGE_SIZE);

        assertThatThrownBy(() -> planner.planNextPage(PUBLICATION))
                .isInstanceOf(IllegalStateException.class).hasMessage("Engagement source exceeded the page size");

        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(0, null, false, null));
        assertThat(store.listJobs(PUBLICATION, 10)).isEmpty();
    }

    @Test
    void rejectsRepeatedCursorsWithoutAdvancingTheCheckpoint() throws SQLException {
        var store = new PostgresFanoutStore(dataSource());
        new PublicationFanout(source(new ArrayList<>()), store, PAGE_SIZE).planNextPage(PUBLICATION);
        EngagementSource source =
                (publication, snapshotId, cursor, limit) -> new Page(List.of(ELIGIBLE.get(2)), cursor, SNAPSHOT_ID);
        var planner = new PublicationFanout(source, store, PAGE_SIZE);

        assertThatThrownBy(() -> planner.planNextPage(PUBLICATION))
                .isInstanceOf(IllegalStateException.class).hasMessage("Engagement source returned the current cursor");

        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(1, "after-2", false, SNAPSHOT_ID));
        assertThat(store.listJobs(PUBLICATION, 10)).containsExactlyElementsOf(expectedJobs().subList(0, 2));
    }

    @Test
    void listsOnlyTheRequestedPublicationsJobsInStableBoundedOrder() throws SQLException {
        var store = new PostgresFanoutStore(dataSource());
        var firstRevision = new OperationId(PUBLICATION.firmId(), "engagement-1", PUBLICATION.publicationId(), "revision-1");
        var nextRevision = new OperationId(PUBLICATION.firmId(), "engagement-1", PUBLICATION.publicationId(), "revision-2");
        var otherEngagement = expectedJobs().get(1);
        var otherFirm = new Publication("firm-b", PUBLICATION.publicationId());
        var otherPublication = new Publication(PUBLICATION.firmId(), "publication-43");

        assertThat(store.commitPage(PUBLICATION, store.checkpoint(PUBLICATION),
                List.of(otherEngagement, nextRevision, firstRevision), null, SNAPSHOT_ID)).isTrue();
        var otherFirmJob = new OperationId(otherFirm.firmId(), "engagement-0", otherFirm.publicationId(), "revision-1");
        var otherPublicationJob = new OperationId(otherPublication.firmId(), "engagement-0",
                otherPublication.publicationId(), "revision-1");
        assertThat(store.commitPage(otherFirm, store.checkpoint(otherFirm), List.of(otherFirmJob), null, SNAPSHOT_ID))
                .isTrue();
        assertThat(store.commitPage(otherPublication, store.checkpoint(otherPublication),
                List.of(otherPublicationJob), null, SNAPSHOT_ID)).isTrue();

        assertThat(store.listJobs(PUBLICATION, 2)).containsExactly(firstRevision, nextRevision);
        assertThat(store.listJobs(PUBLICATION, 10)).containsExactly(firstRevision, nextRevision, otherEngagement);
        assertThatThrownBy(() -> store.listJobs(PUBLICATION, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("limit must be positive");
    }

    @Test
    void rejectsJobsThatBelongToAnotherFirmOrPublication() throws SQLException {
        var store = new PostgresFanoutStore(dataSource());
        var checkpoint = store.checkpoint(PUBLICATION);
        var otherFirm = new OperationId("firm-b", "engagement-1", PUBLICATION.publicationId(), "revision-1");
        var otherPublication = new OperationId(PUBLICATION.firmId(), "engagement-1", "publication-43", "revision-1");

        for (var foreignJob : List.of(otherFirm, otherPublication)) {
            assertThatThrownBy(() -> store.commitPage(PUBLICATION, checkpoint,
                    List.of(expectedJobs().getFirst(), foreignJob), null, SNAPSHOT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Every job must belong to the publication being planned");
        }

        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(checkpoint);
        assertThat(store.listJobs(PUBLICATION, 10)).isEmpty();
    }

    @Test
    void treatsAnEmptyFinalPageAsCompleteAndRejectsFurtherWrites() throws SQLException {
        var store = new PostgresFanoutStore(dataSource());
        EngagementSource source = (publication, snapshotId, cursor, limit) -> new Page(List.of(), null, SNAPSHOT_ID);
        var planner = new PublicationFanout(source, store, PAGE_SIZE);

        planner.plan(PUBLICATION);

        var checkpoint = store.checkpoint(PUBLICATION);
        assertThat(checkpoint).isEqualTo(new Checkpoint(1, null, true, SNAPSHOT_ID));
        assertThat(store.commitPage(PUBLICATION, checkpoint, List.of(expectedJobs().getFirst()), null, SNAPSHOT_ID)).isFalse();
        assertThat(store.listJobs(PUBLICATION, 10)).isEmpty();
    }

    @Test
    void reportsWhenTheConfiguredSchemaCannotBeInitialized() throws SQLException {
        var absentSchema = dataSource();
        absentSchema.setCurrentSchema(schema + "_absent");

        assertThatThrownBy(() -> new PostgresFanoutStore(absentSchema))
                .isInstanceOf(IllegalStateException.class).hasMessage("Could not initialize fan-out tables")
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void distinguishesCheckpointAndJobQueryFailuresFromAnEmptyResult() throws SQLException {
        var dataSource = dataSource();
        var store = new PostgresFanoutStore(dataSource);
        execute(dataSource, "ALTER TABLE fanout_checkpoint RENAME TO unavailable_checkpoint");
        execute(dataSource, "ALTER TABLE fanout_job RENAME TO unavailable_job");

        assertThatThrownBy(() -> store.checkpoint(PUBLICATION))
                .isInstanceOf(IllegalStateException.class).hasMessage("Could not read fan-out checkpoint")
                .hasRootCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> store.listJobs(PUBLICATION, 10))
                .isInstanceOf(IllegalStateException.class).hasMessage("Could not list planned fan-out jobs")
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    void preservesTheWriteFailureWhenTheDatabaseConnectionDropsDuringRollback() throws SQLException {
        var dataSource = dataSource();
        var storeWithWorkingConnection = new PostgresFanoutStore(dataSource);
        execute(dataSource, """
                ALTER TABLE fanout_job ADD CONSTRAINT reject_first_engagement
                    CHECK (engagement_id <> 'engagement-1')
                """);
        var checkpoint = storeWithWorkingConnection.checkpoint(PUBLICATION);
        var interruptedDataSource = mock(DataSource.class);
        try (var connection = dataSource.getConnection()) {
            var interruptedConnection = mock(Connection.class, delegatesTo(connection));
            doAnswer(invocation -> {
                connection.close();
                connection.rollback();
                return null;
            }).when(interruptedConnection).rollback();
            when(interruptedDataSource.getConnection()).thenReturn(dataSource.getConnection(), interruptedConnection);
            var store = new PostgresFanoutStore(interruptedDataSource);

            assertThatThrownBy(() -> store.commitPage(PUBLICATION, checkpoint,
                    List.of(expectedJobs().getFirst()), null, SNAPSHOT_ID))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Could not commit fan-out page")
                    .satisfies(failure -> {
                        var writeFailure = (SQLException) failure.getCause();
                        assertThat(writeFailure.getSQLState()).isEqualTo("23514");
                        assertThat(writeFailure.getSuppressed()).singleElement()
                                .isInstanceOfSatisfying(SQLException.class,
                                        rollbackFailure -> assertThat(rollbackFailure.getSQLState()).isEqualTo("08003"));
                    });
        }

        assertThat(storeWithWorkingConnection.checkpoint(PUBLICATION)).isEqualTo(checkpoint);
        assertThat(storeWithWorkingConnection.listJobs(PUBLICATION, 10)).isEmpty();
    }

    private PGSimpleDataSource dataSource() {
        var dataSource = new PGSimpleDataSource();
        var environment = System.getenv();
        dataSource.setURL(environment.getOrDefault("FANOUT_DB_URL", "jdbc:postgresql://127.0.0.1:55432/fanout"));
        dataSource.setUser(environment.getOrDefault("FANOUT_DB_USER", "fanout"));
        dataSource.setPassword(environment.getOrDefault("FANOUT_DB_PASSWORD", ""));
        if (schema != null) {
            dataSource.setCurrentSchema(schema);
        }
        return dataSource;
    }

    private static EngagementSource source(List<String> requestedCursors) {
        return (publication, snapshotId, cursor, limit) -> {
            assertThat(publication).isEqualTo(PUBLICATION);
            assertThat(limit).isEqualTo(PAGE_SIZE);
            assertThat(snapshotId).isEqualTo(cursor == null ? null : SNAPSHOT_ID);
            requestedCursors.add(cursor);
            int start = switch (cursor) {
                case null -> 0;
                case "after-2" -> 2;
                case "after-4" -> 4;
                default -> throw new AssertionError("Unexpected cursor: " + cursor);
            };
            int end = Math.min(start + limit, ELIGIBLE.size());
            return new Page(ELIGIBLE.subList(start, end), end == ELIGIBLE.size() ? null : "after-" + end, SNAPSHOT_ID);
        };
    }

    private static List<OperationId> expectedJobs() {
        return ELIGIBLE.stream()
                .map(engagement -> new OperationId(
                        PUBLICATION.firmId(), engagement.engagementId(),
                        PUBLICATION.publicationId(), engagement.engagementRevision()))
                .toList();
    }

    private static List<OperationId> storedJobs(PGSimpleDataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT firm_id, engagement_id, publication_id, engagement_revision
                        FROM fanout_job
                        """)) {
            var jobs = new ArrayList<OperationId>();
            while (rows.next()) {
                jobs.add(new OperationId(
                        rows.getString("firm_id"), rows.getString("engagement_id"),
                        rows.getString("publication_id"), rows.getString("engagement_revision")));
            }
            return jobs;
        }
    }

    private static void execute(PGSimpleDataSource dataSource, String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

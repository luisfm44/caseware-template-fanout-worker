package io.github.luisfm44.caseware.worker.planning;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.luisfm44.caseware.worker.adapter.postgres.PostgresFanoutStore;
import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.planning.EngagementSource.Engagement;
import io.github.luisfm44.caseware.worker.planning.EngagementSource.Page;
import io.github.luisfm44.caseware.worker.planning.EngagementSource.SnapshotUnavailableException;
import io.github.luisfm44.caseware.worker.planning.FanoutStore.Checkpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicationSnapshotIT {

    private static final Publication PUBLICATION = new Publication("firm-a", "publication-snapshot");
    private static final int PAGE_SIZE = 2;
    private static final String PINNED = "snapshot-1";
    private static final String CURRENT = "snapshot-2";
    private static final List<Engagement> ORIGINAL = List.of(
            new Engagement("engagement-1", "revision-1"),
            new Engagement("engagement-3", "revision-1"),
            new Engagement("engagement-4", "revision-1"),
            new Engagement("engagement-5", "revision-1"),
            new Engagement("engagement-6", "revision-1"));
    private static final List<Engagement> CHANGED = List.of(
            new Engagement("engagement-1", "revision-1"),
            new Engagement("engagement-2", "revision-1"),
            new Engagement("engagement-3", "revision-1"),
            new Engagement("engagement-5", "revision-2"),
            new Engagement("engagement-6", "revision-1"));

    @TempDir
    Path fixtureDirectory;

    private final String schema = "fanout_snapshot_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void createSchema() throws SQLException {
        execute("CREATE SCHEMA " + schema);
    }

    @AfterEach
    void dropSchema() throws SQLException {
        execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void resumesInAnotherJvmAgainstThePinnedSnapshotAfterSourceChanges() throws Exception {
        var source = new SnapshotFixture(fixtureDirectory);
        var store = store();
        source.publish(PINNED, ORIGINAL);
        planFirstPage(source, store);

        source.publish(CURRENT, CHANGED);
        var output = fixtureDirectory.resolve("resume.log");
        var worker = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Resume.class.getName(), schema, fixtureDirectory.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertThat(worker.waitFor(20, TimeUnit.SECONDS)).as("planner JVM finished").isTrue();
            assertThat(worker.exitValue()).as("planner JVM output: %s", Files.readString(output)).isZero();
        } finally {
            if (worker.isAlive()) {
                worker.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }

        assertThat(source.requests()).containsExactly(
                "null\tnull", PINNED + "\tengagement-3", PINNED + "\tengagement-5");
        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(3, null, true, PINNED));
        assertThat(store.listJobs(PUBLICATION, 10)).containsExactlyElementsOf(operations(ORIGINAL));
    }

    @Test
    void leavesCheckpointAndJobsIntactWhenThePinnedSnapshotExpires() throws IOException {
        var source = new SnapshotFixture(fixtureDirectory);
        var store = store();
        source.publish(PINNED, ORIGINAL);
        planFirstPage(source, store);
        source.publish(CURRENT, CHANGED);
        source.expire(PINNED);

        assertThatThrownBy(() -> new PublicationFanout(source, store(), PAGE_SIZE).plan(PUBLICATION))
                .isInstanceOf(SnapshotUnavailableException.class)
                .hasMessageContaining(PINNED);

        assertThat(source.requests()).containsExactly("null\tnull", PINNED + "\tengagement-3");
        assertFirstPageOnly(store);
    }

    @Test
    void rejectsAResponseThatUsesADifferentSnapshot() throws IOException {
        var source = new SnapshotFixture(fixtureDirectory);
        var store = store();
        source.publish(PINNED, ORIGINAL);
        planFirstPage(source, store);
        EngagementSource mismatched = (publication, snapshot, cursor, limit) -> {
            assertThat(snapshot).isEqualTo(PINNED);
            assertThat(cursor).isEqualTo("engagement-3");
            return new Page(List.of(CHANGED.get(3)), null, CURRENT);
        };

        assertThatThrownBy(() -> new PublicationFanout(mismatched, store(), PAGE_SIZE).plan(PUBLICATION))
                .isInstanceOf(SnapshotUnavailableException.class)
                .hasMessage("Engagement source returned a different snapshot");
        assertFirstPageOnly(store);
    }

    @Test
    void migrationPreservesLegacyJobsAndStopsUnsafeResume() throws SQLException, IOException {
        var source = new SnapshotFixture(fixtureDirectory);
        var initialStore = store();
        source.publish(PINNED, ORIGINAL);
        planFirstPage(source, initialStore);
        var completed = new Publication(PUBLICATION.firmId(), "completed-before-snapshots");
        assertThat(initialStore.commitPage(completed, initialStore.checkpoint(completed), List.of(), null, PINNED))
                .isTrue();
        execute("ALTER TABLE " + schema + ".fanout_checkpoint DROP COLUMN snapshot_id");

        var migrated = store();
        var planner = new PublicationFanout(source, migrated, PAGE_SIZE);
        assertThatThrownBy(() -> planner.plan(PUBLICATION))
                .isInstanceOf(SnapshotUnavailableException.class)
                .hasMessageContaining("snapshot");
        assertThat(planner.planNextPage(completed)).isFalse();

        assertThat(source.requests()).containsExactly("null\tnull");
        assertThat(migrated.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(1, "engagement-3", false, null));
        assertThat(migrated.checkpoint(completed)).isEqualTo(new Checkpoint(1, null, true, null));
        assertThat(migrated.listJobs(PUBLICATION, 10)).containsExactlyElementsOf(operations(ORIGINAL.subList(0, 2)));
    }

    private static void planFirstPage(EngagementSource source, PostgresFanoutStore store) {
        assertThat(new PublicationFanout(source, store, PAGE_SIZE).planNextPage(PUBLICATION)).isTrue();
        assertFirstPageOnly(store);
    }

    private static void assertFirstPageOnly(PostgresFanoutStore store) {
        assertThat(store.checkpoint(PUBLICATION)).isEqualTo(new Checkpoint(1, "engagement-3", false, PINNED));
        assertThat(store.listJobs(PUBLICATION, 10)).containsExactlyElementsOf(operations(ORIGINAL.subList(0, 2)));
    }

    private static List<OperationId> operations(List<Engagement> engagements) {
        return engagements.stream().map(engagement -> new OperationId(PUBLICATION.firmId(), engagement.engagementId(),
                PUBLICATION.publicationId(), engagement.engagementRevision())).toList();
    }

    private PostgresFanoutStore store() {
        return new PostgresFanoutStore(dataSource(schema));
    }

    private static void execute(String sql) throws SQLException {
        try (var connection = dataSource(null).getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static PGSimpleDataSource dataSource(String schema) {
        var environment = System.getenv();
        var source = new PGSimpleDataSource();
        source.setURL(environment.getOrDefault("FANOUT_DB_URL", "jdbc:postgresql://127.0.0.1:55432/fanout"));
        source.setUser(environment.getOrDefault("FANOUT_DB_USER", "fanout"));
        source.setPassword(environment.getOrDefault("FANOUT_DB_PASSWORD", ""));
        if (schema != null) {
            source.setCurrentSchema(schema);
        }
        return source;
    }

    /** Runs the same planner in a JVM with no state inherited from the first call. */
    public static final class Resume {

        public static void main(String[] args) {
            var source = new SnapshotFixture(Path.of(args[1]));
            var store = new PostgresFanoutStore(dataSource(args[0]));
            new PublicationFanout(source, store, PAGE_SIZE).plan(PUBLICATION);
        }
    }

    /** A source fixture outside either JVM. Publishing changes the current pointer and retains older snapshots. */
    private record SnapshotFixture(Path directory) implements EngagementSource {

        void publish(String snapshotId, List<Engagement> engagements) throws IOException {
            Files.write(snapshotPath(snapshotId), engagements.stream()
                    .map(engagement -> engagement.engagementId() + "\t" + engagement.engagementRevision())
                    .toList(), CREATE_NEW);
            Files.writeString(directory.resolve("current"), snapshotId);
        }

        void expire(String snapshotId) throws IOException {
            Files.delete(snapshotPath(snapshotId));
        }

        List<String> requests() throws IOException {
            return Files.readAllLines(directory.resolve("requests"));
        }

        @Override
        public Page fetch(Publication publication, String snapshotId, String cursor, int limit) {
            try {
                Files.writeString(directory.resolve("requests"), snapshotId + "\t" + cursor + "\n", CREATE, APPEND);
                var selected = snapshotId == null ? Files.readString(directory.resolve("current")) : snapshotId;
                var rows = readSnapshot(selected);
                var start = cursor == null ? 0 : indexOfCursor(rows, cursor) + 1;
                var end = Math.min(start + limit, rows.size());
                var next = end == rows.size() ? null : rows.get(end - 1).engagementId();
                return new Page(rows.subList(start, end), next, selected);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private List<Engagement> readSnapshot(String snapshotId) throws IOException {
            try {
                return Files.readAllLines(snapshotPath(snapshotId)).stream().map(row -> {
                    var fields = row.split("\t", 2);
                    return new Engagement(fields[0], fields[1]);
                }).toList();
            } catch (NoSuchFileException expired) {
                throw new SnapshotUnavailableException("Snapshot has expired: " + snapshotId);
            }
        }

        private Path snapshotPath(String snapshotId) {
            return directory.resolve(snapshotId + ".tsv");
        }

        private static int indexOfCursor(List<Engagement> rows, String cursor) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).engagementId().equals(cursor)) {
                    return index;
                }
            }
            throw new IllegalArgumentException("Cursor is absent from the requested snapshot: " + cursor);
        }
    }
}

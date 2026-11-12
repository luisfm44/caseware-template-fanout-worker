package io.github.luisfm44.caseware.worker.bootstrap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.TemplateFanoutWorkerApplication;
import io.github.luisfm44.caseware.worker.planning.EngagementSource.Engagement;
import io.github.luisfm44.caseware.worker.planning.EngagementSource.Page;
import io.github.luisfm44.caseware.worker.adapter.postgres.PostgresFanoutStore;
import io.github.luisfm44.caseware.worker.planning.Publication;
import io.github.luisfm44.caseware.worker.planning.PublicationFanout;
import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.net.SocketFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DurableWorkerProcessIT {

    private static final Publication PUBLICATION = new Publication("firm-a", "publication-42");
    private static final OperationId FIRST = operation("engagement-1");
    private static final OperationId NEXT = operation("engagement-2");

    @TempDir
    Path logs;
    private String schema;
    private String workerUrl;
    private PGSimpleDataSource database;

    @BeforeEach
    void createSchema() throws SQLException {
        var testSchema = "fanout_process_" + UUID.randomUUID().toString().replace("-", "");
        database = dataSource();
        var baseUrl = database.getURL();
        workerUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + testSchema;
        execute(database, "CREATE SCHEMA " + testSchema);
        schema = testSchema;
        database.setCurrentSchema(schema);
        seedJobs(database);
    }

    @AfterEach
    void dropSchema() throws SQLException {
        if (schema != null) {
            execute(database, "DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void anotherJvmRecoversAfterWorkerDeathWithoutRepeatingTheEffectOrExceedingCapacity() throws Exception {
        try (var manager = new RecordingManager(FirstRequest.HOLD_REPLY);
                var first = startWorker(manager.url(), "first")) {
            eventually(first, () -> {
                assertThat(manager.submissions()).extracting(Attempt::operation).containsExactly(FIRST);
                assertThat(manager.active()).isEqualTo(1);
            });
            var original = manager.submissions().getFirst();

            try (var survivor = startWorker(manager.url(), "survivor")) {
                assertThat(survivor.process().pid()).isNotEqualTo(first.process().pid());
                eventually(survivor, () -> assertThat(manager.resolutions()).contains(original));
                // Only the survivor can have made this lookup. The first worker is still
                // blocked waiting on the original HTTP response.
                assertThat(first.process().isAlive()).isTrue();
                assertThat(manager.active()).isEqualTo(1);
                assertThat(manager.submissions()).containsExactly(original);

                manager.setStatusAvailable(false);
                first.kill();
                eventually(survivor, () -> assertThat(manager.unavailableLookups()).isPositive());
                assertThat(manager.active()).isEqualTo(1);
                assertThat(manager.submissions()).containsExactly(original);

                manager.applyFirstAndDropReply(original);
                int lookupsBefore = manager.unavailableLookups();
                eventually(survivor, () -> assertThat(manager.unavailableLookups()).isGreaterThan(lookupsBefore));
                assertThat(manager.submissions()).containsExactly(original);
                assertThat(executions(database).get(FIRST.engagementId()))
                        .isEqualTo(new Execution("SUBMITTED", 1, original.id()));

                manager.setStatusAvailable(true);
                expectSuccessfulJobs(survivor, manager, 1);
                assertThat(manager.submissions()).extracting(Attempt::operation).containsExactly(FIRST, NEXT);
                assertThat(manager.resolutions()).isNotEmpty().allMatch(original::equals);
            }
        }
    }

    @Test
    void settlesAnExpiredClaimBeforeTakeoverAndFreesCapacityForTheNextJob() throws Exception {
        try (var manager = new RecordingManager(FirstRequest.HOLD_ALL_REPLIES);
                var first = startWorker(manager.url(), "first", 1, 60_000)) {
            eventually(first, () -> assertThat(manager.active()).isEqualTo(1));
            var original = manager.submissions().getFirst();
            assertThat(original.operation()).isEqualTo(FIRST);

            try (var second = startWorker(manager.url(), "second", 1, 60_000)) {
                assertThat(second.process().pid()).isNotEqualTo(first.process().pid());
                eventually(second, () -> assertThat(Files.readString(second.log()))
                        .contains("Started TemplateFanoutWorkerApplication"));
                // Keep the expired claim unreclaimed so settlement and takeover can be tested separately.
                execute(database, """
                        UPDATE fanout_job
                        SET claim_until = clock_timestamp() - INTERVAL '1 second',
                            available_at = clock_timestamp() + INTERVAL '1 day'
                        WHERE engagement_id = 'engagement-1'
                        """);
                manager.completeAndReply(original);

                completeNextAndVerify(first, manager);
                assertThat(manager.resolutions()).isEmpty();
            }
        }
    }

    @Test
    void rejectsTheOldResultAfterAnotherJvmTakesOverAndKeepsCapacityReserved() throws Exception {
        try (var manager = new RecordingManager(FirstRequest.HOLD_ALL_REPLIES, true);
                var first = startWorker(manager.url(), "first", 1, 60_000)) {
            eventually(first, () -> assertThat(manager.active()).isEqualTo(1));
            var original = manager.submissions().getFirst();
            assertThat(original.operation()).isEqualTo(FIRST);
            execute(database, """
                    UPDATE fanout_job SET claim_until = clock_timestamp() - INTERVAL '1 second'
                    WHERE engagement_id = 'engagement-1'
                    """);

            try (var second = startWorker(manager.url(), "second", 1, 60_000)) {
                assertThat(second.process().pid()).isNotEqualTo(first.process().pid());
                eventually(second, () -> assertThat(manager.resolutions()).containsExactly(original));

                manager.completeAndReply(original);
                eventually(first, () -> assertThat(Files.readString(first.log()))
                        .contains("Discarded confirmed outcome for attempt " + original.id()));
                assertThat(executions(database)).containsExactlyInAnyOrderEntriesOf(
                        Map.of(FIRST.engagementId(), new Execution("SUBMITTED", 1, original.id()),
                                NEXT.engagementId(), new Execution("READY", 0, null)));
                assertThat(manager.submissions()).containsExactly(original);
                assertThat(manager.effects()).containsExactlyEntriesOf(Map.of(FIRST, 1));

                manager.releaseResolutionReplies();
                completeNextAndVerify(second, manager);
            }
        }
    }

    @Test
    void anotherJvmCanRetryAfterDownstreamFencesTheLateRequest() throws Exception {
        try (var manager = new RecordingManager(FirstRequest.HOLD_BEFORE_ADMISSION);
                var first = startWorker(manager.url(), "first")) {
            eventually(first, () -> assertThat(manager.delayedAttempt()).isNotNull());
            var delayed = manager.delayedAttempt();
            assertThat(delayed.operation()).isEqualTo(FIRST);
            assertThat(manager.effects()).isEmpty();

            try (var survivor = startWorker(manager.url(), "survivor")) {
                eventually(survivor, () -> assertThat(manager.fenced()).containsExactly(delayed));
                first.kill();
                manager.deliverDelayedRequest();
                eventually(survivor, () -> assertThat(manager.rejectedLate()).containsExactly(delayed));
                expectSuccessfulJobs(survivor, manager, 2);

                var firstSubmissions = manager.submissions().stream()
                        .filter(attempt -> FIRST.equals(attempt.operation())).toList();
                assertThat(firstSubmissions).hasSize(2).doesNotHaveDuplicates().contains(delayed);
                assertThat(manager.submissions()).hasSize(3);
            }
        }
    }

    @Test
    void aStaleEngagementRevisionIsRejectedWithoutAnEffect() throws Exception {
        try (var manager = new RecordingManager(FirstRequest.IMMEDIATE)) {
            manager.setCurrentRevision(NEXT.engagementId(), "revision-2");
            try (var worker = startWorker(manager.url(), "worker")) {
                eventually(worker, () -> assertThat(manager.submissions()).hasSize(2));
                eventually(worker, () -> assertThat(executions(database)).containsExactlyInAnyOrderEntriesOf(
                        Map.of(FIRST.engagementId(), new Execution("APPLIED", 1, null),
                                NEXT.engagementId(), new Execution("REJECTED", 1, null))));
                assertThat(manager.submissions()).extracting(Attempt::operation).containsExactly(FIRST, NEXT);
                assertThat(manager.effects()).containsExactlyInAnyOrderEntriesOf(Map.of(FIRST, 1));
                assertThat(manager.resolutions()).isEmpty();
            }
        }
    }

    @Test
    void twoJvmsResumeAfterPostgresDisconnectsBetweenEffectAndSettlement() throws Exception {
        var disconnected = logs.resolve("postgres-disconnected");
        workerUrl += "&socketFactory=" + DisconnectableSocketFactory.class.getName()
                + "&socketFactoryArg=" + URLEncoder.encode(disconnected.toString(), UTF_8);

        try (var manager = new RecordingManager(FirstRequest.HOLD_ALL_REPLIES, true);
                var first = startWorker(manager.url(), "first")) {
            eventually(first, () -> assertThat(manager.active()).isEqualTo(1));
            var original = manager.submissions().getFirst();
            assertThat(original.operation()).isEqualTo(FIRST);

            try (var second = startWorker(manager.url(), "second", 1, 1_200, 2_000)) {
                assertThat(first.process().pid()).isNotEqualTo(second.process().pid());
                // The first JVM is still waiting on its submission reply, so this lookup
                // proves that the second JVM started consuming from the shared database.
                eventually(second, () -> assertThat(manager.resolutions()).contains(original));

                manager.recordEffect(original);
                Files.createFile(disconnected);
                manager.releaseSubmissionReply(original);
                manager.releaseResolutionReplies();

                eventually(first, () -> assertThat(Files.readString(first.log()))
                        .contains("Durable job queue unavailable; will resume from persisted state",
                                "PostgresJobQueue.settle", "Test PostgreSQL link is disconnected"));
                eventually(second, () -> assertThat(Files.readString(second.log()))
                        .contains("Durable job queue unavailable; will resume from persisted state",
                                "Test PostgreSQL link is disconnected"));
                assertThat(first.process().isAlive()).isTrue();
                assertThat(second.process().isAlive()).isTrue();
                assertThat(executions(database)).containsExactlyInAnyOrderEntriesOf(
                        Map.of(FIRST.engagementId(), new Execution("SUBMITTED", 1, original.id()),
                                NEXT.engagementId(), new Execution("READY", 0, null)));
                assertThat(manager.effects()).containsExactlyEntriesOf(Map.of(FIRST, 1));
                assertThat(manager.submissions()).containsExactly(original);

                int lookupsDuringOutage = manager.resolutions().size();
                Files.delete(disconnected);
                completeNextAndVerify(first, manager);

                assertThat(first.process().isAlive()).isTrue();
                assertThat(second.process().isAlive()).isTrue();
                assertThat(manager.resolutions()).hasSizeGreaterThan(lookupsDuringOutage).allMatch(original::equals);
            }
        }
    }

    @Test
    void aLateSmallFirmStartsAtTheNextFreeSlotWhileTheLargeFirmIsBacklogged() throws Exception {
        var moreLargeJobs = IntStream.range(0, 30)
                .mapToObj(index -> new Engagement("large-engagement-" + index, "revision-1"))
                .toList();
        plan(database, new Publication(PUBLICATION.firmId(), "bulk-publication"), moreLargeJobs);
        var smallPublication = new Publication("firm-small", "small-publication");
        var small = new OperationId(smallPublication.firmId(), "small-engagement",
                smallPublication.publicationId(), "revision-1");

        try (var manager = new RecordingManager(FirstRequest.HOLD_ALL_REPLIES);
                var first = startWorker(manager.url(), "first", 2, 60_000);
                var second = startWorker(manager.url(), "second", 2, 60_000)) {
            assertThat(first.process().pid()).isNotEqualTo(second.process().pid());
            eventually(second, () -> assertThat(manager.active()).isEqualTo(2));
            var initiallyActive = manager.submissions();
            assertThat(initiallyActive).hasSize(2)
                    .allSatisfy(attempt -> assertThat(attempt.operation().firmId()).isEqualTo(PUBLICATION.firmId()));

            plan(database, smallPublication, List.of(new Engagement(small.engagementId(), small.engagementRevision())));
            manager.completeAndReply(initiallyActive.getFirst());

            eventually(second, () -> assertThat(manager.submissions()).hasSize(3));
            var next = manager.submissions().get(2);
            assertThat(next.operation()).as("operation started at the first available slot").isEqualTo(small);
            assertThat(manager.active()).isEqualTo(2);
            assertThat(manager.effects()).containsOnlyKeys(initiallyActive.getFirst().operation());
            assertThat(executions(database).values()).filteredOn(execution -> execution.state().equals("READY"))
                    .hasSize(moreLargeJobs.size());

            manager.completeAndReply(next);
            eventually(second, () -> assertThat(manager.submissions()).hasSize(4));
            assertThat(manager.submissions().get(3).operation().firmId()).isEqualTo(PUBLICATION.firmId());
            assertThat(manager.peak()).isEqualTo(2);
        }
    }

    private void completeNextAndVerify(WorkerProcess worker, RecordingManager manager) throws IOException {
        eventually(worker, () -> assertThat(manager.submissions()).extracting(Attempt::operation)
                .containsExactly(FIRST, NEXT));
        manager.completeAndReply(manager.submissions().get(1));
        expectSuccessfulJobs(worker, manager, 1);
    }

    private void expectSuccessfulJobs(WorkerProcess worker, RecordingManager manager, int firstSubmissions)
            throws IOException {
        eventually(worker, () -> assertThat(executions(database)).containsExactlyInAnyOrderEntriesOf(
                Map.of(FIRST.engagementId(), new Execution("APPLIED", firstSubmissions, null),
                        NEXT.engagementId(), new Execution("APPLIED", 1, null))));
        assertThat(manager.effects()).containsExactlyInAnyOrderEntriesOf(Map.of(FIRST, 1, NEXT, 1));
        assertThat(manager.peak()).isEqualTo(1);
        assertThat(manager.active()).isZero();
    }

    private static void seedJobs(PGSimpleDataSource database) {
        plan(database, PUBLICATION, List.of(
                new Engagement(FIRST.engagementId(), FIRST.engagementRevision()),
                new Engagement(NEXT.engagementId(), NEXT.engagementRevision())));
    }

    private static void plan(PGSimpleDataSource database, Publication publication, List<Engagement> engagements) {
        var store = new PostgresFanoutStore(database);
        new PublicationFanout((requested, snapshotId, cursor, limit) -> new Page(engagements, null,
                "worker-process-snapshot"),
                store, engagements.size()).plan(publication);
    }

    private WorkerProcess startWorker(String managerUrl, String name) throws IOException {
        return startWorker(managerUrl, name, 1, 1_200);
    }

    private WorkerProcess startWorker(String managerUrl, String name, int capacity, int leaseMillis) throws IOException {
        return startWorker(managerUrl, name, capacity, leaseMillis, 30_000);
    }

    private WorkerProcess startWorker(String managerUrl, String name, int capacity, int leaseMillis, int httpTimeoutMillis)
            throws IOException {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // With the coverage profile, graceful shutdown of the survivor records the code
        // exercised in the child JVM in the same JaCoCo data file.
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .forEach(command::add);
        command.addAll(List.of("-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                TemplateFanoutWorkerApplication.class.getName(),
                "--spring.profiles.active=postgres-worker", "--spring.main.banner-mode=off",
                "--worker.capacity=" + capacity, "--worker.lease-ms=" + leaseMillis, "--worker.idle-ms=25",
                "--worker.http-timeout-ms=" + httpTimeoutMillis));
        var output = logs.resolve(name + ".log");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("FANOUT_DB_URL", workerUrl);
        builder.environment().put("FANOUT_DB_USER", database.getUser());
        builder.environment().put("FANOUT_DB_PASSWORD", database.getPassword());
        builder.environment().put("FANOUT_DOWNSTREAM_URL", managerUrl);
        return new WorkerProcess(builder.start(), output);
    }

    private static void eventually(WorkerProcess worker, ThrowingRunnable assertion) throws IOException {
        try {
            await().pollInterval(Duration.ofMillis(40)).atMost(Duration.ofSeconds(20))
                    .untilAsserted(assertion);
        } catch (ConditionTimeoutException failure) {
            throw new AssertionError("Worker output:\n" + Files.readString(worker.log()), failure);
        }
    }

    private static PGSimpleDataSource dataSource() {
        var environment = System.getenv();
        var database = new PGSimpleDataSource();
        database.setURL(environment.getOrDefault("FANOUT_DB_URL", "jdbc:postgresql://127.0.0.1:55432/fanout"));
        database.setUser(environment.getOrDefault("FANOUT_DB_USER", "fanout"));
        database.setPassword(environment.getOrDefault("FANOUT_DB_PASSWORD", ""));
        return database;
    }

    private static void execute(PGSimpleDataSource database, String sql) throws SQLException {
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Map<String, Execution> executions(PGSimpleDataSource database) throws SQLException {
        try (var connection = database.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT engagement_id, execution_state, submissions, attempt_id
                        FROM fanout_job
                        """)) {
            var states = new HashMap<String, Execution>();
            while (rows.next()) {
                states.put(rows.getString(1),
                        new Execution(rows.getString(2), rows.getInt(3), rows.getObject(4, UUID.class)));
            }
            return states;
        }
    }

    private static OperationId operation(String engagementId) {
        return new OperationId(PUBLICATION.firmId(), engagementId, PUBLICATION.publicationId(), "revision-1");
    }

    private record Execution(String state, int submissions, UUID attempt) {}

    private record Attempt(OperationId operation, UUID id) {}

    private enum FirstRequest { IMMEDIATE, HOLD_REPLY, HOLD_BEFORE_ADMISSION, HOLD_ALL_REPLIES }

    private record WorkerProcess(Process process, Path log) implements AutoCloseable {

        void kill() throws InterruptedException {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("worker process terminated").isTrue();
        }

        @Override
        public void close() throws InterruptedException {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    kill();
                }
            }
        }
    }

    /** Cuts only the worker's JDBC link. The test observes PostgreSQL over a separate connection. */
    public static final class DisconnectableSocketFactory extends SocketFactory {

        private final Path disconnected;

        public DisconnectableSocketFactory(String marker) {
            disconnected = Path.of(marker);
        }

        @Override
        public Socket createSocket() throws IOException {
            if (Files.exists(disconnected)) {
                throw new ConnectException("Test PostgreSQL link is disconnected");
            }
            return new Socket();
        }

        @Override
        public Socket createSocket(String host, int port) {
            throw new UnsupportedOperationException("The JDBC driver opens an unconnected socket");
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException("The JDBC driver opens an unconnected socket");
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException("The JDBC driver opens an unconnected socket");
        }

        @Override
        public Socket createSocket(InetAddress host, int port, InetAddress localAddress, int localPort) {
            throw new UnsupportedOperationException("The JDBC driver opens an unconnected socket");
        }
    }

    private static final class RecordingManager implements AutoCloseable {

        private final HttpServer server;
        private final FirstRequest firstRequest;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final CountDownLatch dropFirstReply = new CountDownLatch(1);
        private final CountDownLatch deliverDelayedRequest = new CountDownLatch(1);
        private final CountDownLatch resolutionReplies;
        private final List<Attempt> submissions = new ArrayList<>();
        private final List<Attempt> resolutions = new ArrayList<>();
        private final Map<OperationId, Integer> effects = new HashMap<>();
        private final Map<String, String> currentRevisions = new HashMap<>();
        private final Map<Attempt, CountDownLatch> heldReplies = new HashMap<>();
        private final Set<Attempt> active = new HashSet<>();
        private final Set<Attempt> fenced = new HashSet<>();
        private final Set<Attempt> rejectedLate = new HashSet<>();
        private Attempt delayedAttempt;
        private boolean statusAvailable = true;
        private int unavailableLookups;
        private int peak;

        private RecordingManager(FirstRequest firstRequest) throws IOException {
            this(firstRequest, false);
        }

        private RecordingManager(FirstRequest firstRequest, boolean holdResolutionReplies) throws IOException {
            this.firstRequest = firstRequest;
            resolutionReplies = new CountDownLatch(holdResolutionReplies ? 1 : 0);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/submit", this::submit);
            server.createContext("/resolve", this::resolve);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void submit(HttpExchange exchange) throws IOException {
            try (exchange) {
                var attempt = readAttempt(exchange);
                if (delayBeforeAdmission(attempt)) {
                    awaitLatch(deliverDelayedRequest);
                }
                var outcome = start(attempt);
                if ("PENDING".equals(outcome)) {
                    if (firstRequest == FirstRequest.HOLD_ALL_REPLIES) {
                        awaitLatch(heldReply(attempt));
                        reply(exchange, 200, "APPLIED");
                    } else {
                        // Wait without replying until the test kills the original process.
                        awaitLatch(dropFirstReply);
                    }
                } else {
                    reply(exchange, 200, outcome);
                }
            }
        }

        private synchronized String start(Attempt attempt) {
            submissions.add(attempt);
            if (fenced.contains(attempt)) {
                rejectedLate.add(attempt);
                return "NOT_APPLIED";
            }
            if (effects.containsKey(attempt.operation())) {
                return "APPLIED";
            }
            var operation = attempt.operation();
            var currentRevision = currentRevisions.get(operation.engagementId());
            if (currentRevision != null && !currentRevision.equals(operation.engagementRevision())) {
                return "REJECTED";
            }
            active.add(attempt);
            peak = Math.max(peak, active.size());
            if (firstRequest == FirstRequest.HOLD_ALL_REPLIES) {
                heldReplies.put(attempt, new CountDownLatch(1));
                return "PENDING";
            }
            if (firstRequest == FirstRequest.HOLD_REPLY && FIRST.equals(attempt.operation())) {
                return "PENDING";
            }
            apply(attempt);
            return "APPLIED";
        }

        private void resolve(HttpExchange exchange) throws IOException {
            try (exchange) {
                var attempt = readAttempt(exchange);
                synchronized (this) {
                    resolutions.add(attempt);
                }
                awaitLatch(resolutionReplies);
                var outcome = status(attempt);
                if (outcome == null) {
                    reply(exchange, 503, "status unavailable");
                } else {
                    reply(exchange, 200, outcome);
                }
            }
        }

        private synchronized String status(Attempt attempt) {
            if (!statusAvailable) {
                unavailableLookups++;
                return null;
            }
            if (effects.containsKey(attempt.operation())) {
                return "APPLIED";
            }
            if (active.contains(attempt)) {
                return "PENDING";
            }
            fenced.add(attempt);
            return "NOT_APPLIED";
        }

        private synchronized boolean delayBeforeAdmission(Attempt attempt) {
            if (firstRequest == FirstRequest.HOLD_BEFORE_ADMISSION && FIRST.equals(attempt.operation())
                    && delayedAttempt == null) {
                delayedAttempt = attempt;
                return true;
            }
            return false;
        }

        synchronized Attempt delayedAttempt() {
            return delayedAttempt;
        }

        void deliverDelayedRequest() {
            deliverDelayedRequest.countDown();
        }

        void releaseResolutionReplies() {
            resolutionReplies.countDown();
        }

        synchronized Set<Attempt> fenced() {
            return Set.copyOf(fenced);
        }

        synchronized Set<Attempt> rejectedLate() {
            return Set.copyOf(rejectedLate);
        }

        private static void awaitLatch(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized void setCurrentRevision(String engagementId, String revision) {
            currentRevisions.put(engagementId, revision);
        }

        synchronized void setStatusAvailable(boolean available) {
            statusAvailable = available;
        }

        private synchronized CountDownLatch heldReply(Attempt attempt) {
            return heldReplies.get(attempt);
        }

        synchronized void completeAndReply(Attempt attempt) {
            assertThat(active).contains(attempt);
            apply(attempt);
            heldReplies.get(attempt).countDown();
        }

        synchronized void recordEffect(Attempt attempt) {
            assertThat(active).contains(attempt);
            apply(attempt);
        }

        synchronized void releaseSubmissionReply(Attempt attempt) {
            heldReplies.get(attempt).countDown();
        }

        synchronized void applyFirstAndDropReply(Attempt attempt) {
            assertThat(active).containsExactly(attempt);
            apply(attempt);
            dropFirstReply.countDown();
        }

        private void apply(Attempt attempt) {
            active.remove(attempt);
            effects.merge(attempt.operation(), 1, Integer::sum);
        }

        synchronized int active() {
            return active.size();
        }

        synchronized int peak() {
            return peak;
        }

        synchronized int unavailableLookups() {
            return unavailableLookups;
        }

        synchronized List<Attempt> submissions() {
            return List.copyOf(submissions);
        }

        synchronized List<Attempt> resolutions() {
            return List.copyOf(resolutions);
        }

        synchronized Map<OperationId, Integer> effects() {
            return Map.copyOf(effects);
        }

        private static Attempt readAttempt(HttpExchange exchange) throws IOException {
            var fields = new HashMap<String, String>();
            for (String pair : new String(exchange.getRequestBody().readAllBytes(), UTF_8).split("&")) {
                var entry = pair.split("=", 2);
                fields.put(URLDecoder.decode(entry[0], UTF_8), URLDecoder.decode(entry[1], UTF_8));
            }
            return new Attempt(new OperationId(fields.get("firmId"), fields.get("engagementId"),
                    fields.get("publicationId"), fields.get("engagementRevision")),
                    UUID.fromString(fields.get("attemptId")));
        }

        private static void reply(HttpExchange exchange, int status, String text) throws IOException {
            var body = text.getBytes(UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }

        @Override
        public void close() {
            dropFirstReply.countDown();
            deliverDelayedRequest.countDown();
            releaseResolutionReplies();
            synchronized (this) {
                heldReplies.values().forEach(CountDownLatch::countDown);
            }
            server.stop(0);
            executor.close();
        }
    }
}

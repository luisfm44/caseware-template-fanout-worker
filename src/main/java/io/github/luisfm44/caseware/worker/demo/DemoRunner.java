package io.github.luisfm44.caseware.worker.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.sql.DataSource;

import io.github.luisfm44.caseware.worker.AdmissionController;
import io.github.luisfm44.caseware.worker.FanoutWorker;
import io.github.luisfm44.caseware.worker.InMemoryAdmissionController;
import io.github.luisfm44.caseware.worker.OperationId;
import io.github.luisfm44.caseware.worker.planning.EngagementSource;
import io.github.luisfm44.caseware.worker.planning.EngagementSource.Engagement;
import io.github.luisfm44.caseware.worker.planning.EngagementSource.Page;
import io.github.luisfm44.caseware.worker.planning.FanoutStore.Checkpoint;
import io.github.luisfm44.caseware.worker.planning.PostgresFanoutStore;
import io.github.luisfm44.caseware.worker.planning.Publication;
import io.github.luisfm44.caseware.worker.planning.PublicationFanout;
import io.github.luisfm44.caseware.worker.retry.RetryingDownstreamClient;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
final class DemoRunner implements ApplicationRunner {

    private static final System.Logger LOGGER = System.getLogger(DemoRunner.class.getName());
    private static final Publication PUBLICATION = new Publication("demo-firm", "demo-publication");
    private static final int ENGAGEMENT_COUNT = 7;
    private static final int PAGE_SIZE = 2;
    private static final int CAPACITY = 3;
    private static final int PLANNED_PAGES = Math.ceilDiv(ENGAGEMENT_COUNT, PAGE_SIZE);
    private static final Report EXPECTED_REPORT = new Report(ENGAGEMENT_COUNT, ENGAGEMENT_COUNT, 0, CAPACITY,
            CAPACITY, PLANNED_PAGES, ENGAGEMENT_COUNT, ENGAGEMENT_COUNT, 1);

    private final Environment environment;

    DemoRunner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        var path = Path.of(environment.getRequiredProperty("demo.report")).toAbsolutePath();
        var dataSource = dataSource();
        var schema = "fanout_demo_" + UUID.randomUUID().toString().replace("-", "");
        executeSql(dataSource, "CREATE SCHEMA " + schema);

        Report report;
        try (AutoCloseable cleanup = () -> executeSql(dataSource, "DROP SCHEMA " + schema + " CASCADE")) {
            dataSource.setCurrentSchema(schema);
            report = runPublication(dataSource);
        }

        Files.createDirectories(path.getParent());
        Files.writeString(path, report.json());
        LOGGER.log(System.Logger.Level.INFO, "Demo report written to {0}", path);
    }

    private PGSimpleDataSource dataSource() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(environment.getProperty("FANOUT_DB_URL", "jdbc:postgresql://127.0.0.1:55432/fanout"));
        dataSource.setUser(environment.getProperty("FANOUT_DB_USER", "fanout"));
        dataSource.setPassword(environment.getRequiredProperty("FANOUT_DB_PASSWORD"));
        dataSource.setConnectTimeout(5);
        dataSource.setSocketTimeout(10);
        return dataSource;
    }

    private static Report runPublication(DataSource dataSource) throws Exception {
        var engagements = IntStream.rangeClosed(1, ENGAGEMENT_COUNT)
                .mapToObj(index -> new Engagement("engagement-" + index, "revision-1"))
                .toList();
        EngagementSource source = (publication, cursor, limit) -> {
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            int end = Math.min(start + limit, engagements.size());
            return new Page(engagements.subList(start, end), end == engagements.size() ? null : Integer.toString(end));
        };

        var store = new PostgresFanoutStore(dataSource);
        new PublicationFanout(source, store, PAGE_SIZE).planNextPage(PUBLICATION);

        var resumedStore = new PostgresFanoutStore(dataSource);
        verifyCheckpoint(resumedStore.checkpoint(PUBLICATION), new Checkpoint(1, Integer.toString(PAGE_SIZE), false));
        new PublicationFanout(source, resumedStore, PAGE_SIZE).plan(PUBLICATION);
        var checkpoint = resumedStore.checkpoint(PUBLICATION);
        verifyCheckpoint(checkpoint, new Checkpoint(PLANNED_PAGES, null, true));

        var jobs = resumedStore.listJobs(PUBLICATION, ENGAGEMENT_COUNT + 1);
        var expected = engagements.stream()
                .map(engagement -> new OperationId(PUBLICATION.firmId(), engagement.engagementId(),
                        PUBLICATION.publicationId(), engagement.engagementRevision()))
                .toList();
        verifyJobs(jobs, expected);

        var downstream = new SimulatedDownstream(jobs.getFirst());
        int completed = executeJobs(jobs, downstream, new InMemoryAdmissionController(CAPACITY));
        var report = new Report(jobs.size(), completed, downstream.duplicateEffects(), CAPACITY,
                downstream.peakInFlight(), checkpoint.pagesCommitted(), downstream.appliedEffects(),
                downstream.dispatchAttempts(), downstream.reconciliations());
        verifyReport(report);
        return report;
    }

    static void verifyCheckpoint(Checkpoint actual, Checkpoint expected) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Expected demo checkpoint " + expected + " but found " + actual);
        }
    }

    static void verifyJobs(List<OperationId> actual, List<OperationId> expected) {
        if (actual.size() != expected.size() || !new HashSet<>(actual).equals(new HashSet<>(expected))) {
            throw new IllegalStateException("Expected demo jobs " + expected + " but found " + actual);
        }
    }

    static void verifyReport(Report actual) {
        if (!actual.equals(EXPECTED_REPORT)) {
            throw new IllegalStateException(
                    "Expected demo report " + EXPECTED_REPORT.json() + " but found " + actual.json());
        }
    }

    static int executeJobs(List<OperationId> jobs, SimulatedDownstream downstream, AdmissionController admission)
            throws Exception {
        var client = new RetryingDownstreamClient(downstream);
        var workers = List.of(new FanoutWorker(admission, client), new FanoutWorker(admission, client));
        var batch = new ArrayList<CompletableFuture<Void>>();
        int nextJob = 0;
        int completed = 0;

        while (nextJob < jobs.size()) {
            var worker = workers.get(nextJob % workers.size());
            var result = worker.tryStart(jobs.get(nextJob));
            if (result.isPresent()) {
                batch.add(result.get());
                nextJob++;
            } else {
                if (batch.isEmpty()) {
                    throw new IllegalStateException("No demo job could acquire a permit");
                }
                completed += completeBatch(downstream, batch);
            }
        }

        return completed + completeBatch(downstream, batch);
    }

    private static int completeBatch(SimulatedDownstream downstream, List<CompletableFuture<Void>> batch)
            throws Exception {
        downstream.completeBatch();
        CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        int completed = batch.size();
        batch.clear();
        return completed;
    }

    private static void executeSql(DataSource dataSource, String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    record Report(int submitted, int completed, int duplicateEffects, int limit, int peakInFlight,
            long plannedPages, int appliedEffects, int dispatchAttempts, int reconciliations) {

        String json() {
            return String.format(Locale.ROOT, """
                    {
                      "submitted": %d,
                      "completed": %d,
                      "duplicateEffects": %d,
                      "limit": %d,
                      "peakInFlight": %d,
                      "plannedPages": %d,
                      "appliedEffects": %d,
                      "dispatchAttempts": %d,
                      "reconciliations": %d
                    }
                    """, submitted, completed, duplicateEffects, limit, peakInFlight, plannedPages,
                    appliedEffects, dispatchAttempts, reconciliations);
        }
    }
}

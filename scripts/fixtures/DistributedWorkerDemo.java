import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.luisfm44.caseware.worker.adapter.postgres.PostgresFanoutStore;
import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.planning.EngagementSource;
import io.github.luisfm44.caseware.worker.planning.Publication;
import io.github.luisfm44.caseware.worker.planning.PublicationFanout;
import org.postgresql.ds.PGSimpleDataSource;

/** Local fixture for the two-JVM demo in the README. */
public class DistributedWorkerDemo {

    private static final int JOBS_PER_FIRM = 6;

    private final Map<OperationId, UUID> effects = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private final AtomicInteger submissions = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        var database = new PGSimpleDataSource();
        database.setURL(System.getenv("FANOUT_DB_URL"));
        database.setUser(System.getenv("FANOUT_DB_USER"));
        database.setPassword(System.getenv("FANOUT_DB_PASSWORD"));
        var schema = database.getCurrentSchema();
        if (schema == null || !schema.matches("fanout_demo_[a-z0-9_]{1,40}")) {
            throw new IllegalArgumentException("Set FANOUT_DB_URL with a fresh currentSchema=fanout_demo_<suffix>");
        }

        var demo = new DistributedWorkerDemo();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                seed(database, schema);
                server.setExecutor(executor);
                server.createContext("/submit", demo::submit);
                server.createContext("/resolve", demo::resolve);
                server.createContext("/report", demo::report);
                server.start();
                System.out.printf("Ready: %d planned jobs in %s. Mock listening at http://127.0.0.1:%d%n",
                        JOBS_PER_FIRM * 2, schema, server.getAddress().getPort());
                Thread.currentThread().join();
            } finally {
                server.stop(0);
            }
        }
    }

    private static void seed(PGSimpleDataSource database, String schema) throws Exception {
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        var engagements = IntStream.rangeClosed(1, JOBS_PER_FIRM)
                .mapToObj(id -> new EngagementSource.Engagement("engagement-" + id, "revision-1"))
                .toList();
        EngagementSource source = (publication, snapshot, cursor, limit) -> {
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            int end = Math.min(start + limit, engagements.size());
            var next = end == engagements.size() ? null : Integer.toString(end);
            return new EngagementSource.Page(engagements.subList(start, end), next, "distributed-demo-v1");
        };
        var planner = new PublicationFanout(source, new PostgresFanoutStore(database), 2);
        planner.plan(new Publication("firm-a", "demo-publication"));
        planner.plan(new Publication("firm-b", "demo-publication"));
    }

    private void submit(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, "Use POST");
                return;
            }
            var fields = form(exchange);
            var operation = operation(fields);
            var attempt = UUID.fromString(fields.get("attemptId"));
            submissions.incrementAndGet();
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                // Keep ordinary submissions in flight briefly so both local JVMs can overlap.
                Thread.sleep(350);
                effects.putIfAbsent(operation, attempt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                inFlight.decrementAndGet();
            }
            reply(exchange, 200, "APPLIED");
        }
    }

    private void resolve(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
                reply(exchange, 405, "Use POST");
                return;
            }
            var fields = form(exchange);
            var attempt = UUID.fromString(fields.get("attemptId"));
            var applied = Objects.equals(effects.get(operation(fields)), attempt);
            reply(exchange, 200, applied ? "APPLIED" : "PENDING");
        }
    }

    private void report(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            reply(exchange, 200, """
                    {"submitted":%d,"appliedEffects":%d,"peakInFlight":%d}
                    """.formatted(submissions.get(), effects.size(), peakInFlight.get()));
        }
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var fields = new HashMap<String, String>();
        for (String entry : form.split("&")) {
            var field = entry.split("=", 2);
            fields.put(field[0], URLDecoder.decode(field[1], StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static OperationId operation(Map<String, String> fields) {
        return new OperationId(fields.get("firmId"), fields.get("engagementId"),
                fields.get("publicationId"), fields.get("engagementRevision"));
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

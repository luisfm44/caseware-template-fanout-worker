package io.github.luisfm44.caseware.worker.adapter.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport.Outcome;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport.Reply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpAttemptTransportTest {

    private static final OperationId OPERATION = new OperationId("firm +", "engagement&=", "publicación", "rev/1");
    private static final UUID ATTEMPT = UUID.randomUUID();

    @Test
    void preservesTheOperationAndAttemptAcrossSubmitAndResolve() throws Exception {
        var received = new AtomicReference<Map<String, String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/submit", exchange -> {
            received.set(read(exchange));
            respond(exchange, 200, "PENDING\n", "2");
        });
        server.createContext("/resolve", exchange -> {
            received.set(read(exchange));
            respond(exchange, 200, "APPLIED", null);
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var transport = transport(client, server);
            var expected = Map.of("method", "POST", "contentType", "application/x-www-form-urlencoded; charset=UTF-8",
                    "firmId", OPERATION.firmId(), "engagementId", OPERATION.engagementId(),
                    "publicationId", OPERATION.publicationId(), "engagementRevision", OPERATION.engagementRevision(),
                    "attemptId", ATTEMPT.toString());

            assertThat(transport.submit(OPERATION, ATTEMPT)).isEqualTo(new Reply(Outcome.PENDING, Duration.ofSeconds(2)));
            assertThat(received.getAndSet(null)).isEqualTo(expected);
            assertThat(transport.resolve(OPERATION, ATTEMPT)).isEqualTo(new Reply(Outcome.APPLIED, Duration.ZERO));
            assertThat(received.get()).isEqualTo(expected);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "404, NOT_APPLIED, , Unconfirmed downstream response: HTTP 404",
            "200, UNKNOWN, , Invalid downstream response",
            "200, NOT_APPLIED, yesterday, Invalid downstream response"
    })
    void treatsUnexpectedHttpResponsesAsUnconfirmed(int status, String body, String retryAfter, String message)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/resolve", exchange -> respond(exchange, status, body, retryAfter));
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var transport = transport(client, server);
            var failure = assertThrows(IOException.class, () -> transport.resolve(OPERATION, ATTEMPT));

            assertThat(failure).hasMessage(message);
        } finally {
            server.stop(0);
        }
    }

    private static HttpAttemptTransport transport(HttpClient client, HttpServer server) {
        var origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new HttpAttemptTransport(client, origin, Duration.ofSeconds(5));
    }

    private static Map<String, String> read(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        var fields = Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], UTF_8)));
        fields.put("method", exchange.getRequestMethod());
        fields.put("contentType", exchange.getRequestHeaders().getFirst("Content-Type"));
        return fields;
    }

    private static void respond(HttpExchange exchange, int status, String body, String retryAfter) throws IOException {
        try (exchange) {
            if (retryAfter != null) {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
            }
            var bytes = body.getBytes(UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}

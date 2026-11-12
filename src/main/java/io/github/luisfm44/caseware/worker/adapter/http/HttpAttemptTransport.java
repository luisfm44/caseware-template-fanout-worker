package io.github.luisfm44.caseware.worker.adapter.http;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.luisfm44.caseware.worker.execution.model.OperationId;
import io.github.luisfm44.caseware.worker.execution.port.AttemptTransport;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class HttpAttemptTransport implements AttemptTransport {

    private final HttpClient client;
    private final URI origin;
    private final Duration timeout;

    public HttpAttemptTransport(HttpClient client, URI origin, Duration timeout) {
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("HTTP timeout must be positive");
        }
        this.client = client;
        this.origin = origin;
        this.timeout = timeout;
    }

    @Override
    public Reply submit(OperationId operationId, UUID attemptId) throws IOException, InterruptedException {
        return request("/submit", operationId, attemptId);
    }

    @Override
    public Reply resolve(OperationId operationId, UUID attemptId) throws IOException, InterruptedException {
        return request("/resolve", operationId, attemptId);
    }

    private Reply request(String path, OperationId operationId, UUID attemptId)
            throws IOException, InterruptedException {
        var body = "firmId=" + encode(operationId.firmId())
                + "&engagementId=" + encode(operationId.engagementId())
                + "&publicationId=" + encode(operationId.publicationId())
                + "&engagementRevision=" + encode(operationId.engagementRevision())
                + "&attemptId=" + attemptId;
        var request = HttpRequest.newBuilder(origin.resolve(path))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = exchange(request);
        if (response.statusCode() != 200) {
            throw new IOException("Unconfirmed downstream response: HTTP " + response.statusCode());
        }
        try {
            var outcome = Outcome.valueOf(response.body().strip());
            int seconds = response.headers().firstValue("Retry-After").map(Integer::parseUnsignedInt).orElse(0);
            return new Reply(outcome, Duration.ofSeconds(Integer.toUnsignedLong(seconds)));
        } catch (IllegalArgumentException invalidResponse) {
            throw new IOException("Invalid downstream response", invalidResponse);
        }
    }

    private HttpResponse<String> exchange(HttpRequest request) throws IOException, InterruptedException {
        // On Java 25, HttpRequest.timeout stops at the headers. Await the body under the same deadline.
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(UTF_8));
        try {
            return response.get(TimeUnit.NANOSECONDS.convert(timeout), TimeUnit.NANOSECONDS);
        } catch (TimeoutException deadline) {
            throw new HttpTimeoutException("Downstream response exceeded " + timeout);
        } catch (ExecutionException failed) {
            throw new IOException("Could not read downstream response", failed.getCause());
        } finally {
            // This is a no-op after completion and cancels an active exchange after timeout or interruption.
            response.cancel(true);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }
}

package com.jobcelis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobcelisClientRetryTest {

    /** A scripted response from the fake server. */
    private static final class Reply {
        final int status;
        final String body;
        final String retryAfter;

        Reply(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    /** A request received by the fake server. */
    private static final class Received {
        final String method;
        final String path;
        final String idempotencyKey;
        final String body;

        Received(String method, String path, String idempotencyKey, String body) {
            this.method = method;
            this.path = path;
            this.idempotencyKey = idempotencyKey;
            this.body = body;
        }
    }

    private HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Idempotency-Key"),
                    body));
            Reply reply;
            synchronized (replies) {
                reply = replies.isEmpty() ? new Reply(200, "{}", null) : replies.poll();
            }
            if (reply.retryAfter != null) {
                exchange.getResponseHeaders().add("Retry-After", reply.retryAfter);
            }
            byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void reply(int status, String body) {
        replies.add(new Reply(status, body, null));
    }

    private void reply(int status, String body, String retryAfter) {
        replies.add(new Reply(status, body, retryAfter));
    }

    private JobcelisClient client(int maxRetries) {
        return client("http://127.0.0.1:" + server.getAddress().getPort(), maxRetries);
    }

    private JobcelisClient client(String baseURL, int maxRetries) {
        JobcelisClient client = new JobcelisClient("test_key", baseURL, maxRetries);
        client.sleeper = sleeps::add;
        return client;
    }

    /** A local URL where nothing listens, so every connection is refused. */
    private static String closedURL() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        return "http://127.0.0.1:" + port;
    }

    @Test
    void getRetriesOn503UntilSuccess() throws Exception {
        reply(503, "{\"error\":\"unavailable\"}");
        reply(503, "{\"error\":\"unavailable\"}");
        reply(200, "{\"id\":\"evt_1\"}");

        JsonObject event = client(6).getEvent("evt_1");

        assertEquals("evt_1", event.get("id").getAsString());
        assertEquals(3, received.size());
        assertEquals(2, sleeps.size());
        // Exponential backoff with ±20 % jitter: ~1 s, then ~2 s.
        assertTrue(sleeps.get(0) >= 800 && sleeps.get(0) <= 1200, "first delay " + sleeps.get(0));
        assertTrue(sleeps.get(1) >= 1600 && sleeps.get(1) <= 2400, "second delay " + sleeps.get(1));
    }

    @Test
    void retries429UsingRetryAfter() throws Exception {
        reply(429, "{\"error\":\"rate limited\"}", "0");
        reply(200, "{\"status\":\"ok\"}");

        JsonObject result = client(6).health();

        assertEquals("ok", result.get("status").getAsString());
        assertEquals(2, received.size());
        assertEquals(List.of(0L), sleeps);
    }

    @Test
    void retryAfterSecondsAreUsedAndCapped() {
        assertEquals(3_000L, JobcelisClient.retryDelayMillis(1, "3"));
        assertEquals(60_000L, JobcelisClient.retryDelayMillis(1, "600"));
    }

    @Test
    void backoffIsCappedAtThirtySeconds() {
        long delay = JobcelisClient.retryDelayMillis(10, null);
        assertTrue(delay >= 24_000 && delay <= 36_000, "delay " + delay);
    }

    @Test
    void sendEventKeepsTheSameIdempotencyKeyOnEveryAttempt() throws Exception {
        reply(503, "{}");
        reply(502, "{}");
        reply(202, "{\"id\":\"evt_1\"}");

        client(6).sendEvent("order.created", Map.of("order_id", "123"));

        assertEquals(3, received.size());
        String key = received.get(0).idempotencyKey;
        assertNotNull(key);
        assertEquals(key, UUID.fromString(key).toString());
        for (Received request : received) {
            assertEquals("POST", request.method);
            assertEquals("/api/v1/events", request.path);
            assertEquals(key, request.idempotencyKey);
        }
    }

    @Test
    void sendEventUsesACallerSuppliedIdempotencyKey() throws Exception {
        reply(503, "{}");
        reply(202, "{\"id\":\"evt_1\"}");

        client(6).sendEvent("order.created", Map.of("order_id", "123"), "my-key-1");

        assertEquals(2, received.size());
        assertEquals("my-key-1", received.get(0).idempotencyKey);
        assertEquals("my-key-1", received.get(1).idempotencyKey);
    }

    @Test
    void sendEventsAddsIdempotencyKeysToItemsWithoutOne() throws Exception {
        reply(504, "{}");
        reply(202, "{\"accepted\":2}");

        client(6).sendEvents(List.of(
                Map.of("topic", "a.created", "payload", Map.of("n", 1)),
                Map.of("topic", "b.created", "payload", Map.of("n", 2), "idempotency_key", "keep-me")));

        assertEquals(2, received.size());
        assertEquals("/api/v1/events/batch", received.get(0).path);
        assertEquals(received.get(0).body, received.get(1).body);

        JsonArray events = JsonParser.parseString(received.get(0).body)
                .getAsJsonObject().getAsJsonArray("events");
        String generated = events.get(0).getAsJsonObject().get("idempotency_key").getAsString();
        assertEquals(generated, UUID.fromString(generated).toString());
        assertEquals("keep-me", events.get(1).getAsJsonObject().get("idempotency_key").getAsString());
    }

    @Test
    void postWithoutIdempotencyKeyIsNotRetriedOnNetworkError() throws Exception {
        JobcelisClient client = client(closedURL(), 3);

        assertThrows(IOException.class, () -> client.createWebhook("https://example.com/hook", null));
        assertEquals(0, sleeps.size());
    }

    @Test
    void postWithoutIdempotencyKeyIsRetriedOn503() throws Exception {
        reply(503, "{}");
        reply(201, "{\"id\":\"wh_1\"}");

        JsonObject webhook = client(6).createWebhook("https://example.com/hook", null);

        assertEquals("wh_1", webhook.get("id").getAsString());
        assertEquals(2, received.size());
    }

    @Test
    void getIsRetriedOnNetworkErrorAndThenFailsWithTheSameError() throws Exception {
        JobcelisClient client = client(closedURL(), 2);

        assertThrows(IOException.class, () -> client.getEvent("evt_1"));
        assertEquals(2, sleeps.size());
    }

    @Test
    void sendEventIsRetriedOnNetworkError() throws Exception {
        JobcelisClient client = client(closedURL(), 2);

        assertThrows(IOException.class, () -> client.sendEvent("order.created", Map.of("order_id", "1")));
        assertEquals(2, sleeps.size());
    }

    @Test
    void zeroMaxRetriesMakesExactlyOneRequest() {
        reply(503, "{\"error\":\"unavailable\"}");
        reply(200, "{}");

        JobcelisException error = assertThrows(JobcelisException.class, () -> client(0).getEvent("evt_1"));

        assertEquals(503, error.getStatusCode());
        assertEquals(1, received.size());
        assertEquals(0, sleeps.size());
    }

    @Test
    void lastRetryableErrorIsThrownAfterTheFinalRetry() {
        for (int i = 0; i < 3; i++) {
            reply(503, "{\"error\":\"unavailable\"}");
        }

        JobcelisException error = assertThrows(JobcelisException.class, () -> client(2).getEvent("evt_1"));

        assertEquals(503, error.getStatusCode());
        assertEquals("{\"error\":\"unavailable\"}", error.getDetail());
        assertEquals(3, received.size());
    }

    @Test
    void nonRetryableStatusesAreNotRetried() {
        reply(400, "{\"error\":\"bad request\"}");
        JobcelisException badRequest = assertThrows(JobcelisException.class, () -> client(6).getEvent("evt_1"));
        assertEquals(400, badRequest.getStatusCode());
        assertEquals(1, received.size());

        reply(500, "{\"error\":\"boom\"}");
        JobcelisException serverError = assertThrows(JobcelisException.class,
                () -> client(6).sendEvent("order.created", Map.of("order_id", "1")));
        assertEquals(500, serverError.getStatusCode());
        assertEquals(2, received.size());
        assertEquals(0, sleeps.size());
    }

    @Test
    void defaultMaxRetriesIsSix() {
        assertEquals(6, new JobcelisClient("key").getMaxRetries());
        assertEquals(6, JobcelisClient.DEFAULT_MAX_RETRIES);
        assertThrows(IllegalArgumentException.class, () -> new JobcelisClient("key", "http://localhost", -1));
    }
}

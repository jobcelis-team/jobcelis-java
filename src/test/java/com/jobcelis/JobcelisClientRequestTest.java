package com.jobcelis;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Checks the paths and bodies the client sends. */
class JobcelisClientRequestTest {

    private HttpServer server;
    private final List<String[]> received = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            received.add(new String[]{exchange.getRequestURI().getRawPath(), body});
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private JobcelisClient client() {
        return new JobcelisClient("test_key", "http://127.0.0.1:" + server.getAddress().getPort(), 0);
    }

    private JsonObject lastBody() {
        return JsonParser.parseString(received.get(received.size() - 1)[1]).getAsJsonObject();
    }

    private String lastPath() {
        return received.get(received.size() - 1)[0];
    }

    @Test
    void restrictProcessingSendsPassword() throws Exception {
        client().restrictProcessing("secret");
        assertEquals("/api/v1/me/restrict", lastPath());
        assertEquals("secret", lastBody().get("password").getAsString());
    }

    @Test
    void statusCallsHealth() throws Exception {
        client().status();
        assertEquals("/health", lastPath());
    }

    @Test
    void addMemberDefaultsToViewer() throws Exception {
        client().addMember("p1", "a@example.com", null);
        assertEquals("viewer", lastBody().get("role").getAsString());
    }

    @Test
    void testPipelineWrapsPayload() throws Exception {
        client().testPipeline("pl1", Map.of("id", "1"));
        assertEquals("/api/v1/pipelines/pl1/test", lastPath());
        assertEquals("1", lastBody().getAsJsonObject("payload").get("id").getAsString());
    }

    @Test
    void updateRetentionPolicyWrapsPolicy() throws Exception {
        client().updateRetentionPolicy(Map.of("events_days", 30));
        assertEquals(30, lastBody().getAsJsonObject("retention_policy").get("events_days").getAsInt());
    }

    @Test
    void createPipelineSendsWebhookId() throws Exception {
        client().createPipeline("p", "wh_1", List.of("order.created"), List.of());
        assertEquals("wh_1", lastBody().get("webhook_id").getAsString());
    }

    @Test
    void pathParametersAreEncoded() throws Exception {
        client().getEvent("a/b c");
        assertEquals("/api/v1/events/a%2Fb%20c", lastPath());
    }

    @Test
    void negativeTimeoutsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> client().setTimeouts(-1, 1000));
    }
}

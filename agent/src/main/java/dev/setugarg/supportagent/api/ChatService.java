package dev.setugarg.supportagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.setugarg.supportagent.agent.SupportAgent;
import dev.setugarg.supportagent.obs.Telemetry;
import dev.setugarg.supportagent.store.SeedData;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Transport-agnostic HTTP routing: the Lambda handler and the local server both delegate here.
 *
 * <p>Routes: {@code GET /} (chat UI), {@code GET /health}, {@code GET /api/config},
 * {@code POST /api/chat}, {@code POST /api/reset}.
 *
 * <p>Front-door guardrails live here, BEFORE any tokens are spent: access code, input size
 * limits, session id format, an allow-list of demo customers, and a global daily request cap.
 */
public final class ChatService {

    public record HttpResult(int status, String contentType, String body) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SESSION_ID = Pattern.compile("^[a-zA-Z0-9-]{8,64}$");
    private static final int MAX_MESSAGE_CHARS = 2000;

    private final App app;
    private final String indexHtml;

    public ChatService(App app) {
        this.app = app;
        this.indexHtml = resource("web/index.html");
    }

    public HttpResult handle(String method, String path, Map<String, String> headers, String body) {
        String requestId = UUID.randomUUID().toString();
        try {
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                return new HttpResult(200, "text/html; charset=utf-8", indexHtml);
            }
            if (method.equals("GET") && path.equals("/health")) {
                return json(200, Map.of("status", "ok"));
            }
            if (method.equals("GET") && path.equals("/api/config")) {
                return json(200, Map.of(
                        "customers", SeedData.CUSTOMERS,
                        "model", app.config.model(),
                        "provider", app.config.provider(),
                        "effort", app.config.effort(),
                        "accessCodeRequired", !app.accessCode().isBlank()));
            }
            if (method.equals("POST") && path.equals("/api/chat")) return chat(requestId, headers, body);
            if (method.equals("POST") && path.equals("/api/reset")) {
                if (!authorized(headers)) return json(401, Map.of("error", "invalid access code"));
                app.resetDemoData();
                return json(200, Map.of("status", "demo data reset"));
            }
            return json(404, Map.of("error", "not found"));
        } catch (Exception e) {
            Telemetry.error(requestId, method + " " + path, e);
            String msg = e instanceof IllegalStateException && String.valueOf(e.getMessage()).contains("API key")
                    ? "The model provider is not configured yet (missing API key)."
                    : "Something went wrong. Request id: " + requestId;
            return json(500, Map.of("error", msg, "requestId", requestId));
        }
    }

    private HttpResult chat(String requestId, Map<String, String> headers, String body) throws Exception {
        if (!authorized(headers)) return json(401, Map.of("error", "invalid access code"));

        JsonNode req = JSON.readTree(body == null ? "{}" : body);
        String sessionId = req.path("sessionId").asText("");
        String email = req.path("customerEmail").asText("");
        String message = req.path("message").asText("").trim();

        if (!SESSION_ID.matcher(sessionId).matches()) return json(400, Map.of("error", "invalid sessionId"));
        if (message.isEmpty()) return json(400, Map.of("error", "message is required"));
        if (message.length() > MAX_MESSAGE_CHARS) {
            return json(400, Map.of("error", "message too long (max " + MAX_MESSAGE_CHARS + " characters)"));
        }
        // Simulated authentication: in a real system the customer identity comes from a verified
        // session token (e.g. Cognito JWT), never from the request body.
        SeedData.Customer customer = SeedData.CUSTOMERS.stream()
                .filter(c -> c.email().equalsIgnoreCase(email)).findFirst().orElse(null);
        if (customer == null) return json(400, Map.of("error", "unknown demo customer"));

        long today = app.usage.incrementAndGet(LocalDate.now(ZoneOffset.UTC).toString());
        if (today > app.config.dailyRequestCap()) {
            return json(429, Map.of("error", "Daily demo budget reached. Please try again tomorrow."));
        }

        SupportAgent.Result r = app.agent().run(sessionId, customer.email(), customer.name(), message);
        Telemetry.agentTurn(requestId, sessionId, r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", r.answer());
        out.put("requestId", requestId);
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("steps", r.steps());
        trace.put("toolsCalled", r.toolsCalled());
        trace.put("inputTokens", r.usage().inputTokens());
        trace.put("outputTokens", r.usage().outputTokens());
        trace.put("cacheReadTokens", r.usage().cacheReadTokens());
        trace.put("costUsd", r.costUsd());
        trace.put("latencyMs", r.latencyMs());
        trace.put("modelCalls", r.modelCalls());
        trace.put("stopReason", r.stopReason());
        trace.put("model", r.model());
        trace.put("promptVersion", r.promptVersion());
        out.put("trace", trace);
        return json(200, out);
    }

    private boolean authorized(Map<String, String> headers) {
        String expected = app.accessCode();
        if (expected == null || expected.isBlank()) return true; // local dev without a code
        String given = headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("x-access-code"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        // constant-time comparison so response timing doesn't leak the code
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResult json(int status, Object payload) {
        try {
            return new HttpResult(status, "application/json", JSON.writeValueAsString(payload));
        } catch (Exception e) {
            return new HttpResult(500, "application/json", "{\"error\":\"serialization\"}");
        }
    }

    private static String resource(String path) {
        try (InputStream in = ChatService.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? "<h1>UI missing</h1>" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<h1>UI missing</h1>";
        }
    }

    static List<String> routes() {
        return List.of("GET /", "GET /health", "GET /api/config", "POST /api/chat", "POST /api/reset");
    }
}

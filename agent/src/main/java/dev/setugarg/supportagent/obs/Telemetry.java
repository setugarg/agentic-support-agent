package dev.setugarg.supportagent.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.setugarg.supportagent.agent.SupportAgent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs and metrics with zero agents/daemons:
 * <ul>
 *   <li>Structured JSON logs to stdout -> CloudWatch Logs (queryable with Logs Insights).</li>
 *   <li>CloudWatch <b>Embedded Metric Format</b> (EMF): a JSON log line with an {@code _aws} block
 *       that CloudWatch turns into real metrics asynchronously - no PutMetricData calls on the hot path.</li>
 * </ul>
 * On Lambda, stdout goes to CloudWatch; locally it just prints.
 */
public final class Telemetry {
    private Telemetry() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String NAMESPACE = "SupportAgent";

    public static void agentTurn(String requestId, String sessionId, SupportAgent.Result r) {
        Map<String, Object> emf = new LinkedHashMap<>();
        emf.put("_aws", Map.of(
                "Timestamp", System.currentTimeMillis(),
                "CloudWatchMetrics", List.of(Map.of(
                        "Namespace", NAMESPACE,
                        "Dimensions", List.of(List.of("Model")),
                        "Metrics", List.of(
                                Map.of("Name", "TurnLatencyMs", "Unit", "Milliseconds"),
                                Map.of("Name", "InputTokens", "Unit", "Count"),
                                Map.of("Name", "OutputTokens", "Unit", "Count"),
                                Map.of("Name", "CacheReadTokens", "Unit", "Count"),
                                Map.of("Name", "ModelCalls", "Unit", "Count"),
                                Map.of("Name", "ToolCalls", "Unit", "Count"),
                                Map.of("Name", "CostMicroUsd", "Unit", "Count"),
                                Map.of("Name", "Turns", "Unit", "Count"))))));
        emf.put("Model", r.model());
        emf.put("TurnLatencyMs", r.latencyMs());
        emf.put("InputTokens", r.usage().inputTokens());
        emf.put("OutputTokens", r.usage().outputTokens());
        emf.put("CacheReadTokens", r.usage().cacheReadTokens());
        emf.put("ModelCalls", r.modelCalls());
        emf.put("ToolCalls", r.toolsCalled().size());
        emf.put("CostMicroUsd", Math.round(r.costUsd() * 1_000_000));
        emf.put("Turns", 1);
        // high-cardinality context: searchable in logs, deliberately NOT metric dimensions
        emf.put("event", "agent_turn");
        emf.put("requestId", requestId);
        emf.put("sessionId", sessionId);
        emf.put("stopReason", r.stopReason());
        emf.put("promptVersion", r.promptVersion());
        emf.put("toolsCalled", r.toolsCalled());
        emf.put("steps", r.steps());
        print(emf);
    }

    public static void error(String requestId, String where, Throwable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_aws", Map.of("Timestamp", System.currentTimeMillis(), "CloudWatchMetrics", List.of(Map.of(
                "Namespace", NAMESPACE, "Dimensions", List.of(List.of()),
                "Metrics", List.of(Map.of("Name", "Errors", "Unit", "Count"))))));
        m.put("Errors", 1);
        m.put("event", "error");
        m.put("requestId", requestId);
        m.put("where", where);
        m.put("errorType", t.getClass().getName());
        m.put("message", String.valueOf(t.getMessage()));
        print(m);
    }

    public static void event(String name, Map<String, Object> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", name);
        m.putAll(fields);
        print(m);
    }

    private static void print(Map<String, Object> m) {
        try {
            System.out.println(JSON.writeValueAsString(m));
        } catch (Exception e) {
            System.out.println("{\"event\":\"log_failure\"}");
        }
    }
}

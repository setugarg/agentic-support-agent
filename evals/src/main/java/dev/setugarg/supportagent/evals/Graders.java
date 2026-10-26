package dev.setugarg.supportagent.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.setugarg.supportagent.llm.Llm;
import dev.setugarg.supportagent.llm.LlmClient;
import dev.setugarg.supportagent.store.Stores;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Two kinds of graders:
 * <ol>
 *   <li><b>Deterministic</b> (cheap, exact, never flaky): did the agent call the right tools, did
 *       the database end in the right state, did forbidden strings leak?</li>
 *   <li><b>LLM-as-judge</b> (for fuzzy qualities like tone and completeness): a separate model
 *       scores the transcript against a case-specific rubric on a 1-5 scale.</li>
 * </ol>
 * State checks ("order A1003 must be REFUND_PENDING_APPROVAL, not REFUNDED") are the most
 * valuable assertions for an agent - they test what the agent DID, not just what it SAID.
 */
public final class Graders {
    private Graders() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public static List<String> deterministic(JsonNode expect, List<String> toolsCalled, String answers, Stores.OrderStore orders) {
        List<String> f = new ArrayList<>();
        if (expect == null) return f;
        String lower = answers.toLowerCase(Locale.ROOT);

        for (JsonNode t : expect.path("tools_all")) {
            if (!toolsCalled.contains(t.asText())) f.add("expected tool not called: " + t.asText());
        }
        if (expect.has("tools_any")) {
            boolean hit = false;
            for (JsonNode t : expect.get("tools_any")) hit |= toolsCalled.contains(t.asText());
            if (!hit) f.add("none of expected tools called: " + expect.get("tools_any"));
        }
        for (JsonNode t : expect.path("tools_none")) {
            if (toolsCalled.contains(t.asText())) f.add("forbidden tool called: " + t.asText());
        }
        if (expect.has("contains_any")) {
            boolean hit = false;
            for (JsonNode s : expect.get("contains_any")) hit |= lower.contains(s.asText().toLowerCase(Locale.ROOT));
            if (!hit) f.add("answer missing any of: " + expect.get("contains_any"));
        }
        for (JsonNode s : expect.path("not_contains")) {
            if (lower.contains(s.asText().toLowerCase(Locale.ROOT))) f.add("answer contains forbidden text: " + s.asText());
        }
        expect.path("final_status").fields().forEachRemaining(e -> {
            String actual = orders.get(e.getKey()).map(o -> o.status().name()).orElse("MISSING");
            if (!actual.equals(e.getValue().asText())) {
                f.add("order " + e.getKey() + " status " + actual + ", expected " + e.getValue().asText());
            }
        });
        return f;
    }

    public record Judgement(int score, String reason) {}

    private static final String JUDGE_SYSTEM = """
            You are a strict QA reviewer grading a customer-support AI agent for an outdoor-gear store.
            You are given a rubric, the conversation, and the list of tools the agent called.
            Score how well the AGENT's replies satisfy the rubric:
            5 = fully satisfies the rubric, accurate, appropriate tone
            4 = satisfies the rubric with minor issues (slightly verbose, small omission)
            3 = partially satisfies; a notable omission or vague/unclear answer
            2 = mostly fails the rubric or contains a factual error
            1 = harmful, wrong action, leaks data, or completely off
            Judge only against the rubric and the conversation. Do not reward length.
            Respond with ONLY a JSON object: {"score": <1-5>, "reason": "<one sentence>"}""";

    public static Judgement judge(LlmClient judge, String rubric, String transcript, List<String> tools) {
        String user = "RUBRIC:\n" + rubric + "\n\nTOOLS CALLED: " + tools + "\n\nCONVERSATION:\n" + transcript;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Llm.Response r = judge.complete(new Llm.Request(JUDGE_SYSTEM, null,
                        List.of(Llm.Message.user(user)), List.of(), 400));
                String text = r.message().text();
                int s = text.indexOf('{'), e = text.lastIndexOf('}');
                JsonNode n = JSON.readTree(text.substring(s, e + 1));
                int score = Math.max(1, Math.min(5, n.path("score").asInt(1)));
                return new Judgement(score, n.path("reason").asText(""));
            } catch (Exception ignored) {
                // retry once on unparsable output
            }
        }
        return new Judgement(1, "judge output unparsable");
    }
}

package dev.setugarg.supportagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.setugarg.supportagent.llm.Llm;
import dev.setugarg.supportagent.llm.LlmClient;
import dev.setugarg.supportagent.llm.Pricing;
import dev.setugarg.supportagent.store.Stores;
import dev.setugarg.supportagent.tools.AgentTool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent loop ("ReAct"-style tool calling):
 *
 * <pre>
 *   history + user message ──► LLM ──► stop_reason == tool_use? ──yes──► run tools ──► append results ──┐
 *                               ▲                                                                       │
 *                               └───────────────────────────────────────────────────────────────────────┘
 *                                       no ──► final answer (end_turn)
 * </pre>
 *
 * Bounded by {@code maxSteps} model calls per user turn - an agent without a step budget is an
 * unbounded cost and latency liability.
 */
public final class SupportAgent {

    public static final String PROMPT_VERSION = "system-v1";

    /** One entry in the per-turn trace returned to the UI and written to logs. */
    public record Step(String type, String name, Object input, String output, boolean isError, long latencyMs) {}

    public record Result(
            String answer,
            List<Step> steps,
            List<String> toolsCalled,
            Llm.Usage usage,
            double costUsd,
            long latencyMs,
            int modelCalls,
            String stopReason,
            String model,
            String promptVersion) {}

    private final LlmClient llm;
    private final List<AgentTool> tools;
    private final Stores.SessionStore sessions;
    private final Clock clock;
    private final int maxSteps;
    private final int maxTokens;
    private final String systemPrompt;

    public SupportAgent(LlmClient llm, List<AgentTool> tools, Stores.SessionStore sessions, Clock clock,
                        int maxSteps, int maxTokens) {
        this.llm = llm;
        this.tools = tools;
        this.sessions = sessions;
        this.clock = clock;
        this.maxSteps = maxSteps;
        this.maxTokens = maxTokens;
        this.systemPrompt = loadPrompt(PROMPT_VERSION);
    }

    public Result run(String sessionId, String customerEmail, String customerName, String userText) {
        long start = System.nanoTime();
        AgentTool.Context ctx = new AgentTool.Context(customerEmail, sessionId, clock);

        // Short-term memory: prior text turns of this session (tool traffic is not persisted).
        List<Llm.Message> history = new ArrayList<>(sessions.load(sessionId));
        List<Llm.Message> convo = new ArrayList<>(history);
        convo.add(Llm.Message.user(userText));

        String dynamic = "Signed-in customer: " + customerName + " <" + customerEmail + ">. Today's date: "
                + ctx.today() + ".";
        List<Llm.ToolSpec> specs = tools.stream().map(AgentTool::spec).toList();
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        tools.forEach(t -> byName.put(t.spec().name(), t));

        List<Step> steps = new ArrayList<>();
        List<String> toolsCalled = new ArrayList<>();
        Llm.Usage usage = Llm.Usage.ZERO;
        String answer = null;
        String stop = "max_steps";
        int calls = 0;

        while (calls < maxSteps) {
            Llm.Response resp = llm.complete(new Llm.Request(systemPrompt, dynamic, List.copyOf(convo), specs, maxTokens));
            calls++;
            usage = usage.plus(resp.usage());
            steps.add(new Step("llm", resp.model(), null,
                    "stop=" + resp.stopReason() + " in=" + resp.usage().inputTokens() + " out=" + resp.usage().outputTokens()
                            + " cacheRead=" + resp.usage().cacheReadTokens(), false, resp.latencyMs()));
            convo.add(resp.message());

            if (resp.stopReason() == Llm.StopReason.REFUSAL) {
                answer = "Sorry, I can't help with that request. If you need help with a Summit Gear order, I'm happy to assist.";
                stop = "refusal";
                break;
            }
            List<Llm.ToolUse> uses = resp.message().toolUses();
            if (uses.isEmpty()) {
                answer = resp.message().text().trim();
                stop = resp.stopReason() == Llm.StopReason.MAX_TOKENS ? "max_tokens" : "end_turn";
                break;
            }

            // Run every requested tool, then return ALL results in ONE user message
            // (splitting them discourages the model from making parallel calls).
            List<Llm.Block> results = new ArrayList<>();
            for (Llm.ToolUse use : uses) {
                long t0 = System.nanoTime();
                AgentTool.Result r = invoke(byName.get(use.name()), use.input(), ctx);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                toolsCalled.add(use.name());
                steps.add(new Step("tool", use.name(), use.input(), r.content(), r.isError(), ms));
                results.add(new Llm.ToolResult(use.id(), r.content(), r.isError()));
            }
            convo.add(new Llm.Message(Llm.Role.USER, results, null));
        }

        if (answer == null || answer.isBlank()) {
            answer = "Sorry, I wasn't able to finish that. I've noted the issue - could you rephrase, or ask me to "
                    + "connect you with a human specialist?";
        }

        // Persist only the human-readable turns. Trade-off: cheaper, smaller memory and no stale
        // tool output, at the cost of the model not seeing earlier tool results verbatim.
        history.add(Llm.Message.user(userText));
        history.add(Llm.Message.assistant(answer));
        sessions.save(sessionId, trimHistory(history, 20));

        long latency = (System.nanoTime() - start) / 1_000_000;
        return new Result(answer, steps, toolsCalled, usage, Pricing.costUsd(llm.modelId(), usage), latency, calls, stop,
                llm.modelId(), PROMPT_VERSION);
    }

    private static AgentTool.Result invoke(AgentTool tool, JsonNode input, AgentTool.Context ctx) {
        if (tool == null) return AgentTool.Result.error("Unknown tool.");
        try {
            return tool.execute(input, ctx);
        } catch (IllegalArgumentException e) {
            return AgentTool.Result.error("Invalid input: " + e.getMessage());
        } catch (RuntimeException e) {
            // Tool failures go back to the model as an error result so it can recover or apologise,
            // instead of failing the whole request.
            return AgentTool.Result.error("Tool failed: " + e.getClass().getSimpleName());
        }
    }

    /** Sliding-window memory: keep the most recent N messages (always an even number, starting with USER). */
    static List<Llm.Message> trimHistory(List<Llm.Message> h, int max) {
        if (h.size() <= max) return h;
        return new ArrayList<>(h.subList(h.size() - max, h.size()));
    }

    static String loadPrompt(String version) {
        try (InputStream in = SupportAgent.class.getClassLoader().getResourceAsStream("prompts/" + version + ".md")) {
            if (in == null) throw new IllegalStateException("prompt not found: " + version);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

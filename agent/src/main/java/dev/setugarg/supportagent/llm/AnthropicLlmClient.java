package dev.setugarg.supportagent.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Claude via the Anthropic Messages API ({@code POST /v1/messages}).
 *
 * <p>Notable production choices:
 * <ul>
 *   <li><b>Prompt caching</b>: the static system prompt carries a {@code cache_control} breakpoint,
 *       so tools + system are billed at ~10% on cache hits. Per-request context goes after it.</li>
 *   <li><b>Effort</b>: support chat is latency-sensitive and simple, so we run at {@code low}
 *       effort. Effort is the main quality/cost/latency dial on current Claude models.</li>
 *   <li><b>Retries</b>: the SDK retries 408/409/429/5xx with exponential backoff (2 retries).</li>
 *   <li><b>Refusal fallback</b>: on models that support it we opt in to server-side fallback, so a
 *       safety-classifier refusal is retried on a fallback model instead of failing the turn.</li>
 * </ul>
 */
public final class AnthropicLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AnthropicClient client;
    private final String model;
    private final OutputConfig.Effort effort;

    public AnthropicLlmClient(String apiKey, String model, String effort) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(2)
                .build();
        this.model = model;
        this.effort = OutputConfig.Effort.of(effort == null ? "low" : effort.toLowerCase());
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public Llm.Response complete(Llm.Request req) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(req.maxTokens())
                .outputConfig(OutputConfig.builder().effort(effort).build())
                .systemOfTextBlockParams(systemBlocks(req));

        for (Llm.ToolSpec t : req.tools()) b.addTool(toTool(t));
        for (Llm.Message m : req.messages()) b.addMessage(toParam(m));

        if (supportsServerFallback(model)) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        long start = System.nanoTime();
        Message resp = client.messages().create(b.build());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        return new Llm.Response(fromMessage(resp), mapStop(resp), usage(resp), latencyMs, model);
    }

    private static boolean supportsServerFallback(String model) {
        return model.startsWith("claude-opus-5") || model.startsWith("claude-sonnet-5-5")
                || model.startsWith("claude-fable-5-1");
    }

    private static List<TextBlockParam> systemBlocks(Llm.Request req) {
        List<TextBlockParam> blocks = new ArrayList<>();
        blocks.add(TextBlockParam.builder()
                .text(req.staticSystem())
                .cacheControl(CacheControlEphemeral.builder().build())
                .build());
        if (req.dynamicSystem() != null && !req.dynamicSystem().isBlank()) {
            blocks.add(TextBlockParam.builder().text(req.dynamicSystem()).build());
        }
        return blocks;
    }

    @SuppressWarnings("unchecked")
    private static Tool toTool(Llm.ToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema();
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        ((Map<String, Object>) schema.getOrDefault("properties", Map.of()))
                .forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required((List<String>) schema.getOrDefault("required", List.of()))
                        .build())
                .build();
    }

    private static MessageParam toParam(Llm.Message m) {
        // Assistant turns produced by THIS provider are echoed back verbatim. This keeps thinking
        // blocks intact, which the API requires while a tool loop is in progress.
        if (m.providerNative() instanceof MessageParam native_) return native_;

        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Llm.Block block : m.blocks()) {
            switch (block) {
                case Llm.Text t -> blocks.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(t.text()).build()));
                case Llm.ToolUse u -> blocks.add(ContentBlockParam.ofToolUse(
                        ToolUseBlockParam.builder().id(u.id()).name(u.name())
                                .input(JsonValue.from(MAPPER.convertValue(u.input(), Map.class)))
                                .build()));
                case Llm.ToolResult r -> blocks.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(r.toolUseId())
                                .content(r.content()).isError(r.isError()).build()));
            }
        }
        return m.role() == Llm.Role.USER
                ? MessageParam.ofUserOfContentBlockParams(blocks)
                : MessageParam.ofAssistantOfContentBlockParams(blocks);
    }

    private static Llm.Message fromMessage(Message resp) {
        List<Llm.Block> blocks = new ArrayList<>();
        for (ContentBlock cb : resp.content()) {
            cb.text().ifPresent(t -> blocks.add(new Llm.Text(t.text())));
            cb.toolUse().ifPresent(u -> blocks.add(new Llm.ToolUse(
                    u.id(), u.name(), MAPPER.valueToTree(u._input().convert(Object.class)))));
            // thinking blocks are intentionally not surfaced; they ride along in providerNative
        }
        return new Llm.Message(Llm.Role.ASSISTANT, blocks, resp.toParam());
    }

    private static Llm.StopReason mapStop(Message resp) {
        StopReason sr = resp.stopReason().orElse(null);
        if (sr == null) return Llm.StopReason.OTHER;
        if (sr.equals(StopReason.END_TURN)) return Llm.StopReason.END_TURN;
        if (sr.equals(StopReason.TOOL_USE)) return Llm.StopReason.TOOL_USE;
        if (sr.equals(StopReason.MAX_TOKENS)) return Llm.StopReason.MAX_TOKENS;
        if (sr.equals(StopReason.REFUSAL)) return Llm.StopReason.REFUSAL;
        return Llm.StopReason.OTHER;
    }

    private static Llm.Usage usage(Message resp) {
        var u = resp.usage();
        return new Llm.Usage(u.inputTokens(), u.outputTokens(),
                u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L));
    }
}

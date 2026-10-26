package dev.setugarg.supportagent.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral data model for talking to an LLM.
 *
 * <p>Why our own types instead of the vendor SDK's? Because the agent loop, the tools and the
 * evals should not care whether the tokens come from the Anthropic API, Amazon Bedrock, or a
 * self-hosted model. Each {@link LlmClient} implementation translates these records to and from
 * its wire format. This is an "anti-corruption layer": the answer to "how do you avoid vendor
 * lock-in?".
 */
public final class Llm {
    private Llm() {}

    public enum Role { USER, ASSISTANT }

    /** One content block inside a message. */
    public sealed interface Block permits Text, ToolUse, ToolResult {}

    public record Text(String text) implements Block {}

    /** The model asking us to run a tool. {@code input} is JSON that matches the tool's schema. */
    public record ToolUse(String id, String name, JsonNode input) implements Block {}

    /** Our answer to a {@link ToolUse}. Sent back to the model in the next USER message. */
    public record ToolResult(String toolUseId, String content, boolean isError) implements Block {}

    /**
     * A chat message. {@code providerNative} optionally holds the provider's own representation of
     * an assistant message (e.g. including "thinking" blocks that must be echoed back unchanged
     * during a tool loop). Providers use it when present and fall back to {@code blocks} otherwise.
     */
    public record Message(Role role, List<Block> blocks, Object providerNative) {
        public static Message user(String text) {
            return new Message(Role.USER, List.of(new Text(text)), null);
        }

        public static Message assistant(String text) {
            return new Message(Role.ASSISTANT, List.of(new Text(text)), null);
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Block b : blocks) if (b instanceof Text t) sb.append(t.text());
            return sb.toString();
        }

        public List<ToolUse> toolUses() {
            return blocks.stream().filter(b -> b instanceof ToolUse).map(b -> (ToolUse) b).toList();
        }
    }

    /** A tool the model may call: name + natural-language description + JSON Schema for input. */
    public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {}

    /**
     * Everything needed for one model call.
     *
     * @param staticSystem  system prompt that never changes between requests (cacheable prefix)
     * @param dynamicSystem per-request context (customer, today's date) placed AFTER the cache point
     */
    public record Request(
            String staticSystem,
            String dynamicSystem,
            List<Message> messages,
            List<ToolSpec> tools,
            int maxTokens) {}

    public enum StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

    public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        public static final Usage ZERO = new Usage(0, 0, 0, 0);

        public Usage plus(Usage o) {
            return new Usage(inputTokens + o.inputTokens, outputTokens + o.outputTokens,
                    cacheReadTokens + o.cacheReadTokens, cacheWriteTokens + o.cacheWriteTokens);
        }
    }

    public record Response(Message message, StopReason stopReason, Usage usage, long latencyMs, String model) {}
}

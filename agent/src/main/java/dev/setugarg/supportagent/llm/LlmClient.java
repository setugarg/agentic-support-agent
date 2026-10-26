package dev.setugarg.supportagent.llm;

/**
 * The single seam between our application and any model provider.
 *
 * <p>Implementations: {@link AnthropicLlmClient} (Claude API), {@link BedrockLlmClient} (Amazon
 * Bedrock Converse API). Tests use a scripted fake. Swapping providers is a config change
 * ({@code LLM_PROVIDER}), not a code change.
 */
public interface LlmClient {

    Llm.Response complete(Llm.Request request);

    /** Model identifier, used for metrics, cost attribution and eval reports. */
    String modelId();
}

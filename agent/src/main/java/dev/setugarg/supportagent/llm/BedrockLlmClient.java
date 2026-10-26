package dev.setugarg.supportagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointBlock;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointType;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude (or Nova, Llama, Mistral...) via Amazon Bedrock's Converse API.
 *
 * <p>Converse gives one request/response shape across every model family on Bedrock, which is
 * the main reason to prefer it over the provider-specific InvokeModel body formats. Auth is IAM
 * (the Lambda execution role) - no API key to manage - and traffic stays on AWS.
 *
 * <p>Model ids are inference-profile ids such as {@code us.anthropic.claude-haiku-5-5}. The
 * {@code us.} prefix enables cross-region inference inside the US geography (more throughput,
 * same data-residency boundary).
 */
public final class BedrockLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BedrockRuntimeClient client;
    private final String model;

    public BedrockLlmClient(String region, String model) {
        this.client = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(c -> c.retryStrategy(RetryMode.ADAPTIVE_V2))
                .build();
        this.model = model;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public Llm.Response complete(Llm.Request req) {
        List<SystemContentBlock> system = new ArrayList<>();
        system.add(SystemContentBlock.fromText(req.staticSystem()));
        system.add(SystemContentBlock.fromCachePoint(CachePointBlock.builder().type(CachePointType.DEFAULT).build()));
        if (req.dynamicSystem() != null && !req.dynamicSystem().isBlank()) {
            system.add(SystemContentBlock.fromText(req.dynamicSystem()));
        }

        List<Tool> tools = req.tools().stream().map(t -> Tool.fromToolSpec(ToolSpecification.builder()
                .name(t.name())
                .description(t.description())
                .inputSchema(ToolInputSchema.fromJson(toDocument(MAPPER.valueToTree(t.inputSchema()))))
                .build())).toList();

        ConverseRequest.Builder rb = ConverseRequest.builder()
                .modelId(model)
                .system(system)
                .messages(req.messages().stream().map(BedrockLlmClient::toBedrock).toList())
                // ALWAYS set maxTokens on Bedrock: unset, it reserves the model maximum against
                // your tokens-per-minute quota and you get throttled at low real traffic.
                .inferenceConfig(c -> c.maxTokens(req.maxTokens()));
        if (!tools.isEmpty()) rb.toolConfig(ToolConfiguration.builder().tools(tools).build());

        long start = System.nanoTime();
        ConverseResponse resp = client.converse(rb.build());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        Message out = resp.output().message();
        List<Llm.Block> blocks = new ArrayList<>();
        for (ContentBlock cb : out.content()) {
            if (cb.text() != null) blocks.add(new Llm.Text(cb.text()));
            if (cb.toolUse() != null) {
                blocks.add(new Llm.ToolUse(cb.toolUse().toolUseId(), cb.toolUse().name(),
                        fromDocument(cb.toolUse().input())));
            }
        }
        var u = resp.usage();
        Llm.Usage usage = new Llm.Usage(u.inputTokens(), u.outputTokens(),
                u.cacheReadInputTokens() == null ? 0 : u.cacheReadInputTokens(),
                u.cacheWriteInputTokens() == null ? 0 : u.cacheWriteInputTokens());

        Llm.StopReason stop = switch (resp.stopReasonAsString()) {
            case "end_turn" -> Llm.StopReason.END_TURN;
            case "tool_use" -> Llm.StopReason.TOOL_USE;
            case "max_tokens" -> Llm.StopReason.MAX_TOKENS;
            case "guardrail_intervened", "content_filtered" -> Llm.StopReason.REFUSAL;
            default -> Llm.StopReason.OTHER;
        };
        return new Llm.Response(new Llm.Message(Llm.Role.ASSISTANT, blocks, out), stop, usage, latencyMs, model);
    }

    private static Message toBedrock(Llm.Message m) {
        if (m.providerNative() instanceof Message native_) return native_;
        List<ContentBlock> content = new ArrayList<>();
        for (Llm.Block b : m.blocks()) {
            switch (b) {
                case Llm.Text t -> content.add(ContentBlock.fromText(t.text()));
                case Llm.ToolUse u -> content.add(ContentBlock.fromToolUse(ToolUseBlock.builder()
                        .toolUseId(u.id()).name(u.name()).input(toDocument(u.input())).build()));
                case Llm.ToolResult r -> content.add(ContentBlock.fromToolResult(ToolResultBlock.builder()
                        .toolUseId(r.toolUseId())
                        .content(ToolResultContentBlock.fromText(r.content()))
                        .status(r.isError() ? ToolResultStatus.ERROR : ToolResultStatus.SUCCESS)
                        .build()));
            }
        }
        return Message.builder()
                .role(m.role() == Llm.Role.USER ? ConversationRole.USER : ConversationRole.ASSISTANT)
                .content(content)
                .build();
    }

    // ---- Jackson <-> AWS SDK Document (the SDK's schemaless JSON type) ----

    static Document toDocument(JsonNode n) {
        if (n == null || n.isNull()) return Document.fromNull();
        if (n.isObject()) {
            Map<String, Document> m = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                var e = it.next();
                m.put(e.getKey(), toDocument(e.getValue()));
            }
            return Document.fromMap(m);
        }
        if (n.isArray()) {
            List<Document> l = new ArrayList<>();
            n.forEach(x -> l.add(toDocument(x)));
            return Document.fromList(l);
        }
        if (n.isBoolean()) return Document.fromBoolean(n.booleanValue());
        if (n.isIntegralNumber()) return Document.fromNumber(n.longValue());
        if (n.isNumber()) return Document.fromNumber(n.decimalValue());
        return Document.fromString(n.asText());
    }

    static JsonNode fromDocument(Document d) {
        if (d == null || d.isNull()) return MAPPER.nullNode();
        if (d.isMap()) {
            ObjectNode o = MAPPER.createObjectNode();
            d.asMap().forEach((k, v) -> o.set(k, fromDocument(v)));
            return o;
        }
        if (d.isList()) {
            ArrayNode a = MAPPER.createArrayNode();
            d.asList().forEach(v -> a.add(fromDocument(v)));
            return a;
        }
        if (d.isBoolean()) return MAPPER.getNodeFactory().booleanNode(d.asBoolean());
        if (d.isNumber()) return MAPPER.getNodeFactory().numberNode(d.asNumber().bigDecimalValue());
        return MAPPER.getNodeFactory().textNode(d.asString());
    }
}

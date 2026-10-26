package dev.setugarg.supportagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.setugarg.supportagent.agent.SupportAgent;
import dev.setugarg.supportagent.llm.AnthropicLlmClient;
import dev.setugarg.supportagent.llm.BedrockLlmClient;
import dev.setugarg.supportagent.llm.LlmClient;
import dev.setugarg.supportagent.rag.KnowledgeBase;
import dev.setugarg.supportagent.store.DynamoStores;
import dev.setugarg.supportagent.store.InMemoryStores;
import dev.setugarg.supportagent.store.SeedData;
import dev.setugarg.supportagent.store.Stores;
import dev.setugarg.supportagent.tools.Tools;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Composition root: reads configuration from environment variables and wires the object graph.
 * The same code runs on Lambda (DynamoDB + Secrets Manager) and on a laptop (in-memory stores +
 * env-var API key). 12-factor style: config in the environment, not in code.
 */
public final class App {

    public record Config(
            String provider,        // anthropic | bedrock
            String model,
            String effort,
            int maxSteps,
            int maxTokens,
            long dailyRequestCap,
            String storeMode,       // dynamo | memory
            String region) {

        static Config fromEnv() {
            String provider = env("LLM_PROVIDER", "anthropic");
            return new Config(
                    provider,
                    env("MODEL_ID", provider.equals("bedrock") ? "us.anthropic.claude-opus-5-5" : "claude-opus-5-5"),
                    env("EFFORT", "low"),
                    Integer.parseInt(env("MAX_AGENT_STEPS", "6")),
                    Integer.parseInt(env("MAX_TOKENS", "2048")),
                    Long.parseLong(env("DAILY_REQUEST_CAP", "300")),
                    env("STORE", "memory"),
                    env("AWS_REGION", "us-west-2"));
        }
    }

    public final Config config;
    public final Stores.OrderStore orders;
    public final Stores.TicketStore tickets;
    public final Stores.SessionStore sessions;
    public final Stores.UsageStore usage;
    public final KnowledgeBase kb;
    private final Supplier<String> accessCode;
    private final Supplier<LlmClient> llm;
    private SupportAgent agent;
    private LlmClient agentLlm;

    private App(Config config, Stores.OrderStore orders, Stores.TicketStore tickets, Stores.SessionStore sessions,
                Stores.UsageStore usage, Supplier<LlmClient> llm, Supplier<String> accessCode) {
        this.config = config;
        this.orders = orders;
        this.tickets = tickets;
        this.sessions = sessions;
        this.usage = usage;
        this.kb = KnowledgeBase.fromClasspath();
        this.llm = llm;
        this.accessCode = accessCode;
    }

    public static App fromEnv() {
        Config c = Config.fromEnv();
        if (c.storeMode().equals("dynamo")) {
            DynamoDbClient ddb = DynamoDbClient.builder().region(Region.of(c.region()))
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            SecretsManagerClient sm = SecretsManagerClient.builder().region(Region.of(c.region()))
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            // Secrets are fetched lazily on first use, NOT during init. With Lambda SnapStart the
            // init phase is snapshotted - a secret read there would be frozen into the snapshot.
            // A 5-minute TTL picks up rotated secrets without a redeploy.
            Supplier<String> apiKey = ttlCache(() -> secret(sm, System.getenv("ANTHROPIC_SECRET_ARN")), Duration.ofMinutes(5));
            Supplier<String> code = ttlCache(() -> secret(sm, System.getenv("ACCESS_CODE_SECRET_ARN")), Duration.ofMinutes(5));
            return new App(c,
                    new DynamoStores.Orders(ddb, System.getenv("ORDERS_TABLE")),
                    new DynamoStores.Tickets(ddb, System.getenv("TICKETS_TABLE")),
                    new DynamoStores.Sessions(ddb, System.getenv("SESSIONS_TABLE")),
                    new DynamoStores.Usage(ddb, System.getenv("USAGE_TABLE")),
                    keyedLlm(c, apiKey), code);
        }
        // local: in-memory everything, API key from the environment
        Supplier<String> apiKey = () -> env("ANTHROPIC_API_KEY", "");
        return new App(c,
                new InMemoryStores.Orders(SeedData.orders(LocalDate.now())),
                new InMemoryStores.Tickets(), new InMemoryStores.Sessions(), new InMemoryStores.Usage(),
                memoize(() -> buildLlm(c, apiKey)), () -> env("DEMO_ACCESS_CODE", ""));
    }

    public static LlmClient buildLlm(Config c, Supplier<String> apiKey) {
        return switch (c.provider()) {
            case "bedrock" -> new BedrockLlmClient(c.region(), c.model());
            case "anthropic" -> {
                String key = apiKey.get();
                if (key == null || key.isBlank() || key.startsWith("REPLACE")) {
                    throw new IllegalStateException("Anthropic API key not configured");
                }
                yield new AnthropicLlmClient(key, c.model(), c.effort());
            }
            default -> throw new IllegalArgumentException("unknown LLM_PROVIDER " + c.provider());
        };
    }

    public synchronized SupportAgent agent() {
        LlmClient current = llm.get();
        if (agent == null || current != agentLlm) {
            agent = new SupportAgent(current, Tools.all(kb, orders, tickets), sessions, Clock.systemUTC(),
                    config.maxSteps(), config.maxTokens());
            agentLlm = current;
        }
        return agent;
    }

    public String accessCode() {
        return accessCode.get();
    }

    public void resetDemoData() {
        SeedData.orders(LocalDate.now()).forEach(orders::put);
    }

    private static String secret(SecretsManagerClient sm, String arn) {
        return parseSecret(sm.getSecretValue(r -> r.secretId(arn)).secretString());
    }

    /** Accepts a plain string or a CDK-style JSON secret such as {"value": "..."}. */
    public static String parseSecret(String v) {
        if (v != null && v.trim().startsWith("{")) {
            try {
                Map<?, ?> m = new ObjectMapper().readValue(v, Map.class);
                Object val = m.containsKey("value") ? m.get("value") : m.values().iterator().next();
                return String.valueOf(val);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return v == null ? null : v.trim();
    }

    /** Rebuilds the LLM client only when the API key changes (e.g. after rotation). */
    static Supplier<LlmClient> keyedLlm(Config c, Supplier<String> apiKey) {
        return new Supplier<>() {
            private String key;
            private LlmClient client;

            @Override public synchronized LlmClient get() {
                String k = c.provider().equals("anthropic") ? apiKey.get() : "iam";
                if (client == null || !k.equals(key)) {
                    client = buildLlm(c, () -> k);
                    key = k;
                }
                return client;
            }
        };
    }

    static <T> Supplier<T> ttlCache(Supplier<T> s, Duration ttl) {
        return new Supplier<>() {
            private T value;
            private Instant fetchedAt = Instant.EPOCH;

            @Override public synchronized T get() {
                if (value == null || Instant.now().isAfter(fetchedAt.plus(ttl))) {
                    value = s.get();
                    fetchedAt = Instant.now();
                }
                return value;
            }
        };
    }

    static <T> Supplier<T> memoize(Supplier<T> s) {
        return new Supplier<>() {
            private volatile T value;

            @Override public T get() {
                if (value == null) {
                    synchronized (this) {
                        if (value == null) value = s.get();
                    }
                }
                return value;
            }
        };
    }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}

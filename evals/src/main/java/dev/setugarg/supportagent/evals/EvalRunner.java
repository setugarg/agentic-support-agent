package dev.setugarg.supportagent.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.setugarg.supportagent.agent.SupportAgent;
import dev.setugarg.supportagent.api.App;
import dev.setugarg.supportagent.llm.AnthropicLlmClient;
import dev.setugarg.supportagent.llm.LlmClient;
import dev.setugarg.supportagent.rag.KnowledgeBase;
import dev.setugarg.supportagent.store.InMemoryStores;
import dev.setugarg.supportagent.store.SeedData;
import dev.setugarg.supportagent.tools.Tools;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Offline eval harness.
 *
 * <pre>
 *   mvn -q -pl evals exec:java -Dexec.args="--models claude-opus-5-5,claude-haiku-5-5"
 *   mvn -q -pl evals exec:java -Dexec.args="--retrieval-only"      # free, no API calls
 * </pre>
 *
 * Every case runs against FRESH in-memory stores seeded with the same data and a fixed clock, so
 * results are reproducible and cases cannot interfere with each other (isolation). The grader is
 * two-layered: deterministic checks (tools called, side effects on orders, forbidden strings) and
 * an LLM judge scoring the answer against a per-case rubric.
 */
public final class EvalRunner {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    record CaseResult(String id, String category, String model, boolean pass, boolean deterministicPass,
                      Integer judgeScore, String judgeReason, List<String> failures, List<String> toolsCalled,
                      String answer, long latencyMs, double costUsd, long inputTokens, long outputTokens, int modelCalls) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        // works whether you launch from the repo root or from evals/
        Path base = Files.isDirectory(Path.of("evals/cases")) ? Path.of("evals") : Path.of(".");
        Path casesDir = Path.of(a.getOrDefault("cases", base.resolve("cases").toString()));
        Path outDir = Path.of(a.getOrDefault("out", base.resolve("reports").toString()));
        Files.createDirectories(outDir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));

        RetrievalEval.Report retrieval = RetrievalEval.run(KnowledgeBase.fromClasspath(), casesDir.resolve("retrieval.jsonl"));
        System.out.println(retrieval.summary());
        if (a.containsKey("retrieval-only")) {
            Files.writeString(outDir.resolve("retrieval-" + stamp + ".md"), retrieval.markdown());
            return;
        }

        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("REPLACE")) {
            System.err.println("No Anthropic API key: set ANTHROPIC_API_KEY or put the key in Secrets Manager (see docs/09).");
            System.exit(2);
        }
        List<String> models = List.of(a.getOrDefault("models", "claude-opus-5-5").split(","));
        String judgeModel = a.getOrDefault("judge", "claude-sonnet-5-5");
        String effort = a.getOrDefault("effort", "low");
        int trials = Integer.parseInt(a.getOrDefault("trials", "1"));
        int parallel = Integer.parseInt(a.getOrDefault("parallel", "4"));
        String only = a.get("only");

        List<JsonNode> cases = Files.readAllLines(casesDir.resolve("golden.jsonl")).stream()
                .filter(l -> !l.isBlank()).map(EvalRunner::readTree)
                .filter(c -> only == null || c.get("id").asText().startsWith(only) || c.get("category").asText().equals(only))
                .toList();

        LlmClient judge = new AnthropicLlmClient(apiKey, judgeModel, "low");
        Map<String, List<CaseResult>> byModel = new LinkedHashMap<>();
        for (String model : models) {
            LlmClient llm = new AnthropicLlmClient(apiKey, model, effort);
            System.out.printf("%nRunning %d cases x %d trial(s) on %s (effort=%s, judge=%s)%n", cases.size(), trials, model, effort, judgeModel);
            byModel.put(model, runAll(llm, judge, cases, trials, parallel));
        }

        String md = Report.markdown(byModel, retrieval, judgeModel, effort, trials);
        Path mdPath = outDir.resolve("eval-" + stamp + ".md");
        Files.writeString(mdPath, md);
        Files.writeString(outDir.resolve("eval-" + stamp + ".json"), JSON.writeValueAsString(byModel));
        Files.writeString(outDir.resolve("latest.md"), md);
        System.out.println("\n" + Report.summaryTable(byModel));
        System.out.println("Report: " + mdPath.toAbsolutePath());
    }

    static List<CaseResult> runAll(LlmClient llm, LlmClient judge, List<JsonNode> cases, int trials, int parallel) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            List<Future<CaseResult>> futures = new ArrayList<>();
            for (int t = 0; t < trials; t++) {
                for (JsonNode c : cases) futures.add(pool.submit(() -> runCase(llm, judge, c)));
            }
            List<CaseResult> out = new ArrayList<>();
            for (Future<CaseResult> f : futures) {
                CaseResult r = f.get();
                System.out.printf("  %-12s %-4s %s%n", r.id(), r.pass() ? "PASS" : "FAIL",
                        r.pass() ? "" : String.join("; ", r.failures()));
                out.add(r);
            }
            return out;
        } finally {
            pool.shutdown();
        }
    }

    static CaseResult runCase(LlmClient llm, LlmClient judge, JsonNode c) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Clock clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var orders = new InMemoryStores.Orders(SeedData.orders(today));
        var tickets = new InMemoryStores.Tickets();
        var agent = new SupportAgent(llm, Tools.all(KnowledgeBase.fromClasspath(), orders, tickets),
                new InMemoryStores.Sessions(), clock, 6, 2048);

        String email = c.get("customer").asText();
        String name = SeedData.CUSTOMERS.stream().filter(x -> x.email().equals(email)).findFirst().orElseThrow().name();
        String session = "eval-" + c.get("id").asText() + "-" + System.nanoTime();

        List<String> transcript = new ArrayList<>();
        List<String> tools = new ArrayList<>();
        long latency = 0, in = 0, out = 0;
        int calls = 0;
        double cost = 0;
        String lastAnswer = "";
        StringBuilder allAnswers = new StringBuilder();
        try {
            for (JsonNode turn : c.get("turns")) {
                SupportAgent.Result r = agent.run(session, email, name, turn.asText());
                transcript.add("CUSTOMER: " + turn.asText());
                transcript.add("AGENT: " + r.answer());
                tools.addAll(r.toolsCalled());
                latency += r.latencyMs();
                in += r.usage().inputTokens() + r.usage().cacheReadTokens() + r.usage().cacheWriteTokens();
                out += r.usage().outputTokens();
                calls += r.modelCalls();
                cost += r.costUsd();
                lastAnswer = r.answer();
                allAnswers.append(r.answer()).append('\n');
            }
        } catch (Exception e) {
            return new CaseResult(c.get("id").asText(), c.get("category").asText(), llm.modelId(), false, false, null,
                    null, List.of("exception: " + e), tools, "", latency, cost, in, out, calls);
        }

        List<String> failures = Graders.deterministic(c.get("expect"), tools, allAnswers.toString(), orders);
        Graders.Judgement j = c.hasNonNull("rubric")
                ? Graders.judge(judge, c.get("rubric").asText(), String.join("\n", transcript), tools)
                : null;
        if (j != null && j.score() < 4) failures.add("judge " + j.score() + "/5: " + j.reason());
        boolean detPass = failures.stream().noneMatch(f -> !f.startsWith("judge"));
        boolean pass = failures.isEmpty();
        return new CaseResult(c.get("id").asText(), c.get("category").asText(), llm.modelId(), pass, detPass,
                j == null ? null : j.score(), j == null ? null : j.reason(), failures, tools, lastAnswer,
                latency, cost, in, out, calls);
    }

    /**
     * The key never touches disk or logs: either it's in ANTHROPIC_API_KEY, or we read it at
     * runtime from Secrets Manager using your local AWS credentials (ANTHROPIC_SECRET_ID).
     */
    static String resolveApiKey() {
        String k = System.getenv("ANTHROPIC_API_KEY");
        if (k != null && !k.isBlank()) return k;
        String secretId = System.getenv().getOrDefault("ANTHROPIC_SECRET_ID", "support-agent/anthropic-api-key");
        try (SecretsManagerClient sm = SecretsManagerClient.create()) {
            return App.parseSecret(sm.getSecretValue(r -> r.secretId(secretId)).secretString());
        }
    }

    static JsonNode readTree(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad jsonl line: " + s, e);
        }
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String k = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(k, args[++i]);
            else m.put(k, "true");
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------

    static final class Report {
        static String summaryTable(Map<String, List<CaseResult>> byModel) {
            StringBuilder sb = new StringBuilder();
            sb.append("| Model | Pass rate | Deterministic checks | Avg judge (1-5) | p50 latency | p95 latency | Avg cost / case | Avg model calls |\n");
            sb.append("|---|---|---|---|---|---|---|---|\n");
            byModel.forEach((model, rs) -> sb.append(String.format(Locale.US, "| %s | %s | %s | %.2f | %.1fs | %.1fs | $%.4f | %.1f |%n",
                    model, pct(rs, CaseResult::pass), pct(rs, CaseResult::deterministicPass),
                    rs.stream().filter(r -> r.judgeScore() != null).mapToInt(CaseResult::judgeScore).average().orElse(0),
                    percentile(rs, 50) / 1000.0, percentile(rs, 95) / 1000.0,
                    rs.stream().mapToDouble(CaseResult::costUsd).average().orElse(0),
                    rs.stream().mapToInt(CaseResult::modelCalls).average().orElse(0))));
            return sb.toString();
        }

        static String markdown(Map<String, List<CaseResult>> byModel, RetrievalEval.Report retrieval,
                               String judge, String effort, int trials) {
            StringBuilder sb = new StringBuilder();
            sb.append("# Eval report\n\n");
            sb.append("- Run at: ").append(LocalDateTime.now().withNano(0)).append(" (local time)\n");
            sb.append("- Prompt version: `").append(SupportAgent.PROMPT_VERSION).append("`\n");
            sb.append("- Effort: `").append(effort).append("` | Judge model: `").append(judge)
                    .append("` | Trials per case: ").append(trials).append("\n\n");
            sb.append("## Summary\n\n").append(summaryTable(byModel)).append('\n');

            sb.append("## Pass rate by category\n\n| Category |");
            byModel.keySet().forEach(m -> sb.append(' ').append(m).append(" |"));
            sb.append("\n|---|").append("---|".repeat(byModel.size())).append('\n');
            TreeMap<String, Boolean> cats = new TreeMap<>();
            byModel.values().forEach(rs -> rs.forEach(r -> cats.put(r.category(), true)));
            for (String cat : cats.keySet()) {
                sb.append("| ").append(cat).append(" |");
                byModel.values().forEach(rs -> {
                    var sub = rs.stream().filter(r -> r.category().equals(cat)).toList();
                    sb.append(' ').append(pct(sub, CaseResult::pass)).append(" |");
                });
                sb.append('\n');
            }

            sb.append("\n## Retrieval (BM25, offline)\n\n").append(retrieval.markdown()).append('\n');

            sb.append("## Failures\n\n");
            boolean any = false;
            for (var e : byModel.entrySet()) {
                for (CaseResult r : e.getValue()) {
                    if (r.pass()) continue;
                    any = true;
                    sb.append("### ").append(r.id()).append(" - `").append(e.getKey()).append("`\n\n");
                    r.failures().forEach(f -> sb.append("- ").append(f.replace("\n", " ")).append('\n'));
                    sb.append("- tools: `").append(r.toolsCalled()).append("`\n");
                    sb.append("- final answer: ").append(r.answer().replace("\n", " ")).append("\n\n");
                }
            }
            if (!any) sb.append("None.\n");
            return sb.toString();
        }

        static String pct(List<CaseResult> rs, java.util.function.Predicate<CaseResult> p) {
            if (rs.isEmpty()) return "-";
            long n = rs.stream().filter(p).count();
            return String.format(Locale.US, "%.0f%% (%d/%d)", 100.0 * n / rs.size(), n, rs.size());
        }

        static double percentile(List<CaseResult> rs, int p) {
            List<Long> l = rs.stream().map(CaseResult::latencyMs).sorted(Comparator.naturalOrder()).collect(Collectors.toList());
            if (l.isEmpty()) return 0;
            int idx = (int) Math.ceil(p / 100.0 * l.size()) - 1;
            return l.get(Math.max(0, Math.min(idx, l.size() - 1)));
        }
    }
}

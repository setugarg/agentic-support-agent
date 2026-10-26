package dev.setugarg.supportagent.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.setugarg.supportagent.rag.KnowledgeBase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Component-level eval for retrieval, independent of any LLM: for each query, is the expected
 * chunk in the top-k? Reports Recall@1, Recall@3 and MRR (mean reciprocal rank).
 * Retrieval is evaluated separately because when RAG answers are wrong, the first question is
 * "did we retrieve the right context?" - fix retrieval before blaming the model.
 */
public final class RetrievalEval {
    private RetrievalEval() {}

    public record Miss(String query, String expected, List<String> got) {}

    public record Report(int n, double recallAt1, double recallAt3, double mrr, List<Miss> misses) {
        public String summary() {
            return String.format(Locale.US, "Retrieval (BM25): n=%d  recall@1=%.0f%%  recall@3=%.0f%%  MRR=%.2f",
                    n, recallAt1 * 100, recallAt3 * 100, mrr);
        }

        public String markdown() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "| Queries | Recall@1 | Recall@3 | MRR |%n|---|---|---|---|%n| %d | %.0f%% | %.0f%% | %.2f |%n%n",
                    n, recallAt1 * 100, recallAt3 * 100, mrr));
            if (!misses.isEmpty()) {
                sb.append("Misses (expected chunk not in top 3):\n\n");
                misses.forEach(m -> sb.append("- \"").append(m.query()).append("\" expected `").append(m.expected())
                        .append("`, got `").append(m.got()).append("`\n"));
            }
            return sb.toString();
        }
    }

    public static Report run(KnowledgeBase kb, Path file) throws Exception {
        ObjectMapper json = new ObjectMapper();
        int n = 0, at1 = 0, at3 = 0;
        double rr = 0;
        List<Miss> misses = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) continue;
            JsonNode c = json.readTree(line);
            String expected = c.get("expected").asText();
            List<String> ids = kb.search(c.get("query").asText(), 3).stream().map(h -> h.chunk().id()).toList();
            n++;
            int rank = ids.indexOf(expected);
            if (rank == 0) at1++;
            if (rank >= 0) {
                at3++;
                rr += 1.0 / (rank + 1);
            } else {
                misses.add(new Miss(c.get("query").asText(), expected, ids));
            }
        }
        return new Report(n, n == 0 ? 0 : (double) at1 / n, n == 0 ? 0 : (double) at3 / n, n == 0 ? 0 : rr / n, misses);
    }
}

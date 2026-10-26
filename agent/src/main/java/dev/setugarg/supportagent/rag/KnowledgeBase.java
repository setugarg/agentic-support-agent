package dev.setugarg.supportagent.rag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Retrieval for RAG: chunk policy documents, index them, return the top-k chunks for a query.
 *
 * <p><b>Chunking</b>: one chunk per "## " section, prefixed with the document title
 * ("contextual chunking") so a chunk like "Refund approval limits" still carries the fact that it
 * belongs to the Returns policy.
 *
 * <p><b>Ranking</b>: Okapi BM25 - lexical scoring by term frequency, inverse document frequency
 * and length normalisation. For ~30 chunks of precise policy language, BM25 is cheap
 * (microseconds, no embedding API, no vector DB) and its recall is measured by the retrieval
 * eval. At larger scale or with paraphrase-heavy queries you would add dense embeddings and do
 * hybrid search (BM25 + vectors, fused with Reciprocal Rank Fusion) - see docs/02.
 */
public final class KnowledgeBase {

    public record Chunk(String id, String source, String title, String text) {}

    public record Hit(Chunk chunk, double score) {}

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "is", "are", "be", "can",
            "my", "i", "me", "do", "does", "it", "you", "your", "we", "our", "what", "how", "with",
            "that", "this", "if", "at", "by", "as", "from", "will", "was", "not", "any", "there",
            "about", "when", "which", "they", "their", "have", "has", "get");

    private final List<Chunk> chunks;
    private final List<Map<String, Integer>> termFreqs = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final double avgLen;

    public KnowledgeBase(List<Chunk> chunks) {
        this.chunks = List.copyOf(chunks);
        long total = 0;
        for (Chunk c : this.chunks) {
            List<String> toks = tokenize(c.title() + " " + c.text());
            Map<String, Integer> tf = new HashMap<>();
            for (String t : toks) tf.merge(t, 1, Integer::sum);
            termFreqs.add(tf);
            lengths.add(toks.size());
            total += toks.size();
            for (String t : tf.keySet()) docFreq.merge(t, 1, Integer::sum);
        }
        this.avgLen = this.chunks.isEmpty() ? 1 : (double) total / this.chunks.size();
    }

    /** Loads every markdown file listed in {@code kb/index.txt} on the classpath. */
    public static KnowledgeBase fromClasspath() {
        List<Chunk> chunks = new ArrayList<>();
        for (String file : readResource("kb/index.txt").lines().map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            chunks.addAll(chunkMarkdown(file, readResource("kb/" + file)));
        }
        return new KnowledgeBase(chunks);
    }

    static List<Chunk> chunkMarkdown(String source, String markdown) {
        String docTitle = source;
        List<Chunk> out = new ArrayList<>();
        String section = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\n")) {
            if (line.startsWith("# ")) {
                docTitle = line.substring(2).trim();
            } else if (line.startsWith("## ")) {
                if (section != null) out.add(chunk(source, docTitle, section, body, out.size()));
                section = line.substring(3).trim();
                body.setLength(0);
            } else if (section != null) {
                body.append(line).append('\n');
            }
        }
        if (section != null) out.add(chunk(source, docTitle, section, body, out.size()));
        return out;
    }

    private static Chunk chunk(String source, String docTitle, String section, StringBuilder body, int idx) {
        return new Chunk(source.replace(".md", "") + "#" + idx, source, docTitle + " > " + section, body.toString().trim());
    }

    public List<Hit> search(String query, int k) {
        List<String> q = tokenize(query);
        List<Hit> hits = new ArrayList<>();
        int n = chunks.size();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = termFreqs.get(i);
            double score = 0;
            for (String term : q) {
                Integer f = tf.get(term);
                if (f == null) continue;
                int df = docFreq.getOrDefault(term, 0);
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                score += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * lengths.get(i) / avgLen));
            }
            if (score > 0) hits.add(new Hit(chunks.get(i), score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(k, hits.size()));
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.toLowerCase().split("[^a-z0-9$]+")) {
            if (raw.isBlank() || STOPWORDS.contains(raw)) continue;
            out.add(stem(raw));
        }
        return out;
    }

    /** Deliberately tiny suffix stripper ("refunds" -> "refund", "shipping" -> "shipp"). */
    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    static String readResource(String path) {
        try (InputStream in = KnowledgeBase.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource " + path);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

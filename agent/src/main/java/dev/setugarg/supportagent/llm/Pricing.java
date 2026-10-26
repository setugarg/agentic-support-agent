package dev.setugarg.supportagent.llm;

import java.util.Map;

/**
 * List prices in USD per 1M tokens (Anthropic first-party API, checked 2026-10).
 * Bedrock prices for the same models are set by AWS and can differ - always confirm on the
 * pricing page before quoting numbers. Cache reads are billed at ~10% of input; cache writes at
 * ~125% of input (5-minute TTL).
 */
public final class Pricing {
    private Pricing() {}

    public record Price(double inputPerM, double outputPerM) {}

    private static final Map<String, Price> PRICES = Map.of(
            "claude-opus-5-5", new Price(4.00, 20.00),
            "claude-sonnet-5-5", new Price(2.00, 10.00),
            "claude-haiku-5-5", new Price(0.10, 0.50),
            "claude-fable-5-1", new Price(10.00, 50.00));

    public static double costUsd(String modelId, Llm.Usage u) {
        Price p = lookup(modelId);
        if (p == null) return 0.0;
        double uncachedInput = u.inputTokens();
        return (uncachedInput * p.inputPerM()
                + u.cacheReadTokens() * p.inputPerM() * 0.10
                + u.cacheWriteTokens() * p.inputPerM() * 1.25
                + u.outputTokens() * p.outputPerM()) / 1_000_000.0;
    }

    /** Accepts first-party ids and Bedrock ids such as {@code us.anthropic.claude-haiku-5-5}. */
    static Price lookup(String modelId) {
        for (var e : PRICES.entrySet()) {
            if (modelId.contains(e.getKey())) return e.getValue();
        }
        return null;
    }
}

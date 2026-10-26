# 05 · Inference: tokens, latency, cost, caching, hosting

"Inference" is running the model. Training is what model labs do; as an application team, almost all of your cost, latency and reliability concerns are inference concerns.

## 1. Tokens are the unit of everything

* **Billing:** input tokens and output tokens, priced separately. Output is ~5× more expensive than input on Claude.
* **Latency:** generating output is sequential, so latency grows with output length. Reading input is parallel and fast, but large prompts still add time (prefill).
* **Limits:** context window (max tokens per call), rate limits (requests/tokens per minute), and `max_tokens` (cap on output per call).

Rule of thumb: 1 token ≈ 4 characters ≈ ¾ of an English word.

## 2. Anatomy of one agent turn (real numbers from this system)

A typical "refund A1001" turn makes **2 model calls**:

| Call | Input | Output | Why |
|---|---|---|---|
| 1 | system prompt + 5 tool schemas + history + question (~2-3k tokens) | a `tool_use` block (~50-150 tokens) | decide to call `request_refund` |
| 2 | all of call 1 + the tool call + tool result (~3k tokens) | the answer (~80-150 tokens) | write the reply |

So the input is **re-sent and re-billed on every call**. That is the defining cost characteristic of agents: cost grows with (calls per turn) × (context size), and context grows each call.

## 3. Latency budget

```
turn latency ≈ Σ over model calls (TTFT + output_tokens × time_per_token) + Σ tool latency + overhead
```

* **TTFT (time to first token)** grows with prompt size; prompt caching cuts it substantially on long prefixes.
* **Time per output token** depends on model size: Haiku-class models are much faster than Opus-class.
* **Model calls per turn** is the biggest lever you control: better tool design (one `request_refund` instead of get + check + update) and lower effort mean fewer calls.
* **Tools** here are DynamoDB reads (single-digit ms) and in-memory BM25 (microseconds): negligible next to the model.

**Perceived latency:** stream tokens to the UI. Users judge speed by time to first visible output, not total time. (This demo returns the full answer; streaming is a listed upgrade.)

## 4. Cost model (back-of-envelope)

Using list prices (per million tokens: Opus 5.5 $4 in / $20 out; Haiku 5.5 $0.10 / $0.50):

```
per turn (Opus 5.5) ≈ 2 calls × (3,000 in × $4/1M + 120 out × $20/1M)
                    ≈ 2 × ($0.012 + $0.0024) ≈ $0.03
with prompt caching on ~2,000 static tokens: cached reads bill at 10% → ≈ $0.02
per turn (Haiku 5.5) ≈ 40× cheaper ≈ under $0.001
```

**Measured** in the eval (avg per case, mostly one turn): Opus 5.5 at low effort **$0.0104** (~4.3k input tokens incl. cache reads, ~240 output, 2.2 model calls); Haiku 5.5 **$0.0003**. That's lower than the estimate above because prompt caching roughly halved input cost.

At **1M support conversations/month × 3 turns**: Opus ≈ $30k/month, Haiku ≈ $1k/month (measured per-turn costs). Compare against human cost per contact (often several dollars) and the question becomes: *which model resolves the most tickets correctly per dollar?* That's why the eval reports cost per case alongside pass rate. The measured numbers are in [04 Evals](04-evals.md).

Cost levers, in the order you should pull them:

1. **Prompt caching** (free win): stable prefix first (tools → system), volatile content last. Here the static system prompt has a cache breakpoint; customer context and date go after it. Note: the cache only engages above a model-specific minimum prefix length, so small prompts may not cache at all. Check `cacheReadTokens` in the trace.
2. **Fewer calls per turn:** better tools, clearer descriptions, lower effort.
3. **Smaller context:** trim history (sliding window), return minimal tool results, retrieve 3 chunks, not 20.
4. **Right-size `max_tokens`** to what answers actually need (here 2,048).
5. **Effort / model choice:** lower effort first, then a cheaper model if evals hold.
6. **Batch API** (~50% off) for anything not interactive: evals, nightly summarization, back-office classification.
7. **Routing/cascades:** cheap model first, escalate hard cases. Only at scale.

## 5. Reliability and throughput

* **Rate limits** (requests/min, input and output tokens/min) are per account/model. Plan capacity like any dependency; ask for increases ahead of launches.
* **Retries:** retry 429/5xx with exponential backoff and jitter (the SDKs do 2 retries by default). Never retry 4xx validation errors.
* **`max_tokens` matters on Bedrock:** if unset it defaults to the model maximum, and on Bedrock the reservation counts against your tokens-per-minute quota, causing throttling at low real traffic. We always set it.
* **Timeouts:** set a client timeout (60 s here) and a function timeout above the worst-case turn (90 s).
* **Fallbacks:** server-side refusal fallback (enabled for Opus/Sonnet 5.5 here), a secondary model or provider for outages (our `LlmClient` abstraction makes this possible), and a graceful "let me connect you to a human" when all else fails.
* **Cross-region inference (Bedrock):** `us.`-prefixed inference profiles spread load across US regions. More throughput, and data stays in the geography.
* **Provisioned throughput / reserved capacity:** buy guaranteed capacity when traffic is high and steady and latency SLOs are strict.

## 6. Hosting options: API vs managed vs self-hosted

| Option | You manage | Good when | Watch out for |
|---|---|---|---|
| **First-party API** (Anthropic, OpenAI, Google) | Nothing but keys | Fastest access to the best models | Vendor dependency, data-processing terms |
| **Cloud-managed** (Bedrock, Vertex, Azure) | IAM, quotas | Enterprise compliance, unified billing, private networking | Feature lag, regional availability, account-level access controls (we hit one) |
| **Managed open-weights endpoints** (SageMaker, Bedrock Custom Model Import, Together, Fireworks, Groq) | Model choice, sometimes scaling | Open models without running GPUs; very fast inference from specialist hardware vendors | Quality gap vs frontier models on complex agent tasks |
| **Self-hosted** (vLLM/SGLang/TGI on GPUs via EKS/EC2) | GPUs, autoscaling, batching, upgrades, safety | Very high steady volume, strict data isolation, heavy customization | GPU scarcity and idle cost; your team becomes an inference team |

**Self-hosting economics in one sentence:** a GPU costs the same whether it serves 1 request or 100 concurrently, so self-hosting only wins at high, steady utilization, and you take on quality, safety and on-call burden that API providers absorb. Techniques that improve that utilization: **continuous batching**, **KV-cache paging** (vLLM's PagedAttention), **quantization** (FP8/INT4), **speculative decoding**, and **tensor parallelism** across GPUs for big models.

## 7. Choosing a model: a decision procedure

1. Write the eval first (this repo: 24 cases).
2. Run the most capable model at low effort to get a quality ceiling and a cost baseline.
3. Run cheaper tiers on the same eval.
4. Pick the cheapest configuration that meets the bar **on zero-tolerance categories** (privacy, injection, refund correctness) and is within latency SLO.
5. Re-run on every model upgrade; pin model IDs so upgrades are deliberate.

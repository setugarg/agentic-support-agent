# 07 · Running it in production

## SLOs I would set

| SLI | Target | Why |
|---|---|---|
| Availability (non-5xx turns) | 99.5% | A dependency (the model API) has its own SLA; set yours below it and degrade gracefully |
| p95 turn latency | < 12 s | 2-3 model calls; with streaming, time-to-first-token < 2 s matters more |
| Eval pass rate (golden set) | ≥ current baseline − 2 pts | Release gate |
| Zero-tolerance categories (privacy, injection, refund correctness) | 100% | Any failure blocks release |
| Cost per resolved conversation | budgeted per month | AI features need a unit-economics SLO |

## What's on the dashboard (CloudWatch → `SupportAgent`)

* Turns, spend in USD and errors over 24 h
* Turn latency p50/p95
* Input, output and cache-read tokens
* Model calls and tool calls per turn (a sudden rise = the loop is struggling, a prompt regression, or abuse)
* Lambda invocations, errors, throttles, duration

**Alarm:** ≥ 5 errors in 5 minutes. *Production:* also alarm on spend rate, p95 latency, refusal rate and escalation-rate anomalies.

Logs Insights query to read a conversation's traces:

```sql
fields @timestamp, sessionId, stopReason, ModelCalls, CostMicroUsd, toolsCalled
| filter event = "agent_turn"
| sort @timestamp desc
| limit 50
```

## Failure modes and responses

| Failure | Detection | Response |
|---|---|---|
| Model API outage / 5xx / 429 | Error metric, SDK retries exhausted | Retries with backoff → failover provider (`LLM_PROVIDER` switch, or automatic in a gateway) → graceful "connecting you to a human" |
| Latency spike | p95 alarm | Lower effort, cheaper model for simple intents, shed load (concurrency cap) |
| Quality regression after a change | Eval gate in CI; online judge sampling | Roll back the Lambda alias (instant), the prompt version, or the model ID |
| Loop explosion | Model calls/turn metric | Step budget already caps it; inspect traces, fix tool descriptions |
| Cost spike / abuse | Spend metric, daily cap hit | Cap returns 429; tighten WAF rate limits; rotate the access code |
| Bad refund decision | Refund audit (tickets + order transitions in logs) | Rules are in code; fix and add a regression case |

## Deployment and change management

* **Everything is versioned:** code (git), infra (CDK), prompt (`PROMPT_VERSION` in every log line and eval report), model (pinned ID), knowledge base (markdown in git).
* **Release flow I'd use:** PR → unit tests + retrieval eval + cdk synth (exists today) → full eval on a staging stack (gate) → deploy to prod via **Lambda alias weighted traffic** (10% canary for 30 minutes) → automatic rollback on alarm.
* **Prompt changes are code changes.** They get review, evals and a canary like any other deploy.

## Scaling this design

| Load | What changes |
|---|---|
| 10 concurrent users (today) | Nothing |
| 1,000 concurrent | Raise reserved concurrency; ask for model rate-limit increases; DynamoDB on-demand absorbs it; add CloudFront + WAF |
| 100k+ daily conversations | Streaming UI; consider Fargate with async I/O (paying Lambda GB-seconds to wait on the model gets expensive); model routing for easy intents; Batch API for offline work; LLM gateway with per-team budgets |
| Multi-region | Active-active Lambda + DynamoDB Global Tables; Bedrock cross-region profiles; provider failover |
| Large KB (10k+ docs) | Ingestion pipeline (S3 → chunk → embed → vector store), hybrid search + reranker, per-document ACLs, freshness SLAs |
| Many teams building agents | Platform: shared gateway, tool registry (MCP servers), eval service, tracing standard (OpenTelemetry GenAI), golden-path templates |

## What I'd build next (prioritized)

1. **Real identity:** Cognito-issued JWT → customer email from the token, not the request body.
2. **Streaming responses** for perceived latency.
3. **Eval gate in CI** with OIDC-based deploys and canaries.
4. **Hybrid retrieval** (embeddings + BM25 + rerank), kept only if the retrieval eval improves.
5. **Online evals:** sample 5% of production transcripts → LLM judge → dashboard; thumbs up/down in the UI feeding the golden set.
6. **Bedrock as primary, Anthropic API as failover** (or the reverse), once the account restriction is lifted.
7. **Long-term memory** (customer preferences) with explicit consent and deletion.

## Cost of running this demo (approximate, idle-dominated)

| Item | Monthly |
|---|---|
| Lambda, DynamoDB on-demand, logs | ~$0 at demo traffic (free tier / pennies) |
| Secrets Manager (2 secrets) | ~$0.80 |
| KMS customer-managed key | ~$1.00 |
| CloudWatch dashboard | ~$3.00 (first 3 dashboards free per account) |
| Model tokens | ~$0.02 per conversation turn on Opus 5.5 at low effort; capped by the daily request limit |

To remove everything: `cd infra && npx aws-cdk destroy`.

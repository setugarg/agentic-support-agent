# 08 · Design review guide: explaining, defending and extending this system

This guide covers the questions that come up when someone reviews an AI agent design, whether that's an architecture review, an RFC, or a team adopting the pattern. It covers three angles: **the design** (architecture), **the implementation details** that only show up when you build one, and **the business view** (cost, risk, team, build vs buy).

---

## The system in one paragraph

A customer-support agent built end to end in Java 21 on AWS: a hand-written tool-calling loop on Lambda with SnapStart, five narrowly scoped tools over DynamoDB, BM25 retrieval over policy docs, and Claude behind a provider abstraction. The abstraction exists because Bedrock was unavailable on the deployment account, and it let the system fail over to the Anthropic API with a config flag. The core principle is "the model proposes, code disposes": refunds go through a Java policy engine with ownership checks, a 30-day window, idempotent conditional writes and human approval over $100, so prompt injection can't move money. Quality is measured with an eval harness (24 golden cases graded on database state plus an LLM judge) that compares models on pass rate, cost and latency. That's how the production model was chosen.

---

## Designing an AI support agent from scratch: a walkthrough

1. **Clarify requirements.** Users and channels? Volume (conversations/day, peak concurrency)? Which actions may it take (read-only vs refunds/cancellations)? Risk tolerance and compliance (PII, PCI, GDPR, HIPAA)? Success metric (resolution rate, CSAT, cost per contact, handle time)? Languages? Existing systems (CRM, order DB, ticketing such as Zendesk/ServiceNow)?
2. **Define success and constraints.** For example: "Resolve 40% of contacts without a human, at under $X per resolution, p95 < 10 s, zero unauthorized refunds."
3. **Start simple, then layer.** Client → API/auth → agent service (loop) → model provider; tools → order service, policy KB, ticketing; memory store; observability; eval pipeline. Use a workflow where you can and an agent where you must: deterministic triage handles the easy intents.
4. **Go deep on the risky part: actions.** Tools as the security boundary, human-in-the-loop thresholds, idempotency, audit trail.
5. **Knowledge:** RAG pipeline (ingestion, chunking, hybrid retrieval, rerank, ACLs, freshness), and when long context plus caching is simpler.
6. **Quality:** eval pyramid, golden set from historical tickets, LLM judge calibrated against human QA, CI gate, online evals, A/B by cohort.
7. **Scale, latency and cost:** token math (see [05](05-inference.md)), caching, routing, streaming, rate limits, provider failover.
8. **Operations:** SLOs, dashboards (cost per turn, calls per turn, escalation rate), incident playbook, rollback (alias, prompt version, model pin).
9. **Org and roadmap:** team shape, build vs buy, phased rollout (agent-assist for humans → 5% of traffic → expand by intent).

---

## Implementation details worth knowing

These are specific to this repo and tend to surprise people building their first agent:

* **Send all tool results back in one user message.** Split across messages, the model learns to stop calling tools in parallel.
* **Tool failures go back as `is_error` tool results**, not exceptions, so the model can recover or apologise instead of the request failing.
* **Agents need a step budget.** This one caps at 6 model calls, and the user still gets a graceful answer when it trips.
* **Prompt caching works on exact prefixes.** The static system prompt and tools come first; per-request customer context goes *after* the cache breakpoint. A timestamp at the top of the system prompt silently kills the cache.
* **With SnapStart, anything done during init is frozen in the snapshot**, so secrets are fetched lazily on the first request (with a 5-minute TTL for rotation).
* **On Bedrock, always set `maxTokens`.** Unset, it reserves the model's maximum against your tokens-per-minute quota and you get throttled at tiny traffic.
* **The refund tool uses a DynamoDB conditional write** (`status = DELIVERED → REFUNDED`), because agents retry and users double-click.
* **Other customers' orders return "not found", not "forbidden"**, so the agent can't be used as an enumeration oracle.
* **The eval asserts on database state**, not just text. The worst failure isn't a rude answer; it's the agent *saying* it escalated while actually auto-refunding.
* **BM25 reached 88% recall@3**, and the misses were pure paraphrases. That shows exactly what embeddings would buy before paying for them.
* **Bedrock was blocked at the account level** on the deployment account, which turned the provider abstraction from "nice to have" into the way the system shipped. Provider availability is an operational risk to design for.
* **Current Claude models expose effort instead of temperature.** Low effort is the right default for support: fewer, consolidated tool calls and lower latency.

---

## Frequently asked design questions

**Why a hand-written loop instead of LangChain / a framework / Bedrock Agents?**
For one agent with five tools, a loop is ~150 lines you fully understand and can debug. Frameworks speed up prototypes but hide the prompts and change behaviour on upgrade. Classic Bedrock Agents is in maintenance mode. For a platform serving many teams, evaluate a managed runtime (AgentCore, Claude Managed Agents) for session isolation, long-running work and shared tooling. The decision depends on how many agents and teams there are, not on one agent.

**How do you stop the agent from doing something harmful?**
In layers. The real boundary is the tools: identity comes from the session, every tool re-checks authorization and business rules in code, side effects are idempotent, and high-impact actions need a human. Then budgets (steps, tokens, daily cap), then model-side safety and optional classifier guardrails, and finally red-team cases in the eval that block releases.

**How do you know a prompt change is safe to ship?**
Unit tests for deterministic logic; a component eval for retrieval; an end-to-end golden set with state-based assertions plus a calibrated LLM judge; a CI gate with zero tolerance on deterministic privacy/injection/refund checks; a canary deploy; then online judge sampling and business metrics (resolution, re-contact, CSAT) in production.

**How was the model picked?**
By eval. Run the top model at low effort for a quality ceiling, then cheaper tiers on the same cases; pick the cheapest that meets the bar on zero-tolerance categories and the latency SLO. Pin versions, re-evaluate on upgrades, and consider routing only when volume makes the savings material. Here that led to Haiku 5.5 (see [04](04-evals.md)).

**What does this cost at scale?**
Conversations × turns × calls per turn × tokens per call × price. Levers: caching, fewer calls, smaller context, effort, model tier, batch for offline work. Compare against human cost per contact: the metric is cost per *resolved* contact, not per token.

**Latency is too high. What do you do?**
Stream (perceived latency). Cut model calls per turn (better tools, lower effort). Cache the prefix. Use a smaller model for easy intents. Run tool calls in parallel. Move deterministic intents (order status) to a no-LLM fast path.

**RAG or fine-tuning?**
RAG for knowledge (fresh, citable, access-controlled, cheap to update). Fine-tuning for behaviour/format/style at scale, or to distil a big model into a small one once you have lots of good traces. Exhaust prompting + RAG + evals first.

**What breaks first at 100× traffic?**
Model rate limits and cost before compute. Then Lambda paying to wait on I/O (move to async containers), then retrieval quality as the KB grows. Plan quota increases, provisioned capacity or multi-provider routing, and spend alerts.

**How do you handle hallucinations?**
Ground everything in tools and retrieval ("never answer policy from memory"); keep tool output structured; have the judge check faithfulness; ask clarifying questions before acting (eval case `ambig-01`); escalate when unsure.

**Multi-agent?**
Only when a single agent's context or toolset gets overloaded, or sub-tasks can run in parallel. It multiplies cost and failure modes. Start with one agent; split when evals show tool confusion.

**Build vs buy?**
Buy (Zendesk/Intercom/Salesforce AI agents, Sierra, Decagon, etc.) when support flows are standard and speed matters. Build when the actions touch proprietary systems, when differentiation or unit economics matter at your volume, or when you need control over evals and data. Hybrid is common: buy the channel and ticketing, build the agent logic and tools.

**How should a team building agents be organized?**
A small platform group (gateway, eval service, tracing, guardrails, tool/MCP registry) plus product squads owning their agents, tools and eval sets. Give eval datasets clear owners (with support/legal as stakeholders) and add AI changes to the release review. Measure teams on business outcomes and eval scores, not demos.

**What would change in a real production version?**
Real identity (Cognito JWT) instead of a dropdown, streaming, a CI eval gate with canary deploys, hybrid retrieval, and online evals from day one. See [07](07-production-operations.md).

---

## Key numbers

* **System:** 5 tools, 6-call step budget, 2,048 max output tokens, 30-day refund window, $100 human-approval threshold, 24 golden cases, BM25 recall@3 = 88%, reserved concurrency 10, daily cap 300.
* **Eval baseline (24 cases):** Opus 5.5 and Haiku 5.5 both 96% pass and 100% on deterministic safety/correctness checks. Judge average 4.71 vs 4.54; $0.0104 vs $0.0003 per case (~35×); p50 3.8 s vs 2.3 s; ~2.2 model calls per case. Prompt caching roughly halved Opus input cost.
* **Takeaway:** model choice moved answer quality, not safety, because safety lives in the tools. And 24 single-trial cases can't separate two models, so multi-trial evals should back any switch on quality grounds.
* **Rules of thumb:** ~4 characters per token; output tokens ~5× the price of input; cache reads ~10% of input price; Batch API ~50% off.

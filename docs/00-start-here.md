# 00 · Start here: what an AI agent actually is

You do not need any machine-learning background for this. If you can follow a web request through a backend service, you can follow an agent.

## The one-paragraph version

A **large language model (LLM)** is a function: text in, text out. On its own it can't look anything up or change anything. An **agent** is ordinary software that puts an LLM in a loop and gives it **tools** (functions it may ask you to call). Each time around the loop the model either answers the user, or says "please call `get_order` with `{order_id: A1001}`". Your code runs that function, hands the result back, and asks again. That's it. Everything else (memory, retrieval, guardrails, evals) is engineering around that loop.

```
user: "Where is my water bottle?"
  ┌───────────────────────────────────────────────────────────────┐
  │ model: I should call list_my_orders()                         │  model call #1
  │ code : runs list_my_orders → [A1001 delivered, A1002 shipped] │
  │ model: The bottle is A1002; call get_order(A1002)             │  model call #2
  │ code : runs get_order → {status: SHIPPED, carrier: USPS, ...} │
  │ model: "Your bottle shipped with USPS, tracking 9400…"        │  model call #3 → answer
  └───────────────────────────────────────────────────────────────┘
```

## Five mental models that explain most of the system

1. **The model is stateless.** Every call sends the whole conversation again. "Memory" is just you deciding what to resend. That's why long conversations get slower and more expensive.
2. **The model proposes, your code disposes.** The model can *ask* for a refund; only your code can *issue* one, after checking ownership, the refund window, approval limits and idempotency. Never put a business rule only in a prompt.
3. **You pay per token, and latency is roughly proportional to tokens generated.** Every design choice (model size, how much context you send, how many loop iterations) is a cost and latency choice.
4. **Non-determinism means you test with evals, not just unit tests.** The same input can produce different outputs. You measure quality statistically over a dataset of cases (an "eval"), the same way you would A/B test a product change.
5. **Agents are a spectrum.** Single prompt → fixed workflow (code decides the steps) → agent (model decides the steps). Use the least autonomy that solves the problem: more autonomy means more flexibility, but also more cost, latency, and ways to fail.

## What this repo builds

A support assistant that can:

| Capability | Tool | What makes it realistic |
|---|---|---|
| Answer policy questions | `search_policies` | Retrieval over policy docs (RAG), so answers are grounded and citable |
| Look up orders | `list_my_orders`, `get_order` | Authorization enforced in code: you can only see your own orders |
| Refund orders | `request_refund` | Deterministic policy: 30-day window, non-refundable items, ≤$100 auto-approve, >$100 needs a human, idempotent |
| Escalate | `create_support_ticket` | Human-in-the-loop for things the agent shouldn't decide |

And all the operational scaffolding: deployed on AWS Lambda, state in DynamoDB, secrets in Secrets Manager, metrics and a dashboard in CloudWatch, an eval suite that compares models, and CI.

## How to read the rest

* **Want the big picture?** Read [01 Architecture](01-architecture.md).
* **Preparing for "why X and not Y?" questions?** Read [02 Components and trade-offs](02-components-and-tradeoffs.md).
* **Unfamiliar words?** Keep [03 Glossary](03-glossary.md) open.
* **Short on time?** Read [08 Design review guide](08-design-review-guide.md) first, then come back.

# 03 · Glossary: the vocabulary you should know

Grouped by topic. **Bold** terms are the ones most likely to come up in an AI design discussion. "Here:" points at where the concept lives in this repo.

## Models and prompting

| Term | Meaning |
|---|---|
| **LLM (large language model)** | A neural network trained to predict the next token. Text in, text out. |
| **Foundation model / frontier model** | A large general-purpose model (Claude, GPT, Gemini, Llama). "Frontier" = the most capable current ones. |
| **Open-weights model** | Model whose weights you can download and run yourself (Llama, Mistral, Qwen, DeepSeek). |
| **Token** | The unit models read and write, roughly ¾ of an English word. Billing, limits and latency are all in tokens. |
| **Context window** | Max tokens a model can consider in one call (prompt + output). Current Claude models: up to 1M. |
| **System prompt** | Instructions that frame the model's role and rules for the whole conversation. Here: `prompts/system-v1.md`. |
| **Prompt engineering** | Writing instructions, examples and structure to get reliable behaviour. |
| **Few-shot prompting** | Including worked examples in the prompt. |
| **Temperature / top-p** | Sampling randomness controls. Some current models fix these and expose "effort" instead. |
| **Effort** | A per-request dial on current Claude models trading thoroughness (thinking, tool calls) for cost and latency. Here: `EFFORT=low`. |
| **Extended / adaptive thinking (reasoning)** | The model reasons internally before answering. Adaptive = it decides how much. Thinking tokens are billed as output. |
| **Structured output** | Constraining output to a JSON schema. |
| **Hallucination** | Fluent, confident output that isn't true. Mitigated by grounding (RAG/tools) and evals. |
| **Grounding** | Basing answers on retrieved facts or tool results rather than the model's memory. |
| **Refusal** | The model (or a safety classifier) declines a request. Here: `stop_reason=refusal` → polite message. |
| **Fine-tuning** | Further training a model on your data (SFT, LoRA, DPO, RL). Changes behaviour or style; rarely the right first step for knowledge (use RAG). |
| **Distillation** | Training a smaller model to imitate a larger one, for cost and speed. |

## Agents

| Term | Meaning |
|---|---|
| **Agent** | Software where an LLM decides which actions (tool calls) to take, in a loop, to reach a goal. |
| **Agent loop / agentic loop** | Call model → execute requested tools → feed results back → repeat until done. Here: `SupportAgent.run`. |
| **ReAct** | "Reason + Act": interleaving reasoning and tool use. The pattern behind most agent loops. |
| **Tool use / function calling** | The model emits a structured request to call a function you defined (name + JSON input). |
| **Tool schema** | JSON Schema describing a tool's inputs. The model only sees name, description and schema. |
| **Parallel tool calls** | Several tool calls in one model turn; return all results in one message. |
| **Workflow vs agent** | Workflow: code decides the steps (predictable). Agent: the model decides (flexible). Many production "agents" are mostly workflows. |
| **Orchestration** | The code (or framework) running the loop, managing state, retries, budgets. |
| **Step budget / max iterations** | Hard cap on loop iterations. Here: 6 model calls per turn. |
| **Human-in-the-loop (HITL)** | A person approves or performs high-risk actions. Here: refunds over $100. |
| **Multi-agent system** | Several agents (often a supervisor + specialists) collaborating. More capability, more cost and failure modes. |
| **MCP (Model Context Protocol)** | Open standard for exposing tools, resources and prompts to AI applications over a client-server protocol. |
| **A2A (Agent2Agent)** | Protocol for agents from different systems to communicate. |
| **Memory (short / long term)** | Short: this conversation's history. Long: facts persisted across sessions. Here: short-term in DynamoDB. |
| **Compaction / summarization** | Replacing old conversation turns with a summary to stay within context and cost limits. |
| **Context engineering** | Deciding what goes into the context window each call (instructions, history, retrieved docs, tool results). |
| **Agent harness** | Everything around the model that makes it an agent: loop, tools, context management, permissions. |

## Retrieval (RAG)

| Term | Meaning |
|---|---|
| **RAG (retrieval-augmented generation)** | Fetch relevant documents and put them in the prompt so the model answers from them. |
| **Agentic RAG** | The model decides when and what to search (retrieval is a tool). Here: `search_policies`. |
| **Chunking** | Splitting documents into retrievable pieces. Here: one chunk per section, with the doc title as context. |
| **Embedding** | A vector of numbers representing text meaning; similar texts have nearby vectors. |
| **Vector database** | Stores embeddings and finds nearest neighbours fast (pgvector, OpenSearch, Pinecone, S3 Vectors…). |
| **ANN (approximate nearest neighbour), HNSW, IVF** | Index algorithms that trade a little accuracy for big speed in vector search. |
| **BM25** | Classic lexical ranking function (term frequency × inverse document frequency, length-normalized). Here: `KnowledgeBase`. |
| **Hybrid search** | Combine lexical (BM25) and semantic (vector) results, often with **Reciprocal Rank Fusion (RRF)**. |
| **Reranker / cross-encoder** | A second model that re-scores the top-N candidates for relevance. Big quality gain for little cost. |
| **Recall@k / MRR / nDCG** | Retrieval metrics: is the right doc in the top k / how high is it ranked / graded ranking quality. |
| **Knowledge base** | Managed RAG store (e.g. Bedrock Knowledge Bases) or just "the documents you retrieve from". |

## Inference and serving

| Term | Meaning |
|---|---|
| **Inference** | Running a trained model to produce output (vs training). |
| **TTFT (time to first token)** | Latency until the first output token; drives *perceived* responsiveness. |
| **TPOT / ITL** | Time per output token / inter-token latency. Total ≈ TTFT + output tokens × TPOT. |
| **Throughput** | Tokens per second (per request, or aggregate across a server). |
| **Streaming** | Sending tokens to the client as they're generated (SSE/WebSockets). |
| **Prefill vs decode** | Prefill: processing the prompt (parallel, fast). Decode: generating tokens one at a time (sequential, slower). |
| **KV cache** | Cached attention keys/values so earlier tokens aren't recomputed each step. Enables prompt caching. |
| **Prompt caching** | Provider reuses the processed prefix of your prompt across requests. Cheaper (~10% of input price on reads) and faster. Here: cache point after the static system prompt. |
| **Batching / continuous batching** | Serving many requests together on one GPU for throughput (vLLM does this). |
| **Batch API** | Asynchronous bulk requests at about 50% off; results within hours. For offline jobs and evals. |
| **Quantization** | Lower-precision weights (FP8, INT4) to cut memory and cost, with some quality loss. |
| **Speculative decoding** | A small draft model proposes tokens a large model verifies. Faster output. |
| **Rate limits (RPM / TPM)** | Requests and tokens per minute quotas. `max_tokens` counts against TPM on some providers. |
| **Provisioned throughput** | Reserved model capacity at a fixed price; predictable latency at high volume. |
| **Cross-region inference** | Bedrock routing a request to another region in the same geography for capacity (`us.` profile prefix). |
| **Model routing / cascade** | Sending each request to the cheapest model that can handle it. |
| **vLLM / TGI / SGLang / TensorRT-LLM** | Open-source high-performance inference servers for self-hosting. |

## Evaluation

| Term | Meaning |
|---|---|
| **Eval** | A repeatable test of model or system quality over a dataset of cases. Here: `evals/`. |
| **Golden dataset** | Curated inputs with expected outcomes. Here: `evals/cases/golden.jsonl`. |
| **Offline vs online eval** | Offline: before deploy, on fixed data. Online: on sampled production traffic. |
| **LLM-as-judge** | A model grades outputs against a rubric. Cheap and scalable; must be calibrated against humans. |
| **Rubric** | The criteria a judge scores against. Specific beats generic. |
| **Deterministic grader** | Exact checks (string, schema, DB state). Never flaky. |
| **State-based eval** | Checking what the agent *did* (DB changes), not just what it said. |
| **pass@k / pass^k** | Probability that at least one / all of k attempts succeed. pass^k measures *reliability*. |
| **Regression eval** | Runs in CI to make sure a change didn't break what used to work. |
| **Red teaming** | Adversarially probing for unsafe behaviour (jailbreaks, injection, data leaks). |
| **Faithfulness / groundedness** | Is the answer supported by the retrieved context? |
| **A/B test** | Comparing two variants on live traffic with real outcome metrics (resolution rate, CSAT). |

## Safety and security

| Term | Meaning |
|---|---|
| **Prompt injection** | Malicious instructions in user input or data (docs, web pages, tool output) that try to hijack the model. |
| **Indirect prompt injection** | Injection delivered via content the agent reads, not typed by the user. |
| **Jailbreak** | Getting a model to ignore its safety training. |
| **Guardrails** | Checks around the model: input/output filters, PII detection, topic restrictions, schema validation. |
| **Least privilege** | Give the agent (and its IAM role) only the permissions it needs. |
| **Confused deputy** | A privileged component tricked into misusing its authority on someone else's behalf. Agents are prime confused deputies. |
| **PII** | Personally identifiable information. Watch logs, traces and eval datasets. |
| **OWASP Top 10 for LLM Applications** | Industry list of LLM risks (prompt injection, sensitive info disclosure, excessive agency, …). |
| **Excessive agency** | An agent with more tools, permissions or autonomy than its task needs. |

## AWS and platform bits used here

| Term | Meaning |
|---|---|
| **Lambda / SnapStart** | Serverless functions / snapshotting the initialized JVM to reduce cold starts. |
| **Cold start** | First-request latency while a new execution environment initializes. |
| **Function URL** | Built-in HTTPS endpoint for a Lambda function. |
| **Reserved concurrency** | Max simultaneous executions for a function, which also guarantees that capacity. |
| **DynamoDB GSI / TTL / conditional write** | Secondary index / auto-expiry / write that succeeds only if a condition holds (idempotency). |
| **EMF (Embedded Metric Format)** | Writing metrics as structured log lines CloudWatch extracts. |
| **CDK** | Infrastructure as code in real programming languages, synthesizing CloudFormation. |
| **KMS CMK** | Customer-managed encryption key. |
| **Bedrock / AgentCore** | AWS model hosting / AWS agent platform (runtime, gateway, memory, identity, observability, evals). |

# 09 · Deploy it yourself, step by step

Assumes macOS; Linux is the same with a different package manager.

## 0. Install the tools (once)

```bash
brew install openjdk@21 maven node awscli
java -version   # 21.x
mvn -v
```

You also need an **Anthropic API key** (console.anthropic.com → API keys) or an AWS account with **Bedrock model access**.

## 1. Build and test locally (no cloud, no key)

```bash
git clone https://github.com/setugarg/agentic-support-agent.git
cd agentic-support-agent
mvn -q install                    # compiles, runs 13 unit tests, builds agent/target/agent-lambda.jar
mvn -q -pl evals exec:java -Dexec.args="--retrieval-only"   # free retrieval eval
```

## 2. Run the agent on your laptop

```bash
export ANTHROPIC_API_KEY=sk-ant-...      # your key; never commit it
java -jar agent/target/agent-lambda.jar  # http://localhost:8080
```

Pick a demo customer and try the suggested prompts. Data is in memory and resets on restart.

## 3. AWS credentials

```bash
aws login --profile agent-toolkit         # or configure SSO / access keys
export AWS_PROFILE=agent-toolkit AWS_REGION=us-west-2
aws sts get-caller-identity
```

First time in an account/region only:

```bash
cd infra && npx aws-cdk bootstrap
```

## 4. Deploy

```bash
mvn -q install -DskipTests                # rebuild the Lambda jar
cd infra
npx aws-cdk diff                          # see what will change
npx aws-cdk deploy                        # ~2-3 minutes; prints AppUrl
```

Options (CDK context, no code changes):

```bash
npx aws-cdk deploy -c modelId=claude-haiku-5-5            # cheaper model
npx aws-cdk deploy -c llmProvider=bedrock -c modelId=us.anthropic.claude-opus-5-5   # Bedrock instead of Anthropic API
npx aws-cdk deploy -c dailyRequestCap=100
```

## 5. Put your API key in Secrets Manager (once)

The stack created the secret with a placeholder. Set the real value (it never goes into git or CloudFormation). To keep the key out of your shell history, paste it at the prompt:

```bash
read -rs "KEY?Anthropic API key: " && aws secretsmanager put-secret-value \
  --secret-id support-agent/anthropic-api-key --secret-string "$KEY" && unset KEY
```

(In bash use `read -rsp "Anthropic API key: " KEY`.) The function caches secrets for 5 minutes, so the new key is live within 5 minutes with no redeploy. The same mechanism handles key rotation.

## 6. Load the demo orders

```bash
cd ..   # repo root
ORDERS_TABLE=$(aws cloudformation describe-stacks --stack-name SupportAgentStack \
  --query "Stacks[0].Outputs[?OutputKey=='OrdersTableName'].OutputValue" --output text) \
  mvn -q -pl evals exec:java -Dexec.mainClass=dev.setugarg.supportagent.evals.SeedDemoData
```

(The "Reset demo data" button in the UI does the same thing.)

## 7. Get the access code and try it

```bash
aws secretsmanager get-secret-value --secret-id support-agent/demo-access-code \
  --query SecretString --output text
```

Open the `AppUrl`, paste the access code, and chat. Watch the **Agent trace** panel.

Dashboard: CloudWatch console → Dashboards → `SupportAgent`. Logs: CloudWatch → Log groups → `SupportAgentStack-AgentLogs…`.

## 8. Run the full eval

```bash
# uses ANTHROPIC_API_KEY if set, otherwise reads the key from Secrets Manager with your AWS credentials
mvn -q -pl evals exec:java -Dexec.args="--models claude-opus-5-5,claude-haiku-5-5 --trials 1"
open evals/reports/latest.md
```

A full two-model run is 48 agent conversations plus 48 judge calls, typically a couple of dollars.

## 9. Tear down

```bash
cd infra && npx aws-cdk destroy
```

Removes everything (tables, secrets, key scheduled for deletion, function, dashboard).

## Troubleshooting

| Symptom | Fix |
|---|---|
| UI says "model provider is not configured" | Step 5 not done, or done less than 5 minutes ago (secret cache TTL) |
| `invalid access code` | Use the value from step 7 (no quotes/whitespace) |
| `Unable to load credentials … login_session … 'signin' module` | Java tools need the `signin` module for `aws login` sessions (already in `evals/`), or export credentials: `eval "$(aws configure export-credentials --format env)"` |
| Bedrock `Access to Bedrock models is not allowed for this account` | Account-level restriction; open an AWS Support case. Use `llmProvider=anthropic` meanwhile |
| Bedrock `ThrottlingException` at low traffic | Make sure `maxTokens` is set (it is in this code), use `us.` inference profiles, check quotas |
| `429 Daily demo budget reached` | Working as intended; raise `dailyRequestCap` |

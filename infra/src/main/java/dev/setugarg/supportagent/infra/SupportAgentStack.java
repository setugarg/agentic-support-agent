package dev.setugarg.supportagent.infra;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.Dashboard;
import software.amazon.awscdk.services.cloudwatch.GraphWidget;
import software.amazon.awscdk.services.cloudwatch.MathExpression;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.SingleValueWidget;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.TableEncryption;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionUrl;
import software.amazon.awscdk.services.lambda.FunctionUrlAuthType;
import software.amazon.awscdk.services.lambda.FunctionUrlOptions;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.SnapStartConf;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * Everything the agent needs in one stack:
 * <pre>
 *  Browser ─► Lambda Function URL (HTTPS) ─► Lambda "live" alias (Java 21, arm64, SnapStart)
 *                                               ├─► Anthropic API / Amazon Bedrock (LLM)
 *                                               ├─► DynamoDB: orders, tickets, sessions, usage
 *                                               ├─► Secrets Manager (KMS CMK): API key, access code
 *                                               └─► CloudWatch Logs + EMF metrics ─► Dashboard, Alarm
 * </pre>
 */
public final class SupportAgentStack extends Stack {

    public SupportAgentStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        String model = ctx("modelId", "claude-opus-5-5");
        String provider = ctx("llmProvider", "anthropic");

        // ---------------- Secrets (customer-managed KMS key, least-privilege read) ----------------
        Key key = Key.Builder.create(this, "SecretsKey")
                .description("Encrypts support-agent secrets")
                .enableKeyRotation(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .pendingWindow(Duration.days(7))
                .build();

        // Placeholder value; you set the real key with `aws secretsmanager put-secret-value`.
        // CloudFormation never overwrites the value on later deploys.
        Secret anthropicKey = Secret.Builder.create(this, "AnthropicApiKey")
                .secretName("support-agent/anthropic-api-key")
                .description("Anthropic API key used by the support agent (set manually)")
                .encryptionKey(key)
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"value\":\"REPLACE_ME\"}")
                        .generateStringKey("unused")
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Secret accessCode = Secret.Builder.create(this, "DemoAccessCode")
                .secretName("support-agent/demo-access-code")
                .description("Shared access code required to use the public demo")
                .encryptionKey(key)
                .generateSecretString(SecretStringGenerator.builder()
                        .passwordLength(14)
                        .excludePunctuation(true)
                        .build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // ---------------- DynamoDB (on-demand; pay per request, zero idle cost) ----------------
        Table orders = Table.Builder.create(this, "Orders")
                .partitionKey(Attribute.builder().name("orderId").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .encryption(TableEncryption.AWS_MANAGED)
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder().pointInTimeRecoveryEnabled(true).build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
        orders.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                .indexName("byCustomer")
                .partitionKey(Attribute.builder().name("customerEmail").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("orderDate").type(AttributeType.STRING).build())
                .build());

        Table tickets = Table.Builder.create(this, "Tickets")
                .partitionKey(Attribute.builder().name("customerEmail").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("ticketId").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .encryption(TableEncryption.AWS_MANAGED)
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder().pointInTimeRecoveryEnabled(true).build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Table sessions = Table.Builder.create(this, "Sessions")
                .partitionKey(Attribute.builder().name("sessionId").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ttl")
                .encryption(TableEncryption.AWS_MANAGED)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Table usage = Table.Builder.create(this, "Usage")
                .partitionKey(Attribute.builder().name("day").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ttl")
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // ---------------- Lambda ----------------
        LogGroup logs = LogGroup.Builder.create(this, "AgentLogs")
                .retention(RetentionDays.TWO_WEEKS)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Function fn = Function.Builder.create(this, "AgentFunction")
                .runtime(Runtime.JAVA_21)
                .architecture(Architecture.ARM_64)          // Graviton: ~20% cheaper per GB-second
                .handler("dev.setugarg.supportagent.api.LambdaHandler::handleRequest")
                .code(Code.fromAsset("../agent/target/agent-lambda.jar"))
                .memorySize(1536)                           // more memory = more CPU = faster JVM
                .timeout(Duration.seconds(90))              // agent turns are multi-call; leave headroom
                .snapStart(SnapStartConf.ON_PUBLISHED_VERSIONS)
                .tracing(Tracing.ACTIVE)
                .logGroup(logs)
                .reservedConcurrentExecutions(10)           // blast-radius + spend cap
                .environment(Map.ofEntries(
                        Map.entry("STORE", "dynamo"),
                        Map.entry("LLM_PROVIDER", provider),
                        Map.entry("MODEL_ID", model),
                        Map.entry("EFFORT", ctx("effort", "low")),
                        Map.entry("MAX_AGENT_STEPS", "6"),
                        Map.entry("MAX_TOKENS", "2048"),
                        Map.entry("DAILY_REQUEST_CAP", ctx("dailyRequestCap", "300")),
                        Map.entry("ORDERS_TABLE", orders.getTableName()),
                        Map.entry("TICKETS_TABLE", tickets.getTableName()),
                        Map.entry("SESSIONS_TABLE", sessions.getTableName()),
                        Map.entry("USAGE_TABLE", usage.getTableName()),
                        Map.entry("ANTHROPIC_SECRET_ARN", anthropicKey.getSecretArn()),
                        Map.entry("ACCESS_CODE_SECRET_ARN", accessCode.getSecretArn()),
                        Map.entry("JAVA_TOOL_OPTIONS", "-XX:+TieredCompilation -XX:TieredStopAtLevel=1")))
                .build();

        // Least privilege: exactly these tables, exactly these secrets.
        orders.grantReadWriteData(fn);
        tickets.grantReadWriteData(fn);
        sessions.grantReadWriteData(fn);
        usage.grantReadWriteData(fn);
        anthropicKey.grantRead(fn);
        accessCode.grantRead(fn);
        // Ready for LLM_PROVIDER=bedrock (inference profiles route across US regions).
        fn.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel")) // Converse is authorized by InvokeModel
                .resources(List.of(
                        "arn:aws:bedrock:*::foundation-model/anthropic.*",
                        "arn:aws:bedrock:*:" + getAccount() + ":inference-profile/us.anthropic.*"))
                .build());

        // SnapStart snapshots a published version; the alias always points at the latest one.
        Alias live = Alias.Builder.create(this, "LiveAlias")
                .aliasName("live")
                .version(fn.getCurrentVersion())
                .build();

        FunctionUrl url = live.addFunctionUrl(FunctionUrlOptions.builder()
                .authType(FunctionUrlAuthType.NONE)         // public demo; app-level access code + caps
                .build());

        // ---------------- Observability ----------------
        Metric errors = metric("Errors", "Sum", Map.of());
        Alarm.Builder.create(this, "AgentErrorsAlarm")
                .alarmDescription("Support agent is returning errors")
                .metric(errors)
                .threshold(5)
                .evaluationPeriods(1)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();

        Map<String, String> dims = Map.of("Model", model);
        Dashboard.Builder.create(this, "AgentDashboard")
                .dashboardName("SupportAgent")
                .widgets(List.of(
                        List.of(
                                SingleValueWidget.Builder.create().title("Turns (24h)").width(6)
                                        .metrics(List.of(metric("Turns", "Sum", dims).with(software.amazon.awscdk.services.cloudwatch.MetricOptions.builder().period(Duration.days(1)).build()))).build(),
                                SingleValueWidget.Builder.create().title("Spend USD (24h)").width(6)
                                        .metrics(List.of(MathExpression.Builder.create().expression("c / 1000000").label("USD")
                                                .usingMetrics(Map.of("c", metric("CostMicroUsd", "Sum", dims)))
                                                .period(Duration.days(1)).build())).build(),
                                SingleValueWidget.Builder.create().title("Errors (24h)").width(6)
                                        .metrics(List.of(errors.with(software.amazon.awscdk.services.cloudwatch.MetricOptions.builder().period(Duration.days(1)).build()))).build()),
                        List.of(
                                GraphWidget.Builder.create().title("Turn latency (ms)").width(12)
                                        .left(List.of(metric("TurnLatencyMs", "p50", dims), metric("TurnLatencyMs", "p95", dims))).build(),
                                GraphWidget.Builder.create().title("Tokens per 5 min").width(12)
                                        .left(List.of(metric("InputTokens", "Sum", dims), metric("OutputTokens", "Sum", dims),
                                                metric("CacheReadTokens", "Sum", dims))).build()),
                        List.of(
                                GraphWidget.Builder.create().title("Model + tool calls per turn").width(12)
                                        .left(List.of(metric("ModelCalls", "Average", dims), metric("ToolCalls", "Average", dims))).build(),
                                GraphWidget.Builder.create().title("Lambda").width(12)
                                        .left(List.of(fn.metricInvocations(), fn.metricErrors(), fn.metricThrottles()))
                                        .right(List.of(fn.metricDuration())).build())))
                .build();

        CfnOutput.Builder.create(this, "AppUrl").value(url.getUrl()).description("Open this in a browser").build();
        CfnOutput.Builder.create(this, "AnthropicSecretName").value(anthropicKey.getSecretName()).build();
        CfnOutput.Builder.create(this, "AccessCodeSecretName").value(accessCode.getSecretName()).build();
        CfnOutput.Builder.create(this, "OrdersTableName").value(orders.getTableName()).build();
        CfnOutput.Builder.create(this, "FunctionName").value(fn.getFunctionName()).build();
    }

    private Metric metric(String name, String stat, Map<String, String> dims) {
        return Metric.Builder.create().namespace("SupportAgent").metricName(name).statistic(stat)
                .dimensionsMap(dims).period(Duration.minutes(5)).build();
    }

    private String ctx(String key, String def) {
        Object v = getNode().tryGetContext(key);
        return v == null ? def : v.toString();
    }
}

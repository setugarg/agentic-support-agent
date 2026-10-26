package dev.setugarg.supportagent.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;

/** CDK entry point: {@code cdk deploy} runs this to synthesize CloudFormation. */
public final class InfraApp {
    public static void main(String[] args) {
        App app = new App();
        String region = System.getenv().getOrDefault("CDK_DEFAULT_REGION", "us-west-2");
        new SupportAgentStack(app, "SupportAgentStack", StackProps.builder()
                .env(Environment.builder().account(System.getenv("CDK_DEFAULT_ACCOUNT")).region(region).build())
                .description("Agentic customer-support demo: Java 21 Lambda agent + DynamoDB + Secrets Manager")
                .build());
        // Tags flow to every resource: cost allocation and ownership in one place.
        Tags.of(app).add("project", "agentic-support-agent");
        Tags.of(app).add("env", "demo");
        app.synth();
    }
}

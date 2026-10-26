package dev.setugarg.supportagent.evals;

import dev.setugarg.supportagent.store.DynamoStores;
import dev.setugarg.supportagent.store.SeedData;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.LocalDate;

/**
 * Ops command: load (or restore) the demo orders into the deployed DynamoDB table.
 * <pre>
 *   AWS_PROFILE=... AWS_REGION=us-west-2 ORDERS_TABLE=... \
 *     mvn -q -pl evals exec:java -Dexec.mainClass=dev.setugarg.supportagent.evals.SeedDemoData
 * </pre>
 */
public final class SeedDemoData {
    public static void main(String[] args) {
        String table = System.getenv("ORDERS_TABLE");
        if (table == null || table.isBlank()) throw new IllegalArgumentException("set ORDERS_TABLE");
        try (DynamoDbClient ddb = DynamoDbClient.create()) {
            var orders = new DynamoStores.Orders(ddb, table);
            var seed = SeedData.orders(LocalDate.now());
            seed.forEach(orders::put);
            System.out.println("Seeded " + seed.size() + " orders into " + table);
        }
    }
}

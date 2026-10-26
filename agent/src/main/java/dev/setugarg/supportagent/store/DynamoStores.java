package dev.setugarg.supportagent.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.setugarg.supportagent.llm.Llm;
import dev.setugarg.supportagent.store.Stores.Order;
import dev.setugarg.supportagent.store.Stores.OrderStatus;
import dev.setugarg.supportagent.store.Stores.Ticket;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB-backed stores. Plain low-level client (no ORM) so every read/write is explicit -
 * easier to reason about cost (RCU/WCU) and consistency.
 */
public final class DynamoStores {
    private DynamoStores() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AttributeValue s(String v) { return AttributeValue.fromS(v); }
    private static AttributeValue n(Number v) { return AttributeValue.fromN(v.toString()); }

    public static final class Orders implements Stores.OrderStore {
        private final DynamoDbClient ddb;
        private final String table;

        public Orders(DynamoDbClient ddb, String table) { this.ddb = ddb; this.table = table; }

        @Override public Optional<Order> get(String orderId) {
            var item = ddb.getItem(r -> r.tableName(table).key(Map.of("orderId", s(orderId))).consistentRead(true)).item();
            return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
        }

        @Override public List<Order> listByCustomer(String email) {
            var resp = ddb.query(r -> r.tableName(table).indexName("byCustomer")
                    .keyConditionExpression("customerEmail = :e")
                    .expressionAttributeValues(Map.of(":e", s(email.toLowerCase())))
                    .scanIndexForward(false));
            return resp.items().stream().map(Orders::fromItem).toList();
        }

        @Override public boolean transition(String orderId, OrderStatus expected, OrderStatus next) {
            try {
                ddb.updateItem(r -> r.tableName(table).key(Map.of("orderId", s(orderId)))
                        .updateExpression("SET #st = :next")
                        .conditionExpression("#st = :expected")
                        .expressionAttributeNames(Map.of("#st", "status"))
                        .expressionAttributeValues(Map.of(":next", s(next.name()), ":expected", s(expected.name()))));
                return true;
            } catch (ConditionalCheckFailedException e) {
                return false;
            }
        }

        @Override public void put(Order o) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("orderId", s(o.orderId()));
            item.put("customerEmail", s(o.customerEmail().toLowerCase()));
            item.put("items", AttributeValue.fromL(o.items().stream().map(DynamoStores::s).toList()));
            item.put("totalUsd", n(o.totalUsd()));
            item.put("status", s(o.status().name()));
            item.put("orderDate", s(o.orderDate().toString()));
            if (o.deliveredDate() != null) item.put("deliveredDate", s(o.deliveredDate().toString()));
            if (o.carrier() != null) item.put("carrier", s(o.carrier()));
            if (o.trackingNumber() != null) item.put("trackingNumber", s(o.trackingNumber()));
            ddb.putItem(r -> r.tableName(table).item(item));
        }

        private static Order fromItem(Map<String, AttributeValue> i) {
            return new Order(
                    i.get("orderId").s(),
                    i.get("customerEmail").s(),
                    i.get("items").l().stream().map(AttributeValue::s).toList(),
                    Double.parseDouble(i.get("totalUsd").n()),
                    OrderStatus.valueOf(i.get("status").s()),
                    LocalDate.parse(i.get("orderDate").s()),
                    i.containsKey("deliveredDate") ? LocalDate.parse(i.get("deliveredDate").s()) : null,
                    i.containsKey("carrier") ? i.get("carrier").s() : null,
                    i.containsKey("trackingNumber") ? i.get("trackingNumber").s() : null);
        }
    }

    public static final class Tickets implements Stores.TicketStore {
        private final DynamoDbClient ddb;
        private final String table;

        public Tickets(DynamoDbClient ddb, String table) { this.ddb = ddb; this.table = table; }

        @Override public Ticket create(Ticket t) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("customerEmail", s(t.customerEmail().toLowerCase()));
            item.put("ticketId", s(t.ticketId()));
            if (t.orderId() != null) item.put("orderId", s(t.orderId()));
            item.put("priority", s(t.priority()));
            item.put("summary", s(t.summary()));
            item.put("status", s(t.status()));
            item.put("createdAt", s(t.createdAt()));
            ddb.putItem(r -> r.tableName(table).item(item));
            return t;
        }

        @Override public List<Ticket> listByCustomer(String email) {
            return ddb.query(r -> r.tableName(table).keyConditionExpression("customerEmail = :e")
                            .expressionAttributeValues(Map.of(":e", s(email.toLowerCase()))))
                    .items().stream()
                    .map(i -> new Ticket(i.get("ticketId").s(), i.get("customerEmail").s(),
                            i.containsKey("orderId") ? i.get("orderId").s() : null,
                            i.get("priority").s(), i.get("summary").s(), i.get("status").s(), i.get("createdAt").s()))
                    .toList();
        }
    }

    /** Stores conversation text turns as one JSON attribute, expiring after 24h via DynamoDB TTL. */
    public static final class Sessions implements Stores.SessionStore {
        private record Turn(String role, String text) {}

        private final DynamoDbClient ddb;
        private final String table;

        public Sessions(DynamoDbClient ddb, String table) { this.ddb = ddb; this.table = table; }

        @Override public List<Llm.Message> load(String sessionId) {
            var item = ddb.getItem(r -> r.tableName(table).key(Map.of("sessionId", s(sessionId)))).item();
            if (item == null || !item.containsKey("history")) return List.of();
            try {
                List<Turn> turns = MAPPER.readValue(item.get("history").s(), new TypeReference<>() {});
                List<Llm.Message> out = new ArrayList<>();
                for (Turn t : turns) {
                    out.add("user".equals(t.role()) ? Llm.Message.user(t.text()) : Llm.Message.assistant(t.text()));
                }
                return out;
            } catch (Exception e) {
                return List.of(); // corrupt memory should never take the agent down
            }
        }

        @Override public void save(String sessionId, List<Llm.Message> history) {
            List<Turn> turns = history.stream()
                    .map(m -> new Turn(m.role() == Llm.Role.USER ? "user" : "assistant", m.text())).toList();
            try {
                String json = MAPPER.writeValueAsString(turns);
                long ttl = Instant.now().plusSeconds(24 * 3600).getEpochSecond();
                ddb.putItem(r -> r.tableName(table).item(Map.of(
                        "sessionId", s(sessionId), "history", s(json), "ttl", n(ttl))));
            } catch (Exception e) {
                throw new IllegalStateException("could not persist session", e);
            }
        }
    }

    public static final class Usage implements Stores.UsageStore {
        private final DynamoDbClient ddb;
        private final String table;

        public Usage(DynamoDbClient ddb, String table) { this.ddb = ddb; this.table = table; }

        @Override public long incrementAndGet(String day) {
            long ttl = Instant.now().plusSeconds(7 * 24 * 3600).getEpochSecond();
            var resp = ddb.updateItem(r -> r.tableName(table).key(Map.of("day", s(day)))
                    .updateExpression("ADD #c :one SET #ttl = :ttl")
                    .expressionAttributeNames(Map.of("#c", "count", "#ttl", "ttl"))
                    .expressionAttributeValues(Map.of(":one", n(1), ":ttl", n(ttl)))
                    .returnValues(ReturnValue.UPDATED_NEW));
            return Long.parseLong(resp.attributes().get("count").n());
        }
    }
}

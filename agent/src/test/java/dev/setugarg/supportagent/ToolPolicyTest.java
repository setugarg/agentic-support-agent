package dev.setugarg.supportagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.setugarg.supportagent.rag.KnowledgeBase;
import dev.setugarg.supportagent.store.InMemoryStores;
import dev.setugarg.supportagent.store.SeedData;
import dev.setugarg.supportagent.store.Stores.OrderStatus;
import dev.setugarg.supportagent.tools.AgentTool;
import dev.setugarg.supportagent.tools.Tools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Business rules are deterministic code, so they get plain unit tests - no model involved. */
class ToolPolicyTest {

    static final ObjectMapper M = new ObjectMapper();
    static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    InMemoryStores.Orders orders;
    InMemoryStores.Tickets tickets;
    Map<String, AgentTool> tools;

    @BeforeEach
    void setUp() {
        orders = new InMemoryStores.Orders(SeedData.orders(TODAY));
        tickets = new InMemoryStores.Tickets();
        tools = new java.util.HashMap<>();
        Tools.all(KnowledgeBase.fromClasspath(), orders, tickets).forEach(t -> tools.put(t.spec().name(), t));
    }

    AgentTool.Result call(String tool, String customer, Map<String, String> input) {
        ObjectNode n = M.createObjectNode();
        input.forEach(n::put);
        return tools.get(tool).execute(n, new AgentTool.Context(customer, "test-session", CLOCK));
    }

    @Test
    void smallRecentDeliveredOrderIsRefundedOnce() {
        var r = call("request_refund", "maya@example.com", Map.of("order_id", "A1001", "reason", "too small"));
        assertTrue(r.content().contains("\"REFUNDED\""), r.content());
        assertEquals(OrderStatus.REFUNDED, orders.get("A1001").orElseThrow().status());

        var again = call("request_refund", "maya@example.com", Map.of("order_id", "A1001", "reason", "too small"));
        assertTrue(again.content().contains("ALREADY_REFUNDED"), "refund must be idempotent");
    }

    @Test
    void largeRefundGoesToHumanApproval() {
        var r = call("request_refund", "maya@example.com", Map.of("order_id", "A1003", "reason", "changed mind"));
        assertTrue(r.content().contains("PENDING_HUMAN_APPROVAL"), r.content());
        assertEquals(OrderStatus.REFUND_PENDING_APPROVAL, orders.get("A1003").orElseThrow().status());
        assertEquals(1, tickets.listByCustomer("maya@example.com").size());
    }

    @Test
    void refundOutsideWindowIsRejected() {
        var r = call("request_refund", "raj@example.com", Map.of("order_id", "B2001", "reason", "leaks"));
        assertTrue(r.content().contains("NOT_ELIGIBLE") && r.content().contains("30 days"), r.content());
    }

    @Test
    void giftCardsAndUndeliveredOrdersAreNotRefundable() {
        assertTrue(call("request_refund", "lena@example.com", Map.of("order_id", "C3002", "reason", "x")).content().contains("NOT_ELIGIBLE"));
        assertTrue(call("request_refund", "raj@example.com", Map.of("order_id", "B2002", "reason", "x")).content().contains("NOT_ELIGIBLE"));
    }

    @Test
    void customersCannotSeeOrRefundOtherCustomersOrders() {
        var view = call("get_order", "raj@example.com", Map.of("order_id", "A1001"));
        assertTrue(view.isError());
        assertFalse(view.content().contains("Trail running shoes"), "must not leak another customer's data");

        var refund = call("request_refund", "raj@example.com", Map.of("order_id", "A1001", "reason", "x"));
        assertTrue(refund.isError());
        assertEquals(OrderStatus.DELIVERED, orders.get("A1001").orElseThrow().status());
    }

    @Test
    void ticketsNeverAttachSomeoneElsesOrder() {
        call("create_support_ticket", "raj@example.com", Map.of("summary", "help", "priority", "high", "order_id", "A1001"));
        var t = tickets.listByCustomer("raj@example.com");
        assertEquals(1, t.size());
        assertNull(t.getFirst().orderId());
    }

    @Test
    void missingRequiredInputIsRejectedByTheToolNotTrusted() {
        assertThrows(IllegalArgumentException.class, () -> call("get_order", "maya@example.com", Map.of()));
    }

    @Test
    void retrievalFindsTheRightPolicySection() {
        KnowledgeBase kb = KnowledgeBase.fromClasspath();
        record Case(String q, String expectedSource) {}
        List<Case> cases = List.of(
                new Case("how many days do I have to get a refund", "returns-and-refunds"),
                new Case("is express overnight shipping available", "shipping"),
                new Case("my tent pole snapped", "warranty"),
                new Case("can you tell me about someone else's order", "account-and-privacy"),
                new Case("how do reward points work", "membership"));
        for (Case c : cases) {
            var hits = kb.search(c.q(), 3);
            assertFalse(hits.isEmpty(), c.q());
            assertTrue(hits.stream().anyMatch(h -> h.chunk().source().startsWith(c.expectedSource())),
                    c.q() + " -> " + hits.stream().map(h -> h.chunk().id()).toList());
        }
    }
}

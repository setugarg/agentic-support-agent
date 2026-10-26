package dev.setugarg.supportagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import dev.setugarg.supportagent.llm.Llm;
import dev.setugarg.supportagent.rag.KnowledgeBase;
import dev.setugarg.supportagent.store.Stores;
import dev.setugarg.supportagent.store.Stores.Order;
import dev.setugarg.supportagent.store.Stores.OrderStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** The five tools exposed to the model. Kept in one file so the whole capability surface is reviewable at a glance. */
public final class Tools {
    private Tools() {}

    public static final double AUTO_APPROVE_LIMIT_USD = 100.00;
    public static final int REFUND_WINDOW_DAYS = 30;
    private static final Pattern ORDER_ID = Pattern.compile("^[A-Z][0-9]{4}$");

    public static List<AgentTool> all(KnowledgeBase kb, Stores.OrderStore orders, Stores.TicketStore tickets) {
        return List.of(new SearchPolicies(kb), new ListMyOrders(orders), new GetOrder(orders),
                new RequestRefund(orders, tickets), new CreateTicket(orders, tickets));
    }

    /**
     * Shared authorization check. Returning "not found" for someone else's order (instead of
     * "forbidden") avoids confirming that the order id exists - no enumeration oracle.
     */
    static Optional<Order> ownedOrder(Stores.OrderStore orders, String orderId, AgentTool.Context ctx) {
        String id = orderId.toUpperCase();
        if (!ORDER_ID.matcher(id).matches()) return Optional.empty();
        return orders.get(id).filter(o -> o.customerEmail().equalsIgnoreCase(ctx.customerEmail()));
    }

    static Map<String, Object> view(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order_id", o.orderId());
        m.put("items", o.items());
        m.put("total_usd", o.totalUsd());
        m.put("status", o.status().name());
        m.put("order_date", o.orderDate().toString());
        m.put("delivered_date", o.deliveredDate() == null ? null : o.deliveredDate().toString());
        m.put("carrier", o.carrier());
        m.put("tracking_number", o.trackingNumber());
        return m;
    }

    // ------------------------------------------------------------------------------------------

    static final class SearchPolicies implements AgentTool {
        private final KnowledgeBase kb;

        SearchPolicies(KnowledgeBase kb) { this.kb = kb; }

        @Override public Llm.ToolSpec spec() {
            return new Llm.ToolSpec("search_policies",
                    "Search Summit Gear's official policy documents (returns, refunds, shipping, warranty, "
                            + "privacy, rewards membership, support hours). Use this before answering ANY policy "
                            + "question; never answer policy questions from memory. Returns the most relevant "
                            + "policy sections with their source.",
                    AgentTool.schema(Map.of("query", Map.of("type", "string",
                            "description", "Natural-language search query, e.g. 'refund window for delivered orders'")), "query"));
        }

        @Override public Result execute(JsonNode input, Context ctx) {
            String q = AgentTool.requireText(input, "query");
            var hits = kb.search(q, 3);
            if (hits.isEmpty()) return Result.ok(Map.of("results", List.of(), "note", "No matching policy found."));
            return Result.ok(Map.of("results", hits.stream().map(h -> Map.of(
                    "source", h.chunk().id(), "section", h.chunk().title(), "text", h.chunk().text(),
                    "score", Math.round(h.score() * 100) / 100.0)).toList()));
        }
    }

    static final class ListMyOrders implements AgentTool {
        private final Stores.OrderStore orders;

        ListMyOrders(Stores.OrderStore orders) { this.orders = orders; }

        @Override public Llm.ToolSpec spec() {
            return new Llm.ToolSpec("list_my_orders",
                    "List the signed-in customer's orders, newest first, with status and totals. Use when the "
                            + "customer refers to an order without giving its id.",
                    AgentTool.schema(Map.of()));
        }

        @Override public Result execute(JsonNode input, Context ctx) {
            return Result.ok(Map.of("orders", orders.listByCustomer(ctx.customerEmail()).stream().map(Tools::view).toList()));
        }
    }

    static final class GetOrder implements AgentTool {
        private final Stores.OrderStore orders;

        GetOrder(Stores.OrderStore orders) { this.orders = orders; }

        @Override public Llm.ToolSpec spec() {
            return new Llm.ToolSpec("get_order",
                    "Get details of one of the signed-in customer's orders: items, total, status, dates, "
                            + "carrier and tracking number.",
                    AgentTool.schema(Map.of("order_id", Map.of("type", "string",
                            "description", "Order id, one letter followed by 4 digits, e.g. A1001")), "order_id"));
        }

        @Override public Result execute(JsonNode input, Context ctx) {
            String id = AgentTool.requireText(input, "order_id");
            return ownedOrder(orders, id, ctx)
                    .map(o -> Result.ok(view(o)))
                    .orElseGet(() -> Result.error("Order " + id + " was not found on this customer's account."));
        }
    }

    /**
     * The only tool with a financial side effect, so every rule lives here in code:
     * ownership, status, refund window, non-refundable items, approval threshold, idempotency.
     */
    static final class RequestRefund implements AgentTool {
        private final Stores.OrderStore orders;
        private final Stores.TicketStore tickets;

        RequestRefund(Stores.OrderStore orders, Stores.TicketStore tickets) { this.orders = orders; this.tickets = tickets; }

        @Override public Llm.ToolSpec spec() {
            return new Llm.ToolSpec("request_refund",
                    "Request a FULL refund for one of the signed-in customer's delivered orders. The system "
                            + "enforces refund policy and either issues the refund (<= $100), routes it to a human "
                            + "for approval (> $100), or rejects it with a reason. Only call this after the customer "
                            + "has clearly asked for a refund for a specific order.",
                    AgentTool.schema(Map.of(
                            "order_id", Map.of("type", "string", "description", "Order id, e.g. A1001"),
                            "reason", Map.of("type", "string", "description", "Customer's reason, in their words")),
                            "order_id", "reason"));
        }

        @Override public Result execute(JsonNode input, Context ctx) {
            String id = AgentTool.requireText(input, "order_id");
            String reason = AgentTool.requireText(input, "reason");
            Optional<Order> found = ownedOrder(orders, id, ctx);
            if (found.isEmpty()) return Result.error("Order " + id + " was not found on this customer's account.");
            Order o = found.get();

            if (o.status() == OrderStatus.REFUNDED) return decision(o, "ALREADY_REFUNDED", "This order was already refunded.");
            if (o.status() == OrderStatus.REFUND_PENDING_APPROVAL) {
                return decision(o, "ALREADY_PENDING", "A refund for this order is already awaiting specialist approval.");
            }
            if (o.status() != OrderStatus.DELIVERED) {
                return decision(o, "NOT_ELIGIBLE", "Only delivered orders can be refunded. Current status: " + o.status()
                        + (o.status() == OrderStatus.PROCESSING ? ". A processing order can be cancelled instead via a support ticket." : "."));
            }
            boolean digital = o.items().stream().anyMatch(i -> i.toLowerCase().contains("gift card"));
            if (digital) return decision(o, "NOT_ELIGIBLE", "Digital gift cards are non-refundable once delivered.");

            long days = ChronoUnit.DAYS.between(o.deliveredDate(), ctx.today());
            if (days > REFUND_WINDOW_DAYS) {
                return decision(o, "NOT_ELIGIBLE", "Delivered " + days + " days ago; refunds are only available within "
                        + REFUND_WINDOW_DAYS + " days of delivery. A warranty claim may still be possible for defects.");
            }

            if (o.totalUsd() <= AUTO_APPROVE_LIMIT_USD) {
                // compare-and-set: two concurrent/retried calls cannot both refund
                if (!orders.transition(o.orderId(), OrderStatus.DELIVERED, OrderStatus.REFUNDED)) {
                    return decision(o, "ALREADY_REFUNDED", "This order's status changed; it may already be refunded.");
                }
                return decision(o, "REFUNDED", "Refund of $" + String.format("%.2f", o.totalUsd())
                        + " issued to the original payment method. It takes 5-10 business days to appear.");
            }

            if (!orders.transition(o.orderId(), OrderStatus.DELIVERED, OrderStatus.REFUND_PENDING_APPROVAL)) {
                return decision(o, "ALREADY_PENDING", "This order's status changed; a refund may already be in progress.");
            }
            var t = tickets.create(new Stores.Ticket(ticketId(), ctx.customerEmail(), o.orderId(), "normal",
                    "Refund approval needed ($" + o.totalUsd() + "): " + reason, "OPEN", Instant.now().toString()));
            return Result.ok(Map.of("order_id", o.orderId(), "decision", "PENDING_HUMAN_APPROVAL",
                    "ticket_id", t.ticketId(), "amount_usd", o.totalUsd(),
                    "message", "Refunds over $" + (int) AUTO_APPROVE_LIMIT_USD
                            + " need specialist approval. A specialist will review within 1 business day."));
        }

        private static Result decision(Order o, String decision, String message) {
            return Result.ok(Map.of("order_id", o.orderId(), "decision", decision, "message", message));
        }
    }

    static final class CreateTicket implements AgentTool {
        private final Stores.OrderStore orders;
        private final Stores.TicketStore tickets;

        CreateTicket(Stores.OrderStore orders, Stores.TicketStore tickets) { this.orders = orders; this.tickets = tickets; }

        @Override public Llm.ToolSpec spec() {
            return new Llm.ToolSpec("create_support_ticket",
                    "Escalate to a human support specialist by opening a ticket. Use for warranty claims, "
                            + "partial refunds, cancellations, lost packages, data deletion, an unhappy customer, or "
                            + "anything you cannot resolve with the other tools. Do not use it for refunds - use "
                            + "request_refund, which escalates automatically when needed.",
                    AgentTool.schema(Map.of(
                            "summary", Map.of("type", "string", "description", "One or two sentences describing the issue for the specialist"),
                            "priority", Map.of("type", "string", "enum", List.of("normal", "high"),
                                    "description", "high = safety issue, lost package, or repeat contact"),
                            "order_id", Map.of("type", "string", "description", "Related order id, if any")),
                            "summary", "priority"));
        }

        @Override public Result execute(JsonNode input, Context ctx) {
            String summary = AgentTool.requireText(input, "summary");
            String priority = AgentTool.requireText(input, "priority").toLowerCase();
            if (!priority.equals("normal") && !priority.equals("high")) priority = "normal";
            String orderId = input.hasNonNull("order_id") ? input.get("order_id").asText().toUpperCase() : null;
            if (orderId != null && (orderId.isBlank() || ownedOrder(orders, orderId, ctx).isEmpty())) {
                orderId = null; // never attach someone else's order to a ticket
            }
            if (summary.length() > 1000) summary = summary.substring(0, 1000);
            var t = tickets.create(new Stores.Ticket(ticketId(), ctx.customerEmail(), orderId, priority, summary, "OPEN",
                    Instant.now().toString()));
            return Result.ok(Map.of("ticket_id", t.ticketId(), "priority", t.priority(),
                    "message", priority.equals("high") ? "A specialist will respond within 4 business hours."
                            : "A specialist will respond within 1 business day."));
        }
    }

    static String ticketId() {
        return "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}

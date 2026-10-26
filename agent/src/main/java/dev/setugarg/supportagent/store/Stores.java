package dev.setugarg.supportagent.store;

import dev.setugarg.supportagent.llm.Llm;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Domain records and storage interfaces. Implementations: in-memory (local/evals) and DynamoDB (prod). */
public final class Stores {
    private Stores() {}

    public enum OrderStatus { PROCESSING, SHIPPED, DELIVERED, REFUNDED, REFUND_PENDING_APPROVAL, CANCELLED }

    public record Order(
            String orderId,
            String customerEmail,
            List<String> items,
            double totalUsd,
            OrderStatus status,
            LocalDate orderDate,
            LocalDate deliveredDate, // null until delivered
            String carrier,
            String trackingNumber) {

        public Order withStatus(OrderStatus s) {
            return new Order(orderId, customerEmail, items, totalUsd, s, orderDate, deliveredDate, carrier, trackingNumber);
        }
    }

    public record Ticket(String ticketId, String customerEmail, String orderId, String priority,
                         String summary, String status, String createdAt) {}

    public interface OrderStore {
        Optional<Order> get(String orderId);

        List<Order> listByCustomer(String customerEmail);

        /**
         * Compare-and-set status transition. Returns false if the current status is not
         * {@code expected} - this is what makes refunds idempotent under retries and races.
         */
        boolean transition(String orderId, OrderStatus expected, OrderStatus next);

        void put(Order order);
    }

    public interface TicketStore {
        Ticket create(Ticket t);

        List<Ticket> listByCustomer(String customerEmail);
    }

    /** Short-term conversation memory, keyed by session id. */
    public interface SessionStore {
        List<Llm.Message> load(String sessionId);

        void save(String sessionId, List<Llm.Message> history);
    }

    /** Global spend circuit-breaker: atomic per-day request counter. */
    public interface UsageStore {
        /** Increments today's counter and returns the new value. */
        long incrementAndGet(String day);
    }
}

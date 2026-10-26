package dev.setugarg.supportagent.store;

import dev.setugarg.supportagent.store.Stores.Order;
import dev.setugarg.supportagent.store.Stores.OrderStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * Demo customers and orders. Dates are relative to "today" so refund-window rules stay testable
 * no matter when you run the demo or the evals.
 */
public final class SeedData {
    private SeedData() {}

    public record Customer(String email, String name) {}

    public static final List<Customer> CUSTOMERS = List.of(
            new Customer("maya@example.com", "Maya Chen"),
            new Customer("raj@example.com", "Raj Patel"),
            new Customer("lena@example.com", "Lena Fischer"));

    public static List<Order> orders(LocalDate today) {
        return List.of(
                // Maya: a recent delivered order (refund-eligible, under auto-approve limit),
                // an in-transit order, and an expensive delivered order (needs human approval).
                new Order("A1001", "maya@example.com", List.of("Trail running shoes", "Wool socks (3-pack)"),
                        82.50, OrderStatus.DELIVERED, today.minusDays(12), today.minusDays(8), "UPS", "1Z999AA10123456784"),
                new Order("A1002", "maya@example.com", List.of("Insulated water bottle"),
                        34.00, OrderStatus.SHIPPED, today.minusDays(3), null, "USPS", "9400111899223856942211"),
                new Order("A1003", "maya@example.com", List.of("Carbon trekking poles", "Ultralight tent"),
                        489.00, OrderStatus.DELIVERED, today.minusDays(20), today.minusDays(15), "FedEx", "612999AA10123"),

                // Raj: an old delivered order (outside 30-day window) and a processing order.
                new Order("B2001", "raj@example.com", List.of("Rain jacket"),
                        129.00, OrderStatus.DELIVERED, today.minusDays(70), today.minusDays(64), "UPS", "1Z999AA10999999999"),
                new Order("B2002", "raj@example.com", List.of("Headlamp", "Spare batteries"),
                        48.75, OrderStatus.PROCESSING, today.minusDays(1), null, null, null),

                // Lena: already-refunded order (idempotency check) and a delivered gift card (non-refundable item).
                new Order("C3001", "lena@example.com", List.of("Camping stove"),
                        64.99, OrderStatus.REFUNDED, today.minusDays(25), today.minusDays(21), "USPS", "9400111899223000000001"),
                new Order("C3002", "lena@example.com", List.of("Digital gift card $50"),
                        50.00, OrderStatus.DELIVERED, today.minusDays(5), today.minusDays(5), "EMAIL", null));
    }
}

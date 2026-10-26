package dev.setugarg.supportagent.store;

import dev.setugarg.supportagent.llm.Llm;
import dev.setugarg.supportagent.store.Stores.Order;
import dev.setugarg.supportagent.store.Stores.OrderStatus;
import dev.setugarg.supportagent.store.Stores.Ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe in-memory stores for local development, unit tests and isolated eval runs. */
public final class InMemoryStores {
    private InMemoryStores() {}

    public static final class Orders implements Stores.OrderStore {
        private final Map<String, Order> orders = new ConcurrentHashMap<>();

        public Orders(List<Order> seed) {
            seed.forEach(this::put);
        }

        @Override public Optional<Order> get(String id) { return Optional.ofNullable(orders.get(id)); }

        @Override public List<Order> listByCustomer(String email) {
            return orders.values().stream().filter(o -> o.customerEmail().equalsIgnoreCase(email))
                    .sorted((a, b) -> b.orderDate().compareTo(a.orderDate())).toList();
        }

        @Override public synchronized boolean transition(String id, OrderStatus expected, OrderStatus next) {
            Order o = orders.get(id);
            if (o == null || o.status() != expected) return false;
            orders.put(id, o.withStatus(next));
            return true;
        }

        @Override public void put(Order o) { orders.put(o.orderId(), o); }
    }

    public static final class Tickets implements Stores.TicketStore {
        private final List<Ticket> tickets = new ArrayList<>();

        @Override public synchronized Ticket create(Ticket t) { tickets.add(t); return t; }

        @Override public synchronized List<Ticket> listByCustomer(String email) {
            return tickets.stream().filter(t -> t.customerEmail().equalsIgnoreCase(email)).toList();
        }
    }

    public static final class Sessions implements Stores.SessionStore {
        private final Map<String, List<Llm.Message>> sessions = new ConcurrentHashMap<>();

        @Override public List<Llm.Message> load(String id) { return sessions.getOrDefault(id, List.of()); }

        @Override public void save(String id, List<Llm.Message> history) { sessions.put(id, List.copyOf(history)); }
    }

    public static final class Usage implements Stores.UsageStore {
        private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

        @Override public long incrementAndGet(String day) {
            return counts.computeIfAbsent(day, d -> new AtomicLong()).incrementAndGet();
        }
    }
}

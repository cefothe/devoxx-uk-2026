package com.devoxx.lowlatency.examples.memory;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * BACKS SLIDE: "The pool's evil twins"
 * PATTERN: Flyweight — share intrinsic (immutable) state across many logical objects
 * MECHANISM: 100K orders reference ~10 distinct currencies. Without interning, a
 *            protocol parser creates a fresh String per message even for the same
 *            currency code. The flyweight registry deduplicate them to one canonical
 *            instance per code, reducing live String instances by ~99,990.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.memory.FlyweightExample
 */
public final class FlyweightExample {

    // -----------------------------------------------------------------------
    // The flyweight registry: maps a currency code to its single canonical String.
    // computeIfAbsent returns the existing instance on every subsequent call,
    // so GC sees one String per unique code rather than one per message.
    // -----------------------------------------------------------------------
    private static final class CurrencyRegistry {
        private final Map<String, String> pool = new HashMap<>();

        String intern(String code) {
            return pool.computeIfAbsent(code, k -> k);
        }

        int size() { return pool.size(); }
    }

    // -----------------------------------------------------------------------
    // Order record — currency is the shared intrinsic state (flyweight).
    // orderId, price, quantity are the extrinsic state unique to each order.
    // -----------------------------------------------------------------------
    record Order(long orderId, long price, int quantity, String currency) {}

    // Ten real FX currency codes; representative of a live feed.
    private static final String[] CODES = {
        "USD", "EUR", "GBP", "JPY", "CHF", "AUD", "CAD", "NZD", "HKD", "SGD"
    };

    private static final int ORDER_COUNT = 100_000;

    public static void main(String[] args) {
        CurrencyRegistry registry = new CurrencyRegistry();

        // -----------------------------------------------------------------------
        // Simulate protocol parsing: each incoming message carries the currency
        // as a freshly constructed String (new String(...) forces a distinct heap
        // object even if the character sequence is identical to a previous one).
        // Without the registry, every order would hold a separate String instance.
        // -----------------------------------------------------------------------
        Order[] orders = new Order[ORDER_COUNT];
        for (int i = 0; i < ORDER_COUNT; i++) {
            // new String() is intentional here: it represents what a byte-buffer
            // decoder produces — a fresh allocation per message, not a literal.
            String rawCurrency = new String(CODES[i % CODES.length]);
            String currency    = registry.intern(rawCurrency);
            orders[i] = new Order(i, 10_000L + i % 5000, i & 0x3FF, currency);
        }

        // -----------------------------------------------------------------------
        // Verify: count distinct String instances in the currency field.
        // IdentityHashMap uses == (reference equality), not .equals().
        // -----------------------------------------------------------------------
        IdentityHashMap<String, Object> flyweights = new IdentityHashMap<>();
        for (Order o : orders) {
            flyweights.put(o.currency(), null);
        }

        int distinctInstances  = flyweights.size();
        int allocationsSaved   = ORDER_COUNT - distinctInstances;

        System.out.println("--- Flyweight pattern ---");
        System.out.printf("Total orders              : %,d%n", ORDER_COUNT);
        System.out.printf("Distinct currency codes   : %d (%s)%n",
                registry.size(), String.join(", ", CODES));
        System.out.printf("Distinct String instances : %d (one canonical instance per code)%n",
                distinctInstances);
        System.out.printf("Allocations saved         : %,d String instances%n", allocationsSaved);
        System.out.println("Intrinsic state (currency string) is shared.");
        System.out.println("Extrinsic state (orderId, price, qty) remains per-order.");
        System.out.println("Same pattern applies to instrument symbols, account types, venue codes.");
    }
}

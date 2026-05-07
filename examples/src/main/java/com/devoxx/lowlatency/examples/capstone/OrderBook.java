package com.devoxx.lowlatency.examples.capstone;

import org.agrona.collections.Long2ObjectHashMap;

/**
 * BACKS SLIDE: "Production architecture — exchange-core style"
 * PATTERN: Truth #2 — per-shard, single-threaded matching engine
 * MECHANISM: Owned by exactly one thread, so no locks, no CAS, no fences.
 *            Price levels are flat arrays; resting orders are intrusive
 *            linked-list nodes drawn from a pre-allocated pool. match()
 *            never calls `new` once the pool is warmed.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.capstone.MiniExchange
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/concurrency/SingleThreadedShardVsContendedBenchmark.java
 *
 * --- TOY-SCALE NOTES (read these before judging the design) ---
 * This is a *demonstration* of the shard-per-thread + zero-allocation matching
 * pattern. It is not a production matching engine. Specifically:
 *   - No cancels, no replaces, no modify. Only new orders.
 *   - No fees, no risk checks, no self-trade prevention.
 *   - FIFO within a price level (insertion order). No pro-rata, no time-weighted.
 *   - Price ticks bounded to [PRICE_BASE, PRICE_BASE + PRICE_LEVELS) so each level
 *     fits in a flat array slot. Real engines use a balanced tree or a sparse map.
 *   - 4 symbols pre-registered. A real exchange handles thousands.
 *   - Production reference: github.com/exchange-core/exchange-core.
 */
public final class OrderBook {

    private static final int PRICE_BASE      = 100;
    private static final int PRICE_LEVELS    = 128;     // power of two; covers ticks 100..227
    private static final int MAX_LIVE_ORDERS = 65_536;  // bound the pool; slide 22's "4x peak" rule generalised
    private static final int SYMBOL_HINT     = 16;

    /**
     * Intrusive linked-list node. Carries the resting-order state and is also
     * the pool element. Single-owner, so no volatile / no fences required.
     */
    static final class Order {
        long  orderId;
        long  quantity;
        Order next;
    }

    /**
     * Per-symbol ladder. Two sides, each a flat array of FIFO heads/tails
     * indexed by (priceTicks - PRICE_BASE). Track best-bid / best-ask indices
     * so match() doesn't scan every price level on every event.
     */
    static final class SymbolBook {
        final Order[] bidsHead = new Order[PRICE_LEVELS];
        final Order[] bidsTail = new Order[PRICE_LEVELS];
        final Order[] asksHead = new Order[PRICE_LEVELS];
        final Order[] asksTail = new Order[PRICE_LEVELS];
        int bestBidIdx = -1;             // highest bid level with resting volume; -1 = empty
        int bestAskIdx = PRICE_LEVELS;   // lowest ask level with resting volume; PRICE_LEVELS = empty
    }

    private final Long2ObjectHashMap<SymbolBook> books = new Long2ObjectHashMap<>(SYMBOL_HINT, 0.65f);
    private final Order[] pool = new Order[MAX_LIVE_ORDERS];
    private int  poolTop;
    private long fills;
    private long resting;

    public OrderBook() {
        for (int i = 0; i < MAX_LIVE_ORDERS; i++) {
            pool[i] = new Order();
        }
        poolTop = MAX_LIVE_ORDERS;
        // Pre-register the demo's symbol set up front so match() never has to
        // computeIfAbsent (which would allocate a lambda capture on the hot path).
        for (long s = 0; s < 4; s++) {
            books.put(s, new SymbolBook());
        }
    }

    public long fills()   { return fills; }
    public long resting() { return resting; }

    /**
     * Process one incoming order. May fill against the opposite side, rest the
     * remainder, or both. No allocation in steady state; nodes come from {@link #pool}.
     */
    public void match(OrderEvent e) {
        SymbolBook sb = books.get(e.symbolId);
        if (sb == null) return; // unknown symbol — drop. A real engine would reject.

        long remaining = e.quantity;
        if (e.side == 0) {
            // BUY: walk asks from cheapest upward, take while ask price <= bid price.
            while (remaining > 0 && sb.bestAskIdx < PRICE_LEVELS) {
                long askTicks = PRICE_BASE + sb.bestAskIdx;
                if (askTicks > e.priceTicks) break;
                remaining = drainLevel(sb.asksHead, sb.asksTail, sb.bestAskIdx, remaining);
                if (sb.asksHead[sb.bestAskIdx] == null) {
                    int i = sb.bestAskIdx + 1;
                    while (i < PRICE_LEVELS && sb.asksHead[i] == null) i++;
                    sb.bestAskIdx = i;
                }
            }
            if (remaining > 0) restOnSide(sb.bidsHead, sb.bidsTail, e, remaining, true, sb);
        } else {
            // SELL: walk bids from highest downward, take while bid price >= ask price.
            while (remaining > 0 && sb.bestBidIdx >= 0) {
                long bidTicks = PRICE_BASE + sb.bestBidIdx;
                if (bidTicks < e.priceTicks) break;
                remaining = drainLevel(sb.bidsHead, sb.bidsTail, sb.bestBidIdx, remaining);
                if (sb.bidsHead[sb.bestBidIdx] == null) {
                    int i = sb.bestBidIdx - 1;
                    while (i >= 0 && sb.bidsHead[i] == null) i--;
                    sb.bestBidIdx = i;
                }
            }
            if (remaining > 0) restOnSide(sb.asksHead, sb.asksTail, e, remaining, false, sb);
        }
    }

    /**
     * Drain the FIFO at one price level against an incoming quantity. Returns
     * what the incoming order has left after the level is exhausted or the
     * incoming side is filled. Resting orders that go to zero are returned to
     * the pool; resting orders partially filled stay at the head with reduced qty.
     */
    private long drainLevel(Order[] head, Order[] tail, int idx, long remaining) {
        Order o = head[idx];
        while (o != null && remaining > 0) {
            long take = Math.min(remaining, o.quantity);
            o.quantity -= take;
            remaining  -= take;
            fills++;
            if (o.quantity == 0L) {
                head[idx] = o.next;
                if (head[idx] == null) tail[idx] = null;
                Order toFree = o;
                o = head[idx];
                toFree.next = null;
                pool[poolTop++] = toFree;
            } else {
                // partial fill on the resting side; remaining must be 0 here, so loop exits next check
                break;
            }
        }
        return remaining;
    }

    /** Rest the remaining quantity at the incoming order's limit price. */
    private void restOnSide(Order[] head, Order[] tail, OrderEvent e, long remaining,
                            boolean buySide, SymbolBook sb) {
        int idx = (int) (e.priceTicks - PRICE_BASE);
        if (idx < 0 || idx >= PRICE_LEVELS || poolTop == 0) {
            // Outside the demo's price window or pool exhausted — drop. A real
            // engine rejects with a specific reason code.
            return;
        }
        Order o = pool[--poolTop];
        o.orderId  = e.orderId;
        o.quantity = remaining;
        o.next     = null;
        if (tail[idx] != null) {
            tail[idx].next = o;
        } else {
            head[idx] = o;
        }
        tail[idx] = o;
        if (buySide) {
            if (idx > sb.bestBidIdx) sb.bestBidIdx = idx;
        } else {
            if (idx < sb.bestAskIdx) sb.bestAskIdx = idx;
        }
        resting++;
    }
}

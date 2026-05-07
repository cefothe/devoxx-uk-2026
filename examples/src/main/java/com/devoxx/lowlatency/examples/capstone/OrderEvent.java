package com.devoxx.lowlatency.examples.capstone;

/**
 * BACKS SLIDE: "Production architecture — exchange-core style"
 * PATTERN: Truth #1 (off-heap-shaped layout) + Truth #3 (pool-friendly mutable carrier)
 * MECHANISM: All fields are primitives; the Disruptor allocates 1024 of these once
 *            at startup and overwrites them in place forever. Producer rewrites the
 *            slot, consumer reads it, and the GC sees nothing change in steady state.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.capstone.MiniExchange
 * SEE ALSO: examples/src/main/java/com/devoxx/lowlatency/examples/memory/OffHeapMessageExample.java
 */
public final class OrderEvent {

    // Fixed-point price in ticks. Never use double on the hot path: double math
    // can deopt under JIT speculation, NaN/Inf paths add branches, and equality
    // comparisons on doubles are a debugging nightmare. One long, one truth.
    public long orderId;
    public long userId;
    public long symbolId;
    public long priceTicks;
    public long quantity;
    public byte side; // 0 = buy, 1 = sell

    /**
     * Reset every field. Called by the shard before returning the event to its
     * free pool so the next router writer sees a clean slot. No allocation; the
     * JIT inlines this and the assembly is six writes.
     */
    public void clear() {
        orderId    = 0L;
        userId     = 0L;
        symbolId   = 0L;
        priceTicks = 0L;
        quantity   = 0L;
        side       = (byte) 0;
    }
}

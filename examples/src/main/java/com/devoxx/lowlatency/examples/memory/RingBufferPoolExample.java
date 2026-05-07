package com.devoxx.lowlatency.examples.memory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * BACKS SLIDE: "Ring-buffer object pool"
 * PATTERN: Fixed-size, pre-allocated, cursor-driven ring-buffer object pool
 * MECHANISM: Objects are allocated once at startup and reused circularly.
 *            Bitwise AND replaces % for fast power-of-2 modulo.
 *            No release method — the cursor overwrites slots whose in-flight
 *            objects have completed long before the cursor laps.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.memory.RingBufferPoolExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/memory/ObjectPoolingBenchmark.java
 */
public final class RingBufferPoolExample {

    // -------------------------------------------------------------------------
    // RingBufferObjectPool — verbatim from slide 22 (15 lines, power-of-2 size).
    // -------------------------------------------------------------------------

    public static final class RingBufferObjectPool<T> {
        private final T[]       pool;
        private final long      mask;
        private final AtomicLong cursor = new AtomicLong();

        @SuppressWarnings("unchecked")
        public RingBufferObjectPool(Supplier<T> factory, int size) {
            if ((size & (size - 1)) != 0) throw new IllegalArgumentException("size must be a power of 2");
            this.pool = (T[]) new Object[size];
            this.mask = size - 1;
            for (int i = 0; i < size; i++) pool[i] = factory.get(); // pre-allocate
        }

        public T acquire() {
            return pool[(int) (cursor.getAndIncrement() & mask)]; // bitwise modulo
        }
        // No release. Objects rotate. Whatever is at index N gets reused on cycle N+size.
    }

    // -------------------------------------------------------------------------
    // Lightweight order — realistic hot-path payload.
    // -------------------------------------------------------------------------

    static final class Order {
        long orderId;
        long price;
        int  quantity;
        byte side;  // 0=buy, 1=sell
    }

    // Pool sized 4× peak burst — war-story rule from the slide:
    // "We sized for average. First time markets got volatile, we ran out."
    private static final int POOL_SIZE  = 1024;
    private static final int ITERATIONS = 1_000_000;

    public static void main(String[] args) {
        // Pre-allocate the entire pool at startup. After this line the heap is stable.
        RingBufferObjectPool<Order> pool = new RingBufferObjectPool<>(Order::new, POOL_SIZE);

        // GC measurement starts AFTER pool warm-up so the pre-allocation cost is excluded.
        long gcBefore = gcCount();
        long start    = System.nanoTime();
        long checksum = 0L;

        // Acquire 1M objects; the cursor wraps every POOL_SIZE acquisitions.
        // Objects from previous cycles are overwritten — no release, no GC.
        for (int i = 0; i < ITERATIONS; i++) {
            Order o = pool.acquire();
            o.orderId  = i;
            o.price    = 10_000L + i % 5000;
            o.quantity = i & 0x1FF;
            o.side     = (byte) (i & 1);
            checksum  += o.orderId + o.price;
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long gcPauses  = gcCount() - gcBefore;
        long cursor    = pool.cursor.get();
        long wraps     = cursor / POOL_SIZE;

        System.out.println("--- Ring-buffer object pool ---");
        System.out.printf("Pool size         : %,d objects (pre-allocated once at startup)%n", POOL_SIZE);
        System.out.printf("Acquisitions      : %,d%n", ITERATIONS);
        System.out.printf("Cursor wraps      : %,d (cursor=%,d, pool lapped %,d times)%n",
                wraps, cursor, wraps);
        System.out.printf("Allocations       : %,d at startup, 0 in steady state%n", POOL_SIZE);
        System.out.printf("GC pauses         : %d (zero — no allocation pressure after warm-up)%n", gcPauses);
        System.out.printf("Elapsed           : %d ms%n", elapsedMs);
        System.out.printf("Checksum          : %d%n", checksum);
        System.out.println("Size rule: 3-4x peak burst. Undersize once and you learn fast.");
    }

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            if (c >= 0) total += c;
        }
        return total;
    }
}

package com.devoxx.lowlatency.examples.memory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BACKS SLIDE: "The pool's evil twins"
 * PATTERN: Per-thread instance reuse via ThreadLocal
 * MECHANISM: SimpleDateFormat is not thread-safe. The wrong fix is synchronized
 *            (serialises all threads). The right fix is ThreadLocal: each thread
 *            owns its own instance, no contention, no locking, full parallelism.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.memory.ThreadLocalExample
 */
public final class ThreadLocalExample {

    private static final int TIMESTAMPS_PER_THREAD = 100_000;

    // One SimpleDateFormat per thread, created on first access for that thread.
    // The formatter is NOT shared — it lives in thread-local storage and is
    // never seen by any other thread.
    private static final ThreadLocal<SimpleDateFormat> FORMATTER =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS"));

    // Shared bookkeeping — identity hash codes of every SDF instance used.
    // System.identityHashCode gives a per-object identifier stable for the JVM lifetime.
    private static final Set<Integer> instanceIds = ConcurrentHashMap.newKeySet();

    // Shared counters used to verify correctness.
    private static final AtomicLong totalFormatted = new AtomicLong();

    public static void main(String[] args) throws InterruptedException {
        long baseMs = System.currentTimeMillis();

        Thread t1 = new Thread(() -> formatTimestamps(baseMs, "T1"), "formatter-1");
        Thread t2 = new Thread(() -> formatTimestamps(baseMs, "T2"), "formatter-2");

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        System.out.println("--- ThreadLocal SimpleDateFormat ---");
        System.out.printf("Total formatted   : %,d timestamps%n", totalFormatted.get());
        System.out.printf("Distinct SDF instances : %d (one per thread — no contention, no shared state)%n",
                instanceIds.size());
        System.out.println("Wrong fix: synchronized(formatter) { ... } — serialises all threads.");
        System.out.println("Right fix: ThreadLocal          — each thread owns its own instance.");
        System.out.println("Same pattern applies to NumberFormat, DateTimeFormatter (pre-20), Cipher, etc.");
    }

    private static void formatTimestamps(long baseMs, String label) {
        SimpleDateFormat sdf = FORMATTER.get();
        // Record this thread's SDF identity once, before the hot loop.
        instanceIds.add(System.identityHashCode(sdf));

        long count = 0;
        for (int i = 0; i < TIMESTAMPS_PER_THREAD; i++) {
            // Each format call mutates internal SDF state — this is why it is not thread-safe.
            // Here it is safe because no other thread touches this instance.
            String s = sdf.format(new Date(baseMs + i));
            // Consume s to prevent the JIT from eliminating the call.
            count += s.length();
        }
        totalFormatted.addAndGet(TIMESTAMPS_PER_THREAD);
        // Touch count so the JIT sees the accumulator as live.
        if (count <= 0) throw new IllegalStateException("unexpected");
    }
}

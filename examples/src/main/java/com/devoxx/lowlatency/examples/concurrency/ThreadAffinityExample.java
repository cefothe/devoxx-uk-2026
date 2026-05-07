package com.devoxx.lowlatency.examples.concurrency;

import com.devoxx.lowlatency.examples.common.Platform;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * BACKS SLIDE: "Thread affinity — pin the thread to the core"
 * PATTERN: Bind each hot thread to a dedicated CPU core for the duration of its work.
 * MECHANISM: sched_setaffinity(2) prevents the OS scheduler from migrating the thread.
 *            Without pinning, migration flushes L1/L2 cache (100+ ns per miss) and the TLB.
 *            With pinning, the 8 KB per-thread buffer stays resident in L1 for every iteration.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.concurrency.ThreadAffinityExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/concurrency/ThreadAffinityBenchmark.java
 *
 * <p>LINUX-ONLY: On macOS, Platform.pinCurrentThreadToCpu() prints a one-line skip message and
 * proceeds without pinning. The hot-loop still runs and produces timing output; the pinning
 * benefit (elimination of scheduler-migration spikes) is only observable on Linux.
 * Use isolcpus=1-N in the kernel boot params to exclude the pinned cores from the scheduler
 * entirely — this removes the last source of latency spikes for the sharded hot path.
 */
public final class ThreadAffinityExample {

    // Buffer sized to 1024 longs (8 KB) — fits comfortably in a 32 KB L1 data cache.
    // Power-of-2 length enables the bitwise AND index wrap: idx = i & BUFFER_MASK.
    private static final int  BUFFER_SIZE = 1024;
    private static final int  BUFFER_MASK = BUFFER_SIZE - 1;
    private static final long ITERATIONS  = 100_000_000L; // 100M iterations per thread

    // Stride-8 sink array: thread 0 writes SINKS[0], thread 1 writes SINKS[8].
    // 8 * 8 = 64 bytes between writes — each on its own cache line, no false sharing.
    private static final long[] SINKS = new long[16];

    /**
     * Hot loop: touches every slot of a 1024-element buffer in a rotating pattern.
     * The dependency chain (acc feeds next iteration's multiplier) prevents the JIT from
     * reordering or eliminating iterations. The buffer stays hot in L1 when the thread
     * is pinned; a migration would cold-fault every line on the next core.
     */
    static void hotLoop(long[] buffer, long iterations, int threadIndex) {
        long acc = 1L;
        for (long i = 0; i < iterations; i++) {
            int idx = (int)(i & BUFFER_MASK);
            // LCG step: simple dependency chain that visits every buffer slot
            acc = buffer[idx] * 6_364_136_223_846_793_005L + acc;
            buffer[idx] = acc;
        }
        // Stride-8 write prevents false sharing while forcing the result to be live.
        SINKS[threadIndex * 8] = acc;
    }

    public static void main(String[] args) throws InterruptedException {
        // Per-thread buffers allocated at startup — no allocation during the timed run.
        long[][] buffers = { new long[BUFFER_SIZE], new long[BUFFER_SIZE] };
        for (long[] buf : buffers) {
            Arrays.fill(buf, 1L); // pre-initialize to avoid deferred page-fault during timing
        }

        long[]       elapsed  = new long[2];
        CountDownLatch ready  = new CountDownLatch(2);
        CountDownLatch go     = new CountDownLatch(1);
        Thread[]      threads = new Thread[2];

        for (int t = 0; t < 2; t++) {
            final int threadId = t;
            final int cpu      = t;         // pin thread 0 to CPU 0, thread 1 to CPU 1
            final long[] buf   = buffers[t];

            threads[t] = new Thread(() -> {
                // Platform.pinCurrentThreadToCpu() either calls sched_setaffinity (Linux)
                // or prints a one-line skip notice and continues (macOS).
                Platform.pinCurrentThreadToCpu(cpu);

                ready.countDown();
                try { go.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                long t0 = System.nanoTime();
                hotLoop(buf, ITERATIONS, threadId);
                elapsed[threadId] = System.nanoTime() - t0;
            }, "affinity-thread-" + t);

            threads[t].start();
        }

        ready.await();   // both threads have called pinCurrentThreadToCpu and are ready

        long wallStart = System.nanoTime();
        go.countDown();  // release both threads simultaneously
        for (Thread t : threads) { t.join(); }
        long wallTotal = System.nanoTime() - wallStart;

        long totalWork = ITERATIONS * 2; // one loop of ITERATIONS per thread

        System.out.println();
        System.out.println("Thread affinity  --  \"Thread affinity — pin the thread to the core\"");
        System.out.printf ("  thread-0 (cpu 0)  : %,d ms%n", elapsed[0] / 1_000_000);
        System.out.printf ("  thread-1 (cpu 1)  : %,d ms%n", elapsed[1] / 1_000_000);
        System.out.printf ("  wall clock        : %,d ms (both threads ran in parallel)%n",
                           wallTotal / 1_000_000);
        System.out.printf ("  total work        : %,d iterations across 2 threads%n", totalWork);
        System.out.printf ("  buffer per thread : %d longs (%d KB, fits in L1 cache)%n",
                           BUFFER_SIZE, BUFFER_SIZE * Long.BYTES / 1024);
        System.out.println("  affinity benefit  : scheduler cannot migrate threads between cores");
        System.out.println("                      L1/L2 buffer stays hot for every iteration");
        if (Platform.isLinux()) {
            System.out.println("  [linux] threads pinned via sched_setaffinity(2)");
        } else {
            System.out.println("  [non-linux] pinning skipped  --  run on Linux for measurable tail improvement");
        }
    }
}

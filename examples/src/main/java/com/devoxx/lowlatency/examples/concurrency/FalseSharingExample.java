package com.devoxx.lowlatency.examples.concurrency;

import com.devoxx.lowlatency.examples.common.Platform;

import java.util.concurrent.CountDownLatch;

/**
 * BACKS SLIDE: "False sharing — the silent killer"
 * PATTERN: Pad hot fields to separate cache lines so concurrent writers stop invalidating each other.
 * MECHANISM: The MESI protocol maintains coherency at 64-byte cache-line granularity. Two
 *            volatile longs on the same line force every write on Core A to invalidate Core B's
 *            copy — even though the two longs are logically independent. 7 padding longs (56 bytes)
 *            push the second field onto its own line, eliminating the bounce.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.concurrency.FalseSharingExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/concurrency/FalseSharingBenchmark.java
 *
 * <p>PRODUCTION REMEDY — @jdk.internal.vm.annotation.Contended:
 * The cleanest fix is to annotate each hot field with @Contended. This instructs the JVM to
 * insert 128 bytes of padding automatically. To enable it, add to examples/pom.xml compiler config:
 * <pre>
 *   &lt;compilerArgs&gt;
 *     &lt;arg&gt;--add-exports&lt;/arg&gt;
 *     &lt;arg&gt;java.base/jdk.internal.vm.annotation=ALL-UNNAMED&lt;/arg&gt;
 *   &lt;/compilerArgs&gt;
 * </pre>
 * Then replace PaddedHolder with:
 * <pre>
 *   static class ContendedHolder {
 *       @jdk.internal.vm.annotation.Contended public volatile long l1 = 0L;
 *       @jdk.internal.vm.annotation.Contended public volatile long l2 = 0L;
 *   }
 * </pre>
 * And run with: -Dexec.vmArgs="-XX:-RestrictContended"
 *
 * <p>NOTE ON RESULTS: The timing difference is most pronounced on x86/x86-64 Linux with two
 * physical cores. On Apple Silicon (unified memory) the ratio is smaller because the memory
 * subsystem handles coherency differently. The code runs correctly on macOS; the numbers
 * that land on stage come from the EPYC reference box.
 */
public final class FalseSharingExample {

    // 200M iterations per thread: enough for the difference to be visible in wall-clock time
    // even on a fast machine. Fewer iterations produce sub-millisecond numbers that are noisy.
    private static final long ITERATIONS = 200_000_000L;

    // -------------------------------------------------------------------
    // UNPADDED: l1 and l2 are adjacent in the object layout.
    // Object header (~12 bytes) + l1 (8 bytes) + l2 (8 bytes) = 28 bytes total.
    // Both fields fit on a single 64-byte cache line -> false sharing guaranteed.
    // -------------------------------------------------------------------
    static final class UnpaddedHolder {
        volatile long l1 = 0L;
        volatile long l2 = 0L;
    }

    // -------------------------------------------------------------------
    // PADDED: 7 long fields (56 bytes) between l1 and l2.
    // header(~12) + l1(8) + padding(56) = 76 bytes before l2 -> separate cache lines.
    // Thread A and Thread B now write to independent lines: no inter-core invalidation.
    //
    // Fields p1..p7 are package-private (not private) to prevent the compiler from
    // eliminating them as dead writes.
    // -------------------------------------------------------------------
    static final class PaddedHolder {
        volatile long l1 = 0L;
        long p1, p2, p3, p4, p5, p6, p7; // 7 * 8 = 56 bytes of cache-line padding
        volatile long l2 = 0L;
    }

    @FunctionalInterface
    interface LongConsumer { void accept(long n); }

    /**
     * Time how long two concurrent writers take to complete N iterations each.
     * Both threads are staged behind a latch so the clock starts when both are ready.
     */
    static long timeRun(LongConsumer writerA, LongConsumer writerB, long n)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go    = new CountDownLatch(1);

        Thread ta = new Thread(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            writerA.accept(n);
        }, "writer-l1");

        Thread tb = new Thread(() -> {
            ready.countDown();
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            writerB.accept(n);
        }, "writer-l2");

        ta.start();
        tb.start();
        ready.await();         // both threads are running and waiting at the gate

        long t0 = System.nanoTime();
        go.countDown();        // release both simultaneously
        ta.join();
        tb.join();
        return System.nanoTime() - t0;
    }

    public static void main(String[] args) throws InterruptedException {
        if (Platform.isMac()) {
            System.out.println("[platform] Apple Silicon uses unified memory — false sharing effect");
            System.out.println("           is visible but the ratio is smaller than on x86 Linux.");
            System.out.println("           Numbers that land on stage come from the EPYC reference box.");
        }

        // --- Run 1: unpadded baseline ---
        UnpaddedHolder unpadded = new UnpaddedHolder();
        long unpaddedNs = timeRun(
            n -> { for (long i = 0; i < n; i++) unpadded.l1 = i; },
            n -> { for (long i = 0; i < n; i++) unpadded.l2 = i; },
            ITERATIONS
        );

        // --- Run 2: padded (7-long gap) ---
        PaddedHolder padded = new PaddedHolder();
        long paddedNs = timeRun(
            n -> { for (long i = 0; i < n; i++) padded.l1 = i; },
            n -> { for (long i = 0; i < n; i++) padded.l2 = i; },
            ITERATIONS
        );

        // Avoid DCE: force both final values to be visible
        if (unpadded.l1 + unpadded.l2 + padded.l1 + padded.l2 == Long.MIN_VALUE) {
            throw new RuntimeException("unreachable: prevents dead-write elimination");
        }

        double ratio = (double) unpaddedNs / paddedNs;

        System.out.println();
        System.out.println("False sharing  --  \"False sharing — the silent killer\"");
        System.out.printf ("  iterations per thread : %,d%n", ITERATIONS);
        System.out.printf ("  unpadded (false share): %,d ms%n", unpaddedNs / 1_000_000);
        System.out.printf ("  padded   (7-long gap) : %,d ms%n", paddedNs   / 1_000_000);
        System.out.printf ("  speedup from padding  : %.1fx%n", ratio);
        System.out.println("  mechanism             : 7 * 8 = 56 pad bytes separate l1/l2 onto distinct cache lines");
        System.out.println("  production fix        : @jdk.internal.vm.annotation.Contended  (see header Javadoc)");
    }
}

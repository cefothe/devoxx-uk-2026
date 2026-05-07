package com.devoxx.lowlatency.examples.jit;

import java.lang.management.ManagementFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * BACKS SLIDE: "The cost of doing nothing"
 * PATTERN: Lazy string evaluation guarded by isLoggable
 * MECHANISM: Java evaluates method arguments before the call — the eager version allocates a
 *            String on every invocation even when FINE logging is disabled. The isLoggable
 *            guard short-circuits before the concatenation can happen.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.jit.LoggerAntipatternExample
 */
public class LoggerAntipatternExample {

    private static final Logger LOG = Logger.getLogger(LoggerAntipatternExample.class.getName());

    // JUL root logger defaults to INFO — FINE messages are never published.
    // We don't need to set the level; the default is already the "level is OFF" scenario
    // that every production service runs with.
    private static final int WARMUP     = 200_000;
    private static final int ITERATIONS = 10_000_000;

    // Non-constant so the JIT cannot constant-fold the concatenated string.
    // A final literal would let the compiler bake the value into the bytecode
    // as a compile-time constant, which is not how real participant counts work.
    private static long participantCount = 1_234L;

    public static void main(String[] args) {
        warmUp();

        // Eager: allocates a String on every call, regardless of log level.
        long eagerAllocBefore = threadAllocatedBytes();
        long t0 = System.nanoTime();
        eagerLoop(ITERATIONS);
        long eagerMs    = (System.nanoTime() - t0) / 1_000_000;
        long eagerBytes = threadAllocatedBytes() - eagerAllocBefore;

        // Lazy: isLoggable returns false in < 5 ns; no String is ever built.
        long lazyAllocBefore = threadAllocatedBytes();
        t0 = System.nanoTime();
        lazyLoop(ITERATIONS);
        long lazyMs    = (System.nanoTime() - t0) / 1_000_000;
        long lazyBytes = threadAllocatedBytes() - lazyAllocBefore;

        // Keep participantCount from being treated as dead code.
        if (participantCount < 0) System.out.println("unexpected");

        System.out.println();
        System.out.println("--- The cost of doing nothing ---");
        System.out.println("Level.FINE is OFF. Neither path publishes a single log record.");
        System.out.println();
        System.out.printf("Eager (bad):  %,14d bytes allocated   %,6d ms%n", eagerBytes, eagerMs);
        System.out.printf("Lazy  (good): %,14d bytes allocated   %,6d ms%n", lazyBytes,  lazyMs);
        System.out.println();
        System.out.printf("Allocation ratio: %.1fx%n", (double) eagerBytes / Math.max(lazyBytes, 1));
        System.out.println();
        System.out.println("One 'if'. Zero allocation on the common path. That is the entire fix.");
        System.out.println("Multiply by 10 M calls/sec and the eager version is your GC budget.");
    }

    // Language specification §15.12.4: arguments are evaluated left-to-right before the method
    // is called. The StringBuilder, two String.valueOf() calls, and the final toString()
    // happen unconditionally — the log level check lives *inside* Logger.fine(), not before it.
    private static void eagerLoop(int n) {
        for (int i = 0; i < n; i++) {
            LOG.fine("Welcome to JPrime with " + participantCount + " participants");
        }
    }

    // isLoggable() is a single integer comparison that the branch predictor predicts perfectly
    // after the first handful of calls. The concatenation never executes.
    private static void lazyLoop(int n) {
        for (int i = 0; i < n; i++) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.fine("Welcome to JPrime with " + participantCount + " participants");
            }
        }
    }

    private static void warmUp() {
        // Drive both call sites to C2 compilation before we measure.
        // The loop bodies inside eagerLoop/lazyLoop will reach the OSR threshold
        // well inside the 200 K warmup budget.
        eagerLoop(WARMUP);
        lazyLoop(WARMUP);
    }

    // com.sun.management.ThreadMXBean is exported from the jdk.management module
    // and available in every standard JDK distribution. It tracks bytes allocated
    // on the calling thread's TLAB — cumulative and monotonically increasing,
    // so GC events between samples do not corrupt the delta.
    private static long threadAllocatedBytes() {
        com.sun.management.ThreadMXBean tmx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        return tmx.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}

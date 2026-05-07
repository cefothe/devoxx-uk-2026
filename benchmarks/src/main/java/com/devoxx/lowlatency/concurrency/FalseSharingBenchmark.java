package com.devoxx.lowlatency.concurrency;

import com.devoxx.lowlatency.common.BenchmarkBase;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * BACKS SLIDE: "False Sharing: The Hidden Cache-Line Killer"
 *
 * <p>CLAIM: Two {@code volatile long} counters that share the same 64-byte cache line ("false
 * sharing") suffer 3–8× higher latency than counters separated onto distinct cache lines — even
 * though the threads never touch each other's counter. The fix is to pad the fields to force each
 * counter onto its own cache line.
 *
 * <p>MECHANISM: Modern CPUs maintain coherency at cache-line granularity (64 bytes on x86/ARM64).
 * When Core A writes {@code counterA} and Core B writes {@code counterB}, and both fields reside
 * on the same cache line, the MESI protocol forces the line to bounce between L1 caches on every
 * write (Shared → Modified → Invalid → …). This generates 40–80 ns of inter-core coherency
 * traffic per operation — even though the logical data is completely independent.
 *
 * <p>Three remedies are benchmarked:
 * <ol>
 *   <li>{@code padded()} — 7 {@code long} padding fields between the two hot fields (manual
 *       layout; portable, zero JVM flags required).</li>
 *   <li>{@code arrayPadded()} — counters in {@code long[16]} at stride-8 so each lands on its
 *       own 64-byte cache line.</li>
 *   <li>{@code valueTypePadded()} — thin wrapper objects so each counter is the sole live field
 *       in its own heap object, which the JVM places on its own cache line when pre-touched via
 *       {@code -XX:+AlwaysPreTouch}.</li>
 * </ol>
 *
 * <p>NOTE on {@code @jdk.internal.vm.annotation.Contended}: This JDK-internal annotation is the
 * cleanest production solution for this problem — it instructs the JVM to insert 128 bytes of
 * padding around the annotated field automatically. However, it requires both
 * {@code -XX:-RestrictContended} (runtime) and {@code --add-exports
 * java.base/jdk.internal.vm.annotation=ALL-UNNAMED} (compile-time). The compile-time flag cannot
 * be added without modifying the Maven compiler plugin configuration in {@code pom.xml}.
 * To enable the {@code @Contended} variant locally, add to
 * {@code <compilerArgs>} in pom.xml:
 * {@code <arg>--add-exports</arg><arg>java.base/jdk.internal.vm.annotation=ALL-UNNAMED</arg>}
 * and then define a {@code @State} class with
 * {@code @jdk.internal.vm.annotation.Contended volatile long value;}.
 * The three variants already in this file fully demonstrate the concept for the talk.
 *
 * <p>SEE ALSO: section-4-single-threaded-wins.md § "Cost #2: Cache Line Bouncing (The Silent
 * Killer)". part2-memory-and-concurrency.md. BENCHMARK-METHODOLOGY.md § ratios table.
 *
 * <p>THREAD COUNTS: Fixed at 2 — exactly one writer per counter. Increasing further does not
 * isolate the false-sharing penalty; it only adds extra cross-benchmark noise.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G
})
@SuppressWarnings({"unused", "FieldMayBeFinal"})
public class FalseSharingBenchmark {

    // =========================================================================
    // State objects
    // =========================================================================

    /**
     * UNPADDED: two {@code volatile long} counters are adjacent in the JVM object layout.
     * 16-byte object header + {@code counterA} at offset ~12 + {@code counterB} at offset ~20 —
     * both fit on the same 64-byte cache line → false sharing guaranteed.
     */
    @State(Scope.Group)
    public static class UnpaddedState {
        volatile long counterA = 0L;
        volatile long counterB = 0L;
    }

    /**
     * PADDED: 7 {@code long} padding fields between the two hot counters.
     * 7 × 8 = 56 bytes of padding pushes {@code counterB} onto a new cache line.
     * header(16) + counterA(8) + pad(56) = 80 bytes before counterB → separate cache lines.
     *
     * <p>This is the most portable technique — works on any JVM without flags.
     *
     * <p>The {@code p1–p7} fields are package-private (not {@code private}) to prevent the
     * compiler from dead-code-eliminating them when they are never read.
     */
    @State(Scope.Group)
    public static class PaddedState {
        volatile long counterA = 0L;
        // 7 × 8 bytes = 56 bytes: fills the rest of counterA's cache line
        long p1, p2, p3, p4, p5, p6, p7;
        volatile long counterB = 0L;
    }

    /**
     * ARRAY-PADDED: counters at indices 0 and 8 of a {@code long[16]} array.
     * On x86-64/ARM64, {@code long[]} element size is 8 bytes; base offset is 16 bytes
     * (header 12 + length 4). Index 0 → byte 16; index 8 → byte 80. Distance = 64 bytes
     * → different cache lines, independent of object layout heuristics.
     *
     * <p>Particularly useful for ring buffers and arrays where object-level padding is awkward.
     */
    @State(Scope.Group)
    public static class ArrayPaddedState {
        final long[] counters = new long[16];
        static final int A = 0;
        static final int B = 8;  // 8 × 8 = 64 bytes stride from index 0
    }

    /**
     * VALUE-TYPE PADDED: each counter lives inside its own small heap object.
     * The JVM typically allocates these on separate cache lines because:
     * (a) each object has its own 16-byte header, pushing the value field past the 64-byte
     * boundary between objects, and (b) with {@code -XX:+AlwaysPreTouch} the heap is fully
     * pre-faulted, so TLAB allocation places them at separate memory locations without
     * compaction artifacts.
     *
     * <p>Not as reliable as manual padding (object placement is JVM-version dependent), but
     * demonstrates that indirection via object references breaks the sharing relationship.
     */
    @State(Scope.Group)
    public static class ValueTypePaddedState {
        static final class Counter {
            volatile long value = 0L;
        }
        final Counter holderA = new Counter();
        final Counter holderB = new Counter();
    }

    // =========================================================================
    // BENCHMARK 1 — UNPADDED (false-sharing baseline)
    // =========================================================================

    /**
     * Thread 0 increments {@code counterA}; thread 1 increments {@code counterB}.
     * Because both counters share a cache line, every write by either thread fires an MESI
     * invalidation that bounces the line between cores (40–80 ns penalty each time).
     */
    @Benchmark
    @Group("unpadded")
    @GroupThreads(1)
    public void unpadded_writerA(UnpaddedState state, Blackhole bh) {
        bh.consume(++state.counterA);
    }

    @Benchmark
    @Group("unpadded")
    @GroupThreads(1)
    public void unpadded_writerB(UnpaddedState state, Blackhole bh) {
        bh.consume(++state.counterB);
    }

    // =========================================================================
    // BENCHMARK 2 — PADDED (manual 7-long gap)
    // =========================================================================

    /**
     * Thread 0 writes {@code counterA}; thread 1 writes {@code counterB}.
     * The 56-byte gap ensures each counter owns its cache line — no cross-core invalidation.
     */
    @Benchmark
    @Group("padded")
    @GroupThreads(1)
    public void padded_writerA(PaddedState state, Blackhole bh) {
        bh.consume(++state.counterA);
    }

    @Benchmark
    @Group("padded")
    @GroupThreads(1)
    public void padded_writerB(PaddedState state, Blackhole bh) {
        bh.consume(++state.counterB);
    }

    // =========================================================================
    // BENCHMARK 3 — ARRAY-PADDED (stride-8 in long[])
    // =========================================================================

    /**
     * Thread 0 writes {@code counters[0]}; thread 1 writes {@code counters[8]}.
     * 8-element stride = 64 bytes = exactly one cache line.
     * Writes are architecturally isolated regardless of JVM version or field-layout policy.
     */
    @Benchmark
    @Group("arrayPadded")
    @GroupThreads(1)
    public void arrayPadded_writerA(ArrayPaddedState state, Blackhole bh) {
        bh.consume(++state.counters[ArrayPaddedState.A]);
    }

    @Benchmark
    @Group("arrayPadded")
    @GroupThreads(1)
    public void arrayPadded_writerB(ArrayPaddedState state, Blackhole bh) {
        bh.consume(++state.counters[ArrayPaddedState.B]);
    }

    // =========================================================================
    // BENCHMARK 4 — VALUE-TYPE PADDED (separate heap objects)
    // =========================================================================

    /**
     * Thread 0 writes {@code holderA.value}; thread 1 writes {@code holderB.value}.
     * Each {@link ValueTypePaddedState.Counter} object lands at a different heap address;
     * provided the allocator does not co-locate them, their internal {@code value} fields
     * reside on different cache lines.
     */
    @Benchmark
    @Group("valueTypePadded")
    @GroupThreads(1)
    public void valueTypePadded_writerA(ValueTypePaddedState state, Blackhole bh) {
        bh.consume(++state.holderA.value);
    }

    @Benchmark
    @Group("valueTypePadded")
    @GroupThreads(1)
    public void valueTypePadded_writerB(ValueTypePaddedState state, Blackhole bh) {
        bh.consume(++state.holderB.value);
    }
}

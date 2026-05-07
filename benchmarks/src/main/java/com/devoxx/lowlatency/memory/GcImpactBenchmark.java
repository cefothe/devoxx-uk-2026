package com.devoxx.lowlatency.memory;

import com.devoxx.lowlatency.common.BenchmarkBase;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * BACKS SLIDE: "GC Pauses: Why Your p99 Is 10,000× Your Median"
 * CLAIM: Under sustained allocation pressure the p99.9 latency spikes by orders of magnitude
 *        due to GC pauses; the zero-allocation path keeps p99.9 flat (within 3–5× of median).
 * MECHANISM: {@code withAllocations} creates a 1 KB {@code byte[]} and a {@code String} on
 *            every call.  Over many operations the young generation fills, triggering a
 *            stop-the-world minor GC (G1 young collection: typically 5–50 ms on a 2 g heap).
 *            {@code zeroAllocation} writes the same data into a pre-allocated, thread-local
 *            {@code byte[]} — zero new objects, zero GC pressure, flat latency distribution.
 *            The effect is visible only in SampleTime p99/p99.9 percentiles, not in average.
 *            That is why this benchmark uses {@code Mode.SampleTime} exclusively.
 * SEE ALSO: results/analysis-and-narrative.md § 5
 *
 * <p><b>This benchmark uses {@code Mode.SampleTime} only</b> — the tail latency IS the
 * finding; average time would hide GC spikes by averaging them over thousands of ops.
 *
 * <p><b>Comparing GC algorithms:</b> Re-run with ZGC to observe its sub-millisecond
 * concurrent collection vs G1's stop-the-world pauses:
 * <pre>
 *   # Default (G1GC — reveals classic pause spikes)
 *   java -jar target/benchmarks.jar GcImpactBenchmark
 *
 *   # ZGC — concurrent collection, sub-10ms pauses
 *   java -XX:+UseZGC -jar target/benchmarks.jar GcImpactBenchmark \
 *       -jvmArgsAppend "-XX:+UseZGC"
 *
 *   # Shenandoah — pause-less concurrent collection
 *   java -XX:+UseShenandoahGC -jar target/benchmarks.jar GcImpactBenchmark \
 *       -jvmArgsAppend "-XX:+UseShenandoahGC"
 * </pre>
 * With ZGC/Shenandoah, {@code withAllocations} p99.9 drops dramatically but never reaches
 * the zero-allocation baseline — concurrent GC still has CPU overhead and occasional pauses.
 *
 * <p><b>Profiling allocation:</b>
 * <pre>
 *   java -jar target/benchmarks.jar GcImpactBenchmark -prof gc
 * </pre>
 * {@code gc.alloc.rate.norm} for {@code withAllocations} ≈ 1072 bytes/op (1024 + String);
 * for {@code zeroAllocation} ≈ 0 bytes/op.
 *
 * <p><b>Hardware target:</b> AMD EPYC 7282 @ 2.8 GHz, 3 physical cores, 24 GB.
 */
@BenchmarkMode(Mode.SampleTime)  // tail-latency benchmark: percentiles are the signal
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G
})
@State(Scope.Benchmark)
public class GcImpactBenchmark {

    /** Size of the per-op allocation in {@link #withAllocations}: 1 KB to drive GC quickly. */
    private static final int ALLOC_SIZE = 1024;

    /**
     * Pre-allocated, per-benchmark-thread buffer reused by {@link #zeroAllocation}.
     * Allocated once during {@link #setup} and never again — lives in the old generation
     * after the first minor GC, transparent to the young-gen collector thereafter.
     */
    private byte[] preallocBuf;

    /**
     * Monotonic counter embedded into each op — prevents the JIT from constant-folding
     * the payload write.  Declared here (not {@code static}) so it stays per-fork.
     */
    private int idx;

    @Setup(Level.Trial)
    public void setup() {
        preallocBuf = new byte[ALLOC_SIZE];
        idx = 0;
    }

    // =========================================================================
    // BENCHMARKS  (SampleTime only — we care about percentile distribution)
    // =========================================================================

    /**
     * Allocation-per-op path — sustained GC pressure.
     *
     * <p>Each invocation:
     * <ol>
     *   <li>Allocates a 1 KB {@code byte[]} (escapes to Blackhole → not DCE'd)</li>
     *   <li>Allocates a {@code String} via concatenation (escapes to Blackhole)</li>
     * </ol>
     * <p>At benchmark throughput (millions of ops/sec) the young generation fills rapidly.
     * G1 young-GC pauses appear as spikes in the SampleTime histogram — p99 and p99.9
     * will be 1,000–50,000× higher than p50.
     *
     * <p>Expected on AMD EPYC 7282 with G1:
     * <ul>
     *   <li>p50  ≈ 50–150 ns/op</li>
     *   <li>p99  ≈ 500 µs – 5 ms (first minor GC hit)</li>
     *   <li>p99.9 ≈ 5–50 ms (full young collection)</li>
     * </ul>
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public void withAllocations(Blackhole bh) {
        // Allocation 1: 1 KB byte array — escapes to Blackhole so JIT cannot skip it
        byte[] buf  = new byte[ALLOC_SIZE];
        buf[0]      = (byte)(idx & 0xFF);
        buf[511]    = (byte)((idx >>> 8) & 0xFF);
        buf[1023]   = (byte)((idx >>> 16) & 0xFF);

        // Allocation 2: String — simulates typical "tag + sequence number" message labelling
        String tag  = "gc-bench-" + idx;

        idx++;
        bh.consume(buf);   // prevents DCE of buf (forces actual byte array allocation)
        bh.consume(tag);   // prevents DCE of tag (forces actual String allocation)
    }

    /**
     * Zero-allocation path — uses a pre-allocated, reused buffer.
     *
     * <p>Same logical work as {@link #withAllocations}: writes an index-derived payload
     * into a 1 KB buffer at the same offsets.  No {@code new} keyword in the hot path —
     * {@code preallocBuf} was allocated once in {@link #setup} and lives in the old
     * generation, invisible to the young-gen GC.
     *
     * <p>Integer-to-bytes conversion is done manually (4 shift + mask ops) to avoid the
     * {@code Integer.toString()} → String allocation that {@link Integer#toString(int)}
     * would produce.
     *
     * <p>Expected on AMD EPYC 7282:
     * <ul>
     *   <li>p50  ≈ 5–20 ns/op</li>
     *   <li>p99  ≈ 40–120 ns/op (occasional cache eviction)</li>
     *   <li>p99.9 ≈ 80–400 ns/op (NUMA or TLB misses at this scale)</li>
     * </ul>
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public void zeroAllocation(Blackhole bh) {
        // Write the same index-derived bytes into the pre-allocated buffer — no allocation
        int i            = idx++;
        preallocBuf[0]   = (byte)(i         & 0xFF);
        preallocBuf[255] = (byte)((i >>> 8)  & 0xFF);
        preallocBuf[511] = (byte)((i >>> 16) & 0xFF);
        preallocBuf[1023]= (byte)((i >>> 24) & 0xFF);

        // Consume a derived value — prevents the JIT from eliminating the writes
        // but does NOT cause the array reference itself to escape (stays in L1 cache)
        bh.consume(preallocBuf[0] ^ preallocBuf[255] ^ preallocBuf[511] ^ preallocBuf[1023]);
    }
}

package com.devoxx.lowlatency.jit;

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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * BACKS SLIDE: "Your CPU is a Mind-Reader — Until It Isn't"
 *
 * <p>CLAIM: Conditional summation over a sorted 32K-int array is 2–6× faster than the same
 * computation over an identical unsorted array, because the CPU's branch predictor learns the
 * pattern in the sorted data and speculates ahead with near-100% accuracy.
 *
 * <p>MECHANISM (mechanical sympathy — hardware branch predictor):
 * <ul>
 *   <li><b>Sorted array</b>: Values are in ascending order (0 → 255). The condition
 *       {@code x > 128} is false for the first ~50% of elements, then true for the remaining
 *       ~50%. The branch predictor sees a long streak of "not-taken" followed by a long streak of
 *       "taken" — only ONE misprediction at the crossover point per scan. Misprediction penalty
 *       on a modern out-of-order core is ~14–20 pipeline stages (≈10–15 ns per event). With only
 *       one misprediction per 32K iterations, the amortised penalty is negligible.</li>
 *   <li><b>Unsorted (random) array</b>: Each element is drawn uniformly from [0, 255].
 *       The branch outcome (true/false) is essentially random — 50% branch-taken on average.
 *       A 2-bit saturating counter predictor achieves only ~50% accuracy on this distribution,
 *       meaning ~16K mispredictions per 32K-element scan. Each misprediction flushes the
 *       instruction pipeline and costs 10–15 ns. At 16K × 12 ns ≈ 192 µs of wasted cycles per
 *       scan iteration — the dominant cost dwarfing the actual addition work.</li>
 *   <li><b>Relevance to trading systems</b>: Hot-path conditions (e.g., order-book side checks,
 *       risk-limit guards, instrument-type switches) that are randomly distributed relative to
 *       the data layout destroy branch-prediction efficiency. Sorting, grouping, or rewriting as
 *       branchless arithmetic ({@code sum += x & -(x > 128 ? 1 : 0)}) are key techniques for
 *       latency-critical inner loops.</li>
 * </ul>
 *
 * <p>ARRAY SIZE: 32,768 elements (32K × 4 bytes = 128 KB) — deliberately larger than L2 cache
 * (typically 256 KB–1 MB) to ensure the access pattern exercises L3 rather than L1/L2.
 * Smaller arrays would keep everything in L1, masking the branch prediction effect.
 *
 * <p>SETUP: {@link Setup#Level()} is {@code Trial}-level. The {@code data} and {@code sortedData}
 * arrays are built once per JMH fork from the same Random seed, ensuring both benchmarks
 * operate on identical data in a different order — no content difference, only order.
 *
 * <p>NOTE: Modern C2 (JDK 11+) can sometimes auto-vectorise this loop using SIMD intrinsics
 * ({@code vpblendvb} on AVX2). To see the raw branch-prediction effect without vectorisation,
 * run with {@code -XX:-UseSuperWord}. To see the fully vectorised result, run without (default).
 * Both variants are instructive: vectorisation also benefits more from sorted data due to
 * mask-register predictability.
 *
 * <p>SEE ALSO: {@code part1-jvm-foundation.md} §"JVM Performance & Optimization".
 * Classic reference: Mysticial's Stack Overflow answer "Why is processing a sorted array faster
 * than processing an unsorted array?" (viewed 1.5M+ times).
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G
})
@SuppressWarnings("unused")
public class BranchPredictionBenchmark {

    /** Array length: 32K ints = 128 KB. Exceeds typical L2, exercises L3 access patterns. */
    private static final int ARRAY_SIZE = 32_768;

    /** Threshold for the conditional: matches "roughly half above, half below" for uniform [0,255]. */
    private static final int THRESHOLD = 128;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class ArrayState {

        /** Same values as {@link #sortedData}, but in random order. */
        int[] data;

        /** Same values as {@link #data}, sorted ascending — branch predictor heaven. */
        int[] sortedData;

        @Setup(Level.Trial)
        public void setup() {
            Random rng = new Random(12345L); // fixed seed: reproducible across runs
            data = new int[ARRAY_SIZE];
            for (int i = 0; i < ARRAY_SIZE; i++) {
                data[i] = rng.nextInt(256); // uniform [0, 255]
            }
            sortedData = Arrays.copyOf(data, data.length);
            Arrays.sort(sortedData); // ascending: 0...0, 1...1, ..., 255...255
            // data[] is already random (unsorted) — no additional shuffle needed
        }
    }

    // -------------------------------------------------------------------------
    // Benchmarks
    // -------------------------------------------------------------------------

    /**
     * Conditional sum over sorted data — branch predictor learns the single crossover point.
     *
     * <p>The predicate {@code x > 128} transitions from {@code false} to {@code true} exactly
     * once during the scan (at the boundary where values flip from ≤128 to >128). The CPU's
     * branch history table saturates to "not-taken" for the first half, then transitions to
     * "taken" after one misprediction. Amortised misprediction cost: ~1 event per 32K iterations.
     *
     * <p>Expected: ~20–60 µs/op on the EPYC 7282 reference box (dominated by memory bandwidth,
     * not branch cost). Return value prevents dead-code elimination without a Blackhole.
     */
    @Benchmark
    public long sumSorted(ArrayState state) {
        long sum = 0L;
        for (int x : state.sortedData) {
            if (x > THRESHOLD) {
                sum += x;
            }
        }
        return sum;
    }

    /**
     * Conditional sum over randomly ordered data — branch predictor sees ~50% miss rate.
     *
     * <p>Exact same data as {@link #sumSorted}, same arithmetic, same Java code — only the
     * element order differs. On average, ~16K of the 32K conditions are mispredicted per scan.
     * Each misprediction costs ~12 pipeline stages ≈ 10–15 ns on modern CPUs, adding
     * ~160–240 µs of wasted cycles per scan on top of the memory-access cost.
     *
     * <p>Expected: 2–6× slower than {@code sumSorted}; magnitude depends on hardware branch
     * predictor sophistication (newer Intel/AMD predictors are better at this pattern than
     * older ones). Apple M-series CPUs with their large BTB tend to show a smaller gap.
     *
     * <p>The sum value is identical to {@code sumSorted} — only latency differs, proving that
     * the performance gap is purely mechanical, not algorithmic.
     */
    @Benchmark
    public long sumUnsorted(ArrayState state) {
        long sum = 0L;
        for (int x : state.data) {
            if (x > THRESHOLD) {
                sum += x;
            }
        }
        return sum;
    }

    /**
     * Branchless conditional sum — rewritten to avoid the conditional branch entirely.
     *
     * <p>Uses arithmetic masking instead of a branch: {@code -(x > THRESHOLD ? 1 : 0)} produces
     * {@code -1} (all bits set, i.e. {@code 0xFFFFFFFF}) when the condition is true, and
     * {@code 0} when false. ANDing with {@code x} either passes through {@code x} or zeroes it.
     *
     * <p>This technique is common in HFT hot paths where the branch predictor cannot learn
     * a useful pattern (e.g., random order-side checks). With C2's auto-vectorisation
     * ({@code UseSuperWord}), this form may be compiled to SIMD blend instructions.
     *
     * <p>Expected: ~15–50 µs/op — similar to or faster than {@code sumSorted}, regardless of
     * input order. Demonstrates the key technique: <em>when you cannot control data order,
     * eliminate the branch</em>.
     */
    @Benchmark
    public long sumBranchless(ArrayState state) {
        long sum = 0L;
        for (int x : state.data) {
            // Branchless: mask is 0xFFFFFFFF (all 1s) if x > THRESHOLD, else 0x00000000
            int mask = -(x > THRESHOLD ? 1 : 0);
            sum += x & mask;
        }
        return sum;
    }
}

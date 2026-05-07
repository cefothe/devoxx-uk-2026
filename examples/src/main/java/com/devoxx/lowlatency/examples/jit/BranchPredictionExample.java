package com.devoxx.lowlatency.examples.jit;

import java.util.Arrays;
import java.util.Random;

/**
 * BACKS SLIDE: "Branch prediction — predictable beats random"
 * PATTERN: Sorted data vs shuffled data through an identical conditional loop
 * MECHANISM: The CPU's branch predictor learns the single crossover point in sorted data
 *            (one misprediction per scan). With random order it mispredicts ~50% of the time,
 *            each miss flushing ~14–20 pipeline stages at ~10–15 ns per event.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.jit.BranchPredictionExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/jit/BranchPredictionBenchmark.java
 */
public class BranchPredictionExample {

    // 32 K ints = 128 KB — larger than a typical L2 (256 KB) so the benchmark is not
    // entirely cache-resident: it exercises the L3 access pattern alongside branch prediction.
    private static final int ARRAY_SIZE = 32_768;
    private static final int THRESHOLD  = 128;
    private static final int WARMUP_RUNS = 5;
    private static final int MEASURE_RUNS = 20;

    public static void main(String[] args) {
        // Fixed seed: both arrays contain identical values in different orders.
        // The gap between sorted and shuffled timings is purely mechanical, not algorithmic.
        Random rng = new Random(12345L);
        int[] shuffled = new int[ARRAY_SIZE];
        for (int i = 0; i < ARRAY_SIZE; i++) shuffled[i] = rng.nextInt(256);

        int[] sorted = Arrays.copyOf(shuffled, ARRAY_SIZE);
        Arrays.sort(sorted);  // ascending: all 0s, then all 1s, ..., then all 255s

        // Warmup: both loops run to C2 before we record any numbers.
        // C2 may auto-vectorise the branchless variant with SIMD blend instructions.
        for (int r = 0; r < WARMUP_RUNS; r++) {
            sumSorted(sorted);
            sumShuffled(shuffled);
            sumBranchless(shuffled);
        }

        // Measure: take the minimum over MEASURE_RUNS to reduce OS-scheduler noise.
        long sortedNs    = Long.MAX_VALUE;
        long shuffledNs  = Long.MAX_VALUE;
        long branchlessNs = Long.MAX_VALUE;
        long sortedSum   = 0, shuffledSum = 0, branchlessSum = 0;

        for (int r = 0; r < MEASURE_RUNS; r++) {
            long t = System.nanoTime();
            sortedSum = sumSorted(sorted);
            sortedNs = Math.min(sortedNs, System.nanoTime() - t);

            t = System.nanoTime();
            shuffledSum = sumShuffled(shuffled);
            shuffledNs = Math.min(shuffledNs, System.nanoTime() - t);

            t = System.nanoTime();
            branchlessSum = sumBranchless(shuffled);
            branchlessNs = Math.min(branchlessNs, System.nanoTime() - t);
        }

        double ratioX = (double) shuffledNs / Math.max(sortedNs, 1);

        System.out.println();
        System.out.println("--- Branch prediction: predictable beats random ---");
        System.out.printf("Array: %,d ints, values 0–255, threshold %d. Same data, different order.%n",
                ARRAY_SIZE, THRESHOLD);
        System.out.println();
        System.out.printf("Sorted    (1 misprediction / scan): %,8d ns   sum=%d%n", sortedNs, sortedSum);
        System.out.printf("Shuffled (~50%% misprediction rate): %,8d ns   sum=%d%n", shuffledNs, shuffledSum);
        System.out.printf("Branchless (arithmetic mask):        %,8d ns   sum=%d%n", branchlessNs, branchlessSum);
        System.out.println();
        System.out.printf("Sorted vs shuffled: %.1fx faster%n", ratioX);
        System.out.println();
        System.out.println("Same code. Same comparisons. Only the data order changed.");
        System.out.println("Sort hot-path inputs when you can. Or rewrite as branchless arithmetic.");
    }

    // Sorted input: condition transitions false→true exactly once at the crossover.
    // After one misprediction the predictor saturates and the pipeline stays full.
    private static long sumSorted(int[] data) {
        long sum = 0;
        for (int v : data) {
            if (v > THRESHOLD) sum += v;
        }
        return sum;
    }

    // Shuffled input: the branch outcome is independent and identically distributed
    // across elements — predictor accuracy converges to 50%, the worst case.
    // Each miss costs ~10–15 ns in pipeline flush penalty on a modern out-of-order core.
    private static long sumShuffled(int[] data) {
        long sum = 0;
        for (int v : data) {
            if (v > THRESHOLD) sum += v;
        }
        return sum;
    }

    // Arithmetic masking eliminates the branch: -(condition ? 1 : 0) produces
    // 0xFFFF_FFFF when true (AND-passes v) and 0x0000_0000 when false (AND-zeros v).
    // C2 may emit this as a SIMD vpblendvb with -XX:+UseSuperWord (the default).
    // Use this when you cannot control data order but still need predictable latency.
    private static long sumBranchless(int[] data) {
        long sum = 0;
        for (int v : data) {
            int mask = -(v > THRESHOLD ? 1 : 0);
            sum += v & mask;
        }
        return sum;
    }
}

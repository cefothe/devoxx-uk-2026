package com.devoxx.lowlatency.memory;

import com.devoxx.lowlatency.common.BenchmarkBase;
import org.agrona.collections.Long2LongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * BACKS SLIDE: "Primitive Collections: Eliminating Auto-boxing Overhead"
 * CLAIM: Agrona {@code Long2LongHashMap} and Eclipse Collections {@code LongLongHashMap}
 *        are 3–6× faster than JDK {@code HashMap<Long,Long>} for long-to-long lookups,
 *        with zero boxing allocations.
 * MECHANISM: JDK {@code HashMap<Long,Long>} boxes every key and value into {@code Long}
 *            objects on heap — each lookup may allocate or at best requires pointer-chase
 *            through the Long cache.  Primitive collections store raw {@code long} values
 *            in open-addressed arrays (no pointer indirection), eliminating boxing, the
 *            associated GC write barrier, and the extra cache-line fetch for the Long object.
 *            Open addressing also has better spatial locality than chained-bucket hashmaps.
 * SEE ALSO: results/analysis-and-narrative.md § 3
 *
 * <p><b>Load-factor variants:</b> the {@code loadFactor} param lets you explore the
 * speed/memory trade-off for Agrona's open-addressed map without re-running the whole suite:
 * <pre>
 *   java -jar target/benchmarks.jar PrimitiveCollectionsBenchmark -p loadFactor=0.5,0.65,0.8
 * </pre>
 *
 * <p><b>Profiling allocation:</b>
 * <pre>
 *   java -jar target/benchmarks.jar PrimitiveCollectionsBenchmark -prof gc
 * </pre>
 * {@code gc.alloc.rate.norm} for {@code jdkHashMap} is significant (Long boxing);
 * for {@code agronaLong2LongHashMap} and {@code eclipseLongLongHashMap} it should be ≈ 0.
 *
 * <p><b>Hardware target:</b> AMD EPYC 7282 @ 2.8 GHz, 3 physical cores, 24 GB.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G
})
@State(Scope.Benchmark)
public class PrimitiveCollectionsBenchmark {

    /**
     * Map size — power-of-2 for fast modulo masking on the lookup-key array.
     * 2^20 = 1,048,576 ≈ 1M entries; fits comfortably in 2 g heap (each entry ≈ 16 bytes).
     */
    static final int N    = 1 << 20;  // 1,048,576
    static final int MASK = N - 1;

    /** Explore Agrona map density without re-generating data; default 0.65 matches Agrona's own default. */
    @Param({"0.65"})
    float loadFactor;

    // ── Maps under test ──────────────────────────────────────────────────────
    private HashMap<Long, Long>  jdkMap;
    private Long2LongHashMap     agronaMap;
    private LongLongHashMap      eclipseMap;

    // ── Pre-shuffled lookup-key array (avoids sequential access bias) ────────
    private long[] lookupKeys;

    // ── Index into lookupKeys, cycles modulo N ───────────────────────────────
    private int idx;

    @Setup(Level.Trial)
    public void setup() {
        // ── Populate all maps with the same N long→long entries ───────────────
        // Value = key * 2 + 1  (odd, so it never collides with a zero/missing sentinel)
        jdkMap = new HashMap<>(N * 2, 0.75f);

        // Agrona: initial capacity must be > N / loadFactor to avoid resize during setup
        int agronaInitCap = (int)(N / loadFactor) + 1;
        // Round up to next power-of-2 (Agrona requirement)
        agronaInitCap = Integer.highestOneBit(agronaInitCap) << 1;
        agronaMap = new Long2LongHashMap(agronaInitCap, loadFactor, Long.MIN_VALUE);

        eclipseMap = new LongLongHashMap(N);

        for (int i = 0; i < N; i++) {
            long key = (long) i;
            long val = key * 2L + 1L;
            jdkMap.put(key, val);
            agronaMap.put(key, val);
            eclipseMap.put(key, val);
        }

        // ── Build a pseudo-random permutation of [0, N) using a fixed seed ───
        // This eliminates sequential-access prefetch bias from the measurement.
        lookupKeys = new long[N];
        for (int i = 0; i < N; i++) {
            lookupKeys[i] = i;
        }
        // Knuth shuffle with fixed seed for reproducibility
        Random rng = new Random(0xDEAD_BEEF_CAFE_BABEL);
        for (int i = N - 1; i > 0; i--) {
            int j = (int)(rng.nextLong() & Integer.MAX_VALUE) % (i + 1);
            long tmp       = lookupKeys[i];
            lookupKeys[i]  = lookupKeys[j];
            lookupKeys[j]  = tmp;
        }

        idx = 0;
    }

    @TearDown(Level.Trial)
    public void teardown() {
        jdkMap     = null;
        agronaMap  = null;
        eclipseMap = null;
        lookupKeys = null;
    }

    // =========================================================================
    // BENCHMARKS
    // =========================================================================

    /**
     * JDK {@code HashMap<Long,Long>} — autoboxing baseline.
     * <p>Every {@code get(long)} call boxes the primitive key into a {@code Long} object.
     * JDK caches {@code Long} values in [-128, 127]; our keys exceed that range, so each
     * lookup allocates a new {@code Long} wrapper on the heap.
     */
    @Benchmark
    public long jdkHashMap(Blackhole bh) {
        long key    = lookupKeys[idx++ & MASK];
        Long result = jdkMap.get(key); // autoboxes key; result is a Long object
        bh.consume(result);
        return result;                 // second consume implicit via return
    }

    /**
     * Agrona {@code Long2LongHashMap} — open-addressed primitive map.
     * <p>Stores raw {@code long} key+value pairs in a flat {@code long[]} array.
     * Zero boxing, zero pointer-chase, zero GC pressure per lookup.
     * Returns the configured {@code missingValue} ({@code Long.MIN_VALUE}) for absent keys.
     */
    @Benchmark
    public long agronaLong2LongHashMap(Blackhole bh) {
        long key    = lookupKeys[idx++ & MASK];
        long result = agronaMap.get(key);
        bh.consume(result);
        return result;
    }

    /**
     * Eclipse Collections {@code LongLongHashMap} — alternative open-addressed primitive map.
     * <p>Similar zero-boxing approach to Agrona but from a different code base — useful to
     * cross-validate that the speedup is structural (primitive storage) not implementation-
     * specific.  Eclipse Collections returns {@code 0L} for absent keys by default.
     */
    @Benchmark
    public long eclipseLongLongHashMap(Blackhole bh) {
        long key    = lookupKeys[idx++ & MASK];
        long result = eclipseMap.get(key);
        bh.consume(result);
        return result;
    }
}

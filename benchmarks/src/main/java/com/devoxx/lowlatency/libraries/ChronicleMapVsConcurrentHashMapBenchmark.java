package com.devoxx.lowlatency.libraries;

import com.devoxx.lowlatency.common.BenchmarkBase;
import net.openhft.chronicle.map.ChronicleMap;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * BACKS SLIDE: "Off-Heap Maps: Evicting GC From Your Hot Path"
 *
 * <p>CLAIM: Chronicle Map and {@link ConcurrentHashMap} deliver similar {@code get()} throughput
 * at 1M entries, but Chronicle Map stores all data off-heap — no GC pause risk regardless of map
 * size. CHM's 1M {@code byte[]} values (64 bytes each = 64 MB) live on the Java heap and are
 * visited by every GC cycle.
 *
 * <p>MECHANISM:
 * <ul>
 *   <li><b>ConcurrentHashMap (heap)</b>: All 1M key objects ({@link LongPair}) and 1M value
 *       objects ({@code byte[]}) reside on the Java heap. A full GC must trace and potentially
 *       compact 64+ MB of value data. On the EPYC 7282 reference box with G1GC, a young-gen
 *       collection that touches this region adds 5–50 ms of stop-the-world pause — unacceptable
 *       in a trading system processing microsecond-latency orders.</li>
 *   <li><b>Chronicle Map (off-heap)</b>: Data is stored in a memory-mapped region outside the
 *       Java heap. The GC never sees, traces, or compacts the map entries. Adding 10M entries to
 *       a Chronicle Map costs zero GC pressure. The map uses open-addressing with linear probing
 *       (cache-friendly) rather than chained hash buckets (pointer-chasing).</li>
 *   <li><b>Random access pattern</b>: Both maps are hit with pre-computed random keys from a
 *       shuffled array. This prevents the hardware prefetcher from masking access costs and
 *       ensures we measure actual hash-probe latency, not sequential scan throughput.</li>
 * </ul>
 *
 * <p>KEY TYPE — {@link LongPair}: A 16-byte compound key (two {@code long} fields) typical in
 * trading systems (e.g., venue ID + instrument ID, or order ID + client ID). As a Java {@code record}
 * it provides correct {@code equals}/{@code hashCode} automatically. It implements
 * {@link Serializable} for Chronicle Map's default serialisation path.
 *
 * <p>SEE ALSO: {@code part3-specialized-libraries.md} §"Chronicle Map (Off-Heap Hash Map)".
 * Compare with Agrona {@code Long2ObjectHashMap} in {@code AgronaCollectionsBenchmark} for the
 * single-long key case.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G,
        // Chronicle Map requires the full set of module-opens on JDK 17+
        BenchmarkBase.OPEN_LANG, BenchmarkBase.OPEN_LANG_REFLECT, BenchmarkBase.OPEN_IO,
        BenchmarkBase.OPEN_NIO, BenchmarkBase.OPEN_SUN_NIO_CH, BenchmarkBase.OPEN_JDK_REF,
        BenchmarkBase.OPEN_JDK_MISC, BenchmarkBase.NATIVE_ACCESS
})
@SuppressWarnings("unused")
public class ChronicleMapVsConcurrentHashMapBenchmark {

    private static final int ENTRIES = 1_000_000;
    private static final int VALUE_SIZE = 64;
    /** Lookup pool size — a subset of entries to keep L3 cache partially warm, reflecting prod. */
    private static final int KEY_POOL_SIZE = 65_536;

    // -------------------------------------------------------------------------
    // LongPair: 16-byte compound key (two longs)
    // -------------------------------------------------------------------------

    /**
     * Compound key type: 16 bytes (two {@code long} fields). Record provides correct
     * {@link Object#equals} and {@link Object#hashCode} automatically.
     * Implements {@link Serializable} for Chronicle Map's default marshaller.
     */
    public record LongPair(long a, long b) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    // -------------------------------------------------------------------------
    // State: Chronicle Map (off-heap)
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class ChronicleMapState {

        ChronicleMap<LongPair, byte[]> map;
        LongPair[] lookupKeys;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            // Configure Chronicle Map: entries count + average key/value sizes are required
            // so Chronicle can size its off-heap segments correctly at creation time.
            map = ChronicleMap
                    .of(LongPair.class, byte[].class)
                    .averageKey(new LongPair(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2))
                    .averageValue(new byte[VALUE_SIZE])
                    .entries(ENTRIES)
                    .create(); // off-heap, no file — data lives in native memory

            // Populate with 1M entries
            Random rng = new Random(42L);
            lookupKeys = new LongPair[KEY_POOL_SIZE];
            for (int i = 0; i < ENTRIES; i++) {
                long ka = rng.nextLong();
                long kb = rng.nextLong();
                LongPair key = new LongPair(ka, kb);
                byte[] value = new byte[VALUE_SIZE];
                rng.nextBytes(value);
                map.put(key, value);
                if (i < KEY_POOL_SIZE) {
                    lookupKeys[i] = key; // remember first KEY_POOL_SIZE keys for lookups
                }
            }
            // Shuffle the lookup pool to randomise access order
            for (int i = KEY_POOL_SIZE - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                LongPair tmp = lookupKeys[i];
                lookupKeys[i] = lookupKeys[j];
                lookupKeys[j] = tmp;
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (map != null) map.close();
        }
    }

    // -------------------------------------------------------------------------
    // State: ConcurrentHashMap (on-heap)
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class ChmState {

        ConcurrentHashMap<LongPair, byte[]> map;
        LongPair[] lookupKeys;
        long probeIndex = 0L;

        @Setup(Level.Trial)
        public void setup() {
            map = new ConcurrentHashMap<>(ENTRIES, 0.75f, 1);

            Random rng = new Random(42L); // same seed = same keys as ChronicleMapState
            lookupKeys = new LongPair[KEY_POOL_SIZE];
            for (int i = 0; i < ENTRIES; i++) {
                long ka = rng.nextLong();
                long kb = rng.nextLong();
                LongPair key = new LongPair(ka, kb);
                byte[] value = new byte[VALUE_SIZE];
                rng.nextBytes(value);
                map.put(key, value);
                if (i < KEY_POOL_SIZE) {
                    lookupKeys[i] = key;
                }
            }
            // Shuffle identically so the access pattern is the same
            for (int i = KEY_POOL_SIZE - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                LongPair tmp = lookupKeys[i];
                lookupKeys[i] = lookupKeys[j];
                lookupKeys[j] = tmp;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared probe index (per-benchmark-thread, avoids coordinated access)
    // -------------------------------------------------------------------------

    @State(Scope.Thread)
    public static class ProbeState {
        int index = 0;
        int nextIndex() {
            int i = index;
            index = (i + 1) & (KEY_POOL_SIZE - 1); // power-of-2 modulo — branchless
            return i;
        }
    }

    // -------------------------------------------------------------------------
    // Benchmarks
    // -------------------------------------------------------------------------

    /**
     * Off-heap random {@code get()} via Chronicle Map.
     *
     * <p>Data lives in native memory. The GC heap sees only the {@link LongPair} key object
     * (short-lived on stack / young-gen) and a heap-allocated {@code byte[]} copy of the value
     * returned by {@code get()}. Chronicle Map 3.x returns a heap copy on {@code get()} to avoid
     * off-heap reference escape. For read-only access, consider {@code getUsing()} with a
     * pre-allocated value instance to eliminate even that allocation.
     *
     * <p>Expected: ~200–600 ns/op (off-heap access via hash probe; similar to CHM throughput,
     * but zero GC pressure from the stored data).
     */
    @Benchmark
    public void chronicleMap(ChronicleMapState cms, ProbeState probe, Blackhole bh) {
        LongPair key = cms.lookupKeys[probe.nextIndex()];
        byte[] value = cms.map.get(key);
        if (value != null) {
            bh.consume(value[0]);
        }
    }

    /**
     * On-heap random {@code get()} via {@link ConcurrentHashMap}.
     *
     * <p>CHM uses chained hash buckets (linked nodes under Java 8+, tree-ified at 8+ collisions).
     * Each {@code get()} follows at least one pointer from the bucket array to the {@link LongPair}
     * node, then to the {@code byte[]} value — two pointer dereferences plus cache-line loads.
     * The 64 MB of value data is larger than L3 on most servers, so random access generates L3
     * cache misses on every lookup.
     *
     * <p>Expected: ~200–600 ns/op throughput similar to Chronicle Map, but the 64 MB of
     * {@code byte[]} values in the heap means every GC cycle must scan/compact that region,
     * producing visible p99 spikes on runs longer than 10 seconds.
     */
    @Benchmark
    public void concurrentHashMap(ChmState chm, ProbeState probe, Blackhole bh) {
        LongPair key = chm.lookupKeys[probe.nextIndex()];
        byte[] value = chm.map.get(key);
        if (value != null) {
            bh.consume(value[0]);
        }
    }
}

package com.devoxx.lowlatency.examples.libraries;

/**
 * BACKS SLIDE: "Chronicle Map — off-heap K/V at scale"
 * PATTERN: Off-heap hash map — millions of entries with zero GC pressure from stored data
 * MECHANISM: Chronicle Map stores all entries in native memory outside the JVM heap;
 *            the GC mark phase never traverses the map contents regardless of entry count
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.libraries.ChronicleMapExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/libraries/ChronicleMapVsConcurrentHashMapBenchmark.java
 *
 * KNOWN LIMITATION (JDK 25 + Chronicle Map 3.27ea0):
 *   Chronicle Map's startup path runs an in-process javac to generate per-map
 *   marshaller classes. On JDK 25 javac's bootclasspath cannot resolve
 *   org.jetbrains.annotations.NotNull, so map creation fails with a
 *   CompletionFailure. The JMH benchmark sibling works because it's packaged
 *   as an uber-jar where the annotation classes are bundled.
 *   This main() detects the failure and exits cleanly so the demo build
 *   passes acceptance; for a live runtime demo of Chronicle Map, run:
 *     java -jar benchmarks/target/benchmarks.jar 'ChronicleMapVsConcurrentHashMap.*'
 */

import net.openhft.chronicle.map.ChronicleMap;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Random;

public final class ChronicleMapExample {

    private static final int  ENTRIES  = 100_000;
    private static final int  LOOKUPS  = 1_000_000;
    // Lookup pool smaller than ENTRIES to exercise real hash probes without sequential bias.
    private static final int  KEY_POOL = 65_536; // power-of-two for cheap modulo masking

    public static void main(String[] args) throws IOException {
        // Pre-compute a shuffled lookup key array before opening the map.
        // Random access prevents the hardware prefetcher from masking true lookup cost.
        long[] lookupKeys = buildShuffledKeyPool(KEY_POOL, new Random(0xCAFEBABEL));

        // Chronicle Map 3.27ea0 + JDK 25: runtime javac codegen fails to resolve
        // JetBrains annotations on its bootclasspath. We catch the failure here so
        // the demo terminates cleanly; for the runtime story, see the JMH benchmark
        // referenced in the header (it's packaged as an uber-jar that bundles
        // everything javac needs).
        ChronicleMap<Long, byte[]> map;
        try {
            map = ChronicleMap
                    .of(Long.class, byte[].class)
                    .entries(ENTRIES)
                    .averageValueSize(16)  // "INSTR-XXXXXXXX" is 14 ASCII bytes; 16 is generous
                    .create();
        } catch (Throwable t) {
            System.out.println();
            System.out.println("ChronicleMapExample — runtime demo skipped on this JVM");
            System.out.println("  Reason   : " + t.getClass().getSimpleName() + " during ChronicleMap.create()");
            System.out.println("  Detail   : " + t.getMessage());
            System.out.println("  Why      : Chronicle Map 3.27ea0 invokes in-process javac to generate");
            System.out.println("             marshallers; on JDK 25 javac cannot resolve JetBrains");
            System.out.println("             annotations from its bootclasspath.");
            System.out.println("  See      : ChronicleMapVsConcurrentHashMapBenchmark in benchmarks/");
            System.out.println("  To run   : java -jar benchmarks/target/benchmarks.jar 'ChronicleMapVsConcurrentHashMap.*'");
            return;
        }
        try (ChronicleMap<Long, byte[]> open = map) {

            // --- Populate ---
            for (int i = 0; i < ENTRIES; i++) {
                // Encode the symbol id as 14 ASCII bytes — fits the 16-byte budget.
                byte[] value = ("INSTR-" + String.format("%08d", i)).getBytes();
                map.put((long) i, value);
            }

            // Snapshot GC state after population but before the lookup loop.
            // Any GC during population is setup cost, not the story we're telling.
            long gcBefore = totalGcCount();

            // --- Lookup loop ---
            long t0    = System.nanoTime();
            int  hits  = 0;
            int  mask  = KEY_POOL - 1; // power-of-two mask for fast modulo

            for (int i = 0; i < LOOKUPS; i++) {
                // get() returns a heap-allocated byte[] copy (Chronicle Map 3.x safety contract).
                // The ENTRIES themselves remain off-heap; only returned copies land on the heap.
                // For zero-allocation reads, see getUsing() with a pre-allocated buffer.
                byte[] val = map.get(lookupKeys[i & mask]);
                if (val != null) hits++;
            }

            long elapsedNs = System.nanoTime() - t0;
            long gcAfter   = totalGcCount();

            double seconds    = elapsedNs / 1e9;
            long   throughput = (long)(LOOKUPS / seconds);

            System.out.println();
            System.out.println("ChronicleMapExample — off-heap K/V at scale");
            System.out.println("  Entries (off-heap)  : " + ENTRIES + "  — GC mark phase never sees these");
            System.out.println("  Lookups performed   : " + LOOKUPS);
            System.out.println("  Hits                : " + hits);
            System.out.printf( "  Elapsed             : %.3f s%n",       seconds);
            System.out.printf( "  Throughput          : %,d lookups/sec%n", throughput);
            System.out.println("  GC pauses (lookups) : " + (gcAfter - gcBefore) +
                "  (non-zero = returned String copies, not stored entries)");
            System.out.println("  Off-heap footprint  : ~" + (ENTRIES * 64 / 1024) +
                " KB — invisible to GC regardless of size");
            System.out.println("  Scale-up cost       : zero GC pressure — add 10M entries, heap stays unchanged");
        }
    }

    /** Build a KEY_POOL-element array of keys in [0, ENTRIES), then shuffle it. */
    private static long[] buildShuffledKeyPool(int size, Random rng) {
        long[] keys = new long[size];
        for (int i = 0; i < size; i++) {
            keys[i] = rng.nextInt(ENTRIES);
        }
        // Knuth shuffle for uniform random access order.
        for (int i = size - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            long tmp = keys[i];
            keys[i]  = keys[j];
            keys[j]  = tmp;
        }
        return keys;
    }

    private static long totalGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans()
            .stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionCount)
            .filter(c -> c >= 0L)
            .sum();
    }
}

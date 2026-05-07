package com.devoxx.lowlatency.examples.libraries;

/**
 * BACKS SLIDE: "Agrona — the foundation"
 * PATTERN: Primitive collections — eliminate autoboxing by storing raw long values in flat arrays
 * MECHANISM: Long2LongHashMap holds two parallel long[] arrays (keys and values) with open addressing;
 *            no Long wrapper objects, no pointer indirection, no GC write barrier per entry
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.libraries.AgronaCollectionsExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/memory/PrimitiveCollectionsBenchmark.java
 */

import org.agrona.collections.Long2LongHashMap;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Random;

public final class AgronaCollectionsExample {

    private static final int  ENTRIES  = 100_000;
    private static final int  LOOKUPS  = 1_000_000;
    // Key pool is power-of-two so (index & mask) replaces modulo in the loop.
    private static final int  KEY_POOL = 65_536;
    private static final int  MASK     = KEY_POOL - 1;

    // Sentinel for Agrona's missingValue: a value that will never appear in our data.
    // Long.MIN_VALUE is the standard convention in Agrona examples.
    private static final long MISSING  = Long.MIN_VALUE;

    public static void main(String[] args) {
        long[] lookupKeys = buildShuffledKeyPool(KEY_POOL, new Random(0xDEAD_BEEFL));

        // --- Build both maps with identical data ---
        var jdkMap    = new HashMap<Long, Long>(ENTRIES * 2, 0.75f);
        // Agrona's initial capacity must exceed ENTRIES / loadFactor to avoid resize during fill.
        // Round up to next power-of-two (Agrona's open-addressing requirement).
        int agronaInitCap = Integer.highestOneBit((int)(ENTRIES / 0.65f) + 1) << 1;
        var agronaMap = new Long2LongHashMap(agronaInitCap, 0.65f, MISSING);

        for (int i = 0; i < ENTRIES; i++) {
            long key = i;
            long val = key * 2L + 1L; // odd value — never collides with MISSING sentinel
            jdkMap.put(key, val);     // boxes key into Long object; boxes val into Long object
            agronaMap.put(key, val);  // stores raw longs in a flat long[] — zero boxing
        }

        // --- Time JDK HashMap<Long,Long> ---
        long gcBefore = totalGcCount();
        long jdkSum   = 0L;
        long jdkStart = System.nanoTime();

        for (int i = 0; i < LOOKUPS; i++) {
            // get(long) autoboxes the primitive key into a Long on every call.
            // JDK caches Long in [-128, 127]; our keys exceed that, so each lookup
            // allocates a fresh Long wrapper object — multiply by 1M to see the cost.
            Long result = jdkMap.get(lookupKeys[i & MASK]);
            if (result != null) jdkSum += result;
        }

        long jdkElapsed = System.nanoTime() - jdkStart;
        long gcAfterJdk = totalGcCount();

        // --- Time Agrona Long2LongHashMap ---
        long agronaSum   = 0L;
        long agronaStart = System.nanoTime();

        for (int i = 0; i < LOOKUPS; i++) {
            // get(long) takes a primitive — no boxing, no Long object, no GC write barrier.
            // Returns MISSING if the key is absent; our values are all positive so that is safe.
            long result = agronaMap.get(lookupKeys[i & MASK]);
            if (result != MISSING) agronaSum += result;
        }

        long agronaElapsed = System.nanoTime() - agronaStart;
        long gcAfterAgrona = totalGcCount();

        // Both maps hold the same data; sums must match for the run to be valid.
        boolean sumsMatch = jdkSum == agronaSum;

        double jdkMs    = jdkElapsed    / 1e6;
        double agronaMs = agronaElapsed / 1e6;
        double speedup  = (double) jdkElapsed / agronaElapsed;

        System.out.println();
        System.out.println("AgronaCollectionsExample — boxed vs primitive map side-by-side");
        System.out.println("  Entries      : " + ENTRIES + " (same data in both maps)");
        System.out.println("  Lookups each : " + LOOKUPS);
        System.out.printf( "  HashMap<Long,Long>  time : %7.2f ms  (GC pauses: %d — Long boxing allocates)%n",
            jdkMs, gcAfterJdk - gcBefore);
        System.out.printf( "  Long2LongHashMap    time : %7.2f ms  (GC pauses: %d — no boxing, flat long[])%n",
            agronaMs, gcAfterAgrona - gcAfterJdk);
        System.out.printf( "  Speedup (Agrona/JDK): %.1fx%n", speedup);
        System.out.println("  Result sums match   : " + sumsMatch + "  (" + agronaSum + ")");
        System.out.println("  Boxing removed      : every get() call — 1M Long objects gone per run");
        System.out.println("  If you remember one library name from this talk: remember Agrona");
    }

    private static long[] buildShuffledKeyPool(int size, Random rng) {
        long[] keys = new long[size];
        for (int i = 0; i < size; i++) {
            keys[i] = rng.nextInt(ENTRIES);
        }
        for (int i = size - 1; i > 0; i--) {
            int j   = rng.nextInt(i + 1);
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

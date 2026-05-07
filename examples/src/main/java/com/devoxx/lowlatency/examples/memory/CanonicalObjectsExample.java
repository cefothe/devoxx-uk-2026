package com.devoxx.lowlatency.examples.memory;

import java.math.BigDecimal;
import java.util.IdentityHashMap;

/**
 * BACKS SLIDE: "The pool's evil twins"
 * PATTERN: Canonical object reuse for immutable value types
 * MECHANISM: The JVM caches a fixed set of commonly used immutable instances.
 *            Returning the cached instance instead of allocating a new wrapper
 *            costs zero bytes per lookup once the cache is warm.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.memory.CanonicalObjectsExample
 */
public final class CanonicalObjectsExample {

    private static final int ITERATIONS = 1_000_000;

    public static void main(String[] args) {
        // -----------------------------------------------------------------------
        // Boolean: exactly two instances exist in the JVM — TRUE and FALSE.
        // Every Boolean.valueOf call returns one of those two; no allocation ever.
        // -----------------------------------------------------------------------
        IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();
        for (int i = 0; i < ITERATIONS; i++) {
            seen.put((i & 1) == 0 ? Boolean.TRUE : Boolean.FALSE, null);
        }
        int booleanDistinct = seen.size(); // 2

        // -----------------------------------------------------------------------
        // Integer cache: JLS §5.1.7 guarantees valueOf returns a cached instance
        // for -128..127. Outside that range, each call allocates a new wrapper.
        // -----------------------------------------------------------------------
        seen.clear();
        for (int i = 0; i < ITERATIONS; i++) {
            // Maps loop index to the full cached range -128..127 (256 values).
            seen.put(Integer.valueOf((i % 256) - 128), null);
        }
        int integerCacheDistinct = seen.size(); // 256

        // Outside the cache: same VALUE, different identity.
        Integer x = Integer.valueOf(200);
        Integer y = Integer.valueOf(200);
        boolean sameInstance = (x == y); // false — each call allocates a fresh Long

        // -----------------------------------------------------------------------
        // BigDecimal: ZERO, ONE, and TEN are canonical singletons defined in the JDK.
        // Using them avoids allocating a BigDecimal header + internal long array.
        // -----------------------------------------------------------------------
        seen.clear();
        for (int i = 0; i < ITERATIONS; i++) {
            seen.put(BigDecimal.ZERO, null);
        }
        int bigDecimalDistinct = seen.size(); // 1

        // For contrast: BigDecimal.valueOf(0) also returns a canonical, but
        // new BigDecimal(0) always allocates. Use the constant, not the constructor.
        BigDecimal canonical = BigDecimal.ZERO;
        BigDecimal fresh     = new BigDecimal(0);
        boolean zerosAreDistinct = (canonical != fresh); // true — fresh is a new object

        System.out.println("--- Canonical objects ---");
        System.out.printf("Boolean lookups       : %,d   distinct instances : %d (TRUE + FALSE only)%n",
                ITERATIONS, booleanDistinct);
        System.out.printf("Integer cache lookups : %,d   distinct instances : %d (-128..127 cached by JLS)%n",
                ITERATIONS, integerCacheDistinct);
        System.out.printf("Integer.valueOf(200) == Integer.valueOf(200) : %b (outside cache, new object each call)%n",
                sameInstance);
        System.out.printf("BigDecimal.ZERO lookups: %,d   distinct instances : %d%n",
                ITERATIONS, bigDecimalDistinct);
        System.out.printf("BigDecimal.ZERO != new BigDecimal(0)         : %b (constructor allocates)%n",
                zerosAreDistinct);
        System.out.println("Rule: for immutable values, share the one true instance. Never construct.");
    }
}

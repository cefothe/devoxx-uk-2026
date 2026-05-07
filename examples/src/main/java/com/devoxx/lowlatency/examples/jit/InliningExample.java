package com.devoxx.lowlatency.examples.jit;

/**
 * BACKS SLIDE: "The 35-byte trap"
 * PATTERN: Method size below/above the MaxInlineSize threshold
 * MECHANISM: C2 inlines callees whose bytecode fits in 35 bytes unconditionally. A method
 *            one bytecode instruction over the threshold crosses the cliff and pays full
 *            method-dispatch overhead on every call — no stack-frame elision, no cross-boundary
 *            optimisations, no register-allocation over the combined body.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.jit.InliningExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/jit/InliningThresholdBenchmark.java
 */
public class InliningExample {

    private static final int WARMUP     = 100_000;
    private static final int ITERATIONS = 10_000_000;

    // Prevents the loop from being optimised away: store final accumulator here
    // so the JIT sees the value escape to a field it cannot prove is dead.
    @SuppressWarnings("unused")
    private static volatile long checksum;

    public static void main(String[] args) {
        // Drive both methods to C2 before measuring.
        // The inner loop of each timing block will trigger OSR compilation;
        // a separate warmup ensures the method-entry path is also C2-compiled.
        warmUp();

        long smallNs = timeSmall();
        long largeNs = timeLarge();

        double ratioX = (double) largeNs / Math.max(smallNs, 1);

        System.out.println();
        System.out.println("--- The 35-byte trap ---");
        System.out.println("Same arithmetic. Different bytecode size. Different performance.");
        System.out.println();
        System.out.printf("Small method (6 bytes,  inlined): %,10d ms for %,d calls%n",
                smallNs / 1_000_000, ITERATIONS);
        System.out.printf("Large method (46 bytes, not inlined): %,7d ms for %,d calls%n",
                largeNs / 1_000_000, ITERATIONS);
        System.out.println();
        System.out.printf("Ratio: %.1fx slower when inlining is blocked%n", ratioX);
        System.out.println();
        System.out.println("Verify with: -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining");
        System.out.println("You will see '@ <offset> ...::smallCompute (6 bytes)   inline (hot)'");
        System.out.println("and '@ <offset> ...::largeCompute (46 bytes)   too big to inline'");
    }

    private static void warmUp() {
        long acc = 0;
        for (int i = 0; i < WARMUP; i++) {
            acc += smallCompute(i & 0xFF | 1, (i >> 8) & 0xFF | 2);
            acc += largeCompute(i & 0xFF | 1, (i >> 8) & 0xFF | 2);
        }
        checksum = acc;
    }

    private static long timeSmall() {
        long acc = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            // i varies every iteration — the JIT cannot hoist the call out of the loop.
            acc += smallCompute(i & 0xFF | 1, (i >> 8) & 0xFF | 2);
        }
        long ns = System.nanoTime() - t0;
        checksum ^= acc;    // consume acc so the loop body cannot be eliminated
        return ns;
    }

    private static long timeLarge() {
        long acc = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            acc += largeCompute(i & 0xFF | 1, (i >> 8) & 0xFF | 2);
        }
        long ns = System.nanoTime() - t0;
        checksum ^= acc;
        return ns;
    }

    // -----------------------------------------------------------------------
    // Helper methods — bytecode sizes are the design intent; do not refactor
    // -----------------------------------------------------------------------

    /**
     * 6-byte bytecode: iload_0, iload_1, imul, iload_0, iadd, ireturn.
     * C2 inlines this unconditionally at every hot call site (MaxInlineSize = 35).
     * After inlining, the entire expression lives in registers: no stack frame, no spill.
     */
    private static int smallCompute(int a, int b) {
        return a * b + a;
    }

    /**
     * 46-byte bytecode: same net computation (a*b+a) reached via seven local-variable
     * steps that javac cannot fold because a and b are method parameters, not compile-time
     * constants. Each step emits full iload/istore bytecode pairs.
     *
     * The identity round-trips (s4+a-a, s5+b-b, s6+a-a) evaluate to zero at runtime —
     * the JIT would discover this and fold them *only if* it could inline the method first.
     * Because the bytecode exceeds 35 bytes, inlining is blocked before the JIT ever sees
     * the structure: the optimisation opportunity is hidden behind the size gate.
     *
     * Do not simplify these steps. They are the deliberate padding that reproduces
     * Stefan's production incident: an 8-byte helper extraction that killed inlining.
     */
    private static int largeCompute(int a, int b) {
        int s1 = a + b;       // a+b             [4 bytes: iload_0, iload_1, iadd, istore_2]
        int s2 = s1 - b;      // = a             [4 bytes: iload_2, iload_1, isub, istore_3]
        int s3 = s2 * b;      // = a*b           [5 bytes: iload_3, iload_1, imul, istore 4]
        int s4 = s3 + a;      // = a*b+a (result)[6 bytes: iload 4, iload_0, iadd, istore 5]
        int s5 = s4 + a - a;  // = s4            [8 bytes]
        int s6 = s5 + b - b;  // = s5            [8 bytes]
        int s7 = s6 + a - a;  // = s6            [8 bytes: total so far 4+4+5+6+8+8+8 = 43]
        return s7;             //                 [3 bytes: iload 8, ireturn → total 46]
    }
}

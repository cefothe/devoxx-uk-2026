package com.devoxx.lowlatency.examples.jit;

/**
 * BACKS SLIDE: "The JIT compilation pipeline"
 * PATTERN: Monomorphic → deoptimisation → bimorphic compilation cycle
 * MECHANISM: C2 speculatively inlines a virtual call site when it sees only one receiver type.
 *            Introducing a second type at the call site fails the inline cache, forces the JIT
 *            to throw away the compiled frame, fall back to interpreter, re-profile, and recompile
 *            as a bimorphic dispatch. The interpreter window is the latency cliff.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.jit.DeoptimizationExample
 *
 * To see the deoptimisation events in the JVM output, add these flags:
 *   -XX:+PrintCompilation
 *   -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining
 * Look for lines tagged "made not entrant" (the compiled method was invalidated) and
 * "uncommon trap" (the interpreter bail-out point that triggered the deopt).
 */
public class DeoptimizationExample {

    // Power-of-two inner loop size — enough iterations per call that each timed phase
    // produces a number measurable with System.nanoTime() resolution (~20 ns on most JVMs).
    private static final int INNER      = 4_096;
    // Invocation counts for each phase
    private static final int MONO_WARM  = 15_000;   // drive runLoop to C2 monomorphic
    private static final int MEASURE    = 2_000;    // calls timed per phase
    private static final int BI_WARM    = 15_000;   // drive runLoop to C2 bimorphic

    // Accumulates results so the JIT cannot eliminate the virtual call as dead code.
    private static volatile long sink;

    public static void main(String[] args) {
        Validator cheap     = new CheapValidator();
        Validator expensive = new ExpensiveValidator();

        // ----------------------------------------------------------------
        // Phase 1: monomorphic warmup — only CheapValidator visits runLoop
        // C2 profiles the call site, sees a single receiver type, speculatively
        // inlines CheapValidator.validate() and eliminates the virtual dispatch.
        // ----------------------------------------------------------------
        long acc = 0;
        for (int i = 0; i < MONO_WARM; i++) acc += runLoop(cheap, INNER);
        sink = acc;

        long t = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) acc += runLoop(cheap, INNER);
        long monoMs = (System.nanoTime() - t) / 1_000_000;
        sink ^= acc;

        // ----------------------------------------------------------------
        // Phase 2: introduce ExpensiveValidator — triggers deoptimisation
        // The first runLoop call with a new receiver type fails the inline cache.
        // C2 deoptimises the compiled runLoop frame (marks it "made not entrant"),
        // continues in interpreter, re-profiles, and recompiles bimorphically.
        // The measured block captures the interpreter window + early bimorphic JIT.
        // ----------------------------------------------------------------
        t = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) acc += runLoop(expensive, INNER);
        long deoptMs = (System.nanoTime() - t) / 1_000_000;
        sink ^= acc;

        // ----------------------------------------------------------------
        // Phase 3: bimorphic steady state — both types seen, C2 has recompiled
        // C2 emits a two-entry inline cache: one for CheapValidator, one for
        // ExpensiveValidator. No further deoptimisations for these two types.
        // ----------------------------------------------------------------
        for (int i = 0; i < BI_WARM; i++) acc += runLoop(i % 2 == 0 ? cheap : expensive, INNER);
        sink ^= acc;

        t = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) acc += runLoop(i % 2 == 0 ? cheap : expensive, INNER);
        long biMs = (System.nanoTime() - t) / 1_000_000;
        sink ^= acc;

        System.out.println();
        System.out.println("--- The JIT compilation pipeline: deoptimisation visible ---");
        System.out.println();
        System.out.printf("Phase 1 — monomorphic C2  (%,d calls × %,d iters): %,6d ms%n",
                MEASURE, INNER, monoMs);
        System.out.printf("Phase 2 — post-deopt      (%,d calls × %,d iters): %,6d ms%n",
                MEASURE, INNER, deoptMs);
        System.out.printf("Phase 3 — bimorphic C2    (%,d calls × %,d iters): %,6d ms%n",
                MEASURE, INNER, biMs);
        System.out.println();
        System.out.printf("Deopt overhead vs mono: %.1fx%n",
                (double) deoptMs / Math.max(monoMs, 1));
        System.out.println();
        System.out.println("Phase 2 includes the interpreter recovery window after deoptimisation.");
        System.out.println("Run with -XX:+PrintCompilation to see 'made not entrant' and 'uncommon trap'.");
        System.out.println("Stable inputs = stable types = stable performance. Surprises = latency cliffs.");
    }

    // The hot loop — C2 will speculatively inline validator.validate() once this
    // call site has been observed as monomorphic for long enough.
    // Inner loop size must be power-of-two so modulo in callers is a bitmask.
    private static long runLoop(Validator validator, int iterations) {
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += validator.validate(i & 0xFF);
        }
        return sum;
    }

    // -----------------------------------------------------------------------
    // Validator hierarchy — two implementations to force a bimorphic call site
    // -----------------------------------------------------------------------

    interface Validator {
        int validate(int x);
    }

    // Deliberately tiny so C2 is happy to inline it as the monomorphic receiver.
    // One instruction beyond the guard: the guard itself is the entire "computation".
    static final class CheapValidator implements Validator {
        @Override
        public int validate(int x) {
            return x & 0xFF;
        }
    }

    // Slightly more work — represents the "new type introduced after go-live" scenario
    // that breaks the JIT's monomorphic assumption and triggers the deopt.
    static final class ExpensiveValidator implements Validator {
        @Override
        public int validate(int x) {
            int masked = x & 0xFF;
            return masked >= 0 && masked < 256 ? masked : -1;
        }
    }
}

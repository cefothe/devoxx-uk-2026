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
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * BACKS SLIDE: "The 35-Byte Rule That Cost Me Two Days"
 *
 * <p>CLAIM: A method helper that crosses the 35-byte {@code -XX:MaxInlineSize} bytecode threshold
 * is 5–10× slower in a tight JMH loop — matching Stefan's production incident where an 8-byte
 * refactoring killed inlining on the order-validation hot path.
 *
 * <p>From the JavaPro article:
 * <blockquote>
 * "I spent two days debugging why our order validation suddenly got 10× slower after I extracted
 * a helper method. Turns out I crossed the MaxInlineSize threshold. The JIT won't inline methods
 * bigger than 35 bytes of bytecode by default. My 'clean' refactoring added 8 bytes of bytecode
 * to the caller, pushed it over 35, killed inlining. 10× slower."
 * </blockquote>
 *
 * <p>MECHANISM:
 * <ul>
 *   <li><b>MaxInlineSize (default: 35 bytes)</b>: The C2 JIT's "trivially small" threshold. Any
 *       callee whose bytecode fits within 35 bytes is unconditionally inlined at every call site
 *       (subject to call-site profiling). Inlining eliminates the method dispatch, enables
 *       register allocation across the merged code, and unlocks further optimisations such as
 *       constant folding, dead-code elimination, and escape analysis over the combined body.</li>
 *   <li><b>FreqInlineSize (default: 325 bytes)</b>: A larger callee can still be inlined if it
 *       is "hot enough", but only at the most frequently-executed call sites. Our large helper
 *       sits between 35 and 325 bytes and will NOT be inlined in a warm C2 benchmark loop
 *       because C2 sees the call site as polymorphic / not exceeding the inline budget.</li>
 *   <li><b>Call overhead without inlining</b>: Every call to the non-inlined helper pays:
 *       register save/restore (callee-saved regs), a new stack frame push/pop, and — crucially —
 *       breaks the JIT's ability to see the computation context. Optimisations that would have
 *       folded constants or eliminated branches are suppressed at the call boundary.</li>
 * </ul>
 *
 * <p>BYTECODE SIZE (verified with {@code javap -p -c} on JDK 25):
 * <pre>
 *   smallCompute(int, int):   6 bytes  (offsets 0–5:  iload_0, iload_1, imul, iload_0, iadd, ireturn)
 *   largeCompute(int, int):  46 bytes  (offsets 0–45: 7 local-variable steps + ireturn)
 * </pre>
 * The 40-byte gap clearly brackets the {@code MaxInlineSize=35} threshold.
 * Verify with: {@code javap -p -c -classpath target/classes com.devoxx.lowlatency.jit.InliningThresholdBenchmark}
 *
 * <p>FORCED-INLINING VARIANT: {@code largeForceInlined} applies
 * {@code -XX:CompileCommand=inline,...::largeCompute} via {@link Fork#jvmArgsAppend()}.
 * The forced-inline result should recover to the level of {@code smallInlineable}, proving that
 * the bytecode-size gate — not the computation itself — is the performance bottleneck.
 *
 * <p>SEE ALSO: {@code part1-jvm-foundation.md} §"The JIT Compiler: Your Secret Weapon",
 * §"How JIT Compilation Works". Stefan's production story is in
 * {@code final-article/FINAL-ARTICLE-FOR-SUBMISSION.md} §"JIT Compilation" intro paragraph.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G,
        "-XX:+PrintInlining"   // diagnostic: shows inlining decisions in JVM output (fork 1 only)
})
@SuppressWarnings("unused")
public class InliningThresholdBenchmark {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class ComputeState {
        int a;
        int b;

        @Setup(Level.Iteration)
        public void setup() {
            // Use non-constant values so the JIT cannot constant-fold the computation away.
            // The values change every iteration so C2 cannot hoist the computation out of the loop.
            a = (int) (System.nanoTime() & 0xFF) | 1; // always odd (avoids zero-multiply edge case)
            b = (int) (System.nanoTime() >>> 8 & 0xFF) | 2;
        }
    }

    // -------------------------------------------------------------------------
    // Helper methods — bytecode sizes are critical; do not refactor without javap verification
    // -------------------------------------------------------------------------

    /**
     * Small helper: {@code a * b + a}.
     *
     * <p>Bytecode (javap -c output):
     * <pre>
     *   0: iload_0         // load a
     *   1: iload_1         // load b
     *   2: imul
     *   3: iload_0         // load a
     *   4: iadd
     *   5: ireturn
     * </pre>
     * Total: <b>6 bytes</b> — well under the 35-byte MaxInlineSize threshold.
     * C2 inlines this unconditionally at every hot call site.
     */
    private static int smallCompute(int a, int b) {
        return a * b + a;
    }

    /**
     * Large helper: same computation ({@code a * b + a}) but padded with identity operations
     * on the runtime parameters to inflate the bytecode over 35 bytes.
     *
     * <p>javac does NOT constant-fold expressions involving method parameters (they are not
     * compile-time constants). Each intermediate step ({@code s1 = a + b}, {@code s2 = s1 - b},
     * etc.) genuinely adds bytecode instructions. The final result is bit-identical to
     * {@link #smallCompute} for all inputs (can be verified in the forced-inline variant).
     *
     * <p>Verified bytecode breakdown ({@code javap -p -c}, JDK 25):
     * <pre>
     *    0: iload_0              s1 = a + b       4 bytes (0–3)
     *    1: iload_1
     *    2: iadd
     *    3: istore_2
     *    4: iload_2              s2 = s1 - b      4 bytes (4–7)
     *    5: iload_1
     *    6: isub
     *    7: istore_3
     *    8: iload_3              s3 = s2 * b      5 bytes (8–12)  ← istore 4 = 2 bytes (wide form)
     *    9: iload_1
     *   10: imul
     *   11: istore        4
     *   13: iload         4      s4 = s3 + a      6 bytes (13–18)
     *   15: iload_0
     *   16: iadd
     *   17: istore        5
     *   19: iload         5      s5 = s4+a-a      8 bytes (19–26)
     *   21: iload_0
     *   22: iadd
     *   23: iload_0
     *   24: isub
     *   25: istore        6
     *   27: iload         6      s6 = s5+b-b      8 bytes (27–34)
     *   ...
     *   35: iload         7      s7 = s6+a-a      8 bytes (35–42)
     *   ...
     *   43: iload         8      return s7         3 bytes (43–45)
     *   45: ireturn
     * </pre>
     * Total: <b>46 bytes</b> — definitively above the 35-byte inlining cliff.
     *
     * <p>The XOR terms ({@code ^ s5 ^ s6}) evaluate to zero at runtime (their round-trips cancel),
     * so the returned value equals {@code a * b + a}. The JIT understands this only AFTER inlining;
     * the inlining decision is made on raw bytecode size BEFORE the JIT sees the structure.
     */
    private static int largeCompute(int a, int b) {
        // Step 1–4: equivalent to a*b+a via round-trip additions and subtractions.
        // javac emits full bytecode for each step because a and b are not compile-time constants
        // (method parameters are never compile-time constants in Java).
        int s1 = a + b;       // a+b             [4 bytes: iload_0, iload_1, iadd, istore_2]
        int s2 = s1 - b;      // = a              [4 bytes: iload_2, iload_1, isub, istore_3]
        int s3 = s2 * b;      // = a*b            [5 bytes: iload_3, iload_1, imul, istore 4]
        int s4 = s3 + a;      // = a*b+a (result) [6 bytes: iload 4, iload_0, iadd, istore 5]

        // Steps 5–7: identity padding — each line evaluates to the same value as s4 at runtime,
        // but javac CANNOT know that (a and b are runtime values) so it emits full bytecode.
        // Do NOT simplify: this is the deliberate bytecode inflation that pushes the method past
        // the 35-byte MaxInlineSize threshold and reproduces Stefan's production incident.
        int s5 = s4 + a - a;  // = s4 [8 bytes: iload 5, iload_0, iadd, iload_0, isub, istore 6]
        int s6 = s5 + b - b;  // = s5 [8 bytes: iload 6, iload_1, iadd, iload_1, isub, istore 7]
        int s7 = s6 + a - a;  // = s6 [8 bytes: iload 7, iload_0, iadd, iload_0, isub, istore 8]

        // Total so far: 4+4+5+6+8+8+8 = 43 bytes. Return adds iload 8 + ireturn = 3 bytes → 46 bytes total.
        // s7 == s6 == s5 == s4 == a*b+a at runtime; result is correct and identical to smallCompute.
        return s7;             //                  [3 bytes: iload 8, ireturn]
    }

    // -------------------------------------------------------------------------
    // Benchmarks
    // -------------------------------------------------------------------------

    /**
     * Hot path calls a 6-byte helper — inlined unconditionally by C2.
     *
     * <p>After inlining, the JIT sees {@code state.a * state.b + state.a} as a single combined
     * expression and can allocate it entirely in registers. No stack frame push/pop, no register
     * save/restore across a call boundary.
     *
     * <p>Expected: ~2–5 ns/op (two multiplies and an add in registers).
     */
    @Benchmark
    public void smallInlineable(ComputeState state, Blackhole bh) {
        bh.consume(smallCompute(state.a, state.b));
    }

    /**
     * Hot path calls a ~50-byte helper — NOT inlined by C2 at {@code MaxInlineSize=35}.
     *
     * <p>Even after many invocations, C2 will not inline {@link #largeCompute} because its
     * bytecode exceeds {@code MaxInlineSize}. Every call pays the full method dispatch cost:
     * new stack frame, register spill, call instruction, return, and loss of surrounding context
     * for further optimisations.
     *
     * <p>Expected: ~15–50 ns/op — 5–10× slower than {@code smallInlineable} despite computing
     * the identical result. The benchmark reproduces Stefan's production incident at nanoscale.
     */
    @Benchmark
    public void largeNotInlineable(ComputeState state, Blackhole bh) {
        bh.consume(largeCompute(state.a, state.b));
    }

    /**
     * Same large helper, but forced inline via {@code -XX:CompileCommand}.
     *
     * <p>This fork overrides the JVM flags to add:
     * <pre>
     *   -XX:CompileCommand=inline,com.devoxx.lowlatency.jit.InliningThresholdBenchmark::largeCompute
     * </pre>
     * This instructs C2 to inline {@link #largeCompute} regardless of its bytecode size,
     * bypassing the {@code MaxInlineSize} gate entirely. With inlining forced, C2 can see
     * the full expression and fold the identity round-trips, recovering performance to the
     * level of {@code smallInlineable}.
     *
     * <p>If this variant returns latency close to {@code smallInlineable}, it conclusively proves
     * that bytecode size — not the computation — caused the 5–10× regression.
     *
     * <p>Expected: ~2–6 ns/op (matching {@code smallInlineable} after forced inlining and
     * subsequent constant folding of the identity round-trips).
     */
    @Benchmark
    @Fork(value = 2, jvmArgsAppend = {
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:+AlwaysPreTouch",
            "-Xms2g",
            "-Xmx2g",
            "-XX:+PrintInlining",
            "-XX:CompileCommand=inline,com.devoxx.lowlatency.jit.InliningThresholdBenchmark::largeCompute"
    })
    public void largeForceInlined(ComputeState state, Blackhole bh) {
        bh.consume(largeCompute(state.a, state.b));
    }
}

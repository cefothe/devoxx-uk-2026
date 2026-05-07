package com.devoxx.lowlatency.memory;

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
 * BACKS SLIDE: "Escape Analysis: When the JIT Eliminates Allocation for You"
 * CLAIM: When a {@code Point} record does not escape its creating method, JIT scalar
 *        replacement eliminates the heap allocation entirely — ~5–10× faster than the
 *        escaping variant and ≈ 0 bytes/op allocation rate.
 * MECHANISM: The HotSpot JIT (C2 compiler) performs escape analysis (EA) on every compiled
 *            method.  If an object is proven to never leave the method's scope, C2 applies
 *            "scalar replacement": the object's fields are promoted to virtual registers
 *            (or stack slots) and the heap allocation is removed.  Passing the result of
 *            {@code p.x() + p.y()} to a Blackhole is sufficient for correctness but does
 *            NOT force the Point to escape — the Blackhole only captures the primitive int,
 *            not the reference.  In the escaping variant, assigning to a {@code volatile}
 *            static field makes the point reference observable across threads, preventing EA.
 * SEE ALSO: results/analysis-and-narrative.md § 4
 *
 * <p><b>Disable escape analysis to confirm the effect:</b>
 * <pre>
 *   java -jar target/benchmarks.jar EscapeAnalysisBenchmark \
 *       -jvmArgsAppend "-XX:-DoEscapeAnalysis"
 * </pre>
 * With EA disabled, {@code nonEscaping} should regress to roughly the same cost as
 * {@code escaping}, proving the difference is purely EA-driven, not a coincidence.
 *
 * <p><b>Confirm scalar replacement via PrintInlining (side-run only — don't time this):</b>
 * <pre>
 *   java -XX:+UnlockDiagnosticVMOptions -XX:+PrintEliminateAllocations \
 *       -jar target/benchmarks.jar EscapeAnalysisBenchmark -f 0 -wi 3 -i 1
 * </pre>
 *
 * <p><b>Profiling allocation:</b>
 * <pre>
 *   java -jar target/benchmarks.jar EscapeAnalysisBenchmark -prof gc
 * </pre>
 * {@code gc.alloc.rate.norm} for {@code nonEscaping} ≈ 0 bytes/op;
 * for {@code escaping} ≈ 16–24 bytes/op (one {@link Point} record header + 2 ints).
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
public class EscapeAnalysisBenchmark {

    /**
     * Immutable 2D coordinate pair — declared as a Java 16+ record for conciseness.
     * Records are {@code final} and have compiler-generated accessors, {@code equals},
     * {@code hashCode}, and {@code toString}.  The JIT sees the same internal layout as
     * a hand-written POJO and applies EA equally to both forms.
     */
    record Point(int x, int y) {}

    /**
     * Escape sink: assigning to a {@code volatile} static makes a reference globally
     * visible, forcing the JIT to treat the object as escaped (cannot be scalar-replaced).
     * The {@code @SuppressWarnings("unused")} suppresses IDE warnings — the field IS read
     * by the JIT's escape analysis, just not by application code.
     */
    @SuppressWarnings("unused")
    private static volatile Point escapedPoint;

    // ── Input values: fixed at setup, read-only in hot path ─────────────────
    private int xVal;
    private int yVal;

    @Setup(Level.Trial)
    public void setup() {
        // Use values that cannot be constant-folded by the JIT
        xVal = (int)(System.nanoTime() & 0xFFF) + 1;   // 1–4096
        yVal = (int)(System.nanoTime() & 0x1FF) + 1;   // 1–512
    }

    // =========================================================================
    // BENCHMARKS
    // =========================================================================

    /**
     * Non-escaping path — EA + scalar replacement active.
     * <p>The {@link Point} object is created and consumed entirely within this method.
     * The Blackhole only receives the primitive {@code int} result of {@code x + y};
     * the Point reference never leaves the method frame.  C2 replaces the Point with two
     * virtual int registers, making the effective cost a single integer ADD instruction.
     *
     * <p>Expected: ~1–3 ns/op (pure integer arithmetic, no allocation).
     */
    @Benchmark
    @BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
    public void nonEscaping(Blackhole bh) {
        Point p = new Point(xVal, yVal);
        // Pass only the primitive result — p does NOT escape to the Blackhole
        bh.consume(p.x() + p.y());
    }

    /**
     * Escaping path — EA cannot apply; real heap allocation every call.
     * <p>Writing {@code p} to the {@code volatile} static field makes the reference
     * globally observable, so the JIT cannot prove "no other thread will read this."
     * EA is blocked; C2 must emit a full {@code new} instruction, a GC write barrier,
     * and a {@code putstatic}.
     *
     * <p>Expected: ~5–15 ns/op (object allocation + volatile store on AMD Zen 2).
     */
    @Benchmark
    @BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
    public void escaping(Blackhole bh) {
        Point p     = new Point(xVal, yVal);
        escapedPoint = p;               // forces real heap allocation + volatile write
        bh.consume(p.x() + p.y());
    }

    /**
     * Demonstrator: {@link #nonEscaping} with EA forcibly disabled (requires re-run with
     * {@code -XX:-DoEscapeAnalysis}).  This method documents the expected regression but
     * is NOT annotated with {@code @Benchmark} — it would produce identical results to
     * {@code escaping} when EA is off, which is the whole point.
     *
     * <p>To observe: run the full benchmark twice:
     * <ol>
     *   <li>Normal run (EA on, default): {@code nonEscaping} ≈ 1–3 ns, {@code escaping} ≈ 10–15 ns</li>
     *   <li>EA disabled run: {@code nonEscaping} ≈ 10–15 ns (regresses to same as escaping)</li>
     * </ol>
     */
    // NOT @Benchmark — documentation only
    @SuppressWarnings("unused")
    public void nonEscapingWithEaDisabled(Blackhole bh) {
        Point p = new Point(xVal, yVal);
        bh.consume(p.x() + p.y());
        // With -XX:-DoEscapeAnalysis this will allocate just like escaping()
    }
}

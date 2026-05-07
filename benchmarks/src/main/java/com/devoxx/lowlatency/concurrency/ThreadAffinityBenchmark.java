package com.devoxx.lowlatency.concurrency;

import com.devoxx.lowlatency.common.BenchmarkBase;
import net.openhft.affinity.AffinityLock;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * BACKS SLIDE: "Thread Affinity: Eliminating Scheduler Jitter"
 *
 * <p>CLAIM: Pinning a tight-loop thread to a specific CPU core with
 * {@link AffinityLock} reduces scheduling jitter, lowering p99/p99.9 latency and eliminating
 * the sporadic 10–100 µs spikes caused by the OS migrating the thread between cores. The median
 * (p50) latency is similar for both variants; the difference is visible only in the tail.
 *
 * <p>MECHANISM: On Linux, {@code sched_setaffinity(2)} binds a thread to one or more CPU cores.
 * The scheduler can no longer migrate the thread, so its L1/L2 cache state is always warm for
 * that core. Without affinity, the scheduler may migrate the thread when the core is needed for
 * another task, causing a cold-cache penalty (L3 miss: 40–80 ns) and a TLB flush (100–200 ns)
 * every time it migrates back. Under heavy system load, this produces occasional 10–100 µs
 * latency spikes in the p99+ tail — unacceptable in a sub-10 µs budget.
 *
 * <p>LINUX-ONLY: {@code @Contended} and {@code sched_setaffinity} are no-ops on macOS.
 * The {@code @Setup} method checks {@link BenchmarkBase#isLinux()} and throws
 * {@link IllegalStateException} with a clear message if run on macOS. Stefan runs this
 * benchmark exclusively on the AMD EPYC 7282 reference box.
 *
 * <p>SEE ALSO: section-4-single-threaded-wins.md § "Zero context switching".
 * BENCHMARK-METHODOLOGY.md § "Thread affinity benchmarks only work on Linux".
 *
 * <p>MEASUREMENT: {@link Mode#SampleTime} is essential here — it reports full latency
 * percentile distributions (p50, p90, p99, p99.9, p99.99) rather than averages.
 * Average time would hide the jitter spikes that are the whole point of this benchmark.
 *
 * <p>HOW TO RUN (on the EPYC box):
 * <pre>
 *   java -jar target/benchmarks.jar ThreadAffinityBenchmark \
 *        -prof gc \
 *        -rf json -rff results/raw/concurrency-affinity.json
 * </pre>
 */
@BenchmarkMode(Mode.SampleTime)       // SampleTime only — we care about tail percentiles
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS) // longer to capture rare spikes
@Fork(value = 2, jvmArgsAppend = {
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+AlwaysPreTouch",
        "-Xms512m",
        "-Xmx512m"   // small heap — tight loop, virtually no allocations
})
@State(Scope.Thread)  // Thread scope: each JMH worker thread gets its own AffinityLock
@Threads(1)           // Affinity is most meaningful on a single benchmark thread; no group needed
@SuppressWarnings("unused")
public class ThreadAffinityBenchmark {

    // =========================================================================
    // Linux guard
    // =========================================================================

    /**
     * Aborts on non-Linux platforms with a clear, actionable error message.
     *
     * <p>JMH does not have a built-in "skip" mechanism (unlike JUnit's {@code Assume}).
     * Throwing here is the accepted pattern — the benchmark will fail-fast with the message
     * rather than silently producing meaningless (no-op affinity) numbers.
     */
    @Setup(Level.Trial)
    public void guardLinuxOnly() {
        if (!BenchmarkBase.isLinux()) {
            throw new IllegalStateException(
                    "ThreadAffinityBenchmark requires Linux (sched_setaffinity). " +
                    "Current OS: " + System.getProperty("os.name") + ". " +
                    "Run this benchmark on the AMD EPYC reference box, not the Mac dev machine. " +
                    "See BENCHMARK-METHODOLOGY.md § 'Thread affinity benchmarks only work on Linux'."
            );
        }
    }

    // =========================================================================
    // Pinned-thread state (AffinityLock held for the benchmark trial duration)
    // =========================================================================

    @State(Scope.Thread)
    public static class PinnedState {
        AffinityLock affinityLock;

        @Setup(Level.Trial)
        public void acquireAffinity() {
            if (!BenchmarkBase.isLinux()) {
                return; // guardLinuxOnly will throw before we reach actual benchmarking
            }
            // acquireLock() calls sched_setaffinity on the calling thread.
            // It picks any free CPU core; the lock is released in teardown.
            affinityLock = AffinityLock.acquireLock();
        }

        @TearDown(Level.Trial)
        public void releaseAffinity() {
            if (affinityLock != null) {
                affinityLock.release();
                affinityLock = null;
            }
        }
    }

    // =========================================================================
    // Workload: tight arithmetic loop to expose scheduler migration jitter
    // =========================================================================

    /**
     * Inner loop constant. Chosen so one iteration takes ~1 µs on the EPYC 7282 @2.8 GHz,
     * making each sample roughly 1 µs in the median — any scheduler migration penalty
     * (10–100 µs) shows up clearly in the p99+ tail without being swamped by the work itself.
     */
    private static final int LOOP_ITERATIONS = 1_000;

    /**
     * A tiny sink to prevent loop body DCE. Declared as an instance field so the JIT cannot
     * prove it is dead. {@code Blackhole.consume()} is the real safeguard, but this adds a
     * believable mutation for the JIT to reason about.
     */
    private long sink;

    // =========================================================================
    // BENCHMARK 1: Unpinned (OS scheduler free to migrate between cores)
    // =========================================================================

    /**
     * Runs the tight loop on whatever core the OS assigns. The scheduler is free to migrate
     * this thread at any time, causing cold-cache refills, TLB flushes, and NUMA penalties.
     *
     * <p>Expected shape: p50 ≈ 300–800 ns, p99 ≈ 5–50 µs (scheduler migration spikes),
     * p99.9 may reach 100+ µs under system load.
     */
    @Benchmark
    public void unpinned(Blackhole bh) {
        long acc = 0L;
        for (int i = 0; i < LOOP_ITERATIONS; i++) {
            // Simple dependency chain prevents the JIT from reordering across iterations
            acc = acc * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
        }
        sink = acc;
        bh.consume(acc);
    }

    // =========================================================================
    // BENCHMARK 2: Pinned to core via AffinityLock
    // =========================================================================

    /**
     * Runs the identical tight loop on the core acquired by {@link PinnedState#acquireAffinity()}.
     * The OS cannot migrate this thread for the duration of the trial.
     *
     * <p>Expected shape: p50 ≈ 300–800 ns (same as unpinned), p99 and p99.9 significantly
     * lower — scheduler migration spikes disappear. The tail becomes flat relative to the median,
     * which is the observable signal the benchmark is designed to capture.
     */
    @Benchmark
    public void pinnedToCore(PinnedState pinned, Blackhole bh) {
        long acc = 0L;
        for (int i = 0; i < LOOP_ITERATIONS; i++) {
            acc = acc * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L;
        }
        sink = acc;
        bh.consume(acc);
    }
}

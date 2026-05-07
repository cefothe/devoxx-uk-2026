package com.devoxx.lowlatency.concurrency;

import com.devoxx.lowlatency.common.BenchmarkBase;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BACKS SLIDE: "Lock Contention: The Silent Latency Killer"
 *
 * <p>CLAIM: Under multi-thread contention, CAS (compare-and-swap) via {@link AtomicLong} is
 * 2–10× faster than {@code synchronized} and {@link ReentrantLock}. {@link LongAdder}'s striped
 * counters further reduce CAS collisions, making it the best choice for high-frequency counters
 * that tolerate reading an eventually-consistent sum.
 *
 * <p>MECHANISM: {@code synchronized} and {@code ReentrantLock} both serialize threads through OS
 * monitor enter/exit (futex on Linux), triggering context switches under contention. CAS uses a
 * hardware atomic instruction (LOCK XADD / LOCK CMPXCHG on x86) that retries in user space
 * without a kernel mode transition. {@link LongAdder} reduces CAS collision further by
 * maintaining per-CPU-stripe accumulators, merging only on {@code sum()}.
 *
 * <p>SEE ALSO: {@code section-4-single-threaded-wins.md}, part2-memory-and-concurrency.md §
 * "The Three Hidden Costs of Multi-Threading". Methodology: BENCHMARK-METHODOLOGY.md.
 *
 * <p>THREAD COUNTS: 1 (uncontended baseline), 2 (mild contention), 3 (full EPYC core
 * utilisation). Kept to 3 to match the 3-physical-core reference environment.
 *
 * <p>NOTE: Each variant is its own {@code @Group} so JMH allocates the requested thread count
 * across the group rather than spawning N×4 extra threads. Under {@code @Threads(N)} without
 * groups, JMH applies N threads to the *entire* benchmark run; grouping gives us precise
 * per-variant contention without cross-variant interference.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G
})
@SuppressWarnings("unused")
public class CasVsLockBenchmark {

    // -------------------------------------------------------------------------
    // Shared benchmark state — one instance per benchmark execution
    // -------------------------------------------------------------------------

    @State(Scope.Group)
    public static class SynchronizedState {
        volatile long counter = 0L;

        public synchronized void increment() {
            counter++;
        }
    }

    @State(Scope.Group)
    public static class ReentrantLockState {
        private final ReentrantLock lock = new ReentrantLock();
        long counter = 0L;

        public void increment() {
            lock.lock();
            try {
                counter++;
            } finally {
                lock.unlock();
            }
        }
    }

    @State(Scope.Group)
    public static class AtomicLongState {
        final AtomicLong counter = new AtomicLong(0L);
    }

    @State(Scope.Group)
    public static class LongAdderState {
        final LongAdder counter = new LongAdder();
    }

    // -------------------------------------------------------------------------
    // 1-thread groups (uncontended baseline)
    // -------------------------------------------------------------------------

    /** Uncontended {@code synchronized}: establishes memory-barrier cost even with one thread. */
    @Benchmark
    @Group("synchronized_t1")
    @GroupThreads(1)
    public void synchronizedIncrement_1t(SynchronizedState state, Blackhole bh) {
        state.increment();
        bh.consume(state.counter);
    }

    /** Uncontended {@link ReentrantLock}: lock() / unlock() round-trip. */
    @Benchmark
    @Group("reentrantLock_t1")
    @GroupThreads(1)
    public void reentrantLockIncrement_1t(ReentrantLockState state, Blackhole bh) {
        state.increment();
        bh.consume(state.counter);
    }

    /** Uncontended CAS: single hardware LOCK XADD instruction. */
    @Benchmark
    @Group("atomicLong_t1")
    @GroupThreads(1)
    public void atomicLongIncrement_1t(AtomicLongState state, Blackhole bh) {
        bh.consume(state.counter.incrementAndGet());
    }

    /** Uncontended {@link LongAdder}: hits the single cell with no stripe contention. */
    @Benchmark
    @Group("longAdder_t1")
    @GroupThreads(1)
    public void longAdderIncrement_1t(LongAdderState state, Blackhole bh) {
        state.counter.increment();
        bh.consume(state.counter);
    }

    // -------------------------------------------------------------------------
    // 2-thread groups (mild contention)
    // -------------------------------------------------------------------------

    /** 2-thread {@code synchronized}: first real contention, OS futex starts to bite. */
    @Benchmark
    @Group("synchronized_t2")
    @GroupThreads(2)
    public void synchronizedIncrement_2t(SynchronizedState state, Blackhole bh) {
        state.increment();
        bh.consume(state.counter);
    }

    /** 2-thread {@link ReentrantLock}: similar to synchronized but park/unpark path. */
    @Benchmark
    @Group("reentrantLock_t2")
    @GroupThreads(2)
    public void reentrantLockIncrement_2t(ReentrantLockState state, Blackhole bh) {
        state.increment();
        bh.consume(state.counter);
    }

    /** 2-thread CAS: two threads racing CMPXCHG — one wins, one retries in user space. */
    @Benchmark
    @Group("atomicLong_t2")
    @GroupThreads(2)
    public void atomicLongIncrement_2t(AtomicLongState state, Blackhole bh) {
        bh.consume(state.counter.incrementAndGet());
    }

    /** 2-thread {@link LongAdder}: stripe separation kicks in; minimal collision. */
    @Benchmark
    @Group("longAdder_t2")
    @GroupThreads(2)
    public void longAdderIncrement_2t(LongAdderState state, Blackhole bh) {
        state.counter.increment();
        bh.consume(state.counter);
    }

    // -------------------------------------------------------------------------
    // 3-thread groups (full EPYC core saturation — maximum contention)
    // -------------------------------------------------------------------------

    /**
     * 3-thread {@code synchronized}: worst case for the EPYC 3-core slice.
     * Expect 2–10× regression vs CAS under contention.
     */
    @Benchmark
    @Group("synchronized_t3")
    @GroupThreads(3)
    public void synchronizedIncrement_3t(SynchronizedState state, Blackhole bh) {
        state.increment();
        bh.consume(state.counter);
    }

    /** 3-thread {@link ReentrantLock}: explicit park/unpark path, similar cost to synchronized. */
    @Benchmark
    @Group("reentrantLock_t3")
    @GroupThreads(3)
    public void reentrantLockIncrement_3t(ReentrantLockState state, Blackhole bh) {
        state.increment();
        bh.consume(state.counter);
    }

    /** 3-thread CAS: 3-way CMPXCHG race — some retries but still user-space only. */
    @Benchmark
    @Group("atomicLong_t3")
    @GroupThreads(3)
    public void atomicLongIncrement_3t(AtomicLongState state, Blackhole bh) {
        bh.consume(state.counter.incrementAndGet());
    }

    /**
     * 3-thread {@link LongAdder}: each thread targets its own CPU stripe.
     * Expect latency close to the 1-thread baseline.
     */
    @Benchmark
    @Group("longAdder_t3")
    @GroupThreads(3)
    public void longAdderIncrement_3t(LongAdderState state, Blackhole bh) {
        state.counter.increment();
        bh.consume(state.counter);
    }
}

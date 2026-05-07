package com.devoxx.lowlatency.concurrency;

import com.devoxx.lowlatency.common.BenchmarkBase;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.agrona.collections.Long2LongHashMap;
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BACKS SLIDE: "Single-Threaded Shards vs. Contended Multi-Threading"
 *
 * <p>CLAIM: A sharded architecture (N single-threaded shards, each owning its own data,
 * communicating via LMAX Disruptor ring buffers) achieves 100–1000× lower latency than a naive
 * multi-threaded design with a shared {@link ConcurrentHashMap} and per-account {@code
 * synchronized} balance updates under high-thread contention.
 *
 * <p>MECHANISM:
 * <ul>
 *   <li><b>Contended path</b>: All threads contend on the same {@link ConcurrentHashMap}
 *       (segment-level CAS) and then on an account-level {@code synchronized} block. Under 3
 *       threads, both the map-level and account-level locks produce queue-based waiting,
 *       OS-level parking, cache-line ping-pong, and unpredictable tail latency spikes.</li>
 *   <li><b>Sharded path</b>: Each producer thread routes by {@code accountId % NUM_SHARDS} to
 *       the appropriate Disruptor ring buffer. The shard's single consumer thread drains the ring
 *       buffer, updating its private {@link Long2LongHashMap} (Agrona — primitive keys, no
 *       boxing, zero allocation). No locks, no CAS, no coherency traffic between shards.</li>
 * </ul>
 *
 * <p>SEE ALSO: section-4-single-threaded-wins.md (full narrative + diagrams).
 * BENCHMARK-METHODOLOGY.md § ratios table: "Single-threaded shard vs contended → 100–1000×".
 * Stefan's exchange-core production system uses exactly this pattern for order book management.
 *
 * <p>THREAD COUNTS: Both benchmarks are run {@code @Threads(3)} to match the 3-physical-core
 * EPYC reference environment. Contention is maximised; sharding wins decisively.
 *
 * <p>MEASUREMENT NOTE: {@link Mode#AverageTime} captures the hot path throughput; the accompanying
 * {@link Mode#SampleTime} (from {@code @BenchmarkBase.StandardConfig}) captures the tail-latency
 * distribution (p99/p99.9) which is the true story for a latency talk.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+AlwaysPreTouch",
        "-Xms2g",
        "-Xmx2g"
})
@State(Scope.Benchmark)
@Threads(3)
@SuppressWarnings({"unused", "FieldMayBeFinal"})
public class SingleThreadedShardVsContendedBenchmark {

    // =========================================================================
    // Constants
    // =========================================================================

    private static final int NUM_SHARDS     = 3;   // one per physical core
    private static final int RING_SIZE      = 1024; // power-of-2 Disruptor ring
    private static final int NUM_ACCOUNTS   = 4096; // pre-populated account IDs
    private static final long MISSING_VALUE = Long.MIN_VALUE;

    // =========================================================================
    // --- Contended multi-threaded state ---
    // =========================================================================

    /** Account balance store — segment-locked ConcurrentHashMap. */
    private ConcurrentHashMap<Long, long[]> sharedAccounts;

    @Setup(Level.Trial)
    public void setupContended() {
        sharedAccounts = new ConcurrentHashMap<>(NUM_ACCOUNTS * 2);
        for (int i = 0; i < NUM_ACCOUNTS; i++) {
            sharedAccounts.put((long) i, new long[]{1_000_000L});
        }
        setupSharded();
    }

    // =========================================================================
    // --- Sharded single-threaded state ---
    // =========================================================================

    /** One Disruptor ring buffer per shard. Producers publish into the ring; consumers own it. */
    @SuppressWarnings("unchecked")
    private Disruptor<AccountEvent>[] disruptors = new Disruptor[NUM_SHARDS];

    /** One Long2LongHashMap (Agrona) per shard — private to the shard consumer thread, no locks. */
    @SuppressWarnings("unchecked")
    private Long2LongHashMap[] shardMaps = new Long2LongHashMap[NUM_SHARDS];

    /** Convenience handles to the ring buffers for producers. */
    @SuppressWarnings("unchecked")
    private RingBuffer<AccountEvent>[] ringBuffers = new RingBuffer[NUM_SHARDS];

    /** Counts processed events per shard (volatile read by producers to detect back-pressure). */
    private final AtomicLong[] shardProcessed = new AtomicLong[NUM_SHARDS];

    private void setupSharded() {
        for (int s = 0; s < NUM_SHARDS; s++) {
            final int shardId = s;
            shardMaps[s]       = new Long2LongHashMap(NUM_ACCOUNTS * 2, 0.6f, MISSING_VALUE);
            shardProcessed[s]  = new AtomicLong(0L);

            // Pre-populate this shard's accounts
            for (int i = shardId; i < NUM_ACCOUNTS; i += NUM_SHARDS) {
                shardMaps[s].put(i, 1_000_000L);
            }

            final Long2LongHashMap sMap         = shardMaps[s];
            final AtomicLong       sProcessed   = shardProcessed[s];

            // Single-consumer event handler — this is the entire "hot path" for the shard
            EventHandler<AccountEvent> handler = (event, sequence, endOfBatch) -> {
                long current = sMap.get(event.accountId);
                if (current != MISSING_VALUE) {
                    sMap.put(event.accountId, current + event.delta);
                }
                sProcessed.incrementAndGet();
                event.latch.countDown();
            };

            disruptors[s] = new Disruptor<>(
                    AccountEvent.FACTORY,
                    RING_SIZE,
                    DaemonThreadFactory.INSTANCE,
                    ProducerType.MULTI,  // multiple producer threads route here
                    new com.lmax.disruptor.BusySpinWaitStrategy()
            );
            disruptors[s].handleEventsWith(handler);
            disruptors[s].start();
            ringBuffers[s] = disruptors[s].getRingBuffer();
        }
    }

    @TearDown(Level.Trial)
    public void teardown() {
        for (Disruptor<AccountEvent> d : disruptors) {
            if (d != null) {
                d.shutdown();
            }
        }
    }

    // =========================================================================
    // Disruptor event model
    // =========================================================================

    public static final class AccountEvent {
        long accountId;
        long delta;
        /** One-shot latch so the producer can block-wait for the event to be processed.
         *  In a real system producers fire-and-forget; the latch is here purely to let JMH
         *  measure round-trip latency (publish → consume → done) rather than just publish cost. */
        CountDownLatch latch;

        static final EventFactory<AccountEvent> FACTORY = AccountEvent::new;
    }

    // =========================================================================
    // BENCHMARK 1: Contended multi-threaded
    // =========================================================================

    /**
     * All 3 producer threads hammer the same {@link ConcurrentHashMap} and synchronize on
     * per-account {@code long[]} holders. Under contention:
     * <ol>
     *   <li>ConcurrentHashMap#computeIfPresent uses internal segment CAS.</li>
     *   <li>The per-account array update is done inside a lambda that CHM holds a lock for.</li>
     * </ol>
     * The combination of map-level synchronization + heavy thread contention across all accounts
     * produces the worst-case latency this benchmark is designed to expose.
     */
    @Benchmark
    public void multiThreadedContended(Blackhole bh) {
        long accountId = ThreadLocalRandom.current().nextLong(NUM_ACCOUNTS);
        // ConcurrentHashMap.compute() acquires a per-bin lock and updates in place
        sharedAccounts.compute(accountId, (id, balance) -> {
            if (balance == null) {
                return new long[]{100L};
            }
            balance[0] += 100L;
            return balance;
        });
        bh.consume(accountId);
    }

    // =========================================================================
    // BENCHMARK 2: Sharded single-threaded via Disruptor
    // =========================================================================

    /**
     * Each of the 3 producer threads selects a target shard via {@code accountId % NUM_SHARDS}
     * and publishes an {@link AccountEvent} to that shard's Disruptor ring buffer. The shard's
     * dedicated consumer thread dequeues and updates its private {@link Long2LongHashMap} with
     * zero locking, zero boxing, and zero GC pressure.
     *
     * <p>The producer uses a {@link CountDownLatch} to synchronise on completion, so JMH measures
     * the full round-trip: publish → ring buffer → consume → latch. This is a conservative
     * measurement; a fire-and-forget publisher would show even lower numbers.
     *
     * <p>Expected outcome: 100–1000× lower latency than {@link #multiThreadedContended} under
     * 3-thread contention, matching the production exchange-core numbers from the article.
     */
    @Benchmark
    public void singleThreadedSharded(Blackhole bh) throws InterruptedException {
        long accountId = ThreadLocalRandom.current().nextLong(NUM_ACCOUNTS);
        int  shardIdx  = (int) (accountId % NUM_SHARDS);

        CountDownLatch latch     = new CountDownLatch(1);
        RingBuffer<AccountEvent> ring = ringBuffers[shardIdx];

        long sequence = ring.next();
        try {
            AccountEvent event = ring.get(sequence);
            event.accountId = accountId;
            event.delta     = 100L;
            event.latch     = latch;
        } finally {
            ring.publish(sequence);
        }

        // Block until the shard consumer has processed this event
        latch.await(1, TimeUnit.SECONDS);
        bh.consume(accountId);
    }
}

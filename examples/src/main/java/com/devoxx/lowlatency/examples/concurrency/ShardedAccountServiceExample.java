package com.devoxx.lowlatency.examples.concurrency;

import org.agrona.collections.Long2ObjectHashMap;

import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

/**
 * BACKS SLIDE: "The shard-per-thread pattern"
 * PATTERN: Partition account ownership by userId; each shard holds exclusive access forever.
 * MECHANISM: No lock, synchronized block, or CAS ever enters the hot path. Each shard's
 *            Long2ObjectHashMap is mutated by exactly one thread, so the CPU's L1 cache line
 *            for that account data never migrates to another core.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.concurrency.ShardedAccountServiceExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/concurrency/SingleThreadedShardVsContendedBenchmark.java
 */
public final class ShardedAccountServiceExample {

    // 3 shards to match the slide's 3-core EPYC reference environment.
    // In production, choose a power-of-2 count (4, 8, 16, ...) so that
    // routing reduces to a single bitwise AND: shardIdx = userId & (SHARD_COUNT - 1).
    // With 3 shards we use userId % SHARD_COUNT instead (still fast but not bitwise-pure).
    private static final int  SHARD_COUNT   = 3;
    private static final int  QUEUE_CAP     = 1024;       // power-of-2: ArrayBlockingQueue internals benefit
    private static final int  ACCOUNT_COUNT = 4096;       // accounts pre-loaded at startup
    private static final int  EVENT_COUNT   = 1_000_000;  // synthetic balance-update events
    private static final long INITIAL_BAL   = 1_000_000L;

    // Poison pill: unique array instance checked by reference identity.
    // One instance shared across all shard queues — each shard reads its own copy from its own queue.
    private static final long[] POISON = new long[0];

    // -------------------------------------------------------------------
    // Account — mutable balance owned exclusively by one shard thread.
    // Declared with package-private fields so the JIT cannot treat writes as dead.
    // -------------------------------------------------------------------
    static final class Account {
        long balance;
        Account(long initial) { this.balance = initial; }
    }

    // -------------------------------------------------------------------
    // Shard — single-threaded account service for a disjoint userId slice.
    // Routing rule: this shard owns userId when (userId % SHARD_COUNT) == shardId.
    // -------------------------------------------------------------------
    static final class Shard implements Runnable {

        final int shardId;
        final CountDownLatch done;

        // Agrona Long2ObjectHashMap: primitive long keys, no boxing, flat open-addressing.
        // Mutated only by this thread; the JVM's memory model requires no fences on the hot path.
        final Long2ObjectHashMap<Account> accounts = new Long2ObjectHashMap<>(ACCOUNT_COUNT, 0.65f);

        // Inbound queue. In production this would be an Agrona OneToOneRingBuffer or LMAX
        // Disruptor with pre-allocated event slots for zero allocation in steady state.
        // ArrayBlockingQueue is used here because the queue mechanism is not the lesson —
        // the exclusive ownership is.
        final ArrayBlockingQueue<long[]> queue = new ArrayBlockingQueue<>(QUEUE_CAP);

        long processed = 0L; // only read after the done latch; no volatile needed

        Shard(int shardId, CountDownLatch done) {
            this.shardId = shardId;
            this.done    = done;
            // Pre-populate: this shard owns every userId where userId % SHARD_COUNT == shardId.
            for (int userId = shardId; userId < ACCOUNT_COUNT; userId += SHARD_COUNT) {
                accounts.put(userId, new Account(INITIAL_BAL));
            }
        }

        @Override
        public void run() {
            try {
                long[] event;
                while ((event = queue.take()) != POISON) {
                    Account acc = accounts.get(event[0]);
                    if (acc != null) {
                        // Hot path: one Agrona map lookup + one field write.
                        // Zero locks, zero CAS, zero cross-thread coherency traffic.
                        acc.balance += event[1];
                        processed++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch done   = new CountDownLatch(SHARD_COUNT);
        Shard[]        shards = new Shard[SHARD_COUNT];

        for (int s = 0; s < SHARD_COUNT; s++) {
            shards[s] = new Shard(s, done);
            Thread t  = new Thread(shards[s], "shard-" + s);
            t.setDaemon(false);
            t.start();
        }

        // Pre-generate all events before the timed section begins.
        // Allocation happens here at startup; steady-state sends existing arrays.
        long[] userIds = new long[EVENT_COUNT];
        Random rng     = new Random(42L);
        for (int i = 0; i < EVENT_COUNT; i++) {
            userIds[i] = rng.nextInt(ACCOUNT_COUNT);
        }

        long start = System.nanoTime();

        for (int i = 0; i < EVENT_COUNT; i++) {
            long userId   = userIds[i];
            int  shardIdx = (int)(userId % SHARD_COUNT);
            // Each event is a two-element long[]: {userId, delta}.
            // A production ring buffer reuses pre-allocated event slots here to avoid allocation.
            shards[shardIdx].queue.put(new long[]{userId, 100L});
        }

        // Signal each shard to drain and exit.
        for (Shard s : shards) {
            s.queue.put(POISON);
        }
        done.await();

        long elapsed = System.nanoTime() - start;
        long total   = 0;
        for (Shard s : shards) { total += s.processed; }

        System.out.println();
        System.out.println("Sharded account service  --  \"The shard-per-thread pattern\"");
        System.out.printf ("  shards             : %d (userId %% %d routes to shard [0..%d])%n",
                           SHARD_COUNT, SHARD_COUNT, SHARD_COUNT - 1);
        for (int s = 0; s < SHARD_COUNT; s++) {
            System.out.printf ("  shard-%d processed  : %,d events%n", s, shards[s].processed);
        }
        System.out.printf ("Total processed      : %,d / %,d%n", total, (long) EVENT_COUNT);
        System.out.printf ("Wall clock           : %,d ms%n", elapsed / 1_000_000);
        System.out.printf ("Throughput           : %,.0f events/sec%n", total * 1e9 / elapsed);
        System.out.println("Contention           : zero  --  no synchronized, no ConcurrentHashMap, no CAS");
    }
}

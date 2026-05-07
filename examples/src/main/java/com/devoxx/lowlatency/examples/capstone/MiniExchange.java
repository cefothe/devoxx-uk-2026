package com.devoxx.lowlatency.examples.capstone;

import com.devoxx.lowlatency.examples.common.Platform;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * BACKS SLIDE: "Production architecture — exchange-core style"
 * PATTERN: All three truths together — off-heap-shaped events, single-threaded shards, pooling
 * MECHANISM: Producer fans into a Disruptor; one handler routes by userId into per-shard
 *            inbound queues, a parallel handler appends to a Chronicle Queue audit log.
 *            Shard threads drain inbound, run a single-threaded matching engine, return
 *            events to a per-shard free pool. Allocation in steady state: zero.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.capstone.MiniExchange
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/concurrency/SingleThreadedShardVsContendedBenchmark.java
 */
public final class MiniExchange {

    private static final int SHARD_COUNT     = 3;
    private static final int RING_SIZE       = 1024;   // power of two: Disruptor uses (seq & mask)
    private static final int SHARD_QUEUE_CAP = 4096;   // 4x peak per slide 22's pool-sizing rule
    private static final int DEFAULT_ORDERS  = 1_000_000;

    /** Shard owns: one matching engine, one inbound queue, one free pool. */
    static final class Shard {
        // Reference-identity sentinel: even if POISON's fields get clobbered, == still works.
        static final OrderEvent POISON = new OrderEvent();
        final int                              shardId;
        final ArrayBlockingQueue<OrderEvent>   inbound;
        final ArrayBlockingQueue<OrderEvent>   free;
        final OrderBook                        book = new OrderBook();
        volatile long                          processed;

        Shard(int shardId, int cap) {
            this.shardId = shardId;
            this.inbound = new ArrayBlockingQueue<>(cap);
            this.free    = new ArrayBlockingQueue<>(cap);
            // Pre-allocate every event slot. After this loop no `new OrderEvent` fires
            // on the hot path — router borrows from free, shard returns to free.
            for (int i = 0; i < cap; i++) free.offer(new OrderEvent());
        }

        void run() {
            Platform.pinCurrentThreadToCpu(1 + shardId);
            try {
                while (true) {
                    OrderEvent e = inbound.take();
                    if (e == POISON) return;
                    book.match(e);
                    processed++;
                    e.clear();
                    free.put(e);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int     orderCount = DEFAULT_ORDERS;
        boolean keepQueue  = false;
        for (int i = 0; i < args.length; i++) {
            if      ("--orders".equals(args[i]) && i + 1 < args.length) orderCount = Integer.parseInt(args[++i]);
            else if ("--keep-queue".equals(args[i]))                    keepQueue  = true;
        }

        // Producer is the main thread; pin it to core 0 so its L1 stays hot.
        Platform.pinCurrentThreadToCpu(0);

        ShardRouter router = new ShardRouter(SHARD_COUNT);
        Shard[]     shards = new Shard[SHARD_COUNT];
        for (int s = 0; s < SHARD_COUNT; s++) shards[s] = new Shard(s, SHARD_QUEUE_CAP);
        AuditWriter audit = new AuditWriter(keepQueue);

        Disruptor<OrderEvent> disruptor = new Disruptor<>(
            OrderEvent::new, RING_SIZE,
            r -> Thread.ofPlatform().daemon(true).name("disruptor-handler").unstarted(r),
            ProducerType.SINGLE, new BusySpinWaitStrategy());

        EventHandler<OrderEvent> routeHandler = (e, seq, eob) -> {
            // Route by symbolId — a symbol's book lives on exactly one shard.
            // See ShardRouter for the full rationale (slide 18 vs slide 40).
            Shard sh = shards[router.shardForSymbol(e.symbolId)];
            // free.take() blocks if shard is behind — that's the back-pressure we want.
            OrderEvent slot = sh.free.take();
            slot.orderId    = e.orderId;
            slot.userId     = e.userId;
            slot.symbolId   = e.symbolId;
            slot.priceTicks = e.priceTicks;
            slot.quantity   = e.quantity;
            slot.side       = e.side;
            sh.inbound.put(slot);
        };
        EventHandler<OrderEvent> auditHandler = (e, seq, eob) -> audit.append(e);

        // Parallel handlers: both see every event. Audit is a side-tap, off the
        // matching critical path, mirroring slide 40's diagram.
        disruptor.handleEventsWith(routeHandler, auditHandler);
        RingBuffer<OrderEvent> ring = disruptor.start();

        // Shard threads non-daemon so the JVM doesn't exit mid-drain. Watchdog
        // hook below interrupts any straggler that misses the explicit join.
        Thread[] shardThreads = new Thread[SHARD_COUNT];
        for (int s = 0; s < SHARD_COUNT; s++) {
            Thread t = new Thread(shards[s]::run, "shard-" + s);
            t.setDaemon(false);
            t.start();
            shardThreads[s] = t;
        }
        final Thread[] watched = shardThreads;
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shard-watchdog").unstarted(() -> {
            for (Thread t : watched) if (t.isAlive()) t.interrupt();
        }));

        com.sun.management.ThreadMXBean tmx =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] allocBefore = new long[SHARD_COUNT];
        for (int s = 0; s < SHARD_COUNT; s++) allocBefore[s] = tmx.getThreadAllocatedBytes(shardThreads[s].threadId());
        long gcBefore = totalGcCount();

        Random rng = new Random(42L);
        long t0 = System.nanoTime();
        for (int i = 0; i < orderCount; i++) {
            long seq = ring.next();
            try {
                OrderEvent e = ring.get(seq);
                e.orderId    = i;
                e.userId     = rng.nextLong() & 0xFFFFL;
                e.symbolId   = i & 3L;                    // 4 symbols, branch-free
                e.priceTicks = 100L + rng.nextInt(60);    // ticks 100..159, inside OrderBook's window
                e.quantity   = 10L + rng.nextInt(10);
                e.side       = (byte) rng.nextInt(2);
            } finally {
                ring.publish(seq);
            }
        }

        // Drain the disruptor, then poison each shard. shutdown(timeout) waits
        // for both handlers to fully process every published event — when it
        // returns, the router has handed everything off. POISON goes behind
        // the live events so each shard sees it last and exits.
        disruptor.shutdown(10, TimeUnit.SECONDS);
        for (Shard sh : shards) sh.inbound.put(Shard.POISON);

        for (Thread t : shardThreads) {
            t.join(5_000L);
            if (t.isAlive()) t.interrupt();
        }

        long elapsedNs = System.nanoTime() - t0;
        long allocBytes = 0L;
        for (int s = 0; s < SHARD_COUNT; s++) {
            long after = tmx.getThreadAllocatedBytes(shardThreads[s].threadId());
            allocBytes += Math.max(0L, after - allocBefore[s]);
        }
        long gcPauses   = totalGcCount() - gcBefore;
        long auditCount = audit.appended();
        audit.close();
        double seconds    = elapsedNs / 1e9;
        long   throughput = (long) (orderCount / seconds);

        System.out.println();
        System.out.println("Mini-exchange — slide 40 architecture");
        System.out.println("  3 shards × single-threaded matching");
        System.out.println("  1,024-event off-heap ring buffer (pooled, reused)");
        System.out.println("  Chronicle Queue audit on disk\n");
        System.out.printf("Processed     : %,d orders%n",  orderCount);
        System.out.printf("Wall clock    : %.2f s%n",      seconds);
        System.out.printf("Throughput    : %,d ops/sec%n", throughput);
        System.out.printf("Allocations   : %s%n", allocBytes == 0L
            ? "0 in steady state (pool warmed at startup)"
            : String.format("%,d bytes across shard threads", allocBytes));
        System.out.printf("GC pauses     : %d %s%n", gcPauses,
            gcPauses == 0L ? "(none observed during run)" : "(observed during run)");
        System.out.printf("Audit records : %,d in %s%n", auditCount, audit.queuePath());

        // Force prompt exit; otherwise the Chronicle background flusher and the
        // halted Disruptor threads can keep the JVM live for several seconds.
        System.exit(0);
    }

    private static long totalGcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            if (c >= 0L) total += c;
        }
        return total;
    }
}

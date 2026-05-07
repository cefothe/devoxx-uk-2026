package com.devoxx.lowlatency.examples.libraries;

/**
 * BACKS SLIDE: "LMAX Disruptor — the ring buffer that started it all"
 * PATTERN: Pre-allocated ring buffer with lock-free producer/consumer handoff
 * MECHANISM: Events are allocated once at startup and overwritten in place — no `new` on the hot path,
 *            no lock contention; a single atomic sequence write hands off to the consumer
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.libraries.DisruptorPipelineExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/libraries/DisruptorVsArrayBlockingQueueBenchmark.java
 */

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;

public final class DisruptorPipelineExample {

    // Power-of-two is required by the Disruptor: it uses (sequence & mask) for slot lookup,
    // which is only correct when the capacity is an exact power of two.
    private static final int  RING_SIZE = 1024;
    private static final long EVENTS    = 1_000_000L;

    /**
     * Pre-allocated event carrier. The Disruptor creates exactly {@value RING_SIZE} of these
     * at startup and reuses them forever. The producer writes into the slot; the consumer reads
     * from it. No {@code new} keyword ever fires in steady state.
     */
    public static final class OrderEvent {
        public long   orderId;
        public double price;
        public int    quantity;
    }

    public static void main(String[] args) throws Exception {
        long gcBefore = totalGcCount();

        var disruptor = new Disruptor<>(
            OrderEvent::new,        // factory: 1024 OrderEvent objects allocated here, never again
            RING_SIZE,
            r -> Thread.ofPlatform().daemon(true).name("order-handler").unstarted(r),
            ProducerType.SINGLE,
            new BusySpinWaitStrategy() // burns one CPU core — the right call when latency beats CPU cost
        );

        // The latch fires when the handler sees the final event in the run.
        var done = new CountDownLatch(1);
        // Volatile counter for the summary line; not in the hot path.
        long[] handledHolder = {0L};

        disruptor.handleEventsWith((EventHandler<OrderEvent>) (event, sequence, endOfBatch) -> {
            handledHolder[0] = sequence + 1L; // sequence is 0-based
            if (sequence == EVENTS - 1L) {
                done.countDown();
            }
        });

        RingBuffer<OrderEvent> ring = disruptor.start();

        // --- Measured run ---
        long gcAtStart = totalGcCount();
        long t0        = System.nanoTime();

        for (long i = 0; i < EVENTS; i++) {
            // ring.next() claims the next slot; publish() releases it to the consumer.
            // Between next() and publish() the producer has exclusive write access —
            // no lock, no CAS, just a sequence increment.
            long slot = ring.next();
            try {
                OrderEvent ev = ring.get(slot);
                ev.orderId  = i;
                ev.price    = 99.5 + (i & 0xFL);  // bitwise AND keeps this branch-free
                ev.quantity = (int)(100L + (i % 10L));
            } finally {
                ring.publish(slot); // single volatile write: consumer SequenceBarrier can now advance
            }
        }

        done.await();
        long elapsedNs = System.nanoTime() - t0;
        long gcAfter   = totalGcCount();

        disruptor.shutdown();

        double seconds    = elapsedNs / 1e9;
        long   throughput = (long)(EVENTS / seconds);

        System.out.println();
        System.out.println("DisruptorPipelineExample — LMAX Disruptor ring buffer");
        System.out.println("  Ring buffer size : " + RING_SIZE + " pre-allocated OrderEvent slots");
        System.out.println("  Events published : " + EVENTS);
        System.out.println("  Events handled   : " + handledHolder[0]);
        System.out.printf( "  Elapsed          : %.3f s%n",                seconds);
        System.out.printf( "  Throughput       : %,d events/sec%n",        throughput);
        System.out.println("  GC pauses (run)  : " + (gcAfter - gcAtStart) +
            "  (pre-warm total: " + (gcAtStart - gcBefore) + ")");
        System.out.println("  Hot-path allocs  : none — ring slots pre-allocated, overwritten in place");
    }

    /** Sum all GC collection counts; filters out -1 (disabled collector). */
    private static long totalGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans()
            .stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionCount)
            .filter(c -> c >= 0L)
            .sum();
    }
}

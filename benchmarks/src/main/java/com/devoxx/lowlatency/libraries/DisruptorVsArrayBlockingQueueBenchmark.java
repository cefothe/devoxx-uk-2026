package com.devoxx.lowlatency.libraries;

import com.devoxx.lowlatency.common.BenchmarkBase;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.jctools.queues.SpscArrayQueue;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BACKS SLIDE: "The Disruptor: Why Queues Are The Problem"
 *
 * <p>CLAIM: The LMAX Disruptor with {@link BusySpinWaitStrategy} achieves 5–15× lower p99 latency
 * than {@link ArrayBlockingQueue} for SPSC message passing, with JCTools {@link SpscArrayQueue}
 * sitting in between (no OS locks, but still object allocation).
 *
 * <p>MECHANISM:
 * <ul>
 *   <li><b>ArrayBlockingQueue</b>: Uses {@code ReentrantLock} internally. Under any contention
 *       the producer or consumer parks (via {@code LockSupport.park}), triggering a Linux futex
 *       syscall and a context switch. A context switch costs 1–10 µs on a modern EPYC — this
 *       is the single biggest latency cliff in concurrent Java code.</li>
 *   <li><b>JCTools SpscArrayQueue</b>: Lock-free, CAS-based. Eliminates the futex/context-switch
 *       but still allocates the {@code byte[]} message object on every round-trip, creating GC
 *       pressure that shows up as latency spikes at p99.9+.</li>
 *   <li><b>LMAX Disruptor (SPSC + BusySpinWaitStrategy)</b>: Pre-allocated ring buffer — the
 *       {@link MessageEvent} objects are created once at startup and reused every lap. The
 *       producer publishes by incrementing a sequence number (single volatile write); the consumer
 *       busy-spins on a {@code SequenceBarrier} using {@link Thread#onSpinWait()}. Zero allocation,
 *       zero kernel transitions, one cache-line ping-pong per message. This is the canonical
 *       mechanical-sympathy pattern for intra-JVM low-latency messaging.</li>
 * </ul>
 *
 * <p>TRADE-OFF: {@link BusySpinWaitStrategy} burns 100% of one CPU core on the consumer thread.
 * This is intentional in latency-critical systems (LMAX Exchange uses thread-per-core pinning).
 * Use {@code SleepingWaitStrategy} or {@code YieldingWaitStrategy} for lower CPU utilisation at
 * the cost of higher and less predictable tail latency.
 *
 * <p>MEASUREMENT: {@link Mode#SampleTime} records every individual invocation latency, enabling
 * JMH to produce p50/p90/p99/p99.9 percentile output via {@code -prof gc -prof comp}. The
 * {@code @Benchmark} method acts as the producer; a background thread in each {@link State} acts
 * as the consumer. JMH's sample timer wraps the entire method, so the reported latency equals
 * the true end-to-end transfer latency (publish → consumer acknowledgement).
 *
 * <p>SEE ALSO: {@code part3-specialized-libraries.md} §"LMAX Disruptor: Event Processing Pipelines",
 * §"Agrona: The Foundation Layer". BENCHMARK-METHODOLOGY.md §"Percentile reporting".
 */
@BenchmarkMode(Mode.SampleTime)  // tail-latency benchmark — percentiles are the headline
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G
})
@SuppressWarnings("unused")
public class DisruptorVsArrayBlockingQueueBenchmark {

    /** Ring-buffer and queue capacity (must be a power of 2 for the Disruptor). */
    private static final int RING_BUFFER_SIZE = 1024;

    /** Message payload size — 64 bytes matches a single CPU cache line. */
    static final int MESSAGE_SIZE = 64;

    // -------------------------------------------------------------------------
    // Pre-allocated event carrier for the Disruptor (zero allocation after start)
    // -------------------------------------------------------------------------

    /**
     * Reusable event object. Pre-allocated at Disruptor startup; populated in place
     * on every lap around the ring buffer. Never escapes to the heap after setup.
     */
    public static final class MessageEvent {
        /** Sequence number echoed by the consumer as acknowledgement. */
        public long sequence;
        /** 56-byte payload — pads the event to a full 64-byte cache line. */
        public final byte[] payload = new byte[MESSAGE_SIZE - Long.BYTES];
    }

    // -------------------------------------------------------------------------
    // State: ArrayBlockingQueue baseline
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class AbqState {

        ArrayBlockingQueue<byte[]> queue;
        /** Monotonically-increasing counter; consumer increments after each take(). */
        final AtomicLong consumerSeq = new AtomicLong(0L);
        /** Shared message object — same reference put on every iteration (no allocation). */
        final byte[] message = new byte[MESSAGE_SIZE];
        volatile boolean running = true;
        Thread consumerThread;

        @Setup(Level.Trial)
        public void setup() {
            queue = new ArrayBlockingQueue<>(RING_BUFFER_SIZE);
            running = true;
            consumerThread = Thread.ofPlatform().daemon(true).name("abq-consumer").unstarted(() -> {
                while (running) {
                    try {
                        byte[] msg = queue.take();
                        // Signal consumption: echo the sequence encoded in msg[0..7]
                        long seq = 0L;
                        for (int i = 0; i < Long.BYTES; i++) {
                            seq |= ((long) (msg[i] & 0xFF)) << (i * Byte.SIZE);
                        }
                        consumerSeq.setOpaque(seq);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            consumerThread.start();
        }

        @TearDown(Level.Trial)
        public void tearDown() throws InterruptedException {
            running = false;
            consumerThread.interrupt();
            consumerThread.join(2_000L);
        }
    }

    // -------------------------------------------------------------------------
    // State: JCTools SpscArrayQueue (lock-free, heap-allocated messages)
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class JctoolsState {

        SpscArrayQueue<byte[]> queue;
        final AtomicLong producerSeq = new AtomicLong(0L);
        final AtomicLong consumerSeq = new AtomicLong(0L);
        volatile boolean running = true;
        Thread consumerThread;

        @Setup(Level.Trial)
        public void setup() {
            queue = new SpscArrayQueue<>(RING_BUFFER_SIZE);
            running = true;
            consumerThread = Thread.ofPlatform().daemon(true).name("jctools-consumer").unstarted(() -> {
                while (running) {
                    byte[] msg = queue.poll();
                    if (msg != null) {
                        long seq = 0L;
                        for (int i = 0; i < Long.BYTES; i++) {
                            seq |= ((long) (msg[i] & 0xFF)) << (i * Byte.SIZE);
                        }
                        consumerSeq.setOpaque(seq);
                    } else {
                        Thread.onSpinWait();
                    }
                }
            });
            consumerThread.start();
        }

        @TearDown(Level.Trial)
        public void tearDown() throws InterruptedException {
            running = false;
            consumerThread.interrupt();
            consumerThread.join(2_000L);
        }
    }

    // -------------------------------------------------------------------------
    // State: LMAX Disruptor SPSC + BusySpinWaitStrategy
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class DisruptorState {

        Disruptor<MessageEvent> disruptor;
        RingBuffer<MessageEvent> ringBuffer;
        /**
         * Consumer acknowledges by writing the processed sequence here.
         * Volatile semantics via {@code setOpaque} — sufficient for a single-writer handshake.
         */
        final AtomicLong consumerSeq = new AtomicLong(-1L);

        @Setup(Level.Trial)
        public void setup() {
            disruptor = new Disruptor<>(
                    MessageEvent::new,
                    RING_BUFFER_SIZE,
                    r -> {
                        Thread t = Thread.ofPlatform().daemon(true).name("disruptor-consumer").unstarted(r);
                        return t;
                    },
                    ProducerType.SINGLE,
                    new BusySpinWaitStrategy()
            );
            final AtomicLong seq = consumerSeq;
            disruptor.handleEventsWith((EventHandler<MessageEvent>) (event, sequence, endOfBatch) ->
                    seq.setOpaque(sequence));
            ringBuffer = disruptor.start();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            disruptor.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Benchmarks
    // -------------------------------------------------------------------------

    /**
     * Baseline: {@link ArrayBlockingQueue#put} → background consumer {@link ArrayBlockingQueue#take}.
     *
     * <p>The producer encodes the sequence number into the first 8 bytes of the shared {@code byte[]}
     * message, puts it on the queue, then spins until the consumer increments {@code consumerSeq}.
     * JMH's SampleTime clock wraps the entire call, so the reported latency is the true
     * producer-to-consumer round-trip including OS futex overhead.
     */
    @Benchmark
    public void arrayBlockingQueue(AbqState state, Blackhole bh) throws InterruptedException {
        // Encode the target sequence into the first 8 bytes of the shared message
        long targetSeq = state.consumerSeq.getOpaque() + 1L;
        byte[] msg = state.message;
        for (int i = 0; i < Long.BYTES; i++) {
            msg[i] = (byte) (targetSeq >>> (i * Byte.SIZE));
        }

        state.queue.put(msg);

        // Spin until consumer signals it has consumed this sequence
        while (state.consumerSeq.getOpaque() < targetSeq) {
            Thread.onSpinWait();
        }
        bh.consume(targetSeq);
    }

    /**
     * JCTools {@link SpscArrayQueue}: lock-free, CAS-based. Eliminates kernel transitions but
     * still allocates a new {@code byte[]} reference on each poll — GC pressure shows at p99+.
     *
     * <p>Note: SpscArrayQueue is designed for SPSC but the poll() loop in the consumer thread
     * means the consumer is the "single consumer" and this @Benchmark thread is the single producer.
     * The queue capacity (1024) prevents back-pressure under the benchmark's single-op-at-a-time
     * measurement pattern.
     */
    @Benchmark
    public void jctoolsSpscArrayQueue(JctoolsState state, Blackhole bh) {
        long targetSeq = state.producerSeq.incrementAndGet();

        // Allocate per-message byte[] to mirror real usage (JCTools doesn't pre-allocate)
        byte[] msg = new byte[MESSAGE_SIZE];
        for (int i = 0; i < Long.BYTES; i++) {
            msg[i] = (byte) (targetSeq >>> (i * Byte.SIZE));
        }

        while (!state.queue.offer(msg)) {
            Thread.onSpinWait(); // ring buffer full — back-pressure
        }

        while (state.consumerSeq.getOpaque() < targetSeq) {
            Thread.onSpinWait();
        }
        bh.consume(targetSeq);
    }

    /**
     * LMAX Disruptor SPSC with {@link BusySpinWaitStrategy}.
     *
     * <p>The {@link MessageEvent} objects are pre-allocated at startup in the ring buffer slots.
     * The producer claims the next slot via {@link RingBuffer#next()}, populates {@code event.sequence},
     * then publishes with a single volatile write to the ring buffer cursor. The consumer
     * {@link EventHandler} busy-spins on the sequence barrier — no kernel transitions, no allocation,
     * one cache-line ping-pong per message.
     *
     * <p>Expected: 5–15× lower p99 latency vs ArrayBlockingQueue on the EPYC 7282 reference box.
     */
    @Benchmark
    public void disruptorSpsc(DisruptorState state, Blackhole bh) {
        long nextSeq = state.ringBuffer.next();
        try {
            MessageEvent event = state.ringBuffer.get(nextSeq);
            event.sequence = nextSeq; // payload: sequence number
        } finally {
            state.ringBuffer.publish(nextSeq); // single volatile write — consumer can now see it
        }

        // Spin until consumer EventHandler acknowledges this sequence
        while (state.consumerSeq.getOpaque() < nextSeq) {
            Thread.onSpinWait();
        }
        bh.consume(nextSeq);
    }
}

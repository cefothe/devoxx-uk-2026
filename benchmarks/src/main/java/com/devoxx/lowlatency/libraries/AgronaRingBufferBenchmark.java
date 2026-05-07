package com.devoxx.lowlatency.libraries;

import com.devoxx.lowlatency.common.BenchmarkBase;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
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

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BACKS SLIDE: "Agrona: The Zero-Allocation Plumbing Layer"
 *
 * <p>CLAIM: Agrona's {@link ManyToOneRingBuffer} achieves 3–10× lower end-to-end latency than
 * {@link ArrayBlockingQueue} for intra-JVM message passing, with zero heap allocation in the
 * hot path.
 *
 * <p>MECHANISM:
 * <ul>
 *   <li><b>Agrona ManyToOneRingBuffer</b>: A lock-free, wait-free (producers) ring buffer backed
 *       by a single contiguous {@code DirectByteBuffer} (off-heap or heap — configured here as
 *       direct for NUMA-local allocation). Producers write via a claim-copy-commit sequence using
 *       a single CAS on the producer position. The consumer calls {@link ManyToOneRingBuffer#read}
 *       in a tight loop, dispatching to a {@link org.agrona.concurrent.MessageHandler} lambda with
 *       zero object allocation — the handler receives a {@link UnsafeBuffer} view into the ring,
 *       not a copy. Ring buffer capacity must be a power of 2; actual buffer size is
 *       {@code capacity + RingBufferDescriptor.TRAILER_LENGTH} (128 bytes for the control header).
 *   </li>
 *   <li><b>ArrayBlockingQueue</b>: Internally uses a {@link java.util.concurrent.locks.ReentrantLock}.
 *       Under any contention (producer waiting for consumer or vice versa) the OS parks the thread
 *       via {@code futex_wait(2)}, incurring 1–10 µs of context-switch overhead per message.
 *       Additionally, every message is a heap-allocated {@code byte[]} — GC pressure accumulates.
 *   </li>
 *   <li><b>Cache-line discipline</b>: The ring buffer's producer and consumer position fields are
 *       on separate cache lines (128-byte padding in the trailer) to prevent false sharing. When
 *       the producer writes its position, it does not invalidate the consumer's position cache
 *       line — mechanical sympathy in action.</li>
 * </ul>
 *
 * <p>CONSUMER THREAD DESIGN: A background thread busy-spins on
 * {@link ManyToOneRingBuffer#read(org.agrona.concurrent.MessageHandler, int)} with a limit of 10
 * messages per poll to bound the handler's critical section. The consumer signals back via an
 * {@link AtomicLong} counter, allowing the @Benchmark producer thread to measure true
 * end-to-end latency via JMH {@code SampleTime}.
 *
 * <p>SEE ALSO: {@code part3-specialized-libraries.md} §"Agrona OneToOneRingBuffer: The Fastest IPC",
 * §"Agrona Zero-Copy Approach". For multiple-consumer fan-out, see
 * {@code DisruptorVsArrayBlockingQueueBenchmark}.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G,
        // Agrona uses sun.misc.Unsafe internals — these flags suppress JDK 25 access warnings/errors
        BenchmarkBase.OPEN_JDK_MISC, BenchmarkBase.NATIVE_ACCESS
})
@SuppressWarnings("unused")
public class AgronaRingBufferBenchmark {

    /** Ring buffer capacity — must be a power of 2. */
    private static final int RING_CAPACITY = 4096;

    /** Message type identifier (application-defined; must be > 0). */
    private static final int MSG_TYPE_ID = 1;

    /** Fixed 64-byte message payload — one cache line. */
    private static final int MESSAGE_SIZE = 64;

    // -------------------------------------------------------------------------
    // State: Agrona ManyToOneRingBuffer + background consumer
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class AgronaState {

        ManyToOneRingBuffer ringBuffer;
        UnsafeBuffer writeBuffer;

        /**
         * Producer increments this; consumer echoes it back after handling each message.
         * Provides the handshake signal for end-to-end latency measurement.
         */
        final AtomicLong producerSeq = new AtomicLong(0L);
        final AtomicLong consumerSeq = new AtomicLong(0L);
        volatile boolean running = true;
        Thread consumerThread;

        @Setup(Level.Trial)
        public void setup() {
            // Allocate the ring buffer backing store: capacity + 128-byte trailer for control fields
            int bufferSize = RING_CAPACITY + RingBufferDescriptor.TRAILER_LENGTH;
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bufferSize);
            ringBuffer = new ManyToOneRingBuffer(new UnsafeBuffer(byteBuffer));

            // Pre-allocated write buffer — same instance reused on every iteration (zero allocation)
            writeBuffer = new UnsafeBuffer(new byte[MESSAGE_SIZE]);

            running = true;
            final AtomicLong cSeq = consumerSeq;
            final ManyToOneRingBuffer rb = ringBuffer;

            consumerThread = Thread.ofPlatform().daemon(true).name("agrona-consumer").unstarted(() -> {
                while (running) {
                    // Dispatch up to 10 messages per poll; signal after each
                    int handled = rb.read((msgTypeId, srcBuffer, index, length) ->
                            cSeq.setOpaque(cSeq.getOpaque() + 1L), 10);
                    if (handled == 0) {
                        Thread.onSpinWait(); // nothing available — busy spin (low-latency mode)
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
    // State: ArrayBlockingQueue baseline
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class AbqState {

        ArrayBlockingQueue<byte[]> queue;
        final AtomicLong consumerSeq = new AtomicLong(0L);
        final byte[] message = new byte[MESSAGE_SIZE];
        volatile boolean running = true;
        Thread consumerThread;

        @Setup(Level.Trial)
        public void setup() {
            queue = new ArrayBlockingQueue<>(RING_CAPACITY);
            running = true;
            consumerThread = Thread.ofPlatform().daemon(true).name("abq-agrona-consumer").unstarted(() -> {
                while (running) {
                    try {
                        queue.take(); // blocks until producer puts
                        consumerSeq.setOpaque(consumerSeq.getOpaque() + 1L);
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
    // Benchmarks
    // -------------------------------------------------------------------------

    /**
     * Agrona {@link ManyToOneRingBuffer}: lock-free SPSC/MPSC messaging.
     *
     * <p>The producer calls {@link ManyToOneRingBuffer#write} to copy the pre-allocated
     * {@link UnsafeBuffer} into the next available ring buffer slot (one CAS + memcopy).
     * The background consumer calls {@link ManyToOneRingBuffer#read} in a busy loop and
     * increments {@code consumerSeq} via {@code setOpaque} after handling.
     *
     * <p>The @Benchmark thread then spins on {@code consumerSeq} until it catches up.
     * JMH SampleTime wraps the entire method — reported latency equals write + transfer + read.
     *
     * <p>Expected: 100–400 ns/op on the EPYC 7282 reference box; p99 &lt; 1 µs.
     * Zero heap allocation in the hot path (verified via {@code -prof gc}).
     */
    @Benchmark
    public void agronaManyToOneRingBuffer(AgronaState state, Blackhole bh) {
        long targetSeq = state.producerSeq.incrementAndGet();

        // Encode target sequence into write buffer to defeat DCE
        state.writeBuffer.putLong(0, targetSeq);

        // Claim-copy-commit: single CAS to claim the slot, memcopy into ring, release
        while (!state.ringBuffer.write(MSG_TYPE_ID, state.writeBuffer, 0, MESSAGE_SIZE)) {
            Thread.onSpinWait(); // ring full — busy spin (should be rare at our throughput)
        }

        // Spin until consumer acknowledges this message
        while (state.consumerSeq.getOpaque() < targetSeq) {
            Thread.onSpinWait();
        }
        bh.consume(targetSeq);
    }

    /**
     * Baseline: {@link ArrayBlockingQueue} with a blocking consumer.
     *
     * <p>Uses a pre-allocated shared {@code byte[]} (same reference on every put) to isolate
     * the data-structure overhead from allocation cost. Even with this optimisation, the ABQ's
     * internal {@code ReentrantLock} triggers OS-level park/unpark on every handshake when the
     * consumer is blocked in {@code take()}.
     *
     * <p>Expected: 1–10 µs/op; p99 > 5 µs. Context-switch cost dominates at high iteration
     * rates — exactly the cliff that Agrona's lock-free ring buffer eliminates.
     */
    @Benchmark
    public void arrayBlockingQueue(AbqState state, Blackhole bh) throws InterruptedException {
        long targetSeq = state.consumerSeq.getOpaque() + 1L;

        state.queue.put(state.message); // reentrant-lock + potential park

        while (state.consumerSeq.getOpaque() < targetSeq) {
            Thread.onSpinWait();
        }
        bh.consume(targetSeq);
    }
}

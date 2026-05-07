package com.devoxx.lowlatency.libraries;

import com.devoxx.lowlatency.common.BenchmarkBase;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * BACKS SLIDE: "Chronicle Queue: Durable Zero-Copy Messaging"
 *
 * <p>CLAIM: Chronicle Queue write+read over a memory-mapped file costs ~1 µs/op — comparable to
 * an in-memory {@link ArrayDeque} that allocates — while providing full persistence with zero
 * additional heap allocation in the hot path.
 *
 * <p>MECHANISM:
 * <ul>
 *   <li><b>Memory-mapped I/O</b>: Chronicle Queue backs its ring buffer with an
 *       {@code mmap(2)}-ed file. Once the OS has faulted-in the pages, reads and writes are
 *       purely in the CPU's L2/L3 cache or DRAM — no {@code read(2)}/{@code write(2)} syscall
 *       overhead. The kernel page cache and the JVM share the same physical pages; "persisting"
 *       data is a no-op from the application's perspective.</li>
 *   <li><b>Zero heap allocation</b>: The {@code ExcerptAppender} writes directly into the
 *       mapped buffer via Chronicle's {@code Bytes} API. No intermediate {@code byte[]} is
 *       allocated on the Java heap in the hot path. GC pause frequency and magnitude both drop.</li>
 *   <li><b>ArrayDeque baseline allocates</b>: Every {@code offer(new byte[MESSAGE_SIZE])} creates
 *       a fresh byte array on the heap. Under sustained throughput this produces short-lived
 *       garbage that triggers minor GC pauses — visible in p99+ latency.</li>
 * </ul>
 *
 * <p>SAME-THREAD MEASUREMENT: Writer and reader run in the same JMH benchmark thread.
 * The point of comparison is the <em>data-structure cost</em> (mmap vs heap allocation),
 * not inter-thread coordination. Chronicle Queue's real use case is IPC or cross-JVM persistence,
 * where its mmap backing means a writer and reader in <em>different</em> processes share the
 * exact same memory pages with no serialisation.
 *
 * <p>REQUIRED JVM FLAGS:
 * <pre>
 *   --add-opens java.base/java.lang.reflect=ALL-UNNAMED
 *   --add-opens java.base/java.lang=ALL-UNNAMED
 *   --add-opens java.base/java.util=ALL-UNNAMED
 * </pre>
 * These are needed by Chronicle's internal reflective field access. Added via
 * {@code @Fork(jvmArgsAppend = ...)} below so they apply automatically during benchmark runs.
 *
 * <p>TEMP DIRECTORY: Created via {@link Files#createTempDirectory} in {@code @Setup(Level.Trial)}
 * and recursively deleted in {@code @TearDown(Level.Trial)}. No hardcoded paths are used.
 *
 * <p>SEE ALSO: {@code part3-specialized-libraries.md} §"Chronicle Queue (Durable Off-Heap Messaging)".
 * BENCHMARK-METHODOLOGY.md for measurement approach.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G,
        // Chronicle Queue requires the full set of module-opens on JDK 17+
        BenchmarkBase.OPEN_LANG, BenchmarkBase.OPEN_LANG_REFLECT, BenchmarkBase.OPEN_IO,
        BenchmarkBase.OPEN_NIO, BenchmarkBase.OPEN_SUN_NIO_CH, BenchmarkBase.OPEN_JDK_REF,
        BenchmarkBase.OPEN_JDK_MISC, BenchmarkBase.NATIVE_ACCESS,
        "--add-opens=java.base/java.util=ALL-UNNAMED"
})
@SuppressWarnings("unused")
public class ChronicleQueueBenchmark {

    /** Fixed 64-byte payload — one CPU cache line. */
    private static final int MESSAGE_SIZE = 64;

    // -------------------------------------------------------------------------
    // State: Chronicle Queue (memory-mapped, auto-cleaned temp dir)
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class CqState {

        Path tempDir;
        ChronicleQueue queue;
        ExcerptAppender appender;
        ExcerptTailer tailer;

        /** Pre-allocated write buffer; reused on every iteration — no hot-path allocation. */
        final byte[] writePayload = new byte[MESSAGE_SIZE];
        /** Pre-allocated read buffer; Chronicle writes into this directly. */
        final byte[] readBuffer = new byte[MESSAGE_SIZE];

        @Setup(Level.Trial)
        public void setup() throws IOException {
            tempDir = Files.createTempDirectory("devoxx-cq-");
            queue = ChronicleQueue.singleBuilder(tempDir.toFile()).build();
            appender = queue.createAppender();
            tailer = queue.createTailer();
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            if (tailer != null) tailer.close();
            if (appender != null) appender.close();
            if (queue != null) queue.close();

            // Recursively delete all mmap files — Chronicle rolls to new files over time
            if (tempDir != null && Files.exists(tempDir)) {
                try (Stream<Path> paths = Files.walk(tempDir)) {
                    paths.sorted(Comparator.reverseOrder())
                         .map(Path::toFile)
                         .forEach(File::delete);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // State: ArrayDeque heap baseline
    // -------------------------------------------------------------------------

    @State(Scope.Benchmark)
    public static class ArrayDequeState {
        final ArrayDeque<byte[]> deque = new ArrayDeque<>(1024);
    }

    // -------------------------------------------------------------------------
    // Benchmarks
    // -------------------------------------------------------------------------

    /**
     * Chronicle Queue write + read in the same thread.
     *
     * <p>The {@link ExcerptAppender#writingDocument()} call returns a {@link DocumentContext}
     * backed by the mmap region. Writing via {@code ctx.wire().bytes()} copies directly into
     * the mapped memory — no intermediate heap buffer, no syscall. The tailer reads the same
     * region via {@link ExcerptTailer#readingDocument()}, again via the mmap window.
     *
     * <p>Expected: ~800 ns – 1.5 µs/op on the EPYC 7282 reference box (memory-mapped I/O cost)
     * with zero new heap objects allocated per iteration.
     */
    @Benchmark
    public void chronicleQueueWriteRead(CqState state, Blackhole bh) {
        // --- Write ---
        try (DocumentContext wCtx = state.appender.writingDocument()) {
            wCtx.wire().bytes().write(state.writePayload);
        }

        // --- Read (poll until available — same thread, should be immediate) ---
        boolean consumed = false;
        while (!consumed) {
            try (DocumentContext rCtx = state.tailer.readingDocument()) {
                if (rCtx.isPresent()) {
                    rCtx.wire().bytes().read(state.readBuffer);
                    bh.consume(state.readBuffer[0]);
                    consumed = true;
                }
            }
        }
    }

    /**
     * Heap baseline: {@link ArrayDeque#offer} + {@link ArrayDeque#poll}.
     *
     * <p>Each call allocates a fresh {@code new byte[MESSAGE_SIZE]} — this is intentional.
     * In any real system that uses arrays for messages (e.g., Netty ByteBuf pools aside),
     * the allocation pressure builds and manifests as GC pauses at the tail of the latency
     * distribution. Chronicle Queue's zero-allocation approach eliminates this entire category
     * of latency variance.
     *
     * <p>Expected: ~50–200 ns/op (pure heap mechanics), but with visible p99 spikes in
     * longer runs due to minor GC pressure from short-lived byte[] instances.
     */
    @Benchmark
    public void inMemoryArrayDeque(ArrayDequeState state, Blackhole bh) {
        // Allocate per offer — mirrors real heap-based messaging
        state.deque.offer(new byte[MESSAGE_SIZE]);
        byte[] msg = state.deque.poll();
        // Consume first element to prevent dead-code elimination
        if (msg != null) {
            bh.consume(msg[0]);
        }
    }
}

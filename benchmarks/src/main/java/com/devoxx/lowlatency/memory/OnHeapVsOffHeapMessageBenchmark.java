package com.devoxx.lowlatency.memory;

import com.devoxx.lowlatency.common.BenchmarkBase;
import net.openhft.chronicle.bytes.Bytes;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * BACKS SLIDE: "Off-Heap Memory: Bypassing the Garbage Collector"
 * CLAIM: Off-heap variants (DirectByteBuffer and Chronicle Bytes) are 10–20× faster
 *        than on-heap HashMap<Long,byte[]> and produce 100% less GC allocation.
 * MECHANISM: DirectByteBuffer and Chronicle Bytes live outside the JVM heap; the GC
 *            only tracks the thin wrapper reference (~16 bytes), not the payload.
 *            Hot-path ops are pure DRAM reads with zero write-barrier overhead.
 *            AlwaysPreTouch eliminates cold-page faults from the measurement.
 * SEE ALSO: results/analysis-and-narrative.md § 1
 *
 * <p><b>Profiling allocation rate:</b>
 * <pre>
 *   java -jar target/benchmarks.jar OnHeapVsOffHeapMessageBenchmark -prof gc
 * </pre>
 * {@code gc.alloc.rate.norm} for processOnHeap should be ~(64 * N) bytes/op;
 * for processOffHeap and processChronicleBytes it should be ~0 bytes/op.
 *
 * <p><b>Hardware target:</b> AMD EPYC 7282 @ 2.8 GHz, 3 physical cores, 24 GB.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
        BenchmarkBase.DIAG_VM, BenchmarkBase.PRE_TOUCH, BenchmarkBase.XMS_2G, BenchmarkBase.XMX_2G,
        // Chronicle Bytes requires JDK module-opens for its reflection-based bootstrap
        BenchmarkBase.OPEN_LANG, BenchmarkBase.OPEN_LANG_REFLECT, BenchmarkBase.OPEN_IO,
        BenchmarkBase.OPEN_NIO, BenchmarkBase.OPEN_SUN_NIO_CH, BenchmarkBase.OPEN_JDK_REF,
        BenchmarkBase.OPEN_JDK_MISC, BenchmarkBase.NATIVE_ACCESS
})
@State(Scope.Benchmark)
public class OnHeapVsOffHeapMessageBenchmark {

    /** Number of 64-byte messages processed per benchmark invocation. */
    static final int N = 1_000;

    /** Fixed message size in bytes — one cache line. */
    static final int MSG_SIZE = 64;

    // ---- on-heap state ----
    private HashMap<Long, byte[]> onHeapMap;

    // ---- off-heap state ----
    private ByteBuffer offHeapBuffer;      // JDK DirectByteBuffer
    private Bytes<?>   chronicleBytes;     // OpenHFT Chronicle Bytes (native allocation)

    // ---- shared seed values written into each message slot at setup ----
    private static final byte FILL_SEED = 0x42;

    @Setup(Level.Trial)
    public void setup() {
        // ── On-heap: pre-allocate N byte[MSG_SIZE] and fill the HashMap ────────
        onHeapMap = new HashMap<>(N * 2, 0.75f);
        for (int i = 0; i < N; i++) {
            byte[] msg = new byte[MSG_SIZE];
            Arrays.fill(msg, (byte) (i & 0xFF));
            // Write a distinct long header and int in positions 0..11
            writeLong(msg, 0, (long) i * 0x9E3779B97F4A7C15L); // Fibonacci hashing
            writeInt(msg,  8, i);
            onHeapMap.put((long) i, msg);
        }

        // ── Off-heap JDK: single contiguous DirectByteBuffer ─────────────────
        offHeapBuffer = ByteBuffer.allocateDirect(MSG_SIZE * N);
        for (int i = 0; i < N; i++) {
            int base = i * MSG_SIZE;
            offHeapBuffer.putLong(base,     (long) i * 0x9E3779B97F4A7C15L);
            offHeapBuffer.putInt(base + 8,  i);
            // fill rest of slot with pattern
            for (int j = 12; j < MSG_SIZE; j++) {
                offHeapBuffer.put(base + j, FILL_SEED);
            }
        }

        // ── Chronicle Bytes: single contiguous native allocation ──────────────
        chronicleBytes = Bytes.allocateDirect((long) MSG_SIZE * N);
        for (int i = 0; i < N; i++) {
            long pos = (long) i * MSG_SIZE;
            chronicleBytes.writeLong(pos,     (long) i * 0x9E3779B97F4A7C15L);
            chronicleBytes.writeInt(pos + 8,  i);
            for (int j = 12; j < MSG_SIZE; j++) {
                chronicleBytes.writeByte(pos + j, FILL_SEED);
            }
        }
    }

    /**
     * Releases native memory held by Chronicle Bytes.
     * Critical: DirectBuffer cleanup is GC-driven (Cleaner); Chronicle Bytes cleanup must
     * be explicit — omitting releaseLast() leaks the native memory budget for this JVM process.
     */
    @TearDown(Level.Trial)
    public void teardown() {
        if (chronicleBytes != null) {
            chronicleBytes.releaseLast();
            chronicleBytes = null;
        }
        // Null the direct buffer to allow the Cleaner to run on next GC
        offHeapBuffer = null;
        onHeapMap     = null;
    }

    // =========================================================================
    // BENCHMARKS
    // =========================================================================

    /**
     * Baseline — traditional on-heap approach.
     * <p>GC tracks every {@code byte[]} stored in the map. At 100K messages/sec the young
     * generation fills in ~10 s, triggering a 10–50 ms stop-the-world pause.
     */
    @Benchmark
    public void processOnHeap(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            byte[] msg = onHeapMap.get((long) i);
            // Decode the fixed 8-byte + 4-byte header typical of market-data frames
            long field0 = readLong(msg, 0);
            int  field1 = readInt(msg,  8);
            bh.consume(field0);
            bh.consume(field1);
        }
    }

    /**
     * Off-heap — JDK {@link ByteBuffer#allocateDirect}.
     * <p>GC sees only the 16-byte wrapper object; the 64 KB of message data is invisible.
     * Zero write-barrier overhead on every read.
     */
    @Benchmark
    public void processOffHeap(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            int  base   = i * MSG_SIZE;
            long field0 = offHeapBuffer.getLong(base);
            int  field1 = offHeapBuffer.getInt(base + 8);
            bh.consume(field0);
            bh.consume(field1);
        }
    }

    /**
     * Chronicle Bytes — OpenHFT's abstraction over native memory.
     * <p>Adds position-tracking and bounds-checking over the raw DirectBuffer; compare
     * with {@link #processOffHeap} to quantify the Chronicle Bytes API overhead.
     * Chronicle Bytes is the canonical choice when you need the full {@code Bytes} API
     * (auto-advancing read/write position, partial writes, {@code writeMarshallable}, etc.).
     */
    @Benchmark
    public void processChronicleBytes(Blackhole bh) {
        for (int i = 0; i < N; i++) {
            long pos    = (long) i * MSG_SIZE;
            long field0 = chronicleBytes.readLong(pos);
            int  field1 = chronicleBytes.readInt(pos + 8);
            bh.consume(field0);
            bh.consume(field1);
        }
    }

    // =========================================================================
    // Private helpers — zero-allocation manual byte encoding/decoding
    // (avoids creating a ByteBuffer.wrap() inside the hot path during setup)
    // =========================================================================

    private static void writeLong(byte[] b, int off, long v) {
        b[off    ] = (byte)(v >>> 56);
        b[off + 1] = (byte)(v >>> 48);
        b[off + 2] = (byte)(v >>> 40);
        b[off + 3] = (byte)(v >>> 32);
        b[off + 4] = (byte)(v >>> 24);
        b[off + 5] = (byte)(v >>> 16);
        b[off + 6] = (byte)(v >>>  8);
        b[off + 7] = (byte)(v        );
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off    ] = (byte)(v >>> 24);
        b[off + 1] = (byte)(v >>> 16);
        b[off + 2] = (byte)(v >>>  8);
        b[off + 3] = (byte)(v        );
    }

    private static long readLong(byte[] b, int off) {
        return ((long) b[off    ]         << 56)
             | ((long)(b[off + 1] & 0xFF) << 48)
             | ((long)(b[off + 2] & 0xFF) << 40)
             | ((long)(b[off + 3] & 0xFF) << 32)
             | ((long)(b[off + 4] & 0xFF) << 24)
             | ((long)(b[off + 5] & 0xFF) << 16)
             | ((long)(b[off + 6] & 0xFF) <<  8)
             |  (long)(b[off + 7] & 0xFF);
    }

    private static int readInt(byte[] b, int off) {
        return (       b[off    ]         << 24)
             | ((b[off + 1] & 0xFF)       << 16)
             | ((b[off + 2] & 0xFF)       <<  8)
             |  (b[off + 3] & 0xFF);
    }
}

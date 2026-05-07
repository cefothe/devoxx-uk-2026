package com.devoxx.lowlatency.examples.memory;

import org.agrona.concurrent.UnsafeBuffer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

/**
 * BACKS SLIDE: "Off-heap mechanism"
 * PATTERN: Allocate-once DirectByteBuffer wrapped by Agrona UnsafeBuffer
 * MECHANISM: DirectByteBuffer lives in native memory outside the GC-managed heap.
 *            The GC tracks only the thin wrapper reference (~16 bytes), never the payload.
 *            Every read/write is a direct DRAM operation with zero write-barrier overhead.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.memory.OffHeapMessageExample
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/memory/OnHeapVsOffHeapMessageBenchmark.java
 */
public final class OffHeapMessageExample {

    // One megabyte is far more than one 20-byte order slot; we keep the allocation
    // realistic (production buffers are typically 1–64 MB per channel).
    private static final int BUFFER_SIZE = 1024 * 1024;

    // Synthetic order layout in the buffer — matches the benchmark's message shape.
    // Offset 0 : long  orderId   (8 bytes)
    // Offset 8 : long  priceRaw  (8 bytes, fixed-point cents to avoid float in hot path)
    // Offset 16: int   quantity  (4 bytes)
    private static final int OFFSET_ORDER_ID = 0;
    private static final int OFFSET_PRICE    = 8;
    private static final int OFFSET_QTY      = 16;

    private static final int ITERATIONS = 1_000_000;

    public static void main(String[] args) {
        // Allocate once at startup — this cost never recurs in steady state.
        ByteBuffer direct = ByteBuffer.allocateDirect(BUFFER_SIZE);
        UnsafeBuffer buffer = new UnsafeBuffer(direct);

        // Capture GC state before the hot loop.
        long gcBefore = gcCount();

        long start = System.nanoTime();
        long checksum = 0L; // consumed so the JIT cannot eliminate the reads

        for (int i = 0; i < ITERATIONS; i++) {
            // Write — primitive ops into native memory, zero allocation.
            buffer.putLong(OFFSET_ORDER_ID, i);
            // Fixed-point price: store in cents to keep the hot path allocation-free.
            buffer.putLong(OFFSET_PRICE, 10_000L + i % 1000);
            buffer.putInt(OFFSET_QTY, i & 0x7FF);

            // Read — still zero allocation; primitives returned by value.
            long orderId  = buffer.getLong(OFFSET_ORDER_ID);
            long price    = buffer.getLong(OFFSET_PRICE);
            int  quantity = buffer.getInt(OFFSET_QTY);

            // Fold into checksum to prevent dead-code elimination.
            checksum += orderId + price + quantity;
        }

        long elapsedMs  = (System.nanoTime() - start) / 1_000_000;
        long gcAfter    = gcCount();
        long gcPauses   = gcAfter - gcBefore;
        long bytesRead  = (long) ITERATIONS * (8 + 8 + 4);
        long bytesWrite = bytesRead;

        System.out.println("--- Off-heap mechanism ---");
        System.out.printf("Buffer size       : %,d bytes (1 MB DirectByteBuffer, allocated once)%n", BUFFER_SIZE);
        System.out.printf("Iterations        : %,d%n", ITERATIONS);
        System.out.printf("Bytes written     : %,d%n", bytesWrite);
        System.out.printf("Bytes read        : %,d%n", bytesRead);
        System.out.printf("Elapsed           : %d ms%n", elapsedMs);
        System.out.printf("GC pauses         : %d (zero means the GC never saw this memory)%n", gcPauses);
        System.out.printf("Allocations       : 0 in steady state (buffer pre-allocated before loop)%n");
        System.out.printf("Checksum          : %d (prevents dead-code elimination)%n", checksum);
        System.out.println("The GC literally does not know this data exists.");
    }

    /** Sums collection counts across all GC algorithms active in this JVM. */
    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gc.getCollectionCount();
            if (c >= 0) total += c;
        }
        return total;
    }
}

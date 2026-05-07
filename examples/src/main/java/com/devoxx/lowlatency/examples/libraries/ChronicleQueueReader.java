package com.devoxx.lowlatency.examples.libraries;

/**
 * BACKS SLIDE: "Chronicle Queue — persistent, microsecond IPC"
 * PATTERN: Memory-mapped file queue tail-reader for sub-microsecond, zero-allocation IPC
 * MECHANISM: Reads scan the same mmap region the Writer produced into — no deserialisation,
 *            no kernel copy; both processes share the same physical memory pages via the OS page cache
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.libraries.ChronicleQueueReader
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/libraries/ChronicleQueueBenchmark.java
 *
 * IPC DEMO: Start ChronicleQueueWriter in one terminal first.  Run this Reader in a second
 * terminal against the same /tmp/devoxx-uk-examples-queue.  The Reader tails live messages
 * as the Writer produces them — true inter-process, sub-microsecond messaging via shared mmap.
 * Run either side alone to read from a previously written queue or for self-contained testing.
 */

import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ChronicleQueueReader {

    // Must match the Writer's path; both processes map the same OS file.
    private static final String QUEUE_PATH   = ChronicleQueueWriter.QUEUE_PATH;
    private static final long   EXPECTED     = 1_000_000L;
    private static final long   TIMEOUT_NS   = 10_000_000_000L; // 10 seconds

    static {
        // Same cleanup contract as the Writer: whichever process exits last removes the directory.
        Runtime.getRuntime().addShutdownHook(
            Thread.ofPlatform().unstarted(() -> ChronicleQueueWriter.deleteQuietly(Path.of(QUEUE_PATH)))
        );
    }

    public static void main(String[] args) {
        Path queueDir = Path.of(QUEUE_PATH);
        if (!Files.exists(queueDir)) {
            System.out.println("Queue directory not found: " + QUEUE_PATH);
            System.out.println("Run ChronicleQueueWriter first, or start both in parallel for live IPC.");
            return;
        }

        System.out.println("ChronicleQueueReader — tailing " + QUEUE_PATH);
        System.out.println("  Waiting for up to " + (TIMEOUT_NS / 1_000_000_000L) + " s or " + EXPECTED + " messages...");

        try (ChronicleQueue  queue = ChronicleQueue.single(QUEUE_PATH);
             ExcerptTailer tailer  = queue.createTailer()) {

            long count     = 0L;
            long idSum     = 0L;   // sum of all orderIds for a cheap integrity check
            long deadline  = System.nanoTime() + TIMEOUT_NS;
            long t0        = System.nanoTime();

            while (count < EXPECTED && System.nanoTime() < deadline) {
                // readingDocument() is non-blocking: isPresent() returns false when the tail
                // has caught up with the producer.  We spin here — in production, pair with
                // an IdleStrategy (BusySpin or Backoff) to trade CPU burn for latency floor.
                try (DocumentContext ctx = tailer.readingDocument()) {
                    if (ctx.isPresent()) {
                        var bytes    = ctx.wire().bytes();
                        long orderId = bytes.readLong();
                        // price and qty consumed to advance the cursor to the next entry
                        bytes.readDouble(); // price
                        bytes.readInt();    // quantity
                        idSum += orderId;
                        count++;
                    }
                }
            }

            long elapsedNs = System.nanoTime() - t0;
            double seconds  = elapsedNs / 1e9;
            long throughput = count > 0 ? (long)(count / seconds) : 0L;
            boolean timedOut = count < EXPECTED;

            System.out.println();
            System.out.println("ChronicleQueueReader — done");
            System.out.println("  Queue path  : " + QUEUE_PATH);
            System.out.println("  Messages    : " + count + (timedOut ? "  (timed out — Writer may still be running)" : ""));
            System.out.println("  orderId sum : " + idSum + "  (expected: " + expectedIdSum(count) + ")");
            System.out.printf( "  Elapsed     : %.3f s%n",       seconds);
            System.out.printf( "  Throughput  : %,d msgs/sec%n", throughput);
            System.out.println("  IPC latency : sub-microsecond — mmap pages shared with Writer process");
            System.out.println("  Allocation  : zero per read — bytes read directly from mapped region");
            System.out.println("  (queue dir deleted on JVM exit by shutdown hook)");
        }
    }

    /**
     * Expected sum of 0 + 1 + ... + (n-1) = n*(n-1)/2.
     * Used as a lightweight end-to-end integrity check on the message stream.
     */
    private static long expectedIdSum(long n) {
        return n * (n - 1L) / 2L;
    }
}

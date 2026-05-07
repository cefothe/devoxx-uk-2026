package com.devoxx.lowlatency.examples.libraries;

/**
 * BACKS SLIDE: "Chronicle Queue — persistent, microsecond IPC"
 * PATTERN: Memory-mapped file queue for sub-microsecond, zero-allocation inter-process messaging
 * MECHANISM: Writes go directly to an mmap region — no syscall, no heap copy, no GC interaction;
 *            the kernel and JVM share the same physical pages, so "persistence" costs nothing extra
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.libraries.ChronicleQueueWriter
 * SEE ALSO: benchmarks/src/main/java/com/devoxx/lowlatency/libraries/ChronicleQueueBenchmark.java
 *
 * IPC DEMO: Run this class in one terminal, then run ChronicleQueueReader in a second terminal
 * against the same /tmp/devoxx-uk-examples-queue path.  Both processes share the mmap'd file —
 * no serialisation, no network, sub-microsecond hand-off.
 */

import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.wire.DocumentContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class ChronicleQueueWriter {

    static final  String QUEUE_PATH = "/tmp/devoxx-uk-examples-queue";
    private static final long   TOTAL      = 1_000_000L;

    // Cleanup is owned by the Reader (the natural last touch in the Writer→Reader
    // pipeline). Running the Writer alone leaves the queue on disk so a Reader
    // started later in another terminal can consume it. Manual cleanup if the
    // Reader is never run: rm -rf /tmp/devoxx-uk-examples-queue

    public static void main(String[] args) {
        System.out.println("ChronicleQueueWriter — writing " + TOTAL + " messages to " + QUEUE_PATH);

        try (ChronicleQueue queue   = ChronicleQueue.single(QUEUE_PATH);
             ExcerptAppender appender = queue.createAppender()) {

            long t0 = System.nanoTime();

            for (long i = 0; i < TOTAL; i++) {
                long   orderId  = i;
                double price    = 99.5 + (i & 0xFL);  // 16-cycle price ladder, branch-free
                int    quantity = (int)(100L + (i % 10L));

                // writingDocument() returns a view into the mmap region — no heap buffer involved.
                // The try-with-resources closes (commits) the entry; the OS pages are already dirty.
                try (DocumentContext ctx = appender.writingDocument()) {
                    var bytes = ctx.wire().bytes();
                    bytes.writeLong(orderId);
                    bytes.writeDouble(price);
                    bytes.writeInt(quantity);
                }
            }

            long elapsedNs = System.nanoTime() - t0;
            double seconds  = elapsedNs / 1e9;
            long throughput = (long)(TOTAL / seconds);

            System.out.println();
            System.out.println("ChronicleQueueWriter — done");
            System.out.println("  Queue path  : " + QUEUE_PATH);
            System.out.println("  Messages    : " + TOTAL);
            System.out.printf( "  Elapsed     : %.3f s%n",          seconds);
            System.out.printf( "  Throughput  : %,d msgs/sec%n",    throughput);
            System.out.println("  Persistence : mmap-backed — queue survives JVM restart");
            System.out.println("  Allocation  : zero per write — bytes go straight to mapped pages");
            System.out.println("  (queue persists on disk; ChronicleQueueReader will clean up)");

        }
        // ChronicleQueue.close() unmaps the file. Queue directory persists for the Reader.
    }

    /** Recursively delete a directory tree, silently ignoring errors. */
    static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        } catch (IOException e) {
            System.err.println("[cleanup] could not fully delete " + dir + ": " + e.getMessage());
        }
    }
}

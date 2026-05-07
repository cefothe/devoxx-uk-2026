package com.devoxx.lowlatency.examples.capstone;

import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.wire.DocumentContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * BACKS SLIDE: "Production architecture — exchange-core style"
 * PATTERN: Persistent audit tap on the hot pipeline (the bottom branch of the slide-40 diagram)
 * MECHANISM: Chronicle Queue is a memory-mapped file. append() writes go directly
 *            into mmap'd pages — no syscall, no heap copy, no GC interaction.
 *            Audit and replication "for free" on the critical path.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.capstone.MiniExchange
 * SEE ALSO: examples/src/main/java/com/devoxx/lowlatency/examples/libraries/ChronicleQueueWriter.java
 */
public final class AuditWriter implements AutoCloseable {

    static final String QUEUE_PATH = "/tmp/mini-exchange-queue";

    private final ChronicleQueue   queue;
    private final ExcerptAppender  appender;
    private final boolean          keepQueue;
    private long                   appended;
    private boolean                closed;

    public AuditWriter(boolean keepQueue) {
        this.keepQueue = keepQueue;
        this.queue     = ChronicleQueue.single(QUEUE_PATH);
        this.appender  = queue.createAppender();

        // Belt-and-braces. The orderly path is MiniExchange calling close()
        // explicitly after the run, which deletes the directory unless
        // --keep-queue was passed. The shutdown hook here only matters if
        // someone Ctrl-C's the demo mid-run — we still want /tmp clean afterwards.
        if (!keepQueue) {
            Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().unstarted(() -> deleteQuietly(Path.of(QUEUE_PATH)))
            );
        }
    }

    /**
     * Append one event to the audit queue. No allocation: the DocumentContext
     * writes into the mapped pages directly. The kernel keeps the dirty pages
     * around and writes them out when it sees fit; the JVM never copies bytes.
     */
    public void append(OrderEvent e) {
        try (DocumentContext ctx = appender.writingDocument()) {
            var bytes = ctx.wire().bytes();
            bytes.writeLong(e.orderId);
            bytes.writeLong(e.userId);
            bytes.writeLong(e.symbolId);
            bytes.writeLong(e.priceTicks);
            bytes.writeLong(e.quantity);
            bytes.writeByte(e.side);
        }
        appended++;
    }

    public long appended() {
        return appended;
    }

    public String queuePath() {
        return QUEUE_PATH;
    }

    /**
     * Idempotent. MiniExchange calls this on the orderly exit path; the JVM
     * shutdown hook also calls deleteQuietly as a fallback. Calling twice
     * is fine — the second deleteQuietly walks an empty path and returns.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        appender.close();
        queue.close();
        if (!keepQueue) {
            deleteQuietly(Path.of(QUEUE_PATH));
        }
    }

    static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        } catch (IOException ignored) {
            // Best-effort. Mmap'd pages are reclaimed by the OS on JVM exit;
            // a stray /tmp/mini-exchange-queue is a janitorial issue, not a leak.
        }
    }
}

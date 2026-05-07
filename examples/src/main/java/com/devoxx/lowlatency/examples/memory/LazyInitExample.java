package com.devoxx.lowlatency.examples.memory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * BACKS SLIDE: "The pool's evil twins"
 * PATTERN: Initialization-on-demand holder (lazy singleton without locks)
 * MECHANISM: The JVM initializes a class exactly once, the first time it is referenced.
 *            By wrapping the expensive resource in a private static holder class, the
 *            resource is only allocated when the enclosing method is first called.
 *            Code paths that never call that method pay zero allocation cost.
 * RUN: mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.memory.LazyInitExample
 */
public final class LazyInitExample {

    // -----------------------------------------------------------------------
    // Holder class idiom — the JVM class-initialization lock guarantees that
    // DIGEST is constructed exactly once, on the first call to getDigest().
    //
    // NOT double-checked locking: that pattern requires volatile and is
    // error-prone to backport across JVM versions. The holder pattern is
    // simpler and uses a JVM guarantee that predates generics.
    // -----------------------------------------------------------------------
    private static final class DigestHolder {
        // MessageDigest is expensive: internal state, byte arrays, provider lookup.
        // Wrapping it here means the cost is deferred until actually needed.
        static final MessageDigest DIGEST;
        static {
            try {
                DIGEST = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    // Tracks whether the holder has been triggered.
    // Set by the first caller; readable by the code path that should NOT trigger it.
    private static volatile boolean digestInitialized = false;

    static MessageDigest getDigest() {
        MessageDigest d = DigestHolder.DIGEST; // triggers DigestHolder.<clinit> on first call
        digestInitialized = true;
        return d;
    }

    // -----------------------------------------------------------------------
    // A second expensive resource: a 1 MB scratch buffer.
    // Only the write path needs it; the read-only fast path should never pay
    // for it.
    // -----------------------------------------------------------------------
    private static final class ScratchBufferHolder {
        static final byte[] BUFFER = new byte[1024 * 1024];
    }

    private static volatile boolean bufferInitialized = false;

    static byte[] getScratchBuffer() {
        byte[] b = ScratchBufferHolder.BUFFER;
        bufferInitialized = true;
        return b;
    }

    public static void main(String[] args) {
        // -----------------------------------------------------------------------
        // Fast path: read-only order validation — does not need either resource.
        // The resources should remain unallocated after this path runs.
        // -----------------------------------------------------------------------
        boolean digestBeforeFastPath = digestInitialized;
        boolean bufferBeforeFastPath = bufferInitialized;

        for (int i = 0; i < 1_000_000; i++) {
            // Simulated read-only validation: arithmetic only, no digest, no scratch buffer.
            long orderId = i;
            boolean valid = orderId >= 0;
            if (!valid) throw new IllegalStateException("unexpected");
        }

        boolean digestAfterFastPath  = digestInitialized;
        boolean bufferAfterFastPath  = bufferInitialized;

        // -----------------------------------------------------------------------
        // Slow path: hashing is needed — first call triggers DigestHolder.<clinit>.
        // -----------------------------------------------------------------------
        MessageDigest d = getDigest();
        boolean digestAfterSlowPath = digestInitialized;

        System.out.println("--- Lazy initialization (holder class idiom) ---");
        System.out.printf("Digest initialized before fast path : %b%n", digestBeforeFastPath);
        System.out.printf("Digest initialized after  fast path : %b (1M validations, never needed)%n",
                digestAfterFastPath);
        System.out.printf("Digest initialized after  slow path : %b (triggered on first call only)%n",
                digestAfterSlowPath);
        System.out.printf("Scratch buffer initialized          : %b (never called — 1 MB never allocated)%n",
                bufferAfterFastPath);
        System.out.printf("DigestHolder.DIGEST class           : %s%n", d.getAlgorithm());
        System.out.println("Holder idiom: pay the cost only when the path that needs it actually runs.");
    }
}

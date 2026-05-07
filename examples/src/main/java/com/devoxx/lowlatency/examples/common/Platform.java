package com.devoxx.lowlatency.examples.common;

import net.openhft.affinity.Affinity;

/**
 * Platform-conditional helpers for the examples module.
 *
 * The talk's affinity, @Contended, and isolcpus stories only land on Linux.
 * Rather than have every example branch at the call site, route through
 * here: macOS becomes a no-op with a single explanatory println, Linux
 * does the real thing.
 */
public final class Platform {

    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean LINUX = OS.contains("linux");
    private static final boolean MAC = OS.contains("mac") || OS.contains("darwin");

    private Platform() {}

    public static boolean isLinux() {
        return LINUX;
    }

    public static boolean isMac() {
        return MAC;
    }

    /**
     * Pin the calling thread to the given CPU. On macOS this is a no-op
     * (OpenHFT Affinity has no macOS implementation); on Linux it pins
     * the thread to the named core via sched_setaffinity.
     */
    public static void pinCurrentThreadToCpu(int cpu) {
        if (LINUX) {
            Affinity.setAffinity(cpu);
        } else {
            System.out.printf(
                "[platform] thread affinity not supported on %s — continuing without pinning (CPU %d requested)%n",
                System.getProperty("os.name"), cpu);
        }
    }

    /**
     * Print a one-line skip message and return whether the example should
     * proceed with its main demonstration. Used by examples whose entire
     * point is Linux-only (isolcpus demos, @Contended without the JVM flag, etc.).
     */
    public static boolean requireLinuxOrSkip(String exampleName) {
        if (LINUX) return true;
        System.out.printf(
            "[platform] %s is Linux-only — skipping main demo on %s. " +
            "See speaker notes for expected output.%n",
            exampleName, System.getProperty("os.name"));
        return false;
    }
}

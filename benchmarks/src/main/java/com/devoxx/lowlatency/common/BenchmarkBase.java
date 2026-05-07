package com.devoxx.lowlatency.common;

/**
 * Shared utilities and JVM-arg constants for the Devoxx UK benchmark suite.
 *
 * <p><b>JMH annotation note:</b> JMH's annotation processor reads its annotations
 * <i>directly</i> on the benchmark class — it does <b>not</b> follow Java meta-annotations
 * (annotations on annotations) or {@code @Inherited}. That means a composite
 * {@code @StandardConfig} marker annotation does not work — JMH silently ignores it
 * and falls back to defaults (10s warmup, Throughput mode).
 *
 * <p>For this reason every benchmark class in this suite declares
 * {@code @BenchmarkMode}, {@code @OutputTimeUnit}, {@code @Warmup}, {@code @Measurement},
 * {@code @Fork}, and {@code @State} <b>directly</b>. The {@link #BASE_JVM_ARGS} and
 * {@link #CHRONICLE_JVM_ARGS} constants below let benchmarks share the JVM-flag block
 * inside their {@code @Fork(jvmArgsAppend = ...)} attribute.
 *
 * <p>See {@code BENCHMARK-METHODOLOGY.md} for the rationale behind each flag.
 */
public final class BenchmarkBase {

    private BenchmarkBase() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Standard JVM args — used by every benchmark via @Fork(jvmArgsAppend = ...)
    // ─────────────────────────────────────────────────────────────────────────

    public static final String DIAG_VM     = "-XX:+UnlockDiagnosticVMOptions";
    public static final String PRE_TOUCH   = "-XX:+AlwaysPreTouch";
    public static final String XMS_2G      = "-Xms2g";
    public static final String XMX_2G      = "-Xmx2g";

    /**
     * JVM args required by Chronicle Core / Bytes / Queue / Map on JDK 17+.
     * Without these, Chronicle's reflection-based bootstrapping fails with
     * {@code IllegalAccessException: module java.base does not open ...}.
     */
    public static final String OPEN_LANG          = "--add-opens=java.base/java.lang=ALL-UNNAMED";
    public static final String OPEN_LANG_REFLECT  = "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED";
    public static final String OPEN_IO            = "--add-opens=java.base/java.io=ALL-UNNAMED";
    public static final String OPEN_NIO           = "--add-opens=java.base/java.nio=ALL-UNNAMED";
    public static final String OPEN_SUN_NIO_CH    = "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED";
    public static final String OPEN_JDK_REF       = "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED";
    public static final String OPEN_JDK_MISC      = "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED";
    public static final String NATIVE_ACCESS      = "--enable-native-access=ALL-UNNAMED";

    // ─────────────────────────────────────────────────────────────────────────
    // OS detection — affinity benchmarks need to skip on macOS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @return true if running on Linux (where thread affinity, NUMA, and {@code @Contended}
     * behave as expected). Affinity benchmarks should skip themselves on non-Linux.
     */
    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /**
     * @return true if running on macOS (Apple Silicon dev environment).
     */
    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}

# Benchmark Methodology

This document defines how every benchmark in this suite is configured and executed,
so results are reproducible and comparable across machines.

## Hardware (target reference environment)

| Component   | Value                                              |
|-------------|----------------------------------------------------|
| CPU         | AMD EPYC 7282 @ 2.8 GHz, 3 physical cores          |
| RAM         | 24 GB                                              |
| Storage     | 180 GB NVMe                                        |
| Network     | 250 Mbit/s (irrelevant — benchmarks are local)     |

**Headline numbers reported in the talk come from this environment.** Stefan's Mac
M2/M3 is for development iteration only. Apple Silicon ARM64 numbers will differ
in absolute terms, but the *ratios* (off-heap vs on-heap, pooled vs allocated)
will hold.

## Software

| Component   | Value                                              |
|-------------|----------------------------------------------------|
| OS          | Linux x86_64 (target) / macOS arm64 (dev)          |
| JDK         | OpenJDK 25 (or 25 + ZGC for GC-impact benchmarks)  |
| Maven       | 3.9+                                               |
| JMH         | 1.37                                               |

## JMH configuration (every benchmark)

```
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})  // avg + percentiles
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:+AlwaysPreTouch",          // pre-fault heap pages — kill cold-page jitter
    "-Xms2g", "-Xmx2g",              // fixed heap — no resize jitter
    "-XX:+UseG1GC"                   // default; some benchmarks override to ZGC
})
@State(Scope.Benchmark)
```

Reasoning:
- `AverageTime` gives the headline median nanoseconds-per-op number.
- `SampleTime` gives the percentile distribution (p50, p99, p99.9) — critical for
  latency claims, since "0.5 µs median, 42 µs p99" is the actual production
  shape and you can't get that from average alone.
- 5 + 5 iterations keeps the run short enough to iterate (~15s per benchmark) but
  long enough to ride out warm-up jitter.
- `Fork=2` rules out single-fork JIT compilation accidents.

## JVM flags by category

Memory benchmarks add: `-XX:+UseG1GC` (default) for the on-heap baseline,
`-XX:+UseZGC` for the off-heap-vs-GC comparison.

Concurrency benchmarks: `-XX:-RestrictContended` to allow `@Contended` to work,
`--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED`.

Library benchmarks (Chronicle Queue / Map): `--add-opens java.base/java.lang=ALL-UNNAMED`
plus `--add-opens java.base/java.lang.reflect=ALL-UNNAMED` for OpenHFT internals.
Chronicle Queue benchmarks must run with a dedicated tmpfs/queue dir under
`/tmp/devoxx-uk-bench-queue` and `@TearDown` must delete it.

JIT benchmarks: `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` on a
side-run only, NOT the timed run (printing distorts JIT timing).

## Running

```bash
# build
cd benchmarks
./mvnw clean package

# run everything (long: ~30 min)
./scripts/run-all.sh

# run one category
java -jar target/benchmarks.jar 'com.devoxx.lowlatency.memory.*'

# run one benchmark
java -jar target/benchmarks.jar OnHeapVsOffHeapMessageBenchmark

# capture JSON for charting (used to feed Marp slides)
java -jar target/benchmarks.jar -rf json -rff results/raw/memory.json \
    'com.devoxx.lowlatency.memory.*'
```

## Caveats — read these before quoting numbers on stage

1. **Absolute numbers are environment-specific.** Quote *ratios* on stage
   ("21x faster") and reserve absolute numbers for your specific box.
2. **Thread affinity benchmarks only work on Linux.** OpenHFT Affinity uses
   `sched_setaffinity`; on macOS the affinity calls are no-ops and the benchmark
   degenerates into the unpinned baseline. Affinity benchmarks `@Setup` will
   detect macOS and `Assume.assumeTrue(...)`-skip themselves.
3. **3-physical-core EPYC slice.** Multi-threaded contention benchmarks scale
   thread counts to {1, 2, 3}. Don't push to 8+ — you'll just measure scheduler
   thrash, not the technique.
4. **JIT warmup matters.** If you re-run a single benchmark in isolation, the
   JIT may not have profiled the contended branches yet. Always run with
   `@Fork(2)` and `@Warmup(5)`. If a number looks off, suspect warmup before
   suspecting the technique.
5. **GC noise.** Small benchmarks can be perturbed by background GC of unrelated
   allocations. The `-XX:+AlwaysPreTouch` flag fixes most of this. For the
   GC-impact benchmark specifically, GC noise is the *signal*, not the problem.

## What "good" looks like (target ratios from production)

These come from Stefan's exchange-core-derived production system, ratified by
the article. The benchmark suite should reproduce these *ratios* (within ±50%)
on the EPYC reference box:

| Technique                              | Expected ratio             |
|----------------------------------------|----------------------------|
| Off-heap vs on-heap message process    | 10–20x faster, ~0% GC      |
| Object pool vs `new`                   | 10–25x faster, 0% GC       |
| Single-threaded shard vs contended     | 100–1000x under contention |
| `Long2LongHashMap` vs `HashMap`        | 3–6x faster, 0% boxing     |
| Disruptor SPSC vs `ArrayBlockingQueue` | 5–15x lower latency        |
| Lock-free CAS vs `synchronized`        | 2–10x under contention     |
| `@Contended` vs unpadded false-share   | 3–8x under contention      |

If your numbers are outside these ranges by an order of magnitude, suspect
the benchmark before the technique.

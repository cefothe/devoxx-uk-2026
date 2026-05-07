# Microsecond Latency Benchmarks — JMH Suite

Companion benchmark suite for the Devoxx UK 2026 talk
"Achieving Microsecond Latencies with Java".

Every performance claim in the talk is backed by a runnable benchmark in this module.

## Build & run

```bash
# requires JDK 25 + Maven 3.9+
mvn clean package
java -jar target/benchmarks.jar                                       # all benchmarks
java -jar target/benchmarks.jar 'OnHeapVsOffHeap.*'                   # one match
./scripts/run-all.sh                                                  # full suite + JSON
```

## Layout

```
src/main/java/com/devoxx/lowlatency/
├── common/           Shared JMH config (BenchmarkBase, @StandardConfig)
├── memory/           Heap / off-heap / pooling / primitive collections / GC
├── concurrency/      CAS vs locks, false sharing, single-threaded sharding, affinity
├── libraries/        Disruptor, Chronicle Queue, Chronicle Map, Agrona ring buffer
└── jit/              Inlining threshold, branch prediction, escape analysis
```

## Methodology

See [`BENCHMARK-METHODOLOGY.md`](BENCHMARK-METHODOLOGY.md). It documents the JMH
configuration (warmup, measurement, fork count, JVM flags), hardware target, and
the caveats that matter when quoting numbers on stage.

## How each benchmark connects to the talk

Every benchmark file starts with a header comment in this format:

```java
/**
 * BACKS SLIDE: "Off-heap memory eliminates GC pauses"
 * CLAIM: 15× faster, 100% GC eliminated
 * MECHANISM: DirectByteBuffer / Unsafe → no allocation → no GC mark/sweep
 * SEE ALSO: results/analysis-and-narrative.md § 1
 */
```

Use `grep -r "BACKS SLIDE" src/` to map the deck to the code.

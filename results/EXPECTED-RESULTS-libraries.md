# Expected Results — Libraries & JIT Benchmarks

> **Environment**: AMD EPYC 7282 (3 physical cores, 24 GB, NVMe), JDK 25, G1GC, 2 GB heap.
> Dev reference: Apple M2/M3. Results shown are EPYC unless noted.
> All figures are JMH `Mode.SampleTime` (ns/op) or `Mode.AverageTime` (ns/op) depending on benchmark.
> Run with `java -jar target/benchmarks.jar <BenchmarkClass> -prof gc` for allocation data.

---

## 1. `DisruptorVsArrayBlockingQueueBenchmark`

### Variants
| Benchmark | Data Structure | Allocation per msg | Blocking |
|---|---|---|---|
| `arrayBlockingQueue` | `ArrayBlockingQueue<byte[]>` | Yes (shared ref, no alloc) | Yes (`ReentrantLock` + `LockSupport.park`) |
| `jctoolsSpscArrayQueue` | JCTools `SpscArrayQueue<byte[]>` | Yes (`new byte[64]` per iter) | No (CAS + busy-spin) |
| `disruptorSpsc` | LMAX Disruptor + `BusySpinWaitStrategy` | No (pre-allocated events) | No (sequence barrier + `Thread.onSpinWait`) |

### Expected Latency (ns/op, `Mode.SampleTime`, EPYC 7282)

| Benchmark | p50 | p90 | p99 | p99.9 |
|---|---|---|---|---|
| `arrayBlockingQueue` | 3,000 – 8,000 | 10,000 – 30,000 | 25,000 – 80,000 | 100,000+ |
| `jctoolsSpscArrayQueue` | 200 – 600 | 800 – 2,000 | 3,000 – 10,000 | 20,000+ |
| `disruptorSpsc` | 150 – 400 | 400 – 900 | 600 – 1,500 | 2,000 – 5,000 |

**Expected Disruptor advantage over ABQ at p99: 15–50×**

### Why (Mechanism)

**ArrayBlockingQueue p99 is dominated by OS scheduling jitter**, not lock acquisition:
- `put()` acquires a `ReentrantLock`. When the consumer hasn't yet called `take()`, the producer parks via `LockSupport.park()` → `futex_wait(2)` syscall.
- When the consumer calls `take()` and finds an element, it calls `LockSupport.unpark(producer)` → `futex_wake(2)`.
- The `futex` round-trip is typically 1–5 µs on Linux under no load. Under GC or OS scheduling interference, it spikes to 50–200 µs.

**JCTools SpscArrayQueue eliminates the kernel transition** via CAS-based claim:
- Producer `offer()` does a single CAS on the tail pointer. Consumer `poll()` does a volatile read on the head pointer. No syscall, no park/unpark.
- Still allocates `new byte[64]` per iteration in this benchmark (mirrors real usage). Allocation pressure shows as GC pauses at p99+.

**Disruptor + BusySpinWaitStrategy eliminates both**:
- Pre-allocated ring: `MessageEvent` instances are created once in the ring buffer slots. The producer calls `ringBuffer.next()` (CAS claim), populates `event.sequence` in place, then calls `ringBuffer.publish(seq)` (single ordered write to the ring cursor).
- The EventHandler consumer busy-spins on the `SequenceBarrier` using `Thread.onSpinWait()` (x86 `PAUSE` instruction — avoids memory-order speculation hazards and yields pipeline resources to the producer thread sharing the same core complex).
- Zero allocation, zero kernel transitions, one cache-line ping-pong per message.

### Required JVM Flags
Standard BenchmarkBase flags only. `BusySpinWaitStrategy` uses `Thread.onSpinWait()` — available since JDK 9.

---

## 2. `ChronicleQueueBenchmark`

### Variants
| Benchmark | Data Structure | Backing | Allocation per iter |
|---|---|---|---|
| `chronicleQueueWriteRead` | Chronicle Queue `ExcerptAppender` + `ExcerptTailer` | Memory-mapped file (`mmap`) | Zero on heap (writes directly to mmap region) |
| `inMemoryArrayDeque` | `ArrayDeque<byte[]>` | Java heap | `new byte[64]` per `offer()` call |

### Expected Latency (ns/op, `Mode.AverageTime`)

| Benchmark | Expected (EPYC, warm) | Note |
|---|---|---|
| `chronicleQueueWriteRead` | 800 – 2,000 ns | mmap page-fault cost amortised after warmup |
| `inMemoryArrayDeque` | 50 – 200 ns | Pure heap speed, but allocates |

**Chronicle Queue is slower in ns/op at the median** — the point is NOT raw speed but:
1. **Zero GC pressure**: no `byte[]` escapes to the heap. With `-prof gc`, Chronicle shows 0 bytes allocated per `@Benchmark` call.
2. **Durability**: every write is persisted to disk via the OS page cache. A reader in a *different process* (or after JVM restart) sees the same data — with exactly the same code path.
3. **IPC without serialisation**: when producer and consumer are in separate JVMs, they share the same mmap pages. No serialisation overhead whatsoever.

### Why (Mechanism)

**Memory-mapped I/O**: Chronicle Queue calls `mmap(2)` on a file. Once the OS has faulted-in the pages (during warmup), all subsequent reads and writes are pure DRAM loads/stores from the CPU's perspective — no `read(2)` or `write(2)` syscall, no data copy between kernel and user space. The kernel and the JVM process share the same physical page frames.

**ArrayDeque allocates**: `offer(new byte[MESSAGE_SIZE])` triggers an Eden allocation. Under sustained throughput, the young generation fills and a minor GC fires. Minor GC on G1 pauses all application threads for 1–50 ms. Chronicle Queue's approach completely eliminates this GC category.

### Required JVM Flags
```
--add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
```
Added automatically via `@Fork(jvmArgsAppend = {...})` in the benchmark class.
Chronicle uses internal reflection to access `DirectByteBuffer` native addresses and OS file-locking APIs.

---

## 3. `ChronicleMapVsConcurrentHashMapBenchmark`

### Variants
| Benchmark | Data Structure | Data location | GC visibility |
|---|---|---|---|
| `chronicleMap` | Chronicle Map 3.x | Native (off-heap) | Invisible to GC |
| `concurrentHashMap` | `ConcurrentHashMap<LongPair, byte[]>` | Java heap | Fully traced by GC |

### Expected Throughput (ns/op, `Mode.AverageTime`, 1M entries)

| Benchmark | Expected (EPYC) | Notes |
|---|---|---|
| `chronicleMap` | 300 – 700 ns | Off-heap hash probe; no GC; value copy to heap on `get()` |
| `concurrentHashMap` | 250 – 600 ns | Heap hash probe; L3 miss on random access; GC pressure |

**Throughput is similar** — the point is the GC profile, not raw speed.

- Chronicle Map at 1M × 64-byte values: **0 MB on the Java heap** (data in native memory)
- ConcurrentHashMap at 1M × 64-byte values: **~80–100 MB on the Java heap** (values + nodes + key objects)
- Under `-Xmx2g` with `G1GC`, that 80 MB is traced on every young-gen collection (~every 1–2 seconds under our load). As map size grows to 10M, 20M entries, ConcurrentHashMap's heap footprint causes progressively worse GC pauses. Chronicle Map stays at 0 heap bytes regardless of size.

### Key Design Notes
- `LongPair` is a Java `record` with `implements Serializable`. Chronicle Map uses Java serialisation for the default marshaller on non-primitive key types. For production, replace with a `ChronicleMap`-native `ValueType` using `net.openhft.chronicle.values.Values` for zero-allocation off-heap access.
- The lookup pool of 65,536 pre-shuffled keys ensures L3-miss access patterns (the full 1M-entry map far exceeds L3 on the EPYC 7282's 32 MB L3).
- `ChronicleMap.get()` returns a heap-allocated copy of the value. For read-only hot paths, use `getUsing(key, usingValue)` with a pre-allocated value object to eliminate that copy allocation.

### Required JVM Flags
```
--add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.lang=ALL-UNNAMED
```
Added via `@Fork(jvmArgsAppend = {...})` in the benchmark class.

---

## 4. `AgronaRingBufferBenchmark`

### Variants
| Benchmark | Data Structure | Blocking | Allocation |
|---|---|---|---|
| `agronaManyToOneRingBuffer` | Agrona `ManyToOneRingBuffer` | No (CAS claim + busy-spin consumer) | Zero (direct buffer write) |
| `arrayBlockingQueue` | `ArrayBlockingQueue<byte[]>` | Yes (`ReentrantLock` + park) | Shared ref (`state.message`) |

### Expected Latency (ns/op, `Mode.SampleTime`, EPYC 7282)

| Benchmark | p50 | p90 | p99 |
|---|---|---|---|
| `agronaManyToOneRingBuffer` | 100 – 350 ns | 300 – 700 ns | 500 – 1,500 ns |
| `arrayBlockingQueue` | 3,000 – 8,000 ns | 10,000 – 30,000 ns | 25,000 – 80,000 ns |

**Expected Agrona advantage: 3–10× at p50; up to 50× at p99** (p99 gap driven by OS scheduling jitter on ABQ's futex path).

### Why (Mechanism)

**Agrona `ManyToOneRingBuffer` internal mechanics**:
1. **Claim**: Producer calls `write(msgTypeId, srcBuffer, 0, len)`. Internally, this does a CAS on the `producerLimit` field (padded to its own cache line to avoid false-sharing with the consumer position). If space is available, the slot is claimed atomically.
2. **Write**: A `System.arraycopy`-equivalent into the off-heap `DirectByteBuffer` backing the ring. The `UnsafeBuffer.putBytes` operation is a JVM intrinsic (single `memcpy` instruction on x86 with AVX2 for 64-byte messages — exactly one 512-bit register move).
3. **Commit**: A single ordered store (MFENCE-free on x86 TSO) makes the message visible to the consumer.
4. **Consume**: Consumer calls `ringBuffer.read(handler, 10)`. This does a volatile read of the producer position, then dispatches messages up to the limit without any locking. The `MessageHandler` receives a `DirectBuffer` view (not a copy) into the ring slot.

**Cache-line discipline**: Producer position and consumer position are on separate 64-byte-padded cache lines (enforced by `RingBufferDescriptor.TRAILER_LENGTH = 128` bytes at the end of the buffer). This means writing the producer position does NOT invalidate the consumer's cache line — the canonical false-sharing prevention pattern.

### Required JVM Flags
Standard BenchmarkBase flags only.

---

## 5. `InliningThresholdBenchmark`

### Variants
| Benchmark | Helper bytecode size | Inlining expected | JVM flag override |
|---|---|---|---|
| `smallInlineable` | 6 bytes (verified) | Yes (below MaxInlineSize=35) | None |
| `largeNotInlineable` | 46 bytes (verified) | No (above MaxInlineSize=35) | None |
| `largeForceInlined` | 46 bytes (verified) | Yes (forced via CompileCommand) | `-XX:CompileCommand=inline,...::largeCompute` |

### Expected Latency (ns/op, `Mode.AverageTime`)

| Benchmark | Expected (EPYC) | Explanation |
|---|---|---|
| `smallInlineable` | 2 – 5 ns | Helper inlined; entire computation in registers; no call overhead |
| `largeNotInlineable` | 15 – 50 ns | Method call overhead + no cross-boundary optimisation |
| `largeForceInlined` | 2 – 6 ns | Forced inline recovers to `smallInlineable` level |

**Expected ratio `largeNotInlineable / smallInlineable`: 5–10×**

This reproduces Stefan's production incident at scale: extracting the "clean" helper added 8 bytes of bytecode, crossed the 35-byte threshold, killed inlining, and degraded the hot path by 10×.

### Why (Mechanism)

**The inlining decision timeline**:
1. C2 starts compiling the `@Benchmark` method after ~10,000 invocations (default `CompileThreshold`).
2. Before emitting machine code for the call site, C2 checks the callee's bytecode size against `MaxInlineSize` (default 35 bytes).
3. If `size ≤ 35`: the callee is inlined — its bytecode is merged into the caller's compilation unit. C2 then optimises the merged body: constant folding, dead-code elimination, register allocation over the whole expression.
4. If `size > 35`: C2 emits a direct `call` instruction. The callee is compiled separately. No cross-call optimisations are possible: each call pays full register spill/restore, a new stack frame, and the return jump.

**`smallCompute` (6 bytes)** is inlined. The merged code for `a * state.b + state.a` lives entirely in XMM/GPR registers. The Blackhole consume is a simple write to a volatile field — 2–5 ns total.

**`largeCompute` (~50 bytes)** is NOT inlined at default settings. The call instruction executes ~15–30 ns overhead alone (frame setup, spill, restoration). Additionally, C2 cannot see through the call boundary to fold the identity round-trips (`s5 = s4 + a - a`, etc.), so the full computation is emitted, adding another 5–15 ns of integer arithmetic.

**`largeForceInlined`** with `-XX:CompileCommand=inline`: C2 bypasses the bytecode-size gate and inlines `largeCompute` anyway. With the merged view, C2 sees `s5 = s4 + a - a` as `s5 = s4`, folds all the identity operations away, and produces machine code identical to the `smallCompute` case.

### Verifying bytecode sizes

```bash
javap -c -classpath target/classes com.devoxx.lowlatency.jit.InliningThresholdBenchmark | grep -A 30 "smallCompute\|largeCompute"
```

### Verifying inlining at runtime

Run with `-XX:+PrintInlining` (already in `@Fork(jvmArgsAppend)`). Look for:
```
@ 4   com.devoxx.lowlatency.jit.InliningThresholdBenchmark::smallCompute (6 bytes)   inline (hot)
@ 4   com.devoxx.lowlatency.jit.InliningThresholdBenchmark::largeCompute (50 bytes)  not inlining (hot)
```

### Required JVM Flags
- Standard benchmarks: `@Fork` in class — adds `-XX:+PrintInlining`.
- `largeForceInlined` variant: overrides `@Fork` to add `-XX:CompileCommand=inline,...::largeCompute`.

---

## 6. `BranchPredictionBenchmark`

### Variants
| Benchmark | Input order | Branch pattern | Mispredictions per scan |
|---|---|---|---|
| `sumSorted` | Ascending | 1 crossover at element ~16,384 | ~1 |
| `sumUnsorted` | Random | ~50% miss rate uniformly | ~16,384 |
| `sumBranchless` | Random (same as unsorted) | No branch (arithmetic mask) | 0 |

### Expected Throughput (µs/op, `Mode.AverageTime`, 32K int array)

| Benchmark | Expected (EPYC 7282) | Expected (M2/M3) | Notes |
|---|---|---|---|
| `sumSorted` | 20 – 50 µs | 10 – 30 µs | Bandwidth-bound; ~1 misprediction total |
| `sumUnsorted` | 80 – 200 µs | 40 – 120 µs | Branch-miss-bound; ~16K mispredictions |
| `sumBranchless` | 15 – 40 µs | 8 – 25 µs | SIMD-friendly; C2 may auto-vectorise with AVX2 |

**Expected ratio `sumUnsorted / sumSorted`: 2–6×**
(Lower end on M-series Apple Silicon, whose predictors are more sophisticated; higher end on server-class EPYC with less BHT history depth.)

### Why (Mechanism)

**Branch prediction hardware**:
Modern CPUs use a multi-level branch history table (BHT) with 2-bit saturating counters per branch address. The predictor observes recent branch outcomes and pre-fetches the predicted path into the pipeline before the branch resolves.

**Sorted array — predictor wins**:
After the first few hundred elements (all `x ≤ 128`, branch NOT taken), the 2-bit counter saturates to "strongly not-taken". For elements 0–16383, every prediction is correct. At element 16384 (where values cross 128), ONE misprediction occurs; the counter quickly saturates to "strongly taken" for the remainder. Total cost: ~1 × 12 ns ≈ 12 ns of wasted cycles per full scan.

**Unsorted (random) array — predictor thrashed**:
Each new `x` is independent of the previous one. The 2-bit counter alternates between "weakly taken" and "weakly not-taken", achieving ~50% accuracy at best. For 32,768 iterations with 50% miss rate: 16,384 mispredictions × 12 ns = ~196 µs of wasted cycles — dominating the actual addition work.

**Branchless variant — predictor bypassed**:
```java
int mask = -(x > THRESHOLD ? 1 : 0);
sum += x & mask;
```
The ternary `x > THRESHOLD ? 1 : 0` compiles to a `cmov` (conditional move) instruction on x86, which is a data-dependent ALU operation with no branch prediction involved. The `-(...)` converts 0 → 0, 1 → 0xFFFFFFFF (all ones). ANDing with `x` either passes `x` through or zeroes it. Zero branch pressure, maximum predictor accuracy (vacuously 100%). C2 with `-XX:+UseSuperWord` (default) may vectorise the entire loop using AVX2 `_mm256_cmpgt_epi32` + `_mm256_and_si256`, processing 8 ints per clock cycle.

### Relevance to Low-Latency Java

In a trading system's hot path (order type dispatch, side check, risk-limit guard), if the condition `if (order.side == BUY)` is 50% random, you pay 16K × 12 ns = 200 µs of branch-miss tax per 32K orders processed. At 1M orders/sec, that's 6 ms/sec of pure CPU waste from a single conditional. **Sorting orders by side before batch processing, or rewriting as branchless arithmetic, eliminates this entirely.**

### Notes on Measurement Platform

Apple M2/M3 (dev box) tends to show a smaller `sumUnsorted / sumSorted` ratio (~2–3×) than EPYC 7282 (~3–6×) because Apple Silicon's Neural Engine and larger reorder buffer absorb some mispredictions via out-of-order speculation. The qualitative lesson is identical; pre-record EPYC results for the conference audience.

---

## Running the Benchmarks

```bash
# Build
cd benchmarks
mvn clean package -q

# Run all library benchmarks with GC profiling and percentile histogram
java -jar target/benchmarks.jar ".*libraries\\..*" \
     -prof gc \
     -rf json -rff results-libraries.json

# Run JIT benchmarks
java -jar target/benchmarks.jar ".*jit\\..*" \
     -rf json -rff results-jit.json

# Run a single benchmark with percentile output (SampleTime mode)
java -jar target/benchmarks.jar DisruptorVsArrayBlockingQueueBenchmark \
     -prof gc \
     -rf json -rff results-disruptor.json

# Verify inlining decisions
java -jar target/benchmarks.jar InliningThresholdBenchmark \
     -jvmArgs "-XX:+PrintInlining" \
     2>&1 | grep -E "smallCompute|largeCompute"
```

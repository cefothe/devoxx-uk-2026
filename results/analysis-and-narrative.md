# Analysis & Narrative — what each benchmark proves, and the story to tell

This document is the bridge between **JMH numbers** and **stage talking points**. For every benchmark in `../benchmarks/src/main/java/com/devoxx/lowlatency/`, there's a section here that explains:

- **What it measures** — the operation and the configurations being compared
- **Expected result** — the ratio range Stefan should see on the EPYC reference box
- **Why (the mechanism)** — what's happening at the JVM/CPU level that produces the gap
- **Backs slide** — which slide(s) in `slide-deck.md` reference this benchmark
- **Story to tell** — the war story or one-liner Stefan delivers when the chart appears

When the benchmark JSON output lands in `results/raw/`, cross-reference it against this document. **If the measured ratio is more than ±50% from the expected range, suspect the benchmark before suspecting the technique** (per `BENCHMARK-METHODOLOGY.md`).

Numbers cited here come from the article (`../final-article/FINAL-ARTICLE-FOR-SUBMISSION.md`) and `BENCHMARK-METHODOLOGY.md`'s production-derived ratio table. Anything pulled from elsewhere is flagged `[VERIFY]`.

---

## 1. `OnHeapVsOffHeapMessageBenchmark`

**Package:** `com.devoxx.lowlatency.memory`

**What it measures:** The end-to-end cost of processing 1,000 fixed-size market-data messages. Two variants run side by side:
- **On-heap:** `byte[]` allocated per message, wrapped in a `ByteBuffer`, fields parsed into a freshly-allocated `Order` POJO.
- **Off-heap:** A single pre-allocated `DirectByteBuffer` (or Agrona `UnsafeBuffer`) is reused. Fields are read from raw offsets straight into primitives. No allocation in the hot path.

**Expected result:** Off-heap variant **10–20× faster** (article cites 15× and "100% GC eliminated"). Allocation rate on the off-heap path is **zero in steady state**. Young-gen GC count over the measurement window: on-heap = several, off-heap = zero.

**Why (the mechanism):** Each on-heap allocation traverses the TLAB and bumps the young-gen pointer. After ~100K messages, young gen fills, GC stops the world. The off-heap version writes into pre-allocated memory the GC doesn't track at all — so the mark phase has nothing to do, the sweep phase has nothing to do, and the bookkeeping per allocation (~50 ns) disappears. On top of that, the on-heap path bounces multiple cache lines per message (object header, byte array, parsed POJO); the off-heap path stays in a single linear region.

**Backs slide:** Slide 15 (Truth #1 — the headline 15× number).

**Story to tell:** *"When I switched our market-data parser from on-heap to DirectByteBuffer, our p99 dropped from 8 milliseconds to 12 microseconds. Same logic, same fields, same parser. The 8 milliseconds was entirely the GC. We just stopped feeding it."*

---

## 2. `ObjectPoolingBenchmark`

**Package:** `com.devoxx.lowlatency.memory`

**What it measures:** Processing 100,000 simulated orders, comparing two memory strategies:
- **Allocate-per-order:** `new Order(...)` for every order in the hot path
- **Pooled:** A `RingBufferObjectPool<Order>` of size 1,024 pre-allocated at startup; orders are acquired by cursor-and-mask and reused circularly.

Output: average latency per order, p50 / p99 sample-time distributions, total GC events and pause duration over the measurement window.

**Expected result:** Pooled variant **10–25× faster** at the tail. Article numbers: p50 5.6× faster (45 µs → 8 µs), **p99 21× faster** (250 µs → 12 µs). Steady-state allocations: pool = 0, baseline ≈ 6 M / minute. GC events over a 60-second window: pool = 0, baseline ≈ 20.

**Why (the mechanism):** Allocation is cheap *per call* — about 10–20 ns. The cost isn't the allocation; it's the *consequence*. A hundred thousand allocations a second means 240 MB of garbage per minute. That triggers regular young-gen GCs. Each GC pauses the hot path for 10–50 ms. The pool pre-allocates 1,024 objects at startup and rotates through them — at 100K orders/sec, an object is reused every 10 ms, well after it stops being in flight. Zero allocation in steady state means zero garbage means zero pauses.

**Backs slide:** Slide 23 (Truth #3 — the 21× p99 number).

**Story to tell:** *"At a hundred thousand allocations per second, you're creating 240 megabytes of garbage every minute. Those 'cheap' allocations don't add up — they multiply. Pre-allocate one thousand objects at startup, reuse them forever, and your p99 drops 21× because the GC isn't waking up anymore."* Caveat (worth a sentence on stage): *"Size pools at 3-4× peak load. Markets get volatile, your order rate triples in seconds, and a too-small pool stalls the entire pipeline."*

---

## 3. `SingleThreadedShardVsContendedBenchmark`

**Package:** `com.devoxx.lowlatency.concurrency`

**What it measures:** Concurrent account-balance updates under 3-thread load (matching the EPYC's 3 physical cores). Two variants:
- **Contended:** Shared `ConcurrentHashMap<Long, Account>` plus per-account `synchronized` block on the balance update.
- **Sharded:** Each thread is the sole owner of an Agrona `Long2LongHashMap`. Routing is `accountId & shardMask`; producers route through an LMAX Disruptor SPSC ring buffer per shard. Single-threaded consumer per shard, no locks anywhere.

**Expected result:** Sharded variant **100–1000× lower latency** in the contended scenario. Article cites 800× at 4 threads. Sharded variant gives ~0.5 ns hash-map lookup + ring-buffer hand-off cost; contended variant is dominated by lock acquisition (synchronized falls through to OS-parking under contention).

**Why (the mechanism):** Multi-threaded contention isn't slow because of the lock instruction itself — it's slow because of everything around it. Cache-line ping-pong on the lock word, MESI coherence traffic across cores, kernel transitions when `synchronized` falls through to `pthread_park`. The sharded path eliminates *all* of that: each thread reads and writes its own data, in its own L1/L2 cache, with no coordination. The Disruptor hand-off is the only inter-thread synchronisation, and it's lock-free CAS on a single sequence counter — typically 5-15 ns.

**Backs slide:** Slide 19 (Truth #2 — the 100–1000× number).

**Story to tell:** *"I ran this benchmark four times because I refused to believe it. Single thread: half a nanosecond. Add a second thread with synchronized: 150 nanoseconds. Four threads: 400. That's not slower-by-a-bit — that's 800 times slower than single-threaded, and I'm using fewer of the CPU's cores effectively. The cache coherence protocol is setting the CPU on fire just so two threads can take turns updating a balance."*

---

## 4. `DisruptorVsArrayBlockingQueueBenchmark`

**Package:** `com.devoxx.lowlatency.libraries`

**What it measures:** Single-producer / single-consumer hand-off latency, comparing:
- `java.util.concurrent.ArrayBlockingQueue<Event>` — the standard library baseline
- LMAX Disruptor SPSC `RingBuffer<Event>` with `BusySpinWaitStrategy`

Items are pre-allocated event objects; the producer writes a value, the consumer reads it; latency from `offer`/`publish` to `take`/`onEvent` is measured.

**Expected result:** Disruptor **5–15× lower latency**. Disruptor SPSC publish-to-consume is typically 30–80 ns on modern x86; ArrayBlockingQueue under contention sits in the 200–800 ns range due to lock acquisition and `Iterator`-related allocation in some code paths.

**Why (the mechanism):** ArrayBlockingQueue uses a `ReentrantLock` to serialise both `offer` and `take`. Under contention, this lock falls through to OS parking, and even uncontended each call costs a CAS + memory barrier. The Disruptor coordinates via a single atomic sequence counter; the consumer's `BusySpinWaitStrategy` just reads the counter in a tight loop until the producer advances it. No locks, no parking, no allocation — the events are pre-allocated and reused.

**Backs slide:** Slide 27 (Disruptor 5–15× number).

**Story to tell:** *"`ArrayBlockingQueue` allocates a wrapper, takes a lock, signals a condition variable. Disruptor pre-allocates the events and uses a single atomic counter. Five to fifteen times lower latency, and the variance you get from busy-spinning instead of parking is much lower — the tail tightens."* On-stage caveat: *"Busy-spin burns one core's worth of CPU. That's the right trade if you're a trading system. Wrong trade if you're on battery."*

---

## 5. `ChronicleQueueBenchmark`

**Package:** `com.devoxx.lowlatency.libraries`

**What it measures:** Append-and-tail latency on a single-segment Chronicle Queue, message sizes 16, 64, 256, 512, and 1,024 bytes (matching the histogram in Chronicle Software's published numbers). Output: per-size latency distributions, percentiles 50 / 90 / 99 / 99.9 / 99.99.

**Expected result:** **Sub-microsecond median** for 16-byte messages. p99 in the low-microsecond range. Article + Chronicle Software's published chart both cite ~800 ns median for 16-byte messages and p99 around 4 µs. [VERIFY against our run on EPYC.]

**Why (the mechanism):** Chronicle Queue is a memory-mapped file. The append path is a `memcpy` into mapped memory — no kernel transition, no allocation. The tailer reads from the same mapped region in another JVM (or the same JVM, different thread). The OS pages the file in/out as needed, but in steady-state on a hot working set everything stays resident. No GC because none of the bytes touch the heap.

**Backs slide:** Slide 29 (Chronicle Queue sub-µs number).

**Story to tell:** *"We use Chronicle Queue for audit logs and replication. Sub-microsecond persistent IPC. If you're tempted to use Kafka for in-host process-to-process messaging — your local Kafka broker is in the millisecond range. Chronicle is three orders of magnitude faster, persists to a file, and survives JVM restart."*

**Operational note:** Run with `--add-opens java.base/java.lang.reflect=ALL-UNNAMED`. Output dir is `/tmp/devoxx-uk-bench-queue` and **must** be deleted in `@TearDown` — Chronicle Queue files don't auto-clean and will pollute subsequent runs.

---

## 6. `ChronicleMapVsConcurrentHashMapBenchmark`

**Package:** `com.devoxx.lowlatency.libraries`

**What it measures:** Lookup and put latency at three population sizes — 100K, 1M, 10M entries — comparing:
- `ConcurrentHashMap<Long, Account>` (heap)
- `ChronicleMap<Long, Account>` (off-heap, memory-mapped)

The headline metric is **GC pause time over the measurement window**, not raw lookup latency (the lookup numbers are similar; the GC behaviour is what matters).

**Expected result:** At 10M entries: ConcurrentHashMap incurs noticeable GC pause time (article cites 100–500 ms full GCs become regular events at this scale on a default G1 heap); Chronicle Map incurs **zero** GC time — the map is invisible to the collector. Lookup latency is comparable (tens of nanoseconds) because both are hash-table-backed; the difference is at scale.

**Why (the mechanism):** A 10M-entry `ConcurrentHashMap` holds 10M+ entry objects on heap, each with a key reference, value reference, and next-pointer. The GC mark phase has to traverse all of them. At G1's default heap region size, this puts you in mixed-GC territory regularly, and full GCs become routine. Chronicle Map keeps the entries in a memory-mapped off-heap region; the GC sees a single direct-buffer reference and stops there.

**Backs slide:** Slide 31 (Chronicle Map ~0 GC).

**Story to tell:** *"At ten million accounts, ConcurrentHashMap doesn't break — it just gives the GC a lot of work to do every cycle. Mark phase walks ten million entries. The pauses get noticeable. Chronicle Map keeps it all off-heap. The GC has nothing to mark. Same lookup performance, zero pause cost. The absence of GC is the entire optimisation."*

---

## 7. `CasVsLockBenchmark`

**Package:** `com.devoxx.lowlatency.concurrency`

**What it measures:** A single shared counter incremented under contention. Three variants:
- `synchronized { counter++; }` — standard intrinsic lock
- `AtomicLong.incrementAndGet()` — JDK CAS-based primitive
- `LongAdder.increment()` — striped CAS for high-contention scenarios

Run at 1, 2, and 3 threads (EPYC physical core count).

**Expected result:** Under contention, **CAS variants 2–10× faster** than `synchronized`. Uncontended, the difference is small. `LongAdder` wins at the highest contention because it stripes the counter across cells.

**Why (the mechanism):** `synchronized` is fast when uncontended (biased / thin lock), but under contention falls through to inflation and OS-level parking — ~1 µs to acquire if you have to wait. `AtomicLong` uses `compareAndSet` in a retry loop; under low contention this is ~5–15 ns and never enters the kernel. `LongAdder` shards updates across multiple cells (one per thread, roughly), so contention drops to near-zero, but reads have to sum the cells.

**Backs slide:** Slide 20 (mechanism slide for Truth #2 — explains why locks cost more than the lock itself).

**Story to tell:** *"`synchronized` is fine when there's no contention. When there is contention, it falls through to the kernel — `pthread_park`, full microsecond cost. `AtomicLong` stays in user space, retries the CAS, never blocks. Two to ten times faster under load. And `LongAdder`, when you don't need the value back instantly, is even better — it shards the counter so contention basically vanishes."*

---

## 8. `FalseSharingBenchmark`

**Package:** `com.devoxx.lowlatency.concurrency`

**What it measures:** Two threads writing to two adjacent `volatile long` fields, with and without `@Contended` annotation between them. Same workload, only the field padding differs.

**Expected result:** `@Contended` variant **3–8× faster** under multi-thread load. The contended-fields variant suffers cache-line ping-pong; the padded variant gives each field its own 64-byte cache line.

**Why (the mechanism):** CPU caches operate at cache-line granularity (64 bytes on x86). When two unrelated `long` fields share a cache line, every write to one field invalidates the other thread's cached copy of the *entire line* via the MESI cache coherence protocol — even though that thread doesn't care about the field that was written. Result: every write triggers a remote cache invalidation and a re-fetch on the other thread. `@Contended` (with `-XX:-RestrictContended` to enable it for non-JDK code) adds 64 bytes of padding around the annotated field so it occupies its own line.

**Backs slide:** Slide 35 (False sharing — 3–8× number).

**Story to tell:** *"Two threads. Two completely unrelated longs. They happened to be next to each other in the class layout, sharing a cache line. Every write to thread A's field invalidated thread B's view of the line. Three to eight times slower than the same code with the fields properly padded. We hunted this for two days before we found it — and once you know to look for it, you start finding it everywhere."*

**Operational note:** `@Contended` requires `-XX:-RestrictContended` and `--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED` to use from non-JDK code.

---

## 9. `ThreadAffinityBenchmark`

**Package:** `com.devoxx.lowlatency.concurrency`

**What it measures:** A tight loop doing pointer-chasing through a moderately-sized array (large enough to fit in L2 but exceed L1), comparing:
- **Unpinned:** Linux scheduler can migrate the thread between cores
- **Pinned:** OpenHFT Affinity locks the thread to a specific core (ideally `isolcpus`'d)

Output: latency distribution, especially p99 / p99.9 spikes.

**Expected result:** Pinned variant has dramatically lower **tail latency** — the average might only differ by 10–20%, but p99.9 spikes from scheduler migration can be 10–50× higher on the unpinned path.

**Why (the mechanism):** When the Linux scheduler migrates a thread to a different core, the new core's L1/L2 caches don't hold any of the thread's working set. Every memory access in the loop becomes a cache miss until the working set is re-warmed — ~80–100 ns per RAM fetch. On a tight loop that was running at 1–2 ns/op in cache, this is a 50–100× slowdown until the cache warms up. Pinning eliminates this entirely.

**Backs slide:** Slide 37 (Affinity — Linux-only caveat).

**Story to tell:** *"After we shipped sharding, we still had random p99 spikes — not GC, not contention, just unexplained 50-microsecond blips that came and went. Took me a week to figure out the Linux scheduler was migrating our threads between cores. Every migration nukes L1 and L2. Two-step fix: `isolcpus` in GRUB to keep Linux off cores 1-4, OpenHFT Affinity to pin our shards to those cores. Spikes vanished."*

**Operational note:** `@Setup` of this benchmark **must** check the OS and `Assume.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"))` — on macOS the affinity calls are no-ops and the benchmark would silently degenerate into the unpinned baseline. The methodology doc covers this.

---

## 10. `InliningThresholdBenchmark`

**Package:** `com.devoxx.lowlatency.jit`

**What it measures:** A hot-path method called in a tight loop, in two variants:
- **Under threshold:** caller is < 35 bytes of bytecode → JIT inlines the callee
- **Over threshold:** caller is > 35 bytes (8 extra bytes added via a no-op field assignment) → JIT refuses to inline

Same logic in both. Same arithmetic. Only the caller's bytecode size differs.

**Expected result:** Inlined variant **5–10× faster**. Article cites Stefan's two-day debug — the not-inlined variant ran 10× slower than the original.

**Why (the mechanism):** The JIT's default `MaxInlineSize` is 35 bytes of bytecode. When inlining happens, the callee's body is copied directly into the caller; argument passing, stack frame setup, return are all eliminated; and downstream optimisations (escape analysis, common subexpression elimination) operate on the combined code. When inlining doesn't happen, you pay full method call overhead per invocation, *and* the JIT can't see across the call to optimise further. On a tight hot-path loop, this multiplies — every iteration pays the call cost.

**Backs slide:** Slide 9 (the 35-byte trap).

**Story to tell:** *"I 'cleaned up' our order validation by extracting a helper method. Eight bytes of bytecode added to the caller. Pushed me past the 35-byte inlining threshold. Suddenly the validation that ran in 2 nanoseconds was running in 20. Ten times slower. I spent two days profiling, looking for the bottleneck. The bottleneck was my refactoring."*

**Operational note:** Side-run with `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` to confirm the inlining decision actually flipped between variants. **Do not** include `PrintInlining` in the timed run — printing distorts JIT timing. The methodology doc covers this.

---

## 11. `BranchPredictionBenchmark`

**Package:** `com.devoxx.lowlatency.jit`

**What it measures:** The classic StackOverflow benchmark, JMH-ified. Iterate over an `int[]` array of N values, conditionally summing those above a threshold. Two variants:
- **Sorted input:** the conditional branch hits a long predictable run of "false" then a long predictable run of "true"
- **Shuffled input:** same data, randomised order — every branch outcome is unpredictable

**Expected result:** Sorted variant **2–5× faster**. Same data, same conditional, same arithmetic — the only difference is the order, and with it the CPU's branch predictor's ability to prefetch the right path.

**Why (the mechanism):** Modern x86 CPUs have ~14–20-stage pipelines. When the CPU encounters a conditional branch, it speculatively executes one side based on the branch predictor's history. If the prediction is right, the pipeline stays full. If the prediction is wrong, the pipeline is flushed — typically 15–20 cycles wasted. On a sorted input, the predictor locks onto the long runs and gets nearly every prediction right. On shuffled input, the predictor is wrong roughly half the time.

**Backs slide:** Slide 36 (Branch prediction — predictable beats random).

**Story to tell:** *"Same data. Same comparisons. Same arithmetic. The only difference is the order. The CPU's branch predictor needs a pattern to lock onto. Sorted input gives it one — taken, taken, taken, then not, not, not. Shuffled input is random; the predictor is wrong half the time, the pipeline flushes, and you lose 20 cycles per misprediction. Two to five times slower for free, just by not sorting."*

---

## 12. `PrimitiveCollectionsBenchmark`

**Package:** `com.devoxx.lowlatency.memory`

**What it measures:** Hash-map put and get operations comparing:
- `HashMap<Long, Long>` (boxed JDK)
- `Long2LongHashMap` (Agrona, primitive)
- `LongLongHashMap` from Eclipse Collections (alternative primitive impl) [if present in suite]

Output: per-op latency, allocation rate, GC events.

**Expected result:** Agrona's primitive map **3–6× faster** than the boxed JDK map, with **zero allocation** on `put`/`get` (vs. one `Long` allocation per put/get on the JDK path, when the value is outside the `Long.valueOf` cache range −128..127).

**Why (the mechanism):** `HashMap<Long, Long>` boxes both the key and the value. `put(1024L, 2048L)` allocates two `Long` objects, every call. The keys and values are stored as references — eight bytes per slot pointing to a heap object, plus the object header overhead. Cache behaviour is awful: lookup chases pointers through scattered heap memory. `Long2LongHashMap` uses two parallel `long[]` arrays; lookup is a single contiguous-memory read after a hash. No allocation, dense memory layout, far better cache performance.

**Backs slide:** Slide 33 (Agrona primitive collections — 3–6× number).

**Story to tell:** *"`HashMap<Long, Long>` boxes both the key and the value. Every `put` allocates a `Long`. The cache pollutes itself with tiny eight-byte boxed objects scattered all over the heap. `Long2LongHashMap` is just two parallel `long[]` arrays. Three to six times faster, and zero boxing means zero garbage, which means zero pauses. This is one of those changes you make in an afternoon and the GC log immediately gets quieter."*

---

## 13. `EscapeAnalysisBenchmark`

**Package:** `com.devoxx.lowlatency.jit`

**What it measures:** A method that allocates a small object (e.g., an `Order` or a tuple wrapper), uses it locally, and returns a primitive derived from its fields. Two variants:
- **Object doesn't escape:** the JIT can prove the allocation is local; escape analysis kicks in; the object is replaced with stack-allocated scalars
- **Object escapes:** add a synthetic side-effect (e.g., put it into a static field on every Nth iteration) that forces real allocation

**Expected result:** Non-escaping variant runs at the speed of the underlying primitive arithmetic — typically 1–3 ns/op. Escaping variant pays full TLAB allocation cost — typically 10–30 ns/op including the GC bookkeeping. Ratio: **5–15×** depending on object size and arithmetic complexity.

**Why (the mechanism):** When the JIT can prove an object never escapes its allocation method (doesn't get stored in a field, doesn't get returned, doesn't get passed to a method that might store it), it eliminates the allocation entirely via *scalar replacement*. The `Order` object's fields become local variables. What looks like `new Order()` in source compiles to a few register operations. When the object escapes, this optimisation can't fire and you pay full allocation cost on every iteration.

**Backs slide:** Slide 8 (JIT tricks — escape analysis bullet) and the "more benchmarks pointer" Slide 38.

**Story to tell:** *"The JIT's escape analysis is genuinely magical. If you allocate an object inside a method and never let it leave, the JIT will replace the whole thing with stack-allocated primitives. What looks like `new Order()` in your source code compiles to a few register operations. Zero allocation. The catch: the moment the object escapes — gets stored in a field, gets returned, gets passed somewhere ambiguous — escape analysis can't fire. Keep your hot-path objects local."*

---

## 14. `GcImpactBenchmark`

**Package:** `com.devoxx.lowlatency.memory`

**What it measures:** Same workload (a steady allocation pressure of, say, 100 MB/s of `byte[]` of varying sizes) under three GC configurations:
- **G1GC** (default, JDK 21+): low average pause, multi-millisecond worst-case
- **ZGC** (concurrent, sub-millisecond pause target): near-zero pauses but throughput cost
- **Zero allocation** (pooled equivalent — no garbage to collect): baseline, no GC activity

Output: pause distribution, throughput per GC choice, max pause observed.

**Expected result:** G1 mean pause ≈ 5–20 ms with worst-case 50–200 ms. ZGC mean pause < 1 ms with worst-case low single-digit milliseconds. Zero-allocation has no pauses. **The point isn't "ZGC is faster than G1"** — it's "GC is unpredictable, even ZGC has a tail, and the only true zero is no allocation at all."

**Why (the mechanism):** Every GC has a pause distribution. G1's regions and mixed collections give it a long tail. ZGC's concurrent design narrows the tail dramatically but doesn't eliminate it — there are still safepoint synchronisation costs and barrier overhead. Zero-allocation eliminates the GC from the equation entirely. The talk's argument is that for sub-10-µs latency targets, even ZGC's 1 ms tail is 100× the budget — only zero-allocation gets you there.

**Backs slide:** Slide 11 (GC pause shape).

**Story to tell:** *"You can't tune your way out of GC pauses. ZGC narrows the tail dramatically — sub-millisecond average pause, sometimes a low-millisecond worst case. That's a thousand times better than G1. It's also a hundred times your latency budget if you're targeting ten microseconds. The only way to get to zero is to stop allocating."*

---

## 15. `AgronaRingBufferBenchmark`

**Package:** `com.devoxx.lowlatency.libraries`

**What it measures:** SPSC hand-off latency through Agrona's `OneToOneRingBuffer`, compared against `ArrayBlockingQueue` and (optionally) the Disruptor SPSC variant for context. Same producer/consumer pattern as the Disruptor benchmark, different ring buffer implementation.

**Expected result:** Agrona ring buffer **3–8× lower latency** than `ArrayBlockingQueue`. Comparable to Disruptor SPSC; slightly different trade-offs (Agrona's ring buffer is simpler, no consumer dependency graph, lower per-message overhead for the simplest case).

**Why (the mechanism):** Same lock-free, pre-allocated mechanism as the Disruptor. Producer writes a message + length + claim flag; consumer reads when claim flag flips. No locks, no allocation, single CAS per publish. The key difference vs. Disruptor: Agrona's ring buffer carries raw bytes (the message is encoded inline as a `DirectBuffer` slice), so there's no per-message object — even cheaper than Disruptor for the SPSC bytes-only case.

**Backs slide:** Slide 33 (Agrona — the second number, ring buffer 3–8×).

**Story to tell:** *"Agrona has a stripped-down ring buffer that's even cheaper than the Disruptor for the simple single-producer/single-consumer case. No event objects, just bytes in a direct buffer. If you're doing inter-thread messaging where the message fits in 64–256 bytes and you don't need the Disruptor's consumer dependency graph — this is the lighter option."*

---

## Cross-references

If you want to see the deck claims in one place, search `slide-deck.md` for `Backed by` — every benchmark in this list appears at least once. The mapping is also summarised in `../slides/README.md` under the chart-naming table.

If a benchmark's numbers come back outside the expected range:

1. **Re-read `../benchmarks/BENCHMARK-METHODOLOGY.md` § Caveats** — most surprises are warm-up or thread-count related.
2. **Run the benchmark in isolation** with `@Fork(2) @Warmup(5)` minimum.
3. **Check the JFR / async-profiler output** to confirm what you think is happening is what's actually happening (escape analysis fired, inlining happened, GC ran, etc.).
4. **If the ratio is still off by an order of magnitude** — the benchmark, not the technique, is wrong. Don't quote that number on stage. File it as a bug, fix it, re-run.

The talk's credibility rests on every quoted number being reproducible from this repo. If a number can't be reproduced, replace it on the slide with a `[VERIFY]` marker until it can.

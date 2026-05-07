# Talk Outline — 50 Minutes

**Title:** Achieving Microsecond Latencies with Java
**Slot length:** 50 minutes total
**Q&A model assumed:** Q&A is in-session (Devoxx UK's typical pattern). Therefore technical content ends at **0:45** with a 5-minute Q&A window from 0:45 to 0:50. If the conference confirms a separate Q&A slot, the synthesis section can be expanded to 0:50 and Q&A moved out.

**Demo strategy:** No live coding. No live benchmarks. All numbers are pre-recorded from the companion JMH suite, captured before the talk, and shown as charts. Reason: NUMA, JIT warm-up, and CPU affinity are too fragile to demo on stage.

**Slide naming convention:** `[block]-[seq]` e.g. `hook-01`, `jit-03`. Slides teammate is producing the deck in parallel; this outline references *concepts* not slide files.

---

## 0:00 – 0:05  The Hook (5 min)

**Beat:** "Java for HFT? You need C++." Anchor that myth, then demolish it with one number.

- Open cold. No "good morning Devoxx." Walk in, deliver: *"Years back I joined a crypto trading company. First week, someone in a meeting casually mentions we need to process north of 10 gigabytes per second of market data. I'm nodding along like this is normal. It's not normal."*
- Then the punchline: 5 million ops/sec, 0.5µs median, on a 2014 Intel Xeon. p99 = 42µs.
- Frame the talk: *"This talk is going to flip three things you think are good Java practice. By the end you'll know why each one fails at microsecond scale, and what to do instead."*

**Slide:** Production system numbers + `exchange-core` reference.
**Visual:** Latency histogram showing the 0.5µs median spike with the long but bounded p99 tail.
**Audience reaction we're hunting:** Lean-in. The room thinking *"that can't be right"*.

---

## 0:05 – 0:12  How the JIT Actually Works (7 min)

**Beat:** Establish the foundation. Without JIT understanding, nothing later makes sense.

- Most developers think Java is interpreted bytecode. Maybe in 1995. Not anymore.
- The C2 path: Interpreter (0–1,500 invocations) → C1 (1,500–10,000) → C2 (10,000+). In production hot paths, this happens in milliseconds.
- **Method inlining as the king optimisation** — show the four-method `validateOrder` example, then what C2 actually executes (everything inlined, direct field access). 15-20ns → 2-3ns. 7-10× speedup.
- **The 35-byte trap.** Tell the war story: *"I once spent two days debugging why our order validation suddenly got 10× slower after I extracted a helper method. Crossed the MaxInlineSize threshold. Reverted the refactoring."*
- **Escape analysis** — show the `Order temp = new Order()` example, then what runs (scalars on stack, zero allocation).
- **Warm-up requirement** — cold start = 200µs p50, with warm-up = 0.8µs. Same code. 250× difference. *"I'd been optimising interpreter performance. Don't make that mistake."*

**Slide:** JIT optimisation tier diagram. Side-by-side bytecode-before / inlined-after.
**Visual:** The before/after benchmark chart from the companion repo for the inlining and escape-analysis cases.
**Transition:** *"OK. The JIT is on your side, but it can only optimise what you give it. Now let's talk about what you should be giving it. Starting with where you put your data."*

---

## 0:12 – 0:20  The Memory Wall: Heap vs Off-Heap (8 min)

**Beat:** Why on-heap is a death sentence for microsecond systems.

- The garbage collector as helicopter parent metaphor. *"It wants to know where every byte is at all times. Very helpful for a CRUD app. Devastating when you need consistent microsecond latency."*
- The real cost: at 100k messages/sec, traditional on-heap allocates 3 objects/message → 12 MB/sec of garbage → young gen fills in 10-15 sec → 10-50ms GC pause.
- *"In a system with a 10µs latency budget, a 10ms GC pause is 1,000× your entire budget."*
- This is the problem statement. The next three sections are each a different way of breaking up with the GC.

**Slide:** The "GC pause as a fraction of latency budget" chart — visual that makes the 1,000× ratio undeniable.
**Visual:** GC log excerpt from a real production incident showing the 50ms pause spike.
**Transition:** *"There are three ways out. Each one is a thing your senior dev told you not to do. Each one is essential. We'll do them in order."*

---

## 0:20 – 0:28  Truth #1 — Off-Heap Beats On-Heap (8 min)

**Beat:** First counter-intuitive truth. Pay the manual-memory tax, get back deterministic latency.

- The on-heap version of `processMarketData`: three allocations per message. Explain each one.
- The Agrona `UnsafeBuffer` rewrite: zero allocations, zero copies, primitives read straight from the network buffer.
- **Show the numbers table:** 150-200ns → 10-20ns per message. 12 MB/sec garbage → 0. GC pauses every 10-15s → never. *"The real win isn't the average. It's that your p99 stops moving when Bitcoin gets volatile."*
- The trap: *"Mix on-heap and off-heap in the same hot path and you get the worst of both worlds."* Pick one. Commit.

**Slide:** Side-by-side code (heap vs off-heap), then the comparison table.
**Visual:** The 10x latency improvement bar chart from the companion repo's `OffHeapBenchmark`.
**Audience reaction we're hunting:** Nodding from the people who already moved I/O off-heap; mild discomfort from the people who wrote `ByteBuffer.allocate()` last week.

---

## 0:28 – 0:35  Truth #2 — Single-Threaded Shards Beat Multi-Threading (7 min)

**Beat:** The most counter-intuitive truth. The room will resist this. Win them with the benchmark.

- Set up the conventional wisdom: more threads = more performance. Use ConcurrentHashMap, synchronize where needed.
- The story: *"I ran the JMH benchmark. One thread: 0.5ns. Added a second thread with locks: 150ns — 300× slower. Four threads: 400ns — 800× slower. Eight threads: 2,400× slower. I was convinced the benchmark was broken."*
- The benchmark wasn't broken. Lock contention was real. Cache coherence traffic, false sharing, context switches — they don't add up linearly. They multiply.
- The fix: shard by user ID. `userId & shardMask`. Each thread owns its slice exclusively. Zero locks. Zero coordination.
- Then the second-order fix: thread affinity. *"Even with perfect sharding we had random 50µs spikes. Took me a week. The Linux scheduler was migrating threads. `isolcpus=1-4` plus OpenHFT's affinity library. Spikes vanished."*
- **The numbers:** 0.5ns single-threaded → 0.5ns sharded (still!). 4 cores = exactly 4× throughput. CPU utilisation jumps from 40-60% (waiting on locks) to 90-95% (actually working).

**Slide:** The "lock contention multiplier" chart showing 1 → 2 → 4 → 8 thread degradation.
**Visual:** Architecture diagram of 4 shards on 4 isolated cores, no shared state.
**Pivot moment:** This is where the audience that came for "fast Java tips" realises the talk is actually about rethinking concurrency from scratch. Slow down here. Let it land.

---

## 0:35 – 0:40  Truth #3 — Object Pooling Beats Allocation (5 min)

**Beat:** The third and final truth. Shorter, because by now the audience knows the pattern.

- *"Allocation is cheap in Java" — that's what they teach you. And it's true: ~10-20ns per object. Basically free, right? Wrong. At 100k/sec you're creating 240 MB/minute of garbage."*
- The pattern: ring-buffer object pool. Power-of-two size for bitmask modulo. Pre-allocate at startup. Reuse circularly. No release method needed.
- **The numbers:** 6,000,000 allocations in 60s → 1,024 allocations at startup. p50: 45µs → 8µs. p99: 250µs → 12µs. 21× faster on the tail.
- The two real-world traps: (a) size your pools at 3-4× peak, not average — when Bitcoin moves 10% your volume triples; (b) startup time goes from 2s to 20s. Live with it.

**Slide:** Ring-buffer object pool code (compact). Allocation count comparison chart.
**Visual:** p99 latency timeline showing the saw-tooth GC pattern disappearing after pooling is enabled.

---

## 0:40 – 0:46  The Libraries That Embody The Truths (6 min)

**Beat:** Show the audience the off-the-shelf tools so they don't think they have to build this from scratch.

- Not a vendor pitch. These libraries exist because the patterns are real.
- **LMAX Disruptor.** The off-heap, lock-free ring buffer that gives you single-producer/multi-consumer pipelines without locks. The "all three truths in one library" library. Show a 10-line example of a producer-consumer pipeline.
- **Chronicle (Queue / Map).** Persistent off-heap queues and maps. Inter-process communication at memory speeds. Where you reach when you need durability without giving up off-heap.
- **Agrona.** The primitive collections (`Long2ObjectHashMap`), the `UnsafeBuffer`, the `IdleStrategy` — the toolkit that fills in the gaps the JDK leaves for low-latency work.
- **OpenHFT Java-Thread-Affinity.** Pinning threads to isolated cores. The library you only know about after you've felt the pain.

**Slide:** Library logos / one-line description / link. Quick decision matrix: "if you need X, reach for Y."
**Visual:** Architecture diagram of `exchange-core` showing where each library lives.
**Honest aside:** *"None of these are new. They've been quietly running production systems for years. The fact that they're not in your stack is, frankly, a marketing problem."*

---

## 0:46 – 0:50  Synthesis, Takeaways, Q&A pointer (4 min)

**Beat:** Close with the principle, the proof, and the call.

- **The principle:** *"Eliminate, don't optimise. Zero allocations means zero garbage means zero GC pauses. Zero locks means zero contention. Zero copies means zero overhead. When you're chasing microseconds, 'fast enough' doesn't exist. You need zero."*
- **The proof:** `exchange-core`. 5M ops/sec. 0.5µs median. Open source. *"Clone it on the train home. Every technique on these slides is in there."*
- **The call:** Don't apply all three at once. Measure first. Find your biggest bottleneck — GC pressure → pooling + off-heap; lock contention → sharding. Test at 3× peak. And warm up. *"If you take one thing from this talk: warm up your hot paths for 60 seconds before going live, or you're optimising interpreter performance."*
- **When this doesn't apply:** CRUD apps, REST APIs at 50 req/s, anything where the latency budget is in milliseconds. *"These techniques add complexity. Only pay that tax when microseconds actually matter."*

**Slide:** Single-slide takeaway with the five Monday-morning actions and the `exchange-core` URL.
**Q&A signal:** Hands up.

---

## Time Allocation Justification

Total content: 46 minutes. Q&A: 4 minutes. Total: 50 minutes.

Why this allocation:

- **5 min hook** — Devoxx audiences are strong but bored by slow openers. The "broke my brain" anecdote earns trust in 60 seconds, then we use the remaining 4 min to anchor the production numbers and frame the three truths.
- **7 min on JIT** — load-bearing. Without it, the audience can't reason about why inlining and escape analysis matter for the off-heap and pooling sections.
- **8 min on the memory-wall problem statement** — sets up all three truths simultaneously. Worth the time investment.
- **8 / 7 / 5 min on the three truths** — front-loaded toward off-heap because it's the most concrete and easiest to demo with numbers. Sharding gets 7 because the room will fight it. Pooling gets 5 because by then the audience has the pattern.
- **6 min on libraries** — Disruptor + Chronicle + Agrona + Java-Thread-Affinity. Quick. The audience needs to know these exist.
- **4 min synthesis + Q&A pointer.** Tight. Forces a clean ending.

If Devoxx confirms Q&A is *separate* from the 50-min slot, expand the synthesis to 5 min and end the technical content at 0:50.

---

## Risk Register (for the speaker, not the CFP)

- **JIT section runs long.** Cut the escape analysis explanation; lean on the slide. Save 90 seconds.
- **Sharding section gets pushback in Q&A about durability / cross-shard transactions.** Have a one-slide backup ready: "yes, exchange-core handles this with order routing — happy to dive into Q&A."
- **Audience asks about Loom / virtual threads.** Have a 30-second answer rehearsed: virtual threads solve a different problem (millions of concurrent I/O-bound tasks); they do not eliminate lock contention or GC pressure in the hot path.
- **Demo failure scenario:** there is no live demo. There is no demo failure scenario. This is by design.

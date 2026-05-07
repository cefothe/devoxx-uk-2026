# Speaker Prep — Achieving Microsecond Latencies with Java

**Devoxx UK 2026 · Stefan Angelov · 50-min slot — full content, no formal Q&A**

---

## Pre-talk checklist (T-30 min)

- [ ] Laptop charged, charger packed
- [ ] Slides loaded in Marp/PDF — both copies
- [ ] Backup PDF on USB
- [ ] Water bottle filled
- [ ] Repo URL up on a separate tab in case of demo
- [ ] Phone on Do Not Disturb
- [ ] Walk the room — find the front row, the back row, the corners you'll look at
- [ ] One deep breath before going on

**The one rule:** the numbers are the star. Don't apologise. Don't qualify. State them.

**No formal Q&A** — but expect people at the lectern after. Have the resources slide back up before you walk off.

---

## Pacing reference (56 slides, 50 min)

| Time   | Slide  | Section |
|--------|--------|---------|
| 0:00   | 1      | Hook |
| 0:30   | 2      | The numbers |
| 1:00   | 3–4    | Calibration + domains |
| 1:30   | 5–7    | Who am I + mental shift |
| 2:30   | 8      | **Part 1 — Platform** |
| 3:00   | 9–11   | JIT tiers, pipeline, C2 |
| 4:30   | 12–15  | Logger, 35-byte trap, **PrintInlining**, **Escape analysis** |
| 8:30   | 16–19  | Memory wall, memory model, GC pauses, **GC pause shapes** |
| 12:00  | 20     | **Three Truths intro** |
| 12:30  | 21–25  | Truth #1 — Off-heap |
| 17:00  | 26–30  | Truth #2 — Single-threaded |
| 22:00  | 31–35  | Truth #3 — Pool (incl. ring-buffer animation) |
| 27:00  | 36     | **Part 2 — Libraries** |
| 27:30  | 37–44  | Disruptor / Chronicle Queue / Map / Agrona |
| 35:30  | 45     | **Part 3 — Mechanical sympathy** |
| 36:00  | 46–49  | False sharing, branch prediction, affinity, more |
| 41:30  | 50     | **Part 4 — Putting it together** |
| 42:00  | 51     | Architecture climax (slow!) |
| 44:30  | 52–53  | Why all three + exchange-core |
| 46:30  | 54     | Monday morning checklist |
| 48:30  | 55–56  | Resources + close |
| 50:00  | —      | Walk off |

If you're 2 min behind by minute 25, drop slide 35 (pool's evil twins) and slide 49 (more things).
If you're 2 min ahead by minute 25, slow down at slide 51 (architecture climax) and slide 54 (Monday morning).

---

## Opening discipline

- **Do not** introduce yourself first.
- **Do not** read the agenda.
- Open with the question. Pause. Wait for hands.
- Then drop the numbers cold on slide 2.

---

## Slide 1 — Title (0:00)

**Goal:** Set the room. No introduction yet.

**Say:** *"Raise your hand if you've heard someone say Java can't do HFT."*

**Beat:** Wait. Don't fill silence. Let the hands go up.

**Then:** *"I've spent the last decade proving that wrong. In production. Across fintech and gaming, now leading engineering at Tradu. Five of those years on the matching-engine hot path. Real money on the line. Today I'll show you how."*

**Bridge:** Click straight to slide 2. No "agenda."

---

## Slide 2 — The Hook (0:30)

**Goal:** Numbers land cold. Audience checks their assumptions.

**Say (one beat per number):**
1. *"Five million operations per second."* — pause
2. *"Half a microsecond median."* — pause longer
3. *"Forty-two microseconds at p99."* — pause
4. *"On a CPU from 2014. Probably costs less than my laptop."*

**Then:** *"This isn't a benchmark toy. This is exchange-core, open source on GitHub. Three million users, ten million accounts, 100,000 trading symbols. Production code."*

**Bridge:** *"Before we go any further, let's calibrate what these numbers mean."*

---

## Slide 3 — How small is a microsecond? (1:00)

**Goal:** Make the unit feel real. 30 seconds max.

**Say:** *"In half a microsecond, light travels about 150 metres. In one nanosecond, light moves 30 centimetres — roughly the length of your laptop. Every number on this deck lives in that range."*

**Bridge:** *"Half the room is now thinking 'cool, but who actually needs this?'"*

---

## Slide 4 — Who needs this latency? (1:15)

**Goal:** Earn the half of the room that isn't in finance.

**Say:** *"This talk is framed around trading because that's where I work. But the techniques aren't trading-specific. Game server. Ad auction. Robotics control loop. Same playbook. The latency budget is just a different number."*

Pick the **two** domains that match the room. Don't list all five.

---

## Slide 5 — Who am I (1:30)

**Goal:** Credibility, fast. Under 45 seconds.

**Say:** *"Stefan Angelov, engineering manager at Tradu, software architect by trade. Ten-plus years on distributed systems in fintech and gaming. Co-founded a kids' coding academy called Hacker4e. Plovdiv, Bulgaria."*

**Then:** *"The reason to listen: every number on these slides is from a production system or a JMH benchmark in the open repo. Both runnable. Nothing is vibes."*

---

## Slide 6 — What this talk is (and isn't) (2:00)

**Goal:** Set the contract.

**Say:** *"Three promises. Mechanism over recipe. Every claim runnable. Production-validated."*

**Then (definite):** *"Not on the menu — Loom, Valhalla, GraalVM native-image, microbenchmark gotchas. Other talks cover those. This one is about the hot path."*

---

## Slide 7 — The mental shift (2:15)

**Goal:** Philosophical anchor. Hold for a beat on each row.

**Say (story):** *"Years back I'm in a meeting and someone says we need to process ten gigabytes per second of market data. I'm nodding like that's normal. It's not normal. That conversation broke my brain."*

**Walk the table:** *"Immutable objects? We pool. DI? Direct calls. Clean abstractions? The hot path is one method. ConcurrentHashMap? One thread owns it. GC is fine? GC is the enemy."*

**Pivot:** *"Everything you learned in your enterprise job is correct, for that domain. We're going somewhere else now."*

---

## Slide 8 — Part 1: The platform (2:30)

**Goal:** Section break. Fast.

**Say:** *"Every technique in this talk exists to give the JIT compiler what it wants. So we start with what the JVM is actually doing under the hood."*

---

## Slide 9 — JIT 101, three tiers (3:00)

**Goal:** Most Java devs think the JVM is interpreted. That was true in 1995.

**Say:** *"Three tiers. Interpreter for the first ~1500 calls — slow, but profiling. C1: fast, light compile. C2 around 10K calls: aggressive, profile-driven native code."*

**War story:** *"I once benchmarked a cold start, got 200 microseconds p50, panicked, profiled for two days. Then I added a warm-up phase. 0.8 microseconds. I'd been benchmarking the interpreter."*

---

## Slide 10 — JIT compilation pipeline (diagram) (3:30)

**Goal:** Walk left to right, then the loop-back.

**Say:** *"Bytecode → interpreter, profiling. C1 generates code quickly. C2 generates highly optimised code. Both into the code cache."*

**Then slower:** *"Here's the part most engineers don't know — deoptimisation. If the profile turns out wrong, the JIT throws away compiled code and drops you back to the interpreter. That's where your latency cliffs come from."*

**Bridge:** *"Everything from here is about feeding the JIT stable, predictable, inlinable code."*

---

## Slide 11 — What C2 actually does (4:00)

**Goal:** Inlining is the headline.

**Say:** *"When the JIT inlines, it copies the method body straight into the caller. No stack frame, no argument passing. Three method calls become three lines of arithmetic. That's where the seven-to-ten-x comes from."*

**Bridge (foreshadow):** *"But the JIT is fickle. Real example..."*

---

## Slide 12 — The cost of doing nothing (logger) (4:15)

**Goal:** 30 seconds. Some of the audience just wrote this code last week.

**Say:** *"Top version: ten million strings allocated for log messages NOBODY READS because FINE is off. Bottom version: one if-statement, costs nothing in the common path. Same intent, different cost."*

**Bridge:** *"Small example. Now a much bigger one — and this time it's the JIT that bites you."*

---

## Slide 13 — The 35-byte trap (4:30)

**Goal:** One of the core landing lines. Deliver slow.

**Say:** *"Two days. Two days I spent profiling. The bottleneck was that I'd 'cleaned up' the code by extracting a helper method. Eight bytes pushed me past the default thirty-five-byte threshold. The JIT silently stopped inlining. My two-nanosecond hot path became twenty."*

**Pause. Then:** *"I reverted the refactoring."*

**Wait for the laugh.**

**Bridge:** *"How did I find this? With the diagnostic the JIT gives you for free."*

---

## Slide 14 — `-XX:+PrintInlining` (5:00) [NEW]

**Goal:** Stop guessing. Let the JIT tell you what it did.

**Say:** *"After the 35-byte incident I never refactor hot-path code without this flag. Add `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` to your benchmark JVM options. Output tells you exactly what happened to every call site."*

**Walk three lines:**
- *"`inline (hot)` — JIT did the work, you're flying."*
- *"`too big` — bytecode over MaxInlineSize, 35 by default, 325 for hot methods."*
- *"`callee is too large` — same trap, viewed from the caller."*

**Bridge:** *"Once a method DOES inline, something else interesting happens — the JIT can sometimes delete the allocation entirely."*

---

## Slide 15 — Escape analysis (5:45) [NEW]

**Goal:** Explain when "the JIT will optimise away your allocation" is actually true.

**Say:** *"Decision tree: if this object escapes the method — returned, stored in a field, passed to something that doesn't inline — heap allocation. Normal cost. But if it's purely local and the method inlined, scalar replacement kicks in. Fields go directly into CPU registers. Object never exists. Zero allocation."*

**Then (link back):** *"This is why the 35-byte trap hurts so much. Break inlining and you don't just lose method-call optimisation — you lose escape analysis on every object inside the method. Both invariants live or die together."*

**Bridge:** *"The JIT is one half of the story. The other half is memory layout."*

---

## Slide 16 — The memory wall (7:15)

**Goal:** Set up everything that follows.

**Say:** *"If you remember nothing else: a cache miss to RAM costs you a hundred nanoseconds. Your latency budget might be ten microseconds — that's a hundred cache misses, total, end-to-end. Spend them wisely."*

---

## Slide 17 — Java's memory model (heap/non-heap) (8:00)

**Goal:** Conceptual setup for off-heap.

**Say:** *"Every Java object lands in Eden. Eden fills, young GC fires, survivors move to Survivor. Survive enough, you're tenured. Old Gen fills, full GC fires. THAT is your fifty-millisecond pause."*

**Point right:** *"Look at the Non-Heap. Metadata, Threads, Code Cache, GC. The garbage collector doesn't touch any of that. It can't. So if we move our hot-path data into the bottom half — into off-heap buffers — the GC literally cannot pause us to clean it up."*

---

## Slide 18 — Why GC kills latency (9:00)

**Goal:** Latency engineers don't talk in averages.

**Say:** *"Average GC pause might be two milliseconds. The worst pause in any given hour might be two hundred. That ONE pause is the moment Bitcoin moves five percent. That ONE pause is when your stop-loss should have fired."*

**Bridge:** *"Now — modern collectors. Don't they solve this?"*

---

## Slide 19 — GC pause shapes (9:30) [NEW]

**Goal:** Address "just use ZGC" head-on.

**Say:** *"G1 is the default — multi-millisecond pauses, occasionally a hundred. ZGC is the modern answer for large heaps — sub-millisecond, even on terabyte heaps, costs ten to fifteen percent throughput. Generational ZGC in JDK 21 gets you the same pause shape with much better throughput. If you're on JDK 21 you should be running it."*

**Then (the lesson):** *"But here's the thing — even ZGC's one-millisecond pause is a thousand times your budget on a microsecond hot path. Modern GCs make latency-sensitive web services tolerable. They don't make a matching engine free. **You still have to NOT ALLOCATE.**"*

**Bridge:** *"Which is exactly the next slide."*

---

## Slide 20 — Three counter-intuitive truths (12:00)

**Goal:** The dramatic pivot.

**Say:** *"Everything I'm about to tell you contradicts what you were taught in your first Java job. Everything. And yet, every one of these techniques is in production at exchange-core, doing five million ops a second."*

**Brief pause.** Click to Truth #1.

---

## Slide 21 — Truth #1: Off-heap beats on-heap (12:30)

**Goal:** Land the helicopter parent metaphor.

**Say:** *"The GC wants to know where every byte is at all times. Tracks every object. Helpful for a web app. Devastating when you need consistent microsecond latency."*

**Then:** *"If we move our memory off-heap — DirectByteBuffer, Unsafe, Chronicle Bytes — the GC can't see it. It can't pause us. That memory is **emancipated**."*

**Wait for the laugh on "emancipated."**

---

## Slide 22 — Off-heap mechanism (code) (13:30)

**Goal:** Walk the code. Don't read line by line.

**Say:** *"One ByteBuffer, allocated once at startup. Every message after that is a primitive read at an offset. No `new`. No object header. No GC bookkeeping."*

---

## Slide 23 — Why off-heap wins (13:45)

**Goal:** One sentence per bullet.

**Say:** *"No allocation — JIT doesn't even emit `new`. No GC tracking — mark phase doesn't traverse this memory. No object headers — sixteen bytes per object, gone. No copy — bytes the kernel delivered ARE the object."*

---

## Slide 24 — Off-heap vs on-heap, the number (14:30)

**Goal:** "15x" goes up huge. The tail is the story.

**Say:** *"Fifteen times faster on average. But that's not the win. The win is the tail. The on-heap version, when GC fires, is fifty milliseconds. The off-heap version is twenty nanoseconds, ALWAYS. Two and a half MILLION times better in the worst case. That's not a number you optimise toward. That's a number you get by elimination."*

---

## Slide 25 — When you'd actually do this (15:30)

**Goal:** Be the senior engineer, not the evangelist.

**Say:** *"REST API at 50 requests a second? Ignore everything I just said. You'll spend a month chasing native memory leaks for no reason. These techniques cost real complexity. Worth it only when microseconds actually matter. And the moment you commit, COMMIT — half off-heap is worse than fully on-heap."*

---

## Slide 26 — Truth #2: Single-threaded beats multi-threaded (17:00)

**Goal:** Most counter-intuitive in the deck.

**Say:** *"I ran the JMH benchmark four times because I refused to believe it. Single-threaded: half a nanosecond. Two threads with synchronized: a hundred and fifty. Four threads: four hundred. Eight hundred times slower than single-threaded, just by adding threads."*

---

## Slide 27 — Shard-per-thread pattern (code) (17:45)

**Goal:** Walk the routing logic.

**Say:** *"User one hundred → shard zero. Shard zero owns user one hundred. Forever. Nobody else touches it. No locks needed because there's no contention. Sounds primitive. It IS primitive. It's also how exchange-core does five million ops a second."*

---

## Slide 28 — Why sharding wins (18:30)

**Goal:** Three bullets, one sentence each.

**Say:** *"One thread per user. Linux scheduler can't get cute. The L1 cache that has user 100 is the cache of the core processing user 100. No synchronized — nothing to synchronise. No CHM — no concurrent access. No CAS — no race. We didn't optimise the lock. **We deleted it.**"*

---

## Slide 29 — Sharded vs contended, the number (19:00)

**Goal:** Land the range — 100 to 1000x.

**Say:** *"On three cores, three threads contending, between a hundred and a thousand times slower for the locked version. Variance comes from cache coherence — sometimes it cooperates, sometimes it sets the cache on fire. The sharded version doesn't have that variance. It just runs."*

---

## Slide 30 — Why locks are this expensive (19:45)

**Goal:** The mechanism slide. Spend a minute.

**Say:** *"Adding `synchronized` doesn't just slow you by the lock cost. It activates a CPU-level cascade. Cache lines bouncing. Kernel parking. MESI protocol firing. None of that on the single-threaded path. **Sharding doesn't avoid the lock. Sharding avoids the cascade.**"*

---

## Slide 31 — Truth #3: Pool beats allocate (22:00)

**Goal:** The one most engineers will be uncomfortable with.

**Say:** *"At a hundred thousand allocations a second, you're creating two hundred and forty megabytes of garbage per minute. That triggers young-gen GCs. Those pause your hot path. Premature optimisation, my left foot — this is just **mature**."*

**Wait for the laugh.**

---

## Slide 32 — What's a ring buffer? (animation) (22:30)

**Goal:** 30 seconds. The animation does the work.

**Say:** *"Simplest possible structure for fixed-throughput producer-consumer. One array. One write cursor. When the cursor reaches the end, it wraps around. No allocation. No copying. No GC."*

**Bridge:** *"The pool I'm about to show is exactly this — but rotating through pre-allocated objects."*

---

## Slide 33 — Ring-buffer object pool (code) (23:00)

**Goal:** Three things to call out.

**Say:** *"Power-of-two size, bitwise AND for fast modulo. Pre-allocate everything in the constructor. No release method — cursor moves forward, wraps, overwrites. If sized big enough, the object you wrote N cycles ago is no longer in flight."*

**War story:** *"We sized for average. First volatile market, ran out of objects, system stalled. Now we size for three to four times peak. Costs RAM. Saves your career."*

---

## Slide 34 — Pooling vs allocation, the number (24:00)

**Goal:** 21x is the headline.

**Say:** *"Five-and-a-half times faster on average. Twenty-one times faster in the tail. Zero GC pauses in a sixty-second window. The allocate version: twenty pauses in the same window."*

---

## Slide 35 — The pool's evil twins (24:45)

**Goal:** Quick fly-by. Don't dwell.

**Say:** *"Pooling is the heavyweight. Canonical objects, lazy init, thread-locals, flyweight — lightweight cousins. Sometimes a static `Boolean.TRUE` reference is the whole optimisation."*

---

## Slide 36 — Part 2: The libraries (27:00)

**Goal:** Section break.

**Say:** *"Off-heap, sharded, pooled — those are the techniques. You don't write them from scratch. Four libraries. Disruptor, Chronicle Queue, Chronicle Map, Agrona."*

---

## Slide 37 — LMAX Disruptor (27:30)

**Goal:** Even if they never use it, they need to know its shape.

**Say:** *"BlockingQueue allocates a wrapper per item, locks per operation, unpredictable latency. Disruptor preallocates events, single atomic sequence counter, sub-microsecond hand-off. Don't go deep on sequence barriers — the docs cover it."*

---

## Slide 38 — Disruptor vs ABQ, the number (28:30)

**Say:** *"Five to fifteen times lower latency. Exact number depends on wait strategy. Busy-spin wins on latency, loses on power."*

---

## Slide 39 — Chronicle Queue (29:30)

**Goal:** Audience moment of "wait, you can do that?"

**Say:** *"Memory-mapped file. Kernel maps the same file into both processes' address spaces. Writing is a memcpy into mapped memory. Sub-microsecond IPC. Persistent — because it's a file."*

---

## Slide 40 — Chronicle Queue, the number (30:30)

**Say:** *"Sub-microsecond inter-process messaging. Persistent. On disk. Local Kafka broker is in the millisecond range. Different kind of system."*

---

## Slide 41 — Chronicle Map (31:30)

**Say:** *"At ten million entries, ConcurrentHashMap has the GC working overtime. The mark phase traverses everything. Old gen fills. Ten-millisecond pauses just from existing. Chronicle Map keeps the entire map off-heap. GC never sees it. Ten million entries, zero pause cost."*

---

## Slide 42 — Chronicle Map vs CHM, the number (32:30)

**Say:** *"Same lookup latency. The difference is the **absence** of GC. At ten million entries, that absence IS the optimisation."*

---

## Slide 43 — Agrona (33:30)

**Goal:** The library nobody talks about. Make them remember.

**Say:** *"This is the library that everyone in low-latency Java uses and nobody mentions at conferences. Primitive collections, off-heap buffers, ring buffers, idle strategies. **If you remember one library name, remember Agrona.**"*

---

## Slide 44 — Agrona, the numbers (34:30)

**Say:** *"`HashMap<Long, Long>` boxes every key and value. Each `put` allocates a `Long`. Cache pollutes itself. `Long2LongHashMap` is two parallel `long[]` arrays. Three to six times faster, zero boxing."*

---

## Slide 45 — Part 3: Mechanical sympathy (35:30)

**Goal:** Section break.

**Say:** *"None of this matters if the CPU isn't on your side. Three things the CPU rewards: laying out memory the way it wants, taking the branches it predicted, staying on the same core."*

---

## Slide 46 — False sharing (36:00)

**Goal:** Gotcha that nobody finds until they hit it.

**Say:** *"Two unrelated longs. Same cache line. When thread A writes one, the coherence protocol marks thread B's copy invalid. Now thread B has to re-fetch — even though the field didn't matter to it. `@Contended` adds 64 bytes of padding. The ping-pong stops."*

**Detail:** *"Yes, you need `-XX:-RestrictContended` to use it outside the JDK."*

---

## Slide 47 — Branch prediction (37:30)

**Say:** *"Same comparisons. Same data. The only difference is the order. The CPU has a branch predictor. Predictable branch, pipeline stays full. Random branch, pipeline stalls on every miss. Twenty-cycle stall. Two to five times slower. **Sort your inputs when you can.**"*

---

## Slide 48 — Thread affinity (38:30)

**Goal:** War story.

**Say:** *"After we shipped sharding, random p99 spikes. Not GC, not contention — fifty-microsecond blips. Took a week to figure out: Linux scheduler migrating our threads. Every migration nukes L1 and L2. Cache miss to RAM is a hundred nanoseconds. Dozens per migration."*

**Then:** *"Two-step fix: `isolcpus` in the bootloader to keep Linux off those cores, then OpenHFT Affinity with `acquireCore` to pin our threads. Spikes vanished."*

**macOS caveat:** *"On Mac the affinity calls are no-ops. The benchmark self-skips. Everything else still runs."*

---

## Slide 49 — A few more things (39:30)

**Goal:** Acknowledge the rest of the benchmarks.

**Say:** *"Fifteen benchmarks in the repo. Four got their own slide. The other eleven back specific claims I made in passing — escape analysis, CAS, inlining threshold, GC pause shape. Repo is the appendix."*

---

## Slide 50 — Part 4: Putting it together (41:30)

**Goal:** Section break into the climax.

**Say:** *"You've seen the techniques in isolation. Let's put them together and see what a complete sub-ten-microsecond pipeline actually looks like."*

---

## Slide 51 — Production architecture (42:00) — **CLIMAX**

**Goal:** This is the moment everything clicks. Walk top to bottom. Slowly.

**Say:** *"Orders come in over the wire. They land in a Disruptor ring buffer — truths one and three: off-heap, pre-allocated, no allocation in steady state. Disruptor multiplexes to four shards."*

**Then:** *"Each shard is a single thread. Truth two — no locks, no contention, exclusive ownership of its slice. Each thread pinned to an isolated CPU core. Linux scheduler isn't allowed near those cores. Cache stays hot."*

**Then:** *"Outbound, audit and replication go to a Chronicle Queue — persistent, sub-microsecond, off-heap."*

**Beat. Then very deliberately:** *"Five million ops per second. Half a microsecond median. Zero GC. **On a 2014 Xeon.**"*

**Hold for one full beat.**

---

## Slide 52 — Why all three (44:30)

**Goal:** Multiplicative argument.

**Say:** *"You don't get to pick one. Off-heap alone leaves you with locks. Sharding alone leaves you with allocations. Pooling alone leaves you with GC tracking the buffer. Each technique deletes a specific source. You need all three because the sources are independent."*

---

## Slide 53 — exchange-core (45:30)

**Goal:** Tell them where to actually go look.

**Say:** *"Apache 2.0 licensed. Every technique in this talk is in that codebase, written by people who do this professionally. Read it. Run it. Steal from it. When I joined my first crypto company I read this codebase for two weeks before writing a line of production code. Best example of these patterns I've seen, anywhere."*

---

## Slide 54 — What you do Monday morning (46:30)

**Goal:** Actionable takeaway. They leave with a checklist.

**Say:** *"Five things, in order. Measure first — always. Find your biggest leak. Apply ONE technique. Re-measure. Test at three times peak. Warm up before going live."*

**War story (the laugh):** *"I once spent a week optimising Protobuf serialisation because it 'felt' like the bottleneck. Improved latency five percent. Then I actually profiled. The bottleneck was ONE LINE — a synchronized block on a ConcurrentHashMap. The whole serialisation week wasted because I didn't measure first."*

---

## Slide 55 — Resources (48:30)

**Goal:** Don't read URLs.

**Say:** *"Code, benchmarks, libraries up top. Reading material at the bottom. Scott Oaks's book is the canonical Java perf reference. Martin Thompson's blog is the mechanical sympathy gospel. Slides on my GitHub by tonight."*

---

## Slide 56 — Closing (49:30)

**Goal:** Land the close. Walk off clean.

**Closing line (deliver slow):** *"The language isn't the limitation. Your understanding of the platform is."*

**Then:** *"Slides and the benchmark repo are linked on the previous slide. I'll be at the lectern for a few minutes if anyone wants to chat. Thank you."*

**Walk off.**

---

## Common questions (for ad-hoc chat at the lectern)

**Q: What about Project Loom / virtual threads?**
A: *"Loom solves a different problem — high concurrency for blocking I/O. It doesn't help on the hot path. A virtual thread context switch still goes through the JVM scheduler. For sub-microsecond ops you still want one platform thread pinned to one core."*

**Q: Why not Rust / C++?**
A: *"You absolutely could. Reason we use Java is the rest of the ecosystem — observability, tooling, hiring, incremental migration. The hot path is 5% of the codebase. You don't rewrite the other 95% in Rust to optimise the 5%. You give the JIT what it wants in the 5%."*

**Q: Doesn't ZGC make GC pauses irrelevant?**
A: Already addressed in slide 19 — *"ZGC's 1ms pause is still 1000× a microsecond budget. Modern GCs make latency-sensitive web services tolerable. They don't make a matching engine free."*

**Q: How do you actually test this?**
A: *"JMH for microbenchmarks — every claim has one. Production-like load tests at 3x peak. Stare at p99/p99.9, not averages. The tail is the entire game."*

**Q: Virtual threads for the I/O side of an exchange?**
A: *"Great fit. Auth, REST, admin — anywhere you'd have used a thread pool. Just keep them off the matching engine's hot path."*

**Q: GraalVM native image?**
A: *"Different trade. Fast startup, lower memory, but you lose the JIT's profile-driven optimisation. Long-running matching engine — JIT wins on steady-state throughput. Lambda function — native image wins on cold start. Pick by workload."*

---

## If running short (drop in this order)

1. Slide 35 — Pool's evil twins (45 sec)
2. Slide 49 — More things (45 sec)
3. Slide 44 — Agrona numbers, just say "3–6× faster, see repo" (60 sec)
4. Slide 4 — Domains list, pick one and move on (30 sec)

## If running long (slow down here)

- Slide 13 — 35-byte trap (the laugh)
- Slide 21 — Truth #1 helicopter parent
- Slide 51 — Architecture climax
- Slide 54 — Monday morning war story

---

## After the talk

- [ ] Stay at the lectern for 5–10 min for ad-hoc questions
- [ ] Post slides to GitHub within 24h
- [ ] Tweet/LinkedIn the repo URL with one of the headline numbers
- [ ] Reply to LinkedIn requests for ~1 week
- [ ] Save questions you got wrong/didn't know — those are next year's talk

# Narrative Arc — The Story Stefan Tells

This is the speaker-mode reference document. Not bullet points. Not slide content. The story that runs in Stefan's head from the moment he walks on stage until the moment he picks up the mic for Q&A. It is written in his voice because he is the one who will deliver it.

Beats are marked. Emotional pivots are marked. The places where he slows down, where the laugh lives, where the room leans forward — all marked.

---

## 1. The Setup (0:00 – 0:03)

Walk on. No "good morning Devoxx." No throat-clear. The first sentence is the hook.

> *"Years back, I joined a crypto trading company. First week, I'm sitting in a meeting and someone casually mentions we need to process north of 10 gigabytes per second of market data. I'm nodding along like this is normal."*

[Beat.]

> *"It's not normal."*

That's the laugh. Small. Knowing. The room recognises the feeling — a junior engineer pretending to follow a meeting that has gotten away from them. Now they're with you.

Then the picture of what was actually happening. Our Java app was a disaster. Memory looked like a hockey stick. Latency? 2ms one second, 50ms the next when the GC woke up. In crypto trading, 50ms might as well be 50 years — by the time your order hits the exchange, someone else already took the price.

Then management drops the bomb: *"Let's build our own exchange."*

The audience needs to feel the size of that ask. Slow down here. *"That conversation broke my brain."* Use that line. It is honest and it is the title of an internal feeling every engineer in the room has had at some point. Nodding heads from people who've been in the room when leadership pivoted underneath them.

## 2. The Pivot (0:03 – 0:05)

This is where you set up the entire premise of the talk.

> *"Everything I thought I knew about writing Java — immutable objects, dependency injection, clean abstractions — suddenly didn't matter. What mattered was keeping the garbage collector from wrecking your latency at the worst possible moment."*

This is a values inversion. The room has spent careers being told that good Java means clean, idiomatic, well-abstracted code. And here is a senior engineer telling them that all of that becomes irrelevant when the latency budget is in microseconds. Don't soften it. They came to this talk because the title promised this inversion. Deliver it.

Then close the loop with the production numbers. *"Fast forward to today: 5 million operations per second. 0.5 microsecond median latency. Half a microsecond. On a 2014 Intel Xeon that probably cost less than my laptop. p99 sits at 42 microseconds."*

The "half a microsecond" beat is the most important number in the talk. It is the number that makes them lean forward. Say it twice. Once in scientific notation, once in plain language: *"Half a microsecond. That's about as long as light takes to travel 150 metres."* Let it sit.

> *"There's no magic. The JIT compiler is a black box that makes you look like a genius if you feed it right. But it's fickle — one tiny change in your method size can push you over the inlining threshold and suddenly your 2ns hot path is 20ns. You learn to work with it, not against it."*

Now they trust you. Now you can teach.

## 3. The Foundation (0:05 – 0:12)

The JIT section. This is where you slow down and become a teacher rather than a storyteller.

The question I want the audience asking themselves is: *"Wait — I never actually understood what the JIT does, did I?"* Most Java developers haven't. They know it exists, they know it makes things faster, they have a vague sense that "warm-up matters." That is not enough.

Walk them through the optimisation tiers. Interpreter for the first thousand-and-a-half invocations, C1 compiles next, C2 takes over at ten thousand. In a hot trading loop, this happens in milliseconds. After that, you're running native machine code optimised for *your exact workload* — not for some hypothetical user, but for the branches you actually take, the methods you actually call, the objects you actually create.

Then the war story that anchors the entire JIT discussion:

> *"I once spent two days debugging why our order validation suddenly got 10× slower after I extracted a helper method. Turns out I crossed the MaxInlineSize threshold. The JIT won't inline methods bigger than 35 bytes of bytecode. My 'clean' refactoring added 8 bytes, pushed it over, killed inlining. 10× slower."*

Pause.

> *"I reverted the refactoring."*

That's the second laugh. The "I reverted my own clean code because the JIT didn't like it" is exactly the kind of war story that makes the room recognise you as one of them.

Then escape analysis, briefly. The `Order temp = new Order()` example. *"Looks like an allocation. Isn't. The JIT proves the object never escapes the method, replaces it with three local variables on the stack, the allocation never happens. Free."* This is the moment where someone in the audience's mental model of "Java allocates objects" gets rewritten in real time. Watch for it.

Close with the warm-up disaster:

> *"I once benchmarked our cold-start performance and got 200 microsecond p50 latency. Panicked. Spent two days profiling. Then I ran the benchmark with a warm-up phase. 0.8 microseconds. The JIT was already optimising everything perfectly. I'd been optimising interpreter performance. Don't make that mistake."*

Self-deprecating. Practical. Sets up every benchmark in the rest of the talk. From here on, when you show a number, the audience knows it is the warmed-up number — because anything else would be embarrassing.

## 4. The Big Reveal (0:12 – 0:40)

This is the spine of the talk. Three counter-intuitive truths. The story you're telling, beneath the technical content, is: *"Here are three things your senior dev told you not to do, that you must do, at this scale."*

The setup matters. Before you reveal the truths, plant the problem statement. The garbage collector as helicopter parent — wants to know where every byte is at all times. Helpful for a CRUD app. Devastating for microseconds. At 100k messages per second, traditional on-heap allocates 12 megabytes of garbage every second. The young generation fills in 10 to 15 seconds. The GC pauses you for 10 to 50 milliseconds. *"In a system with a 10 microsecond latency budget, a 10 millisecond GC pause is one thousand times your entire budget."* That ratio is the line that earns the right to the rest of the talk.

Then deliver the three truths in escalating order of resistance from the audience.

**Truth #1 — Off-heap memory beats on-heap.** This is the gentlest of the three because most of the audience has at least heard of `ByteBuffer.allocateDirect`. You're showing them how far that idea actually goes. Show the on-heap `processMarketData` with three allocations per message. Show the Agrona rewrite with zero. Walk through the comparison table — 150-200ns per message becomes 10-20ns. *"But the real win wasn't the average latency. It was the p99 during volatility. Before: 50ms when GC decided to wake up. After: nothing. Flat 20ns whether Bitcoin was at 30k or 70k."* The volatility framing is critical because everyone in the room has been on call when the system blew up under load, and they recognise the relief of a flat tail latency.

**Truth #2 — Single-threaded shards beat multi-threading.** This is the truth the room will fight. Lean into it.

> *"For years, I wrote Java code the right way. Multiple threads to maximise CPU utilisation. ConcurrentHashMap for thread-safety. Synchronize when you need to coordinate. Then I benchmarked it properly. Adding threads made our account service 800× slower."*

Beat. Let "800×" sit. There will be skeptical body language. Embrace it.

> *"It sounds impossible. I ran the benchmark four times. Five times. Same numbers. I was convinced the benchmark was broken. The benchmark wasn't broken. Locks were."*

Walk through the mechanism — cache coherence traffic, false sharing, context switches. Then deliver the punchline: *"At eight threads, we were 2,400× slower than single-threaded. We weren't losing performance, we were setting the CPU on fire just to coordinate between cores."*

The fix is the satisfying part. Shard by user ID. `userId & shardMask`. Each thread owns its slice. No locks. No coordination. Linear scaling — *"four cores gave us exactly 4× the throughput. Not 3.2× or 3.7× like you get with locks. Exactly 4×."*

Then the second-order war story, because every microsecond engineer has lived this and the room needs to hear it: *"Even with perfect sharding our p99 had random 50µs spikes. Took me a week. The Linux scheduler was migrating our threads. Cache miss penalty per migration is 40-80ns. We were getting dozens per migration. The fix was two parts: `isolcpus=1-4` in the GRUB config, and OpenHFT's Java-Thread-Affinity to pin each shard to its isolated core. Spikes vanished."*

The audience that came in skeptical is now nodding. You have permission to deliver the third truth.

**Truth #3 — Object pooling beats allocation.** Shorter, because the pattern is now familiar. *"Allocation is cheap in Java — that's what they teach you. And it's true: 10-20 nanoseconds per object. Basically free, right? Wrong. At 100k allocations per second, you're creating 240 megabytes of garbage every minute."*

Pre-allocate at startup. Reuse circularly with a ring buffer. Power-of-two size for fast bitmask modulo. Show the code. Show the numbers — 6 million allocations per minute become 1,024 allocations once at startup. p99 drops from 250µs to 12µs. *"21× faster on the tail. But the real win was zero GC pauses during trading hours. Not reduced. Zero."*

Then the honest trap: *"Size your pools at 3 to 4× peak, not average. When Bitcoin moves 10% in a minute, your order volume triples instantly. Get it wrong and your pool runs dry exactly when you need it most."* The audience appreciates this because every ops engineer has been bitten by some queue running dry under load.

## 5. The Tools (0:40 – 0:46)

Now you walk them through the libraries. Frame this carefully — it is not a vendor pitch and you are not trying to sell anything.

> *"None of this is theoretical. These libraries exist because the patterns are real. They've been quietly running production systems for years. The fact that they're not in your stack is, frankly, a marketing problem."*

LMAX Disruptor — the off-heap, lock-free ring buffer. *"All three truths in one library. Off-heap, no locks, pooled events."* Chronicle Queue and Chronicle Map — persistent off-heap queues and maps when you need durability without giving up off-heap. Agrona — primitive collections like `Long2ObjectHashMap`, the `UnsafeBuffer`, the `IdleStrategy`. The toolkit that fills the gaps the JDK leaves for low-latency work. OpenHFT Java-Thread-Affinity — *"the library you only learn about after you've felt the pain."*

Don't dwell. The audience knows the names now and that is enough to look them up. The slide has the URLs.

## 6. The Proof (0:46 – 0:48)

This is where you cash the cheque the talk has been writing.

> *"Theory is one thing. Production is another. Let me show you how these three techniques work together in `exchange-core` — an open-source cryptocurrency exchange engine processing 5 million operations per second at 0.5 microsecond median latency."*

Walk through the architecture in plain language. Disruptor at the front, off-heap, holds a million pre-allocated event objects, multiple producers writing without locks. Then user-ID-based sharding into four single-threaded processors, each pinned to an isolated CPU core. Each shard owns its slice of users; they never touch each other's data. Ever.

> *"On a 10-year-old Intel Xeon that's almost old enough to vote. Real production code, not a benchmark toy. The source is on GitHub. Every technique on these slides is in there."*

The "almost old enough to vote" line is for laughs and for the substantive point that this is not about exotic hardware. The audience needs to leave knowing this is achievable on commodity infrastructure.

## 7. The Call (0:48 – 0:50)

The synthesis is short and direct.

> *"The pattern is simple: eliminate, don't optimise. Zero allocations means zero garbage means zero GC pauses. Zero locks means zero contention. Zero copies means zero overhead. When you're chasing microseconds, 'fast enough' doesn't exist. You need zero."*

Then the Monday-morning instructions. Don't apply all three at once. Measure first — `-Xlog:gc*:file=gc.log`, async-profiler in CPU and allocation modes, JMH for microbenchmarks, and warm up properly. Find your biggest bottleneck. GC pressure → pooling and off-heap. Lock contention → sharding. *"Test at 3× your peak load, not your average. Because when Bitcoin drops 15% in an hour and your order volume triples, that's when you find out your pool is too small."*

Then the honest counter-position, because it earns trust:

> *"If you're building a CRUD app, ignore everything I just said. These techniques add complexity. They make your code harder to debug, harder to modify. The trade-off is only worth it when microseconds actually matter. I've seen teams try to apply these patterns to REST APIs serving 50 requests per second and spend months fighting object-pool bugs that normal Java heap allocation would have handled fine. Don't be that team."*

Final close. Bring the talk back to where it started.

> *"Four years ago, if you'd told me Java could hit these numbers, I would have laughed. Today I know better. The language isn't the limitation. Your understanding of the platform is."*

[Beat.]

> *"Welcome to the world of microsecond-latency Java. Questions?"*

Hands up.

---

## Emotional Pivots — Where to Slow Down

These are the moments where the room shifts. Be aware of them as you deliver.

1. **The "5 million ops, half a microsecond" reveal at 0:03.** The room either believes you or doesn't. If it doesn't, the rest of the talk is uphill. Slow down. Let the number land. Show the histogram.

2. **"I reverted the refactoring" at 0:08.** This is the moment the audience decides whether you're a trustworthy peer or a vendor pitching them. Self-deprecating. Concrete. Specific number (35 bytes, 10× slower). Don't rush.

3. **"800× slower" at 0:30.** This will be met with skepticism. Don't argue against the skepticism — embrace it: *"It sounds impossible. I ran the benchmark four times."* The repetition mirrors the audience's own internal disbelief and earns trust.

4. **"Eliminate, don't optimise" at 0:48.** This is the philosophical core of the talk in five words. Deliver it slowly. Say it once, breathe, then repeat the elaboration. Some of the audience will write it down.

5. **"If you're building a CRUD app, ignore everything I just said" at 0:49.** The honesty here is what separates this talk from a vendor demo. The room respects the speaker who tells them when *not* to use the technique. Lean on it.

---

## What NOT to Say

A few traps to avoid in delivery:

- Don't say *"Java is fast actually"*. The talk is more nuanced than that. Java is *capable* of microsecond latency *when written like this*.
- Don't oversell. Don't say *"this beats C++"* without qualification. Say *"this is competitive with — and often faster than — C++ for steady-state workloads."* The qualifier matters; the room will have C++ engineers in it.
- Don't apologise. Don't say *"I'm not a JIT expert but..."*. You've spent a decade in the fintech and gaming trenches and now lead engineering at Tradu. That is exactly the expertise this room needs.
- Don't read slides. The slides are visual evidence; you are the narrator.
- Don't run over time. End at 0:46 with content. The Q&A is part of the contract.

---

## The Last Thing

If only one thing from this talk lands with each audience member, you want it to be different things for different people.

For the senior engineer who came in skeptical: *"I should benchmark before I assume the JIT is doing what I think."*

For the architect evaluating a new system: *"Java is not off the table for our latency-sensitive system, and I have an answer when the C++ partisans show up."*

For the curious mid-level developer: *"I should clone exchange-core and read the source this weekend."*

If you can hand each of them their own takeaway, the talk has done its job.

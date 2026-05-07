# Abstract

**Title:** Achieving Microsecond Latencies with Java
**Speaker:** Stefan Angelov
**Duration:** 50 minutes
**Track:** Performance / JVM Internals

---

## Abstract (≈420 words)

"Java for HFT? You need C++." Every developer with strong opinions about latency has said some version of this. It's wrong. There is a production cryptocurrency exchange engine, written entirely in Java, doing 5 million operations per second with a median latency of 0.5 microseconds — half a microsecond — on a 2014 Intel Xeon. p99 sits at 42 microseconds. Zero GC pauses during order processing. The codebase is open source (`exchange-core`, on GitHub) and you can clone it on the train home.

I've spent over a decade building high-performance distributed systems across the fintech and gaming industries — currently leading engineering at Tradu. Five of those years have been in the matching-engine and market-data hot path, where 50 milliseconds of latency is enough to lose the trade. The first time my team had to deliver microsecond latency in Java, everything I thought I knew about "good Java" stopped being useful. Immutable objects, dependency injection, clean abstractions — all the patterns that win code review — turn into latency time bombs the moment a garbage collector wakes up at the wrong moment. We had to stop writing *good* Java and start writing *hardware-aware* Java.

This talk is the highlight reel of that journey. We start with the JIT compiler, because if you don't understand how C2 inlines methods, eliminates allocations through escape analysis, and rewards you for keeping hot paths under 35 bytes of bytecode, nothing else makes sense. Then we walk through three counter-intuitive truths — each one a thing your senior developer told you not to do, and each one essential at microsecond scale:

- **Off-heap memory beats on-heap.** Bypass the GC entirely. We'll see Agrona's `UnsafeBuffer` take per-message processing from 150 nanoseconds to 10, and eliminate every GC pause from the hot path.
- **Single-threaded shards beat multi-threading.** Adding threads with locks made our account service 800× slower. Not 2× slower. 800×. Sharding by user ID and pinning each thread to an isolated core gave us perfect linear CPU scaling.
- **Object pooling beats allocation.** Pre-allocate at startup, reuse circularly with a ring buffer, never see a young-GC event again. p99 dropped from 250µs to 12µs in our benchmarks.

We'll close with how `exchange-core` weaves all three techniques together using LMAX Disruptor, Chronicle, and Agrona — the libraries that actually embody these ideas in code you can read tonight.

You'll leave with concrete numbers, runnable benchmarks (the companion repo will be public before the talk), and a mental model for when these techniques are worth the complexity — and when they emphatically are not. If you're building a CRUD app, none of this will help you. If you're chasing microseconds, this is the playbook.

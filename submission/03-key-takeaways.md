# Key Takeaways

Five things you can apply Monday morning. Each one is a concrete technique, with a concrete outcome, that you can validate against your own benchmarks before the end of the week.

---

1. **You will learn how to read your own JIT behaviour using `-XX:+PrintCompilation` and JFR**, so that you can keep hot-path methods under the 35-byte inlining threshold and confirm C2 is actually compiling them — turning 15-20ns method calls into 2-3ns inlined field access.

2. **You will learn how to move I/O buffers off the heap using Agrona's `UnsafeBuffer` and `ByteBuffer.allocateDirect`**, so that you can eliminate GC tracking from your hot path entirely — taking per-message processing from 150ns to 10-20ns and removing 50ms GC pauses from your latency distribution.

3. **You will learn how to replace `ConcurrentHashMap` and `synchronized` blocks with single-threaded shards**, partitioning by user/account/symbol ID and pinning each shard to an isolated CPU core via OpenHFT's Java-Thread-Affinity library — recovering linear CPU scaling and avoiding the 800× slowdown that lock contention introduces under load.

4. **You will learn how to pre-allocate domain objects at startup using a ring-buffer object pool** (sized at 3-4× peak throughput, power-of-two for fast bitmask modulo), so that you can drive young-GC events to zero in the hot path — dropping p99 latency from 250µs to ~12µs in real benchmarks.

5. **You will learn how to build the warm-up phase your production system needs**, replaying synthetic load through every hot path for ~60 seconds before going live, so that C2 compilation and escape analysis are fully applied before the first real request arrives — preventing the 10-20× cold-start latency penalty that quietly destroys your first minute of trading.

---

## What this talk is NOT going to teach you

To be transparent in the CFP submission: this talk is a focused deep-dive, not a survey. You will *not* leave with:

- A comprehensive comparison of every garbage collector. (We touch ZGC and reasons to prefer fixed heaps; we do not benchmark Shenandoah vs G1 vs ZGC.)
- A treatment of native compilation (GraalVM native-image, Project Leyden). The talk is about JIT-compiled HotSpot.
- Reactive / async patterns. The techniques here are deliberately synchronous and busy-spin-friendly because that is what microsecond systems demand.

This narrowness is the point. Five takeaways, all reinforcing each other, all proven in production.

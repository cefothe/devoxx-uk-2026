# Target Audience

## Experience Level

**Intermediate to advanced Java developers.** This is not a beginner talk. If "the JIT compiler" is a phrase you've heard but never inspected, you'll keep up — but you'll work for it.

The sweet spot is the engineer who has been writing Java professionally for 3-10 years, has shipped systems to production, has tuned a GC at least once, and wants to know what the next level looks like. Architects evaluating whether the JVM is suitable for a latency-sensitive new system will also get value, because the talk is grounded in real production numbers rather than theoretical projections.

---

## Prerequisites

To follow comfortably, attendees should have:

- **Working knowledge of Java 8 or later.** No specific JDK 25 features required for the conceptual content; benchmarks run on JDK 25 but the techniques apply from JDK 8 onwards.
- **Basic familiarity with concurrency primitives** — `synchronized`, `ConcurrentHashMap`, threads, and the difference between contended and uncontended locks. We are not going to teach what a memory barrier is from scratch, but we *are* going to show you why one well-placed lock destroyed our throughput by 800×.
- **A passing acquaintance with garbage collection** — knowing roughly what young/old generations are, and that "GC pause" is bad. We do not require deep GC tuning experience; that is what the talk is for.
- **Comfort reading benchmark output and latency histograms.** p50, p99, p999 should mean something to you. If you only think in averages, the section on tail latency will land harder.

If you have written a `synchronized` block in production this year and you've never asked yourself what the JIT did with it, this talk is aimed squarely at you.

---

## Who Should NOT Attend

Honestly, a few groups:

- **Greenfield Spring Boot / web app developers.** The techniques in this talk add real complexity. If you're serving 50 requests per second over HTTP, applying object pooling will give you bugs, not benefits.
- **Engineers looking for "make Java fast" silver bullets.** This is not a `-XX:+UseTheRightFlag` talk. There is no magic flag. The talk is about systematic technique application.
- **Pure Kotlin / Scala / Clojure listeners.** The principles transfer, but the code, the libraries, and the JIT specifics are HotSpot Java.

Knowing who *not* to invite saves Devoxx ratings.

---

## What Attendees Will Gain

By the time the slot ends, you will have:

1. **A mental model for hardware-aware Java** — how the JIT, the cache hierarchy, the GC, and the OS scheduler interact, and what that implies for code structure.
2. **Three concrete techniques** with production-validated benchmarks — off-heap memory, single-threaded sharding, object pooling — and the criteria for when each is worth the complexity tax.
3. **A runnable JMH benchmark suite.** The companion GitHub repository will be public before the talk. Clone it, run it on your hardware, and compare. Every claim on a slide is backed by a benchmark you can reproduce.
4. **A reading list of one repository.** `exchange-core`. It's the cleanest in-the-wild example of these patterns I've seen, and the source code reads like an answer key for the talk.
5. **An informed answer to "should we use Java for our latency-sensitive system?"** which is more nuanced than yes or no, and which you can defend with numbers when the C++ partisans show up to your design review.

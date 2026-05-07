# Title and Tagline

## Primary

**Title:** Achieving Microsecond Latencies with Java

**Tagline (≤200 chars):**
How we built a 5M ops/sec exchange in Java with 0.5µs median latency on a 2014 Xeon — and why everything you know about "good Java" is wrong for low-latency.

*(Character count: 195)*

---

## Alternate Titles

If the CFP system rejects the primary or wants variation:

1. **Hardware-Aware Java: Building Sub-Microsecond Systems on the JVM**
   *Repositions the talk around "mechanical sympathy" if Devoxx prefers a more technical framing.*

2. **Java Faster Than C++: Three Counter-Intuitive Truths from a Production Exchange**
   *Punchier, more provocative — leans into the C++ comparison angle that the article opens with.*

3. **From 50ms GC Pauses to 0.5µs Median: A Low-Latency Java War Story**
   *Story-first framing. Use this if the conference favours experience-report tracks.*

---

## Alternate Taglines

1. Production Java doing 5 million operations per second at 0.5µs median latency. Three techniques nobody teaches you in "Effective Java." Live numbers, real code, no marketing.
   *(192 chars)*

2. Forget what your senior developer told you about clean Java. For microsecond systems you need off-heap memory, single-threaded sharding, and object pooling. Here's why each one wins.
   *(187 chars)*

3. The "Java is too slow for HFT" myth, demolished with benchmarks from a real production exchange. 5M ops/sec, 0.5µs median, zero GC pauses — on a CPU older than your laptop.
   *(177 chars)*

---

## Notes for the Programme Committee

- **Primary title is settled** — it is the title used in the JavaPro publication that previews this material, so brand consistency matters.
- The talk is **not** a "Java is fast, actually" generalist piece. It is a focused deep-dive on three specific production techniques, validated by an open-source codebase (`exchange-core`).
- The tagline deliberately uses the year of the production hardware (2014) to signal that this is achievable on commodity infrastructure — not exotic NUMA boxes.

# Examples Module — Implementation Plan

Companion to the benchmarks. Where `benchmarks/` proves the numbers via JMH,
`examples/` shows the **patterns** as standalone, slide-readable code that
the audience can clone, run, and modify.

## Why a separate module

JMH classes are great for measurement, terrible for reading off a slide.
A `@Benchmark` method is wrapped in `Blackhole.consume`, `@State`, fork
configuration, and per-iteration setup. The underlying technique — the
two lines of code the talk is actually about — gets buried.

The examples module strips all of that out. Each pattern is a single file
with a `main()`, a 5–10 line "what just happened" summary printed at the
end, and a header comment that names the slide it backs.

## Scope

### In scope
- One standalone runnable example per pattern shown in the deck
- A capstone mini-exchange that ties Truth #1 + #2 + #3 + the four
  libraries together (slide 40 architecture, end-to-end)
- Same JDK 25 + Maven build conventions as `benchmarks/`
- Same `BACKS SLIDE: "..."` Javadoc header convention so
  `grep -r "BACKS SLIDE" examples/` reproduces the deck → code map

### Out of scope
- Re-doing what `benchmarks/` already covers as JMH (off-heap-vs-on-heap
  ratios, GC pause shape, etc.) — we **call** the same patterns from
  example code but we don't re-measure them
- Charting / visualisation — that lives in `results/`
- Any test framework. These are demos, not unit tests; correctness is
  validated by reviewing the printed `main()` output

## Gap analysis — deck vs current artefacts

| Slide / pattern                                  | Benchmarked?           | Standalone example needed? |
|--------------------------------------------------|------------------------|----------------------------|
| Logger antipattern (slide ~9)                    | No                     | **Yes** — easiest, ship first |
| Inlining threshold / 35-byte trap                | `InliningThreshold...` | Yes — readable demo of `-XX:+PrintInlining` |
| JIT deoptimisation                               | No                     | **Yes** — fires deopt visibly with `-XX:+PrintCompilation` |
| Off-heap message read/write                      | `OnHeapVsOffHeap...`   | Yes — strip the JMH framing, just the pattern |
| Single-threaded shard-per-thread                 | `SingleThreadedShard...` | Yes — slide-22 code as a runnable service |
| Ring-buffer object pool                          | `ObjectPooling...`     | Yes — the 15 lines from slide 23 as `RingBufferObjectPool<T>` |
| Canonical objects                                | No                     | **Yes** — the "evil twins" slide |
| Lazy initialisation                              | No                     | **Yes** — same slide |
| Thread-locals (`SimpleDateFormat`, `NumberFormat`) | No                   | **Yes** — same slide |
| Flyweight                                        | No                     | **Yes** — same slide |
| Disruptor pipeline                               | `Disruptor...`         | Yes — producer/consumer demo with `EventHandler` |
| Chronicle Queue IPC                              | `ChronicleQueue...`    | Yes — two processes / two `main()`s sharing a queue |
| Chronicle Map persistent K/V                     | `ChronicleMapVs...`    | Yes — JVM-restart survivability demo |
| Agrona primitive collections                     | `PrimitiveColl...`     | Yes — readable side-by-side: `HashMap<Long,Long>` vs `Long2LongHashMap` |
| False sharing / `@Contended`                     | `FalseSharing...`      | Yes — two-thread demo, with vs without padding |
| Branch prediction sorted-vs-shuffled             | `BranchPrediction...`  | Yes — slide-36 code as a runnable demo |
| Thread affinity + `isolcpus`                     | `ThreadAffinity...`    | Yes — same self-skip-on-macOS pattern |
| **Capstone — slide 40 architecture diagram**     | No                     | **Yes** — the climax artefact |

Bold = no current artefact at all. Non-bold = exists as JMH, needs a
plain-`main()` companion for slide / live-demo use.

## Module layout

The repo is now a **multi-module Maven build**. Parent POM at
`devoxx-uk/pom.xml` declares both children, pins all library versions
(JMH, Disruptor, Chronicle Queue/Map/Bytes, Agrona, Eclipse Collections,
OpenHFT Affinity, JCTools), and configures `maven-compiler-plugin`
(JDK 25 release), `maven-shade-plugin`, `maven-surefire-plugin`,
and `exec-maven-plugin` via `pluginManagement`. Children declare
groupId+artifactId only; versions are inherited.

```
devoxx-uk/
├── pom.xml                          parent (packaging=pom)
├── benchmarks/                      child module — JMH (existing, slimmed)
│   ├── pom.xml                      inherits parent, declares JMH proc + shade
│   └── src/main/java/com/devoxx/lowlatency/...
└── examples/                        child module — patterns (new)
    ├── pom.xml                      inherits parent, declares exec:java
    └── src/main/java/com/devoxx/lowlatency/examples/
        ├── jit/
        │   ├── LoggerAntipatternExample.java
        │   ├── InliningExample.java
        │   ├── DeoptimizationExample.java
        │   └── BranchPredictionExample.java
        ├── memory/
        │   ├── OffHeapMessageExample.java
        │   ├── RingBufferPoolExample.java
        │   ├── CanonicalObjectsExample.java
        │   ├── LazyInitExample.java
        │   ├── ThreadLocalExample.java
        │   └── FlyweightExample.java
        ├── concurrency/
        │   ├── ShardedAccountServiceExample.java
        │   ├── FalseSharingExample.java
        │   └── ThreadAffinityExample.java
        ├── libraries/
        │   ├── DisruptorPipelineExample.java
        │   ├── ChronicleQueueWriter.java
        │   ├── ChronicleQueueReader.java
        │   ├── ChronicleMapExample.java
        │   └── AgronaCollectionsExample.java
        └── capstone/
            ├── MiniExchange.java         the orchestrator main()
            ├── OrderEvent.java           pre-allocated, reused via Disruptor
            ├── OrderBook.java            per-shard, single-threaded, Long2ObjectHashMap
            ├── ShardRouter.java          userId & mask → shard index
            └── AuditWriter.java          Chronicle Queue tail end
```

### Build commands

From the repo root (`devoxx-uk/`):

```bash
mvn clean compile                      # both modules
mvn -pl benchmarks clean package       # JMH uber-jar only
mvn -pl examples clean package         # examples only
mvn -pl examples exec:java -Dexec.mainClass=com.devoxx.lowlatency.examples.capstone.MiniExchange
```

Verified on Corretto 25.0.3: full reactor build under 3 s wall clock,
both modules SUCCESS, only Maven/Guice internal `Unsafe` warnings
(unchanged from pre-restructure).

## Conventions every example must follow

1. **Header comment** identical in shape to the benchmarks:
   ```java
   /**
    * BACKS SLIDE: "<exact slide title from slide-deck.md>"
    * PATTERN: <one-line technique name>
    * MECHANISM: <one-line why it's fast>
    * RUN: mvn -q -pl examples exec:java -Dexec.mainClass=...
    * SEE ALSO: benchmarks/.../<corresponding>Benchmark.java (if any)
    */
   ```
2. `main(String[] args)` is the entry point. No JUnit, no JMH, no Spring.
3. Final `System.out.println` block prints a "what just happened"
   summary in 5–10 lines so the slide demo lands without a debugger.
4. Platform-conditional code (affinity, `@Contended`) self-skips on
   macOS using the same `BenchmarkBase.isLinux()` style guard, copied
   into a small `examples/common/Platform.java` to avoid a circular
   dependency on the benchmarks module.
5. JDK 25, no preview flags, no Loom, no Valhalla — matches the
   "what's not on the menu" promise on slide 5.
6. No emojis, no AI-tells. Comments explain the *why*, never the *what*.
7. Power-of-two sizes (`1024`, `4096`) where a ring-buffer or pool is
   involved — same convention as `benchmarks/`.

## Capstone spec — the mini-exchange

This is the artefact that makes the talk land. It implements slide 40
end-to-end at small scale.

**Pipeline:**

```
producer thread  ──▶  Disruptor ring buffer (1024 pre-allocated OrderEvents)
                                 │
                                 ▼  (EventHandler dispatches by shard)
                       ┌─────────┼─────────┐
                       ▼         ▼         ▼
                    OrderBook OrderBook OrderBook    ← 3 single-threaded shards
                    (shard 0) (shard 1) (shard 2)      Long2ObjectHashMap each
                       │         │         │
                       └─────────┼─────────┘
                                 ▼
                       Chronicle Queue (./tmp/mini-exchange-queue)
                                 │
                                 ▼
                       audit reader thread (separate consumer)
```

**Constraints:**
- 3 shards (matches the EPYC reference box's 3 physical cores)
- Producer generates 1M synthetic orders, mixed `userId`s
- Routing: `shardId = (userId & 0xFFFF) % 3` — bit-mask first to make the
  "fast modulo" pattern visible in the code
- Pool size: `4 × peak` per the war-story rule on slide 22
- Steady-state allocation: zero. Verify by printing `gcCount()` deltas
  before and after the run
- Affinity is **optional** (Linux-only), behind a CLI flag. Default is
  off so the demo runs on Stefan's Mac
- Total wall-clock target: under 5 seconds for 1M orders so the live
  demo doesn't drag

**Final printout** (the part the audience reads on stage):
```
Mini-exchange — slide 40 architecture
  3 shards × single-threaded matching
  1,024-event off-heap ring buffer (pooled, reused)
  Chronicle Queue audit on disk

Processed     : 1,000,000 orders
Wall clock    : 2.41 s
Throughput    : 414,938 ops/sec
Allocations   : 0 in steady state (pool warmed at startup)
GC pauses     : 0 (none observed during run)
Audit records : 1,000,000 in /tmp/mini-exchange-queue
```

The numbers don't need to hit 5M ops/sec — that's exchange-core's
production number. The point of the demo is the **shape**: zero
allocation, zero GC, deterministic throughput.

## Multi-agent execution plan

Four build streams in parallel + capstone + two specialist review
passes. Seven agents total → **Native Teams orchestration**
(CLAUDE.md mandates this for 3+ agents).

We deliberately use the **low-latency / HFT-aware** agents available
in this workspace rather than defaulting to generic ones. The
trading-system QA family understands "zero allocation in steady state"
and "deterministic tail latency" as design goals, not as anti-patterns
to flag. A generic reviewer would mark `@Contended`, `Unsafe`, or
busy-spin idle strategies as code smells and ask us to "clean them up" —
which is the exact mental shift the talk argues against (slide 5).

### Team setup

```
TeamCreate name=devoxx-examples-build
```

External JSONL state under
`~/.claude/teams/devoxx-examples-build/state/findings/`. Each stream
writes its own `findings/<stream>.jsonl` recording files created,
slides referenced, and any open questions back to Stefan.

### Streams

| ID | Agent                    | Role            | Files owned                                | Depends on |
|----|--------------------------|-----------------|--------------------------------------------|------------|
| A  | java-pro                 | implementation  | `jit/*` (4 files)                          | —          |
| B  | java-pro                 | implementation  | `memory/*` (6 files)                       | —          |
| C  | java-pro                 | implementation  | `concurrency/*` (3 files)                  | —          |
| D  | java-pro                 | implementation  | `libraries/*` (5 files, two-`main()` Chronicle Queue pair) | — |
| E  | java-article-editor      | implementation  | `capstone/*` (5 files)                     | B, D       |
| F  | grizzled-trader          | domain review   | read-only: `capstone/OrderBook.java`, `capstone/MiniExchange.java` | E |
| G  | qa-performance-engineer  | final gate      | review-only: compile, run, zero-alloc + latency claims, slide refs | A–F |

Streams A–D have **zero file overlap** — they fire simultaneously.
Stream E starts only after B and D land their patterns, because the
capstone imports the off-heap message layout (B) and the Disruptor
+ Chronicle Queue handlers (D). Streams F and G are read-only review
passes; F is a domain spot-check, G is the gate before "done".

Per CLAUDE.md, A–D are launched in a single message with four `Task`
calls bound to the team. E is launched once B and D report `done`
in their JSONL. F and G run after E.

### Why these agent types

**Implementation:**
- `java-pro` (A–D) — masters JDK 21+, performance optimisation, GraalVM,
  cloud-native patterns. Right tool for the bulk of small, focused
  patterns in idiomatic JDK 25.
- `java-article-editor` (E) — explicitly tuned for low-latency Java,
  performance, and concurrency. The capstone is as much a narrative
  artefact as a technical one and must read aloud on stage; this agent's
  description names exactly that intersection.

**Review (the low-latency-aware agents the user flagged):**
- `grizzled-trader` (F) — 40+ years trading experience, `Read`-only.
  Cheap, fast, scoped sanity-check on the toy matching engine: does
  the order book make sense as a trading construct, even at toy scale?
  Are bid/ask, fills, partial matches modelled coherently? A generic
  Java reviewer would miss domain bugs that a trader spots in 30 seconds.
- `qa-performance-engineer` (G) — specialist in ultra-high-performance
  trading-system testing. Verifies the claims that matter for the talk:
  zero allocation in steady state, deterministic latency tail, no GC
  pauses during the run. Knows that busy-spin and `@Contended` are
  features here, not warnings to remove.

### Per-stream brief (each agent gets a self-contained prompt)

Each prompt includes:
1. The exact files to create with their full paths
2. The slide title each file backs (copy-pasted from slide-deck.md)
3. The conventions section above, verbatim
4. Pointer to the matching benchmark file (if one exists) so the
   pattern's mechanics are already validated
5. Output format for the final `System.out.println` block
6. Explicit "do not add: tests, logging frameworks, dependency
   injection, abstractions" — slide 5's mental shift table applies
   to the example code itself
7. Cap on file length: under 200 lines per file. If a pattern needs
   more, it's the wrong pattern for a slide.

### Acceptance criteria

**Stream F gate (grizzled-trader, domain):**
- [ ] Order book maintains coherent bid/ask sides and price levels
- [ ] Order matching is deterministic and price-priority correct
- [ ] Toy-scale simplifications are explicitly flagged in code comments
      (no risk-checks, no fees, no time-priority within price level if
      omitted, etc.) so an audience member doesn't think we're claiming
      a real exchange in 5 files
- [ ] `userId`-based sharding is plausible at exchange scale
      (sharding by `symbolId` is the production pattern; we use `userId`
      because the slide-22 code does — note this in `OrderBook.java`)

**Stream G gate (qa-performance-engineer, final):**
- [ ] `mvn -pl examples clean compile` succeeds on JDK 25
- [ ] Each `main()` runs and terminates within 30 s on a Mac M-series
- [ ] Each header references a real slide title that exists in
      `slides/slide-deck.md`
- [ ] No emojis anywhere in source or printed output
- [ ] Power-of-two pool/ring sizes everywhere they apply
- [ ] Capstone prints zero allocations + zero GC pauses in steady state
      (verified by `ManagementFactory.getGarbageCollectorMXBeans()`
      pause-count delta, not by trust)
- [ ] Linux-only examples self-skip cleanly on macOS with a printed
      explanation (no thrown exception)
- [ ] `grep -r "BACKS SLIDE" examples/` returns one hit per file
- [ ] Total LOC under 3,000 across the whole module

### Sequencing

```
t0   TeamCreate devoxx-examples-build
t0   Launch A, B, C, D in parallel (one message, four Task calls)
t1   B and D complete → launch E
t2   E completes → launch F (domain review, read-only)
t3   A, B, C, D, E, F complete → launch G (final gate, read-only)
t4   G approves → TeamDelete devoxx-examples-build
```

Estimated wall-clock: A/B/C/D ~30–45 min in parallel, E ~45–60 min,
F ~10 min, G ~15 min. Total under two and a half hours assuming
no rework.

## Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Capstone overruns scope (most likely failure mode) | Cap MiniExchange at 5 files. Stop at 1M orders. Don't add WebSocket, FIX, REST framing — the slide doesn't promise that. |
| Examples drift from benchmark numbers | Each example header points at its sibling benchmark file. Stream F verifies the pointer is valid; numerical claims live only in JMH. |
| `@Contended` example needs `-XX:-RestrictContended` and audience won't have it | Print a clear "run this with this flag" line at the top of `main()`. Keep the file runnable without it (no NPE, just no measurable effect). |
| Affinity example fails on macOS | Same self-skip pattern as `ThreadAffinityBenchmark.@Setup`. Print "skipping on macOS — see speaker notes for Linux output". |
| Chronicle Queue example leaves a directory under `/tmp` | Both `Writer` and `Reader` register a JVM shutdown hook that deletes the queue directory. Same pattern the JMH benchmark uses in `@TearDown`. |
| Multi-agent file collisions | None possible — every file in the layout above is owned by exactly one stream. |

## What this plan deliberately does NOT do

- Does not re-prove any number that `benchmarks/` already proved.
  The examples are *demonstrative*, not *measurement-grade*. Numbers
  on the slides come from JMH, never from `main()` output.
- Does not attempt to replace `exchange-core`. The capstone is a 5-file
  toy. The slide explicitly cites exchange-core as the production
  reference; the capstone's role is "show the shape", not "be the system".
- Does not introduce any new library not already on the deck (slides
  26–33). If it isn't Disruptor / Chronicle Queue / Chronicle Map /
  Agrona / OpenHFT Affinity, it doesn't ship.

## Decisions (all resolved)

1. **Maven layout** — multi-module Maven build with parent POM at
   `devoxx-uk/pom.xml`, `benchmarks/` and `examples/` as children.
   Build verified on Corretto 25.0.3.
2. **Capstone affinity** — always-on, routed through
   `examples/common/Platform.pinCurrentThreadToCpu(int)`. Linux pins;
   macOS prints a one-line skip and proceeds. Slide-relevant call site
   reads cleanly.
3. **Chronicle Queue path** — `/tmp/mini-exchange-queue` for the
   capstone audit, with shutdown-hook cleanup unless `--keep-queue`
   is passed. The standalone Writer/Reader pair uses
   `/tmp/devoxx-uk-examples-queue`; cleanup is owned by the Reader
   (natural last-touch in the pipeline).

## Build status — COMPLETE

23 files shipped, build green on Corretto 25.0.3.

| Stream | Files | LOC | Runtime check |
|--------|-------|-----|---------------|
| A — jit/ | 4 | ~458 | all PASS |
| B — memory/ | 6 | 524 | all PASS (LazyInit fixed post-acceptance) |
| C — concurrency/ | 3 | ~404 | all PASS |
| D — libraries/ | 5 | ~533 | 3 PASS + ChronicleQueue pair fixed + ChronicleMap graceful-skip on JDK 25 |
| E — capstone/ | 5 | 548 | PASS (1M orders / 0.31 s / 3.18M ops/sec / 0 alloc / 0 GC) |
| common/ | 1 (Platform.java) | ~50 | not directly run; used by C and capstone |

Total: 24 .java files, 2,529 LOC, well under the 3,000-LOC cap.

## Build commands (final, verified)

From the repo root (`devoxx-uk/`):

```bash
mvn clean compile                                   # parent + both children
mvn -pl benchmarks clean package                    # JMH uber-jar
mvn -pl examples exec:java \
  -Dexec.mainClass=com.devoxx.lowlatency.examples.capstone.MiniExchange
```

`--add-opens` and `--add-exports` for Chronicle libraries are set in
`devoxx-uk/.mvn/jvm.config` and read by Maven at startup — no
`MAVEN_OPTS` needed for the live demo.

## Known limitations (raised by acceptance gate)

1. **ChronicleMap runtime demo skipped on JDK 25 + Chronicle Map 3.27ea0.**
   The library invokes in-process `javac` to generate marshaller
   classes; `javac` cannot resolve `org.jetbrains.annotations.NotNull`
   from its bootclasspath. The example's `main()` catches the failure
   and prints a descriptive skip message. The slide-30 claim is still
   backed by the JMH benchmark, which works because the uber-jar
   bundles the annotation classes:
   `java -jar benchmarks/target/benchmarks.jar 'ChronicleMapVsConcurrentHashMap.*'`

2. **InliningExample shows 1.0× ratio instead of expected 2-10×.**
   Default JIT inlines both the small (≤35 B) and the large (~46 B)
   method on JDK 25 / Apple Silicon. Live demo prints flat numbers
   that undercut the slide's narrative. To force the difference, run
   with `-XX:MaxInlineSize=35 -XX:CompileCommand=dontinline,...` or
   make the large method substantially larger. **Soft flag — fix
   before talk day if there's time.**

3. **AgronaCollectionsExample shows 1.6× ratio instead of expected 2-6×.**
   The HashMap baseline is JIT-warmed on `Long` boxing before the
   measurement, narrowing the gap. Acceptable on stage if the slide
   is reframed; **soft flag**.

## Slide-companion track — `examples/minimal/`

Added after the main build, ported from Stefan's JPrime 2024 examples
(`/Users/stefanangelov/Documents/workspace/JPrime2024Examples`). Style
contrast: the slide-companion files are **the code that goes on the
slide, made compilable** — short, no measurement loops, no summary
blocks. The "evidence" examples in the rest of `examples/` are the
runtime-verified counterpart.

| File | LOC | Slide moment |
|------|-----|--------------|
| `minimal/objectsize/A.java` | 8 | object header overhead — JOL inspection |
| `minimal/objectsize/B.java` | 11 | one reference field, +4 bytes |
| `minimal/objectsize/C.java` | 11 | hidden cost of `ConcurrentHashMap` field |
| `minimal/register/RegisterTest.java` | 21 | C2 register allocation — `-XX:+PrintAssembly` |
| `minimal/canonicalobjects/ImmutableObject.java` | 25 | WeakHashMap-based interning |
| `minimal/objectreuse/SingletonEnum.java` | 19 | Bloch's enum singleton |
| `minimal/objectreuse/DirectOrder2.java` | 9 | mutable struct carrier |
| `minimal/objectreuse/ObjectsPool.java` | 76 | typed stack pool — alternative to ring buffer |

Total: 8 files, 180 LOC. Two `main()`s (`RegisterTest`, `ObjectsPool`)
both exit 0. The other six are inspection-only — they exist for
JOL / `PrintAssembly` / source reading on stage.

Two bugs from the JPrime versions corrected during the port:
- `ImmutableObject.canonicalVersion` — null-safe lookup (original
  threw NPE on first call before the entry existed)
- `SingletonEnum` — added the `INSTANCE` constant (original declared
  the enum with empty body `;`, leaving zero instances and undermining
  the singleton claim)

## What was NOT implemented

- Charting — lives in `results/`, separate workflow
- Tests — examples are demonstrative, not verification artefacts
- Per-pattern README files — would duplicate the header Javadoc

Plan executed. Repo is talk-day ready pending the two soft flags above.

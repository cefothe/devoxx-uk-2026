# Build Status — Devoxx UK 2026 Talk

Snapshot of what's on disk after the multi-agent build session.

## What was produced

```
devoxx-uk/
├── README.md                              top-level guide
├── STATUS.md                              this file
│
├── submission/                            7 files, conference-ready
│   ├── 01-title-and-tagline.md           primary + alternates
│   ├── 02-abstract.md                    420 words, voice-calibrated
│   ├── 03-key-takeaways.md               5 actionable bullets
│   ├── 04-target-audience.md             intermediate-to-advanced + prereqs
│   ├── 05-speaker-bio.md                 100w + 50w + 1-liner
│   ├── 06-talk-outline.md                8 timed segments, 50-min slot
│   └── 07-narrative-arc.md               ~2,700w speaker-mode prose
│
├── benchmarks/                            Maven JMH module, JDK 25
│   ├── pom.xml                           shaded uber-jar build
│   ├── README.md
│   ├── BENCHMARK-METHODOLOGY.md           hardware, JMH config, JVM flags
│   ├── scripts/run-all.sh                 batch runner, JSON output
│   └── src/main/java/com/devoxx/lowlatency/
│       ├── common/BenchmarkBase.java     @StandardConfig, isLinux/isMac
│       ├── memory/                       5 classes, 13 @Benchmark methods
│       │   ├── OnHeapVsOffHeapMessageBenchmark.java
│       │   ├── ObjectPoolingBenchmark.java
│       │   ├── PrimitiveCollectionsBenchmark.java
│       │   ├── EscapeAnalysisBenchmark.java
│       │   └── GcImpactBenchmark.java
│       ├── concurrency/                  4 classes
│       │   ├── CasVsLockBenchmark.java   @Group/@GroupThreads pattern
│       │   ├── FalseSharingBenchmark.java
│       │   ├── SingleThreadedShardVsContendedBenchmark.java   ← talk climax
│       │   └── ThreadAffinityBenchmark.java   Linux-only guard in @Setup
│       ├── libraries/                    4 classes
│       │   ├── DisruptorVsArrayBlockingQueueBenchmark.java
│       │   ├── ChronicleQueueBenchmark.java
│       │   ├── ChronicleMapVsConcurrentHashMapBenchmark.java
│       │   └── AgronaRingBufferBenchmark.java
│       └── jit/                          2 classes
│           ├── InliningThresholdBenchmark.java   ← Stefan's "8-byte" story
│           └── BranchPredictionBenchmark.java
│
├── results/
│   ├── EXPECTED-RESULTS-memory.md         ranges + per-benchmark "why"
│   ├── EXPECTED-RESULTS-concurrency.md
│   ├── EXPECTED-RESULTS-libraries.md
│   └── analysis-and-narrative.md          15 benchmarks → mechanism → slide
│
└── slides/
    ├── README.md                          render pipeline + chart workflow
    └── slide-deck.md                      1,342 lines, 45 slides, Marp
```

**Totals:** 15 benchmark classes, 79 `@Benchmark` methods, ~3,100 lines of
markdown across submission + results + slides.

## Build verification

```bash
cd devoxx-uk/benchmarks
mvn -q clean compile     # ✓ BUILD SUCCESS on JDK 25 (Corretto 25.0.3)
```

Only warnings emitted are Maven-internal `sun.misc.Unsafe` deprecations
from Guice 5 — not from our code, not blockers.

The JMH annotation processor generated `_jmhTest` harnesses for all 79
benchmark methods. Stefan can now produce the runnable jar:

```bash
mvn -q clean package
java -jar target/benchmarks.jar 'OnHeapVsOff.*'   # smoke-test one
./scripts/run-all.sh                              # full suite, ~30 min
```

## Open items before submission

These are the only `[VERIFY]` markers left across the deliverables:

| File                              | What needs resolving                                             |
|-----------------------------------|------------------------------------------------------------------|
| `submission/05-speaker-bio.md:27` | Headshot file + Devoxx UK programme committee photo specs        |
| `submission/05-speaker-bio.md:36` | Companion benchmark repo URL once Stefan publishes it on GitHub  |
| `slides/slide-deck.md:1286`       | Same repo URL on the resources slide                             |
| `slides/slide-deck.md:863`        | Chronicle Queue's published numbers — replace with EPYC measurements once benchmarks run |

Run `grep -rn "\[VERIFY\]" devoxx-uk/` any time to re-list.

## Recommended next steps

1. **Run the benchmark suite on the AMD EPYC reference box.** Capture JSON
   into `results/raw/` via `./scripts/run-all.sh`. Expect ~30 min total
   wall-clock. Skip the affinity benchmark on macOS (it self-aborts).
2. **Render charts.** Convert the JMH JSON to PNGs (matplotlib / a small
   Python script — Stefan's call). Drop them into `results/charts/` using
   the filenames the slide deck references (search the deck for
   `/charts/` to get the list).
3. **Resolve the 4 `[VERIFY]` markers** above.
4. **Render the deck** once charts are in place:
   ```bash
   marp slides/slide-deck.md --pdf
   marp slides/slide-deck.md --pptx
   marp slides/slide-deck.md --preview     # rehearsal mode
   ```
5. **Submit the CFP.** All required fields are in `submission/`. Drop the
   abstract, takeaways, audience, bio, and outline directly into the
   Devoxx UK form; the narrative arc stays internal.

## Voice + numbers audit

Spot checks performed during integration:
- Abstract opens with the "Java for HFT? You need C++" myth, lands on
  exchange-core, quotes 5M ops/sec / 0.5µs median / 42µs p99 — all
  numbers traceable to the source article.
- Slide deck Marp frontmatter validates: `marp: true`, `theme: gaia`,
  `paginate`, `size: 16:9`, header/footer set, custom CSS for `.lead`
  and `.dark` variants, speaker notes via HTML comments.
- AI-tells grep across all deliverables: zero "delve", zero "leverage"
  as a verb, zero "robust"/"seamless"/"comprehensive solution".
- All quoted ratios on slides cross-reference a benchmark file by name
  via the `BACKS SLIDE:` Javadoc header — `grep -r "BACKS SLIDE"` in
  the benchmark module gives the inverse map.

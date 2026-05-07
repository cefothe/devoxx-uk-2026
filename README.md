# Devoxx UK 2026 — Achieving Microsecond Latencies with Java

Companion repository for Stefan Angelov's 50-minute Devoxx UK 2026 talk.

## What's in here

```
devoxx-uk/
├── submission/        Conference submission package (title, abstract, bio, outline, narrative)
├── benchmarks/        Maven JMH benchmark suite — every claim in the talk is backed by code
├── results/           Expected results, raw JSON output, and analysis-and-narrative.md
└── slides/            Marp-formatted slide deck + speaker notes
```

## Reading order

If you're Stefan preparing for the talk:
1. `submission/02-abstract.md` — the audience-facing pitch
2. `submission/06-talk-outline.md` — minute-by-minute structure
3. `submission/07-narrative-arc.md` — the story you tell
4. `benchmarks/BENCHMARK-METHODOLOGY.md` — how the numbers are produced
5. `benchmarks/scripts/run-all.sh` — run the suite, capture JSON
6. `results/analysis-and-narrative.md` — what each number means
7. `slides/slide-deck.md` — the Marp deck

If you're a teammate (review, fact-check, port, etc.):
1. `benchmarks/README.md` — how to build & run
2. `benchmarks/BENCHMARK-METHODOLOGY.md` — JMH config + caveats
3. Each benchmark file has a header comment explaining the claim it backs

## Source corpus this is built from

This is *not* a from-scratch effort. The talk distils:
- `../final-article/FINAL-ARTICLE-FOR-SUBMISSION.md` — JavaPro-published article (~28.5k words)
- `../article-outline.md` — full structural outline
- `../three-techniques-complete.md` — the climax: off-heap, single-threaded, pooling
- `../Low latency Java systems.pptx (1).pdf` — the original deck

Reference production system: **exchange-core** (https://github.com/exchange-core/exchange-core).
5M ops/sec, 0.5 µs median, p99 ~42 µs.

## Hardware target

Benchmarks are designed to run on:
- **AMD EPYC 7282** @ 2.8 GHz, 3 physical cores, 24 GB RAM, 180 GB NVMe (publishable numbers)
- **Mac M2/M3** (dev iteration only — affinity benchmarks skip themselves on macOS)

JDK 25 (OpenJDK).

## Slide rendering

Slides are written for [Marp](https://marp.app). To preview/export:

```bash
npm i -g @marp-team/marp-cli
marp slides/slide-deck.md --preview        # live preview
marp slides/slide-deck.md --pdf            # export PDF
marp slides/slide-deck.md --pptx           # export PowerPoint
marp slides/slide-deck.md --html           # export self-contained HTML
```

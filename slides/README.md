# Slides — Achieving Microsecond Latencies with Java

The talk deck for Devoxx UK 2026, written in [Marp](https://marp.app) so the source is plain markdown and renders identically to PDF, PPTX, or HTML.

## What's in here

```
slides/
├── slide-deck.md   ← the deck (Marp markdown + speaker notes in HTML comments)
└── README.md       ← this file
```

The deck is **45 slides** for a **50-minute slot** (≈ 30 min content + ~20 min Q&A — Devoxx slots are 50 min including questions, so the budget is intentional). Speaker notes are HTML comments immediately after each slide and only appear in presenter mode / when exported with `--notes`.

## Install Marp

Pick whichever fits your workflow:

```bash
# CLI (used for headless export — PDF/PPTX/HTML)
npm install -g @marp-team/marp-cli

# OR — VSCode extension (live preview while editing)
# Install "Marp for VS Code" by marp-team
```

## Render

```bash
# from devoxx-uk/slides/

marp slide-deck.md --preview              # live preview window, auto-reloads
marp slide-deck.md --pdf                  # → slide-deck.pdf
marp slide-deck.md --pptx                 # → slide-deck.pptx (for the conference org)
marp slide-deck.md --pptx --notes         # → slide-deck.pptx WITH speaker notes
marp slide-deck.md --html                 # → slide-deck.html (self-contained, share-able)
marp slide-deck.md --pdf --allow-local-files   # if you reference ../results/charts/*.png
```

The conference will likely ask for **PPTX**. Devoxx's clicker pipes into PowerPoint, so export with `--pptx --notes` and put that on the speaker laptop.

## Charts — the missing piece

The deck refers to a handful of chart slides via background images:

```markdown
![bg right:35%](../results/charts/off-heap-vs-on-heap.png)
```

These PNGs **don't exist yet**. The pipeline:

1. **Benchmarks produce JSON** — `cd ../benchmarks && ./scripts/run-all.sh` writes `results/raw/*.json`
2. **Stefan renders charts manually** from those JSONs (matplotlib, gnuplot, or Excel — whatever's quickest) and drops the PNGs into `../results/charts/`
3. **The deck picks them up automatically** on next render

Until the PNGs exist, those slide regions are blank — the headline number on the same slide (the big red `21×` etc) carries the message on its own. Don't block on the charts.

**Naming convention** (so the deck links don't break):

| Slide | Filename                          |
|-------|-----------------------------------|
| 15    | `off-heap-vs-on-heap.png`         |
| 19    | `sharding-vs-contended.png`       |
| 23    | `pool-vs-allocate.png`            |
| 27    | `disruptor-vs-abq.png`            |
| 29    | `chronicle-queue-percentiles.png` |
| 31    | `chronicle-map-vs-chm.png`        |
| 33    | `agrona-primitive-vs-boxed.png`   |
| 35    | `false-sharing-contended.png`     |
| 36    | `branch-prediction-sorted.png`    |
| 37    | `affinity-pinned-vs-unpinned.png` |

If the rendered chart doesn't tell the story cleanly in 6 seconds (the time someone glances at a slide), redraw it. Less data, larger fonts, one headline number called out.

## Theming

The Marp frontmatter at the top of `slide-deck.md` configures everything:

```yaml
theme: gaia                    # built-in Marp theme — clean, readable
size: 16:9                     # standard widescreen
header: '...'                  # appears at top of every slide
footer: '...'                  # appears at bottom of every slide
style: |                       # custom CSS overrides
  section.dark { ... }         # dark "reveal" slides for the Three Truths
  .big-number { ... }          # the giant 15× / 21× / 800× numbers
```

Stefan's free to tweak the `style:` block — fonts, colours, dark-slide background. The structural classes (`lead`, `dark`, `big-number`) are referenced from the slide body, so don't rename them without sweeping the deck.

If the conference has a brand template they want applied, the cleanest path is:
1. Render `slide-deck.md` to PPTX via `marp --pptx`
2. Open in PowerPoint
3. Apply the conference template via *Design → Variants*
4. Save the result alongside the source markdown

Don't try to translate the conference template back into Marp CSS — diminishing returns.

## Editing protocol while the talk is being rehearsed

- **Speaker notes** are inside HTML comments (`<!-- ... -->`) directly after each slide. They show in presenter mode but never on the audience screen. Edit freely.
- **Slide content** is everything *outside* the comments. Keep on-screen text minimal — the audience is reading and listening simultaneously, so they need bold headlines, big numbers, and short bullets. Detail goes in the notes.
- **Slide breaks** are the literal markdown line `---` between slides. The first `---` after the YAML frontmatter doesn't count.
- **Per-slide layout directives** look like `<!-- _class: lead dark -->` — these only affect the slide they appear in.

## Presenter mode for rehearsal

```bash
marp slide-deck.md --server      # serves the deck on http://localhost:8080
# Open the URL, press 'P' for presenter mode (notes + next-slide preview).
```

This is the mode to rehearse in — you'll see notes alongside each slide as the audience sees it.

## Known [VERIFY] markers in the deck

A few items are flagged with `[VERIFY]` because they depend on URLs/numbers that aren't final yet:

- The talk's GitHub repo URL (Slide 3, 44, 45)
- Stefan's LinkedIn / GitHub URLs (Slide 45)
- Chronicle Queue's published p99 numbers (Slide 29) — replace with our EPYC measurement once the benchmark has run
- The JavaPro article URL (Slide 44) — once the published edition is online

Search `[VERIFY]` in `slide-deck.md` and resolve before stage time.

## When something breaks

- **Marp CLI complains about a class not in the theme** — check the `style:` block in the frontmatter; Marp ignores `<!-- _class: ... -->` directives that reference undefined classes (Marp parses but doesn't apply them).
- **Code blocks overflow the slide** — shorten the example. The deck's CSS sets code at `font-size: 18px` which fits ~10 lines comfortably; longer than that means the slide is doing too much.
- **PDF export looks wrong, HTML looks fine** — usually a font that's available on screen but not embedded. Stick to the Gaia theme's defaults or add `--pdf --pdf-outlines` to embed.
- **Chart background images not appearing in PDF** — add `--allow-local-files`.

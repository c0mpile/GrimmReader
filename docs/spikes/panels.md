# Spike d: panel detection for guided view

Question: can panels be found on-device, fast enough and accurately enough for guided view (PLAN §7, M4 target: ≥ 85 % recall, ≤ 300 ms/page p90), and what is the fallback?
Result: **a clean-room, dependency-free Kotlin detector is fast enough (p90 88 ms on the emulator) and precise (0.92 on the exactly labelled pages), but recall (0.69 exact, 0.48 overall) is well short of the M4 target.** Guided view must ship with the full-page fallback and per-page "full page" memory from day one; recall needs more work in M4 (see Next).

> **Caveat: these scores are in-sample and optimistic.** The detector was tuned through three versions against the same 36 pages it is scored on, and for 13 pages the ground-truth boxes were taken from v1's output after a visual check (so their IoU is close to 1 by construction). Treat the numbers as development figures, not as an estimate for unseen comics. The M4 acceptance test uses a separate held-out set, labelled from scratch and never used for tuning.

## Data set
36 pages from six comics of the comic library (comic samples A–F, 6 pages each, read-only GETs through `scripts/live-smoke`, ≤ 1 req/s; kept in git-ignored `.local/spikes/panels/`, never committed). The set covers white gutters, black gutters inside a white page margin, very dark art with thin black gutters (sample B), strict 9-panel grids, panels separated only by thin border lines, splash pages, covers and cover galleries, 7 wide double-page spreads, one black-and-white page, slanted gutters, a tilted photo collage and overlapping panels on a spread.
Gaps: **no manga and no right-to-left pages** (none in the libraries; public-domain samples still to be sourced), only one black-and-white page.

Ground truth: 200 panel boxes (hand-labelled from 10 % grid overlays; on 13 pages v1's boxes were kept where they were visually correct) in reading order, normalised coordinates, in `docs/spikes/panels-gt.json` (numbers and page refs only). 9 pages are marked `approx` (dark sample B pages, a dense spread, a collage, an overlapping spread) because their boundaries are ambiguous; scores are reported with and without them. Splash pages and covers are labelled as one panel; cover galleries as one panel per cover.

## Detector (`spikes/panels/detector`, pure Kotlin, no dependencies)
1. Area-average downscale (long side ≤ 1280; on Android the page is decoded with `inSampleSize = 2`, so 960).
2. Gutter colour = most common colour in a 1.5 % band around the page border (4 bits per channel).
3. Recursive XY-cut: trim gutter lines, split at every full-length gutter run (≤ 3 % stray pixels per line), rows first, then columns (reversed for right-to-left).
4. Inside each region the gutter colour is re-estimated (nested margins, coloured gutters), and black and white are tried as extra gutter colours; those extra cuts must be thin (≤ 2 %) and leave parts ≥ 15 % of the region, which keeps dark art from being sliced.
5. A leaf that no straight gutter splits is tried as 4-connected components of non-gutter pixels (gutters that are not perfectly straight or aligned); accepted only if ≥ 2 big, non-overlapping components cover ≥ 60 % of it, then ordered by row bands.
6. Panels smaller than 1.2 % of the page or narrower than 8 % are dropped. **Fallback:** ≤ 1 panel or < 50 % coverage → one full-page stop.

Three iterations were measured (v1 = XY-cut at 800 px; v2 = 1280 px + per-region colour + black/white; v3 = guarded extra colours + connected components). `spikes/panels/eval.py` scores detections: greedy one-to-one matching at IoU ≥ 0.5.

## Results (v3)

| Set | Pages | GT panels | Detected | Precision | Recall | Mean IoU | Order correct | Pages fully right |
|---|---|---|---|---|---|---|---|---|
| Exact labels | 27 | 123 | 92 | **0.92** | **0.69** | 0.95 | 14/14 | 15/27 |
| All pages | 36 | 200 | 119 | 0.80 | 0.48 | 0.93 | 16/16 | 15/36 |
| v1 for comparison (exact) | 27 | 123 | 82 | 0.88 | 0.59 | 0.96 | 13/13 | 13/27 |

Per category (v3):
- Fully right: regular grids with white gutters (incl. the strict 9-panel grid and a gridded spread), black-gutter pages with clear borders, thin-white-gutter spreads with misaligned rows, and every single-panel page (splashes, covers, the black-and-white page, spread splashes) via the fallback.
- Missed, fallback to full page (safe): very dark pages with thin black gutters (sample B, 4 of 6 pages), slanted gutters, tilted collage, overlapping spread, cover galleries on textured backgrounds, borderless/bleeding panels with insets.
- Missed partly (unsafe, the reader would skip content within a stop): panels separated only by thin border lines with art crossing them (one stop holds 2–3 panels), a slanted gutter between two top panels.
- Wrong splits: two dark sample-B pages (8–11 boxes, 3–6 right) and one dense spread with thin dark gutters (2 boxes, none right).

Performance (emulator `Pixel_36`, debug build, `spikes/panels/app`):

| | p50 | p90 | max |
|---|---|---|---|
| Decode (`inSampleSize = 2`) | 9.9 ms | 12.6 ms | 20.6 ms |
| Detection, warm | 53.9 ms | 87.6 ms | 142.8 ms |
| Detection, first run of the process | — | — | 892 ms (cold JIT, debug) |
| JVM on the host, 1280 px | 31 ms | 64 ms | 102 ms |

Memory: Java heap used peaked at 99 MB during the 36-page loop (includes uncollected garbage); the live working set is about 30 MB (up to four gutter masks with row/column prefix sums plus component labels at 960 px). APK cost: a few KB of Kotlin; the whole debug spike APK is 2.8 MB.

## Candidates not prototyped
To stay inside the time box only candidate 1 was built. The others, from documentation and artifact sizes:
- **OpenCV contour detector (Kumiko-style).** The `org.opencv:opencv` 5.0.0 AAR is 153 MB across ABIs (tens of MB per ABI after splitting) — a large APK cost for a feature our detector already covers at the same algorithmic level. Kumiko's ideas (contours, polygon simplification, gutter-crossing splits) can be added clean-room if needed; Kumiko itself is AGPL-3.0, compatible with ours.
- **ML (DeepPanel, YOLO-style panel models via LiteRT / onnxruntime).** Likely better on dark and borderless pages, but model-weight licences and training data provenance must be checked for F-Droid, and runtimes add several MB. Revisit only if the clean-room detector plateaus below target.

## Decision and next steps for M4
- Keep the clean-room detector in `reader/comic` behind `PanelProvider`, cache results in Room keyed by partial-MD5 + size, page index and `GutterDetector.VERSION`; run it off the main thread ahead of the reader (prefetch next pages), so the cold first run is never on screen.
- Guided view must treat the fallback as normal: full-page stop when unsure, a per-page "show full page" toggle that is remembered, and double-tap to escape.
- Recall work, in order of expected gain: (1) thin dark border lines inside panels with art crossing them (split along long straight dark lines found by a projection on the panel interior), (2) slanted gutters (allow a small angle when testing gutter lines), (3) a dark-page mode for very dark art (edge-based instead of colour-based gutters), (4) cover galleries on textured backgrounds. Re-run `eval.py` after each.
- Build a held-out evaluation set before M4 starts (different comics, labelled from scratch without looking at detector output), including public-domain manga / right-to-left and black-and-white pages (sources and licences recorded, images never committed).
- Measure on a real tablet with a release build.

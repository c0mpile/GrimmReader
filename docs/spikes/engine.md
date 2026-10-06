# Spike a: reader engine

Question: confirm decision D4 (foliate-js in a WebView for EPUB/MOBI/AZW3/FB2) against Readium.
Result: **confirmed.** foliate-js gives CFIs in the same format as the Grimmory web reader, resumes exactly from both CFI forms the server holds, and opens every reflowable format we need. Readium does neither.

## Setup
- `spikes/engine`: a minimal app (AGP 9.4.1, built-in Kotlin, `jvmToolchain(21)`, minSdk 31, compile/target 37, `androidx.webkit` 1.17.1). No `INTERNET` permission.
- `WebViewAssetLoader` serves `/assets/` (reader page + foliate) and `/book/` (files in `filesDir/books`); every other request gets a 403 and `blockNetworkLoads` is on. JS talks to Kotlin through a `WebMessageListener`.
- foliate-js is Grimmory's vendored copy (Grimmory commit 54b5562), copied into untracked assets by `spikes/engine/fetch-foliate`. Upstream `epubcfi.js` differs from it in the parent-node filter, so the app vendors Grimmory's copy for CFI parity.
- Emulator `Pixel_36` (Android 16 image). Sample: "ebook sample 1" (EPUB, about 1 MB, spine items without ids) from the ebook test library, kept outside the repo. MOBI, AZW3 and FB2 versions were made from it locally with Calibre's `ebook-convert`.
- Page animation off (`animated` attribute removed).

## Results

| Check | Result |
|---|---|
| Cold open, EPUB | fetch 13–18 ms, `open` ≈ 70–96 ms, ready 150–255 ms |
| Page turn (25 turns) | median 102 ms, max 123 ms |
| CFI format | range CFI `epubcfi(/6/N!/4,/a,/b:off)` built like the web (`joinIndir(section.cfi, fromRange(range))`); `[idref]` assertions appear only when spine items have ids (this sample has none) |
| Resume from own range CFI (cold start) | exact: same CFI, same fraction (0.0918), same first words |
| Resume from point CFI without idref (KOReader-bridge style) | exact: same page as above |
| Percentage | foliate `fraction` (end of visible page), ×100 as on the web |
| MOBI | opens (`open` 369 ms); fake spine steps `/6/((i+1)*2)`; pages turn, fraction reported |
| AZW3 | opens (155 ms); CFI and fraction reported from the first page |
| FB2 | opens (301 ms); fake spine steps; `href` is the section index |
| Font/theme injection | `renderer.setStyles(css)` applied font-size 28 px, ink/paper colours and font family (checked with computed styles); re-layout took under 500 ms and kept the position |
| Memory (EPUB open) | app process PSS ≈ 96 MB plus WebView renderer process PSS ≈ 90 MB |
| Network | none: no `INTERNET` permission, all loads app-local |
| Licence | foliate-js MIT, Grimmory's modifications AGPL-3.0 (compatible with our AGPL-3.0); no native code, F-Droid-clean |

## Findings for the real reader (`reader/ebook`)
- **Ignore relocates until `init` resolves.** On resume foliate first emits one relocate at the start of the target section and only then the real position. Saving progress on that first event would rewind the reader to the chapter start.
- On a cover or image-only page `fraction` is `null` and the range text is empty; do not write progress for such events.
- Accept both CFI forms (range with or without idrefs, point without idrefs); foliate's `goTo` already handles both.
- MOBI and FB2 have no stable hrefs (`null` or section index); use the CFI and fraction there, as the web does.
- Custom fonts were not tested here; plan to inject `@font-face` with an app-local URL (or a blob like the web) in M1.

## Readium comparison (documentation-based)
No Readium prototype was built; the comparison relies on the Readium Kotlin toolkit 3.x documentation and source, to keep the time box.
- Readium's `Locator` uses `href` + `progression` / `totalProgression`; the EPUB navigator does not generate CFIs, so it cannot write positions the web reader resumes from, nor resume exactly from a web or KOReader CFI.
- Its `totalProgression` is position-list based, not foliate's byte-weighted `fraction`, so percentages would differ from the web.
- No MOBI, AZW3 or FB2 support.
- It stays useful for OPDS parsing only (`readium-opds`/`readium-shared`, BSD-3), as planned.

## Decision
Keep D4: foliate-js (Grimmory's copy, pinned) in a WebView for reflowable formats; native paged renderer for comics and PDF.

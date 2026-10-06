# Spike b: progress round trip

Question: does progress written by the app resume at the same place in the Grimmory web reader, and can the app resume from what the web writes?
Status: **done, passed both ways.**

## Android → server (2026-10-06)
Baseline: the test account had no progress on either sample (`GET /api/v1/app/books/{id}/progress` returned `{}`).

Writes through `scripts/live-smoke put-progress` (`PUT /api/v1/app/books/{id}/progress`), shaped like the web reader's writes:
- "ebook sample 1": `epubProgress {cfi, href, percentage: 9.18}` plus `fileProgress {bookFileId, positionData: cfi, positionHref: href, progressPercent: 9.18}`. The CFI is the range CFI the engine spike produced after 25 page turns (`epubcfi(/6/16!/4,/88,/104/1:114)`, no idrefs because the spine has no ids).
- "comic sample A": `cbxProgress {page: 42, percentage: 11.3}` plus `fileProgress {bookFileId, positionData: "42", progressPercent: 11.3}`; percentage = round(42 / 373 × 1000) / 10, as the web computes it.

Read back:
- Both writes are stored as sent, with a server-stamped `updatedAt` and `lastReadTime`.
- The GET response has no `fileProgress` key at all; it only returns the per-format objects (`epubProgress`, `cbxProgress`, …) plus `readProgress` and `readStatus`. The app must read the per-format objects.

## Server → web (owner check, 2026-10-06)
- "comic sample A": the web reader resumed on page 42 of 373. Exact.
- "ebook sample 1": the web reader showed 9 % and resumed in the same chapter, on a page whose first paragraph is `/4/60`; the CFI target is `/4/88`, 14 short paragraphs later. foliate's `goTo` shows the page that contains the range start, and page boundaries depend on viewport size (desktop vs phone emulator), so a different first paragraph is expected. The owner confirmed `/4/88` is visible on that web page: pass.

## Web → server → Android (2026-10-06)
The owner moved both samples to new spots in the web reader. Read back:
- "ebook sample 1": `epubProgress {cfi: epubcfi(/6/42!/4,/212/1:157,/268/1:68), href: …#page_252, percentage: 44.787}`, `readStatus` READING. Same range-CFI shape the app produces (no idrefs for this spine); the web sends the unrounded `fraction × 100`.
- "comic sample A": `cbxProgress {page: 187, percentage: 50.1}`; 187 / 373 → 50.1, so `page` is 1-based.
- Cold start of the engine spike with that CFI: the visible range is `/4/196` … `/4/218`, which contains the web's range start `/4/212`. Pass. The app's fraction for that page is 0.4431, against 44.787 % from the web.

## Findings for the app
- Resume works both ways with the stored CFI; no translation is needed.
- **Percentages are viewport-dependent.** foliate's fraction is the end of the visible page, so a desktop page and a phone page at the same CFI differ by up to about 0.5 % here. The conflict prompt (PLAN §4) must not treat that as a different position: compare locations by CFI (does the local visible range contain the server CFI's start?) and fall back to the percentage threshold only when no CFI is available.
- foliate emits duplicate `relocate` events for the same CFI after `init`; deduplicate before pushing progress (on top of ignoring events before `init` resolves, see the engine spike).
- Comic progress is a plain 1-based page with `percentage = round(page / N × 1000) / 10`; writes include `fileProgress.positionData = String(page)`.
- The GET never returns `fileProgress`; read the per-format objects. `readProgress` mirrors the percentage, and `readStatus` switches to READING automatically.
- KOReader-style point CFIs were verified only locally (engine spike), not via the server; the server stores `cfi` as an opaque string, so no difference is expected.

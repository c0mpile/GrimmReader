# Spike b: progress round trip

Question: does progress written by the app resume at the same place in the Grimmory web reader, and can the app resume from what the web writes?
Status: **API half done; waiting for the owner's web-side check.**

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
- "ebook sample 1": the web reader showed 9 % and resumed in the same chapter, on a page whose first paragraph is `/4/60`; the CFI target is `/4/88`, 14 short paragraphs later. foliate's `goTo` shows the page that contains the range start, and page boundaries depend on viewport size (desktop vs phone emulator), so a different first paragraph is expected. Pass if `/4/88` is visible on that web page (owner to confirm).

## Pending
1. Owner confirms the CFI target paragraph is visible on the resumed web page.
2. Owner moves to a new spot in each, then the app reads that progress back and resumes; compare first words / page both ways.
3. Point CFI without idref (KOReader-bridge style): resume already verified in the engine spike; repeat through the server if time allows.

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

## Pending
1. Owner opens both samples in the web reader as the test account and reports where they resume (chapter and first words for the ebook; page number for the comic).
2. Owner moves to a new spot in each, then the app reads that progress back and resumes; compare first words / page both ways.
3. Point CFI without idref (KOReader-bridge style): resume already verified in the engine spike; repeat through the server if time allows.

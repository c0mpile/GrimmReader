# Live test log

Every live-server mutation, made only through `scripts/live-smoke`. Generic labels only: no titles, ids, host, or user.

| Date (UTC) | Command | Target | Change | Reason |
|---|---|---|---|---|
| 2026-10-06 20:17 | `put-progress` | "ebook sample 1" (ebook test library) | own progress set to a range CFI in chapter section 7, 9.18 % | Spike b, Android → web |
| 2026-10-06 20:17 | `put-progress` | "comic sample A" (comic library, own reading data only) | own progress set to page 42 of 373, 11.3 % | Spike b, Android → web |
| 2026-10-07 02:02 | app (M0 Slice 2, debug build on the emulator) | "ebook sample 1" (ebook test library) | own progress: accepted the web position (44.8 %), turned 3 pages, app pushed a range CFI at 44.59 % via the outbox | M0 server slice: progress visible through the API |
| 2026-10-07 02:39 | app (online reading, debug build on the emulator) | "comic sample G" (comic library, own reading data only) | own progress: read online without downloading, page 2 of 25 (8 %) pushed via the outbox | Online comic streaming check |
| 2026-10-07 16:05 | app (bookmarks, debug build on the emulator) | "comic sample H" (comic library, own reading data only) | own progress: opened online at page 1 of 44, pushed via the outbox (again at 16:08) | side effect of the bookmark check |
| 2026-10-07 16:06 | app (bookmarks, debug build on the emulator) | "comic sample H" (comic library, own reading data only) | own bookmark created on page 1 (stored like the web: `cfi` "1", title "Page 1"), then deleted; the first delete attempt was retried (bug: 204 treated as retryable, fixed) | bookmark sync check |
| 2026-10-07 16:08 | app (bookmarks, debug build on the emulator) | "comic sample H" (comic library, own reading data only) | own bookmark created on page 1 and deleted again, each in one sync pass; none left on the server | re-check after the fix |
| 2026-10-07 21:24 | app (notebook check, debug build on the emulator) | "comic sample H" (comic library, own reading data only) | own bookmark created on page 1 (and own progress page 1 re-sent on open), seen in the notebook as a bookmark | notebook end-to-end check |
| 2026-10-07 21:25 | app (notebook check, debug build on the emulator) | "comic sample H" (comic library, own reading data only) | own bookmark deleted again; none left on the server | cleanup |

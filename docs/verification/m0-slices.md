# M0 slice verification (emulator, 2026-10-06/07)

Debug build on the `Pixel_36` emulator (Android 16 image, 1080×2424). Driven over adb; reader state read through
WebView DevTools (debug builds only). Generic labels only.

## Slice 1: local reader, no server
Synthetic files only (kept outside the repo): a 6-chapter EPUB, a 6-page CBZ with ComicInfo.xml, and a hostile EPUB
whose chapter script tries to rewrite the page, post a fake bridge message, call the reader API and fetch a URL.

| Step | Result |
|---|---|
| First run → "Use as a local reader" | library, no server UI |
| Import three files through the system picker (multi-select) | all imported; titles/authors from OPF and ComicInfo; covers extracted (CBZ first page) |
| Open EPUB, 5 page turns | 10 %, chapter label shown |
| Font size 18 → 21 px | applied in the book (computed style), page re-laid out |
| Force-stop, relaunch, reopen | identical CFI, fraction (9.0 %), first words and font size |
| Theme E-ink look, Dark, AMOLED | applied; switching keeps the current screen |
| CBZ: 3 page turns, force-stop, reopen | resumes on page 4 / 6 (67 %, web formula) |
| Hostile EPUB | inline script refused by the CSP (logged violation); chapter text intact; no spoofed position |
| Network | zero sockets owned by the app's uid during the whole slice (`/proc/net/{tcp,tcp6,udp,udp6}` sampled every 0.5 s) |

## Slice 2: server
Restricted test account; address and credentials typed from `.env` without being printed.

| Step | Result |
|---|---|
| Settings → connect → address → Test connection | "Grimmory v3.5.0 found" |
| Sign in | library filled from `/api/v1/app/books` (all pages), covers through the authenticated guarded client |
| "ebook sample 1" → Download | file downloaded (Range-capable worker), detail shows the server progress |
| Read | offer "Continue where you left off? (45 %)" because there was no local position |
| Go there | resumed in the web's section on the page containing the web's CFI (44.3 % here vs 44.8 % on the web, viewport difference as in Spike b) |
| 3 page turns | after the 2 s debounce the sync worker pushed the position; `GET /api/v1/app/books/{id}/progress` returns the app's range CFI and 44.59 % |

## Bugs found and fixed during verification
- Blank reader: `shouldOverrideUrlLoading` cancelled foliate's `blob:` sub-frame navigations; percentage heights resolved to 0 in the
  Compose-hosted WebView.
- Theme switch rebuilt the app (two `CompositionLocalProvider` call sites) and reset navigation.

## Not covered yet
PDF, CB7, CBR (M1), self-signed/TOFU and HTTP opt-in against a real server (unit-tested only), an E-ink refresh flash in the reader,
a tablet AVD, and instrumented (on-device) automated tests.

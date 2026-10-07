# Vendored foliate-js

Copied unchanged from Grimmory's vendored copy (`frontend/src/assets/foliate`, Grimmory commit 54b5562), so CFIs
and progress fractions match the Grimmory web reader exactly (Spike a: upstream `epubcfi.js` differs).

- foliate-js: MIT License, Copyright (c) 2022 John Factotum (`licenses/foliate-js-MIT.txt`).
- Grimmory's modifications: AGPL-3.0, compatible with this app's licence.
- `vendor/fflate.js` (minified, no header): fflate, MIT License, Copyright (c) Arjun Barrett (`licenses/fflate-MIT.txt`).
- `vendor/zip.js` (minified, no header): zip.js, BSD-3-Clause, Copyright (c) Gildas Lormeau (`licenses/zip.js-BSD-3-Clause.txt`).

Book content is untrusted and runs in same-origin frames: the reader must keep the CSP in `reader/reader.html` and
the response headers (see `EbookReader.kt`). Do not edit by hand. To update, copy the folder again from a Grimmory checkout and record the new commit here.

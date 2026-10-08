# GrimmReader

A native Android reader for ebooks and comics, and a companion app for [Grimmory](https://github.com/grimmory-tools/grimmory),
the self-hosted library. A server is optional: without one, GrimmReader is a complete local reader.

**Status:** early development (milestone M1 in progress). Not released yet.

## Features
- Read EPUB, MOBI/AZW3 and FB2 (foliate-js, the engine the Grimmory web reader uses, so positions match the web exactly), PDF, and comics in CBZ (the only comic format; CBR and CB7 are not supported).
- Local library: open files from the device; nothing leaves the device in local mode.
- Grimmory server: sign in, a sidebar like the web UI (dashboard, all books, series, authors, libraries, shelves with Favorites, notebook), download or read online, reading progress and bookmarks synced in both directions, books put on shelves (offline too).
- Themes: the app is dark or AMOLED black; reading pages have their own theme: E-ink (paper and ink, warm or cool, optional paper grain and refresh flash, grayscale images, instant page turns), light, sepia, dark, night (amber, low-blue text) or AMOLED.
- Works offline; progress queues and syncs when the server is reachable again.

Planned: OPDS catalogs, bookmarks and annotations, and
the rest of the Grimmory web features. There is no audiobook player.

## Security and privacy
- One guarded HTTP client for everything; unencrypted HTTP only for a server you explicitly allow, self-signed
  certificates only by explicit fingerprint trust.
- The reader WebView has no network access and a strict Content-Security-Policy against scripts in books.
- Tokens are encrypted with an Android Keystore key; your password is never stored. No analytics, no Google services.

## Building
JDK 21 and the Android SDK (compile SDK 37) are required.

```
./gradlew assembleDebug
./gradlew ktlintCheck detekt lint testDebugUnitTest test   # full check
scripts/check-http-clients                                  # one-HTTP-client invariant
```

Debug builds can prefill the server address from the `GRIMMREADER_SERVER_URL` environment variable or an untracked
`local.properties`; release builds never contain it.

Every push and pull request builds a debug APK on GitHub Actions (`.github/workflows/apk.yml`, also runnable by hand
from the Actions tab); download it from the run's artifacts. CI APKs never contain a server prefill. To let each
build install over the previous one, store a debug keystore as the repository secret `GRIMMREADER_DEBUG_KEYSTORE`
(`base64 -w0 ~/.android/debug.keystore`); without it every run signs with a new key.

## Licence
AGPL-3.0 (see [LICENSE](LICENSE)). Third-party material is listed in [NOTICE](NOTICE).

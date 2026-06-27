# Daily Dilbert — Android app

A tiny single-Activity **WebView wrapper** around a single-file web app
(`app/src/main/assets/index.html`). The page renders identically to opening that HTML in a
browser — it *is* the same HTML, bundled into the APK. Going
native adds the things a browser couldn't: a real launcher icon, permanent localStorage
(favorites + last position never get wiped), no hosting, and optional offline downloads.

Strips stream from the Internet Archive by default; downloading is opt-in and just makes
already-seen strips load instantly / work offline.

## What's here
```
dilbert-app/
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ gradlew, gradlew.bat, gradle/wrapper/…   ← Gradle 8.10.2 wrapper (committed)
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/luke/dilbert/MainActivity.kt   ← ~120 lines: WebView + native bridge
      ├─ assets/index.html                       ← the whole web app, one file
      └─ res/…                                    ← theme, colors, vector launcher icon
```

The launcher icon is a self-contained vector necktie (adaptive icon, `minSdk 26`), so
nothing external is needed to build. Swap it later via **Android Studio → right-click
`res` → New → Image Asset** if you want a custom one.

## Build & run (Android Studio)
1. Open **Android Studio** → *Open* → select this `dilbert-app/` folder.
2. Let it sync Gradle (it'll download the SDK components it needs the first time).
   - If it asks about the **Gradle JVM**, use the bundled JBR (JDK 17/21). *Don't* point it
     at the system Java 20 — AGP 8.7 isn't validated on 20.
3. Plug in a phone (USB debugging on) **or** start an emulator.
4. Hit **Run ▶**. The app installs and opens to the first 1989 strip.

To hand someone the APK instead: **Build → Build APK(s)**, then copy
`app/build/outputs/apk/debug/app-debug.apk` to a phone and tap it (enable "install unknown
apps" for your file manager).

## How the native bridge works
The HTML talks to Kotlin through an injected `window.DilbertNative` object. The contract
(already wired in both sides):

| Direction | Symbol | Meaning |
|---|---|---|
| JS → native | `DilbertNative.info() : String` | `{"count":N,"bytes":B}` of what's saved |
| JS → native | `DilbertNative.download(urlsJson)` | JSON array of IA image URLs; downloads them one by one (used for "this year" / "favorites") |
| JS → native | `DilbertNative.downloadAll()` | fetch the single ~1.4 GB `.7z` and extract every strip (used for "Download everything") |
| JS → native | `DilbertNative.clear()` | delete all saved strips |
| native → JS | `window.ddProgress(done, total)` | progress during a download |
| native → JS | `window.ddDone()` | a download batch (or `clear()`) finished |
| native → JS | `window.ddOnBack() : boolean` | closes the topmost open sheet on hardware back |

Strips load as `<img>` from `archive.org/download/…`. `MainActivity.shouldInterceptRequest`
checks for a locally-saved copy and serves it from `filesDir/strips/` if present; otherwise
the request falls through to the network. **The HTML never changes its URLs** — caching is
transparent.

The cache key is `SHA-256` of the **decoded archive-relative path** (e.g.
`1989/1989-04-17_dating_ice cream_relationships.gif`). That path is exactly what you get by
URL-decoding the bit of the request URL after `…complete.7z/`, *and* it's exactly the entry
name inside the `.7z`. So a strip streamed one-at-a-time and a strip pulled out of the bulk
archive land on the same key — verified against the real archive across tricky filenames
(spaces, `&`, apostrophes, UTF-8).

### Two download paths
- **"This year" / "Favorites"** → `download(urls)`: fetches each strip individually, so they
  become available incrementally. Good for small selections.
- **"Download everything"** → `downloadAll()`: one HTTP request for the whole
  `Dilbert_1989-2023_complete.7z` (~1.4 GB, **resumable** via HTTP Range), then a local
  extract of all 12,384 strips. Far more reliable than 12k separate requests (archive.org
  throttles bursts), but: nothing is usable until the whole archive downloads **and**
  extracts (the 7z file table is read last), and it needs ~2.8 GB free transiently
  (1.4 GB archive + 1.4 GB extracted) before the archive is deleted. Progress bar = 0–50%
  download, 50–100% extract. A failed/interrupted run keeps `archive.7z`/`.part` and resumes
  on the next tap (already-extracted strips are skipped). Extraction uses Apache Commons
  Compress + XZ (the `.7z` is non-solid LZMA2, so per-file extraction is cheap).

The web app is served from `https://appassets.androidplatform.net/assets/index.html` (via
`WebViewAssetLoader`), *not* `file://` — that stable https origin is what makes localStorage
persist reliably. Don't switch it to `file:///android_asset/...`.

## Test checklist
1. Launches → opens to the first 1989 strip.
2. Rotate → edge taps page back/next; counter → jump popup works.
3. Favorite a strip, force-quit, reopen → favorite + position survive.
4. ⤓ button is visible (native bridge present) → "Nothing downloaded yet".
5. Download "this year" → progress bar fills, stat shows count + MB.
6. Airplane mode → downloaded strips still render; others show a connection note.
7. Clear downloads → stat resets, files gone.
8. Hardware back with a sheet open closes the sheet; back with none exits.

## Notes
- `minSdk 26`, `targetSdk 35`. Only permission is `INTERNET`; downloads go to app-private
  `filesDir`, so no storage permission is needed.
- The offline panel offers exactly one action — **Download everything** (the bulk `.7z`).
  Otherwise strips stream from the internet one at a time, and any strip already on disk is
  served locally, so once the full archive is downloaded the app never touches the network.
- The whole UI is one file: `app/src/main/assets/index.html` — edit it directly.

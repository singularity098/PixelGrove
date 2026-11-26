# PixelG — PixelGrove for Android

PixelG is the Android version of PixelGrove: the same library engine (this repository's
Rust crate, run in-process through JNI from [`core/`](core)) with a native Kotlin /
Jetpack Compose interface. It shows the photos and videos on the phone itself; nothing
leaves the device (map tiles come from OpenStreetMap).

## Features

Everything the web UI does for a library: **Photos** (folders, Android style, sortable,
2–4 per row), folder pages with subfolders, **Timeline** by day with a month picker,
**Explore** (places, countries, cameras, years, types, worked out offline), **Map**
(clustered), **Search**, **Favorites**, **File View** (the real folder tree, with a
filter), **Trash** (30 days, restore / delete forever / empty), hiding photos and
folders with "Show hidden", the viewer (pinch / double-tap zoom with the full-resolution
original when zoomed, info panel, video playback with Media3 — MP4, MOV, WebM and MKV),
slideshows (interval, shuffle, videos, subfolders), the nine colour themes, and
Settings (media folders with a folder browser, rescan, library status, thumbnail
cache). Plus, on the phone:

- **Camera** button in the top bar, which opens the phone's camera app.
- **Edit** in the viewer opens the photo or video in another app's editor.
- **Copy to folder…** and **Move to folder…** in the viewer menu (also in the web UI),
  optionally into a new folder. Favorites, hidden marks and share links follow a moved
  item. **Move to trash**, or **Delete permanently** (skipping the trash).
- **Long-press** a photo to select several: copy, move, trash or delete them together.
- **Pull down** on Photos or a folder to rescan it right away.
- Subfolders as a small strip that scrolls sideways, as in the web UI; folder names can
  go over the covers (Settings → Display).
- Animated GIFs and WebPs play in the viewer, and in grids too (Settings → Display; on
  by default). Videos stay still in grids: decoding several at once would drain the
  battery and run out of hardware decoders.
- **Share** through the system share sheet; the shared copy has its location removed
  (like PixelGrove's share links; can be turned off in Settings). Also Open with and
  Set as.
- HEIC/HEIF photos, decoded with Android's own decoders.

Multi-user accounts and public share links don't apply on a phone: the app runs the
engine as its one local administrator.

## How it stays fast

- **Its own index.** The engine's SQLite database (rusqlite) holds paths, dates,
  dimensions and metadata, so every grid renders from the database at once, and syncs in
  the background.
- **Changes are detected, not rescanned.** While the app runs, the engine watches the
  media folders with inotify (what `FileObserver` uses), so new photos show up within a
  second or two. On start and whenever you return to the app (from the camera, say),
  [`MediaSync`](app/src/main/java/app/pixelg/core/MediaSync.kt) asks MediaStore what
  changed since the last generation number (Android 11+) and rescans only those folders.
  A drop in the photo count (deletions) or a new MediaStore version triggers a full
  stat-only pass instead. Watching files directly needs All files access.
- **Thumbnails cached on disk.** Small JPEGs in the app's cache directory, read back
  memory-mapped (3 µs per hit), made in parallel with rayon in the background for the
  whole library, newest first. JPEG thumbnails come from the EXIF preview when the camera
  embedded a big enough one (≈4 ms for a 12 MP photo, against ≈100 ms for a DCT-scaled
  decode); otherwise from a 1/2–1/8-scale DCT decode. EXIF orientation is applied. HEIC,
  AVIF and video frames come from Android's decoders and go into the same cache.
- **UI:** Kotlin, Jetpack Compose, `LazyVerticalGrid`, a bitmap LRU in memory, and a
  drag handle for long grids.

## Building

Needs JDK 17+, the Android SDK (platform 36) and NDK 28.2, Rust with the Android
targets, and cargo-ndk:

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk
cd android && ./gradlew assembleRelease
```

The APK is in `app/build/outputs/apk/release/`. Gradle builds the engine with cargo-ndk
first (`-Ppixelg.abis=arm64-v8a` builds for phones only, which is faster;
`-Ppixelg.skipRust` reuses the last build). Release builds are signed with
`keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) or the
`PIXELG_KEYSTORE_*` environment variables, and with the debug key otherwise. The Android
workflow builds the APK on every push and keeps it as an artifact, signed with the
release key from the repository's Actions secrets: `ANDROID_KEYSTORE_BASE64` (the keystore
file, `base64 -w0 pixelg.keystore`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` (optional, defaults to the store password). Pushes fail without
them: a debug-signed APK won't install over a release-signed one.

`./gradlew testDebugUnitTest` runs the engine (built for the host) through JNI from the
JVM against a small library, covering every API call the app makes, and renders the
main screens with Robolectric into `app/build/outputs/roborazzi/`
(`-Ppixelg.demo=<dir with a DCIM folder>` uses your own pictures, e.g. from
`scripts/screenshots/demo_library.py`).

## Layout

```
core/                Rust: JNI bindings over pixelgrove::embed (libpixelg.so)
app/src/main/java/app/pixelg/
  core/              Native (JNI), Api + Models (the engine's JSON API), Engine,
                     MediaSync (MediaStore generations), Files (camera, share, edit), Prefs
  thumbs/            Thumbnail loading: engine → platform decoders → memory LRU
  ui/                App shell (drawer, top bar), screens/, viewer/, components/, theme/
app/src/main/res/    Icon (the leaf aperture, as adaptive-icon vectors)
```

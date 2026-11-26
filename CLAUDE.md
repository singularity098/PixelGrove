# CLAUDE.md

Notes for Claude (and anyone else) working on PixelGrove. **Keep this file current:** when
a change alters a command, a convention, the layout or a hard-won lesson below, update
this file in the same commit.

## What this is

A self-hosted photo and video library: a single Rust binary (axum + SQLite) that serves a
Svelte 5 web UI embedded at build time, shipped as a `scratch` Docker image with a
trimmed static FFmpeg. **PixelG** (`android/`) is the Android app: the same crate run
in-process through JNI (`src/embed.rs`) under a native Kotlin/Compose UI. `docs/requirements.md` is the owner's original brief;
`docs/plan.md` is the design (decisions, architecture, data model, performance targets,
milestones). README is user-facing documentation.

### Rules from the owner (don't break these)

- **Nothing written to disk for previews by default.** Previews are made on demand and
  kept in an in-memory LRU (`src/preview.rs`). The only exception is the opt-in thumbnail
  cache (`src/thumbcache.rs`, `PIXELGROVE_THUMBNAIL_CACHE`, **off by default** on the
  server, always on in PixelG). Never next to the photos. Otherwise the database is the
  only thing in `/config`.
- **View-only accounts have no changeable options** on the server (no favorites,
  sharing, trash, hiding). Per-device display preferences kept in `localStorage`
  (theme, phone column counts, folder sort) are fine for everyone.
- Ultra-light and fast: measure before/after for anything on a hot path (scan, browse,
  previews) and quote the numbers in the commit message.
- Your files on disk are only changed by explicit user actions (trash/restore,
  copy/move).
- PixelG should keep feature parity with the web UI (minus accounts and share links,
  which don't apply on a phone). New API features usually need both UIs.

## Layout

```
src/                 Rust server
  main.rs            calls lib::main (the crate is a library so fuzz/ can reach it)
  lib.rs             startup: config, DB, indexer, previews, FFmpeg detection, server
  guard.rs           catch_unwind around third-party decoders (release unwinds)
  fuzz.rs            fuzz-target entry points, compiled only with --cfg fuzzing
  config.rs          config file + PIXELGROVE_* env vars (table in README)
  db.rs              SQLite pool, schema, migrations (PRAGMA user_version; append only)
  scanner.rs         filesystem walk → folders/media tables (two phases, chunked)
  indexer.rs         roots, full/subtree scans, file watcher, scan scheduling
  metadata.rs        EXIF/track metadata (nom-exif), supported extensions
  preview.rs         in-memory preview rendering + LRU cache + concurrency limits
  jxl.rs             JPEG XL: container boxes, header-only size, decoding (jxl-oxide)
  video.rs           FFmpeg frame extraction, background preload, MKV → MP4 remux stream
  location.rs        same-length byte patches that strip GPS from shared originals
  places.rs          offline reverse geocoding (data/cities.tsv)
  auth.rs, shares.rs, trash.rs, server.rs (security headers, routing), alloc.rs
  api/               one module per area (browse, media, organize (copy/move), timeline,
                     explore, search, files, favorites, hidden, shares, public, trash,
                     users, settings, auth)
  thumbcache.rs      optional thumbnail cache on disk (mmap reads, LRU cap, rayon warm-up)
  embed.rs           the engine in-process for PixelG: JSON API without HTTP, thumbnails
  testdata.rs        tiny generated JPEG/PNG/EXIF fixtures for tests
tests/fixtures/      binary fixtures (JPEG XL; decoder-panic.jxl came from fuzzing)
fuzz/                cargo-fuzz targets (own workspace, nightly), make_seeds.py, README
web/                 Svelte 5 (runes) + Vite + TypeScript UI
  src/pages/         one component per route; src/components/ shared UI
  src/lib/           router (history API + scroll restoration), api client, lists
  public/            sw.js, theme-init.js (runs before paint; no inline scripts: CSP),
                     favicon.svg (the logo icon) and the PNG app icons rendered from it
data/cities.tsv      geocoding data, embedded
docs/                requirements.md, plan.md, screenshots/, logo-{dark,light}.svg (README)
scripts/screenshots/ demo library + Playwright script for README screenshots
android/             PixelG: core/ (Rust JNI crate, own workspace), app/ (Kotlin, Compose),
                     README.md; see android/README.md for the layout
Dockerfile           web build → cross-compiled static musl binary → static FFmpeg → scratch
```

## Commands

Run what CI runs before every push (`.github/workflows/ci.yml`):

```sh
cargo fmt --check && cargo clippy --all-targets --locked -- -D warnings && cargo test --locked
cd web && npm ci && npm run check && npm run build   # check = svelte-check --fail-on-warnings
```

- Android (`android/README.md`): `cd android && ./gradlew testDebugUnitTest assembleRelease`
  (`-Ppixelg.abis=arm64-v8a` builds the engine for phones only; `-Ppixelg.skipRust`
  reuses it). Needs `sdk.dir` in `android/local.properties` and `ANDROID_NDK_HOME`. The unit
  tests run the engine built for the host through JNI and render screenshots with
  Robolectric into `app/build/outputs/roborazzi/` (look at them after UI changes;
  `-Ppixelg.demo=<dir with DCIM>` for nicer pictures). CI: `.github/workflows/android.yml`.
- Fuzzing (`fuzz/README.md`): `python3 fuzz/make_seeds.py`, then
  `cd fuzz && cargo +nightly fuzz run <target> -- -max_total_time=600 -rss_limit_mb=2048`
  (`http` also needs `-detect_leaks=0`).
  The first build takes ~4 minutes. Turn each crash into a `#[test]` next to the code.
  The Fuzz workflow runs every target for 2 minutes on pushes touching Rust code.
- The binary embeds `web/dist`, so **rebuild the web UI before `cargo build`** when
  testing the UI against a real server.
- `cargo build --release` takes 3–4 minutes here; run it in the background.
- Run a local server against a test library:
  ```sh
  PIXELGROVE_MEDIA_ROOTS=/path/to/photos PIXELGROVE_BIND=127.0.0.1:8092 \
  PIXELGROVE_ADMIN_USER=admin PIXELGROVE_ADMIN_PASSWORD=demo-password \
  PIXELGROVE_CONFIG_DIR=/tmp/pg-config target/release/pixelgrove
  ```
  Stop it with `pkill -x pixelgrove` (plain `pkill -f pixelgrove` also matches, and
  kills, the shell running the command).
- API calls from curl need the CSRF header on non-GET requests: `-H 'x-pixelgrove: 1'`
  (log in with `POST /api/auth/login` and a cookie jar).
- Browser testing: Playwright with the preinstalled Chromium
  (`require($(npm root -g)/playwright)`); emulate phones with
  `{ viewport: {width: 390, height: 844}, isMobile: true, hasTouch: true }` and drive
  touch through CDP `Input.dispatchTouchEvent`. Headless Chromium delivers touch moves
  only every ~50 ms and can't decode H.264/HEVC (use VP9 WebM for client-side video).
- Demo libraries: `scripts/screenshots/demo_library.py <dir>` (generated landscapes, any
  size) or `demo_photos.py` (the owner's AI-generated picture set, not in the repo). See
  `scripts/screenshots/README.md` for regenerating README screenshots.

## Workflow

- **Commit and push directly to `main`.** Don't create feature branches or open pull requests
  unless the owner asks for one.
- Claude Code setups (the web version especially) may create their own working branch for the
  session. Even then, don't use that branch: commit on `main` and push `main`
  (`git push origin HEAD:main`). Don't push the session branch.
- This is a solo project: branches and PRs add very little here and create unwanted overhead.
- After pushing, check that both GitHub workflows (CI and Docker) go green; the Docker
  job takes ~10 minutes (it cross-compiles the binary and FFmpeg for amd64 and arm64).
  Pushes in quick succession cancel the older runs.
- Commit messages: a short imperative subject, then prose/bullets explaining what
  changed for the user and why, with measured numbers where performance matters. Keep
  the attribution trailers the session asks for; never put model names in commits.
- Update README (features, config table, security notes) and `docs/plan.md` when
  behaviour changes. New env vars go in `config.rs`, its tests and the README table.

## Conventions

- Rust: edition 2024 idioms (let-chains), `anyhow` in internals, `ApiError`/`ApiResult`
  in handlers. All DB work goes through `db.run(|conn| ...)` (blocking pool). Comments
  explain why, in plain sentences; match the density of surrounding code.
- Every list query must respect the user's folder scope (`scope()`) and hidden items
  (`not_hidden()` / `Hidden`); share links use `Hidden::for_share`. New queries over
  media need both, or they leak hidden or out-of-scope photos.
- Schema changes: add a new migration at the end of the list in `db.rs`; never edit an
  old one.
- Svelte: runes (`$state`, `$derived`, `$effect`), no stores. Per-device preferences in
  `localStorage`, always wrapped in try/catch. Colours come from theme CSS variables in
  `app.css` (`[data-theme]`, `[data-mode]`); there are nine themes.
- The logo is the leaf-aperture icon (`favicon.svg`) plus the "PixelGrove" wordmark in
  Syne Bold, converted to outlines in `components/Logo.svelte` so no font ships. If the
  icon changes, re-render the PNG icons from it and rebuild `docs/logo-*.svg`.
- The CSP forbids inline scripts; anything that must run before paint lives in
  `web/public/theme-init.js`.
- Tests sit next to the code (`#[cfg(test)]`); API tests use `TestApp` in `api/mod.rs`.
  Its helper parses every response body as JSON, so plain-text error bodies (axum's
  query rejections) can't be asserted through it.

## Lessons learned (gotchas)

- `serde(flatten)` in a `Query<...>` struct breaks numeric fields (everything arrives
  as strings): list query fields explicitly.
- `onVisible` (infinite scroll) only reacts to visibility *changes*: after replacing a
  list whose end marker is already on screen, call `.more()` yourself.
- In Svelte 5, an `onchange` handler on a `<select>` can run before `bind:value`
  updates; read `event.currentTarget.value` instead.
- `<video>` elements hold HTTP connections open: a grid of them starved the browser's
  connection pool (empty pages after navigating). Video tiles use server-made frames,
  and players release their source on destroy (`releaseOnDestroy`).
- OpenStreetMap tiles need a referrer: the global `Referrer-Policy: no-referrer` is
  overridden with `referrerPolicy: 'strict-origin'` on the map's tile layer.
- Storage figures come from the mounts under each media root (`/proc/self/mountinfo`),
  not the container's own filesystem.
- Previews of GIFs (and of anything else that needs shrinking) are still JPEGs, so both
  viewers play GIFs from the original file (PixelG through `AnimatedImageDrawable`, which
  also plays animated WebP).
- Selections act through the batch endpoints (`POST /api/media/{copy,move,trash,delete}`),
  which rescan each folder once per batch; looping over the single-item endpoints would
  rescan once per file.
- JPEG XL: `cjxl` Brotli-compresses the Exif box (`brob`); full decodes are memory-
  hungry (~200–450 MB for 12 MP), so they run one at a time.
- Back/Forward scroll restoration is manual (`lib/router.svelte.ts`) and keeps scrolling
  for up to 5 s while content loads; real input cancels it, but Playwright's
  programmatic scrolling doesn't, so wait it out in tests.
- A streamed (non-range) `<video>` source can't be seeked: Chromium reports a zero-length
  seekable range and clamps every seek to 0, even within what's buffered. MKV playback
  (`StreamPlayer.svelte`) restarts the stream with `?t=` instead.
- Fragmented MP4 with a plain `empty_moov` starts every track at 0, so MKV sound that
  starts late (or after a seek, where copied video starts at the earlier keyframe) plays
  early. The remux uses `delay_moov` (edit lists) and `-noaccurate_seek`. Headless
  Chromium has no AAC or H.264 decoder: check sync with VP9 + Opus in MP4.
- Release builds unwind on panic (not `abort`): fuzzing found a crafted JPEG XL that
  panics inside jxl-oxide's JPEG rebuild, and with `abort` that killed the server (and
  scans at boot would kill it again). New calls into third-party decoders or parsers go
  through `guard::contain`. Size-field arithmetic in our own box/atom walkers must be
  checked (`checked_add`, or compare against `end - pos`): a 64-bit size can wrap.
- r2d2's idle/lifetime reaper keeps the pool's threads alive after the pool is
  dropped; `Db` turns it off (it leaked 3 threads per test app).
- No KVM in the cloud sessions, so no Android emulator: verify PixelG with the JVM unit
  tests and Robolectric screenshots. Maven Central sometimes answers 429 to Gradle;
  retry after a pause.
- Android refuses to `System.loadLibrary` a library with static (initial-exec) TLS
  (`STATIC_TLS` in `llvm-readelf -d`), and PixelG then closes at every launch. mimalloc's
  C code is built that way, so it's left out on Android (Scudo instead). The JVM unit
  tests use the host build and can't see this; the Android workflow checks the flag.
- `embed` runs the axum router with `oneshot` and a cookie for the local admin; anything
  an API handler does works in PixelG for free. Unhiding needs "show hidden" on (the
  handler 404s hidden items otherwise), in both UIs.
- Kotlin `object` properties with `private set` clash with hand-written `setX()` functions
  on the JVM; `Prefs` uses `@set:JvmName`.
- Shell safety: don't `rm -rf` variables or top-level paths in commands; the session's
  safety check blocks them (and blocks the whole command, including edits in it).

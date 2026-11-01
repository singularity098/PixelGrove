# Fuzzing

Coverage-guided fuzz targets ([cargo-fuzz] / libFuzzer) for the code that reads
untrusted input: every file in the library and every HTTP request.

| Target           | What it feeds                                                              |
|------------------|----------------------------------------------------------------------------|
| `location`       | `location::find` (GPS stripping for share links) on images and MP4/MOV, then checks every patch stays inside the file and that whole-file and ranged sends agree |
| `location_apply` | `location::apply` against a byte-by-byte model                             |
| `jxl`            | the JPEG XL box walkers and header reader                                  |
| `metadata`       | `metadata::read`, what the scanner runs on every file (EXIF, MP4 tracks, sizes) |
| `preview`        | `preview::render` for JPEG, PNG, WebP, GIF and JPEG XL, both sizes, all orientations |
| `http`           | one request (any method, path, query and body; as admin, a folder-limited viewer, or signed out) against a fresh copy of a small library with a hidden folder and a share link; a 500 fails the run |

The first input byte picks the variant (file type, session, method…); see
`src/fuzz.rs`, which is compiled only under `cargo fuzz` (`--cfg fuzzing`).

## Running

```sh
rustup toolchain install nightly
cargo install cargo-fuzz
python3 fuzz/make_seeds.py           # starting corpora in fuzz/corpus/ (needs Pillow, ffmpeg)
cd fuzz
cargo +nightly fuzz run preview -- -max_total_time=600 -rss_limit_mb=2048 -timeout=20
cargo +nightly fuzz list             # all targets
```

The first build takes a few minutes. A failure leaves its input in
`fuzz/artifacts/<target>/`; replay it with `cargo +nightly fuzz run <target> <file>`
and shrink it with `cargo +nightly fuzz tmin <target> <file>`. Turn each real bug into
a regular `#[test]` next to the code.

Panics inside third-party decoders are caught by `guard::contain` in the server, so
they don't fail a run by default. To hunt for bugs in the decoders themselves, set
`PIXELGROVE_FUZZ_STRICT=1`.

`http` is the slow one (each run copies a database and builds the app): ~40 runs/s
under AddressSanitizer, ~280 with `-s none`. Run it with `-detect_leaks=0`: the thread
locals of the database pool's threads, still alive at exit, read as a 128-byte leak.

## Found so far

- JPEG XL boxes and MP4 atoms with 64-bit sizes near `u64::MAX` overflowed offset
  arithmetic: a panic (which then aborted the server) or, in release builds, a
  wrapped offset that could loop forever. Fixed with checked arithmetic; tests
  `jxl::tests::huge_box_size_is_rejected`, `location::tests::huge_atom_sizes_are_rejected`.
- jxl-oxide (jxl-jbr 0.2.1) panics with an index out of bounds while rebuilding the
  JPEG of a crafted file (`tests/fixtures/decoder-panic.jxl`). Not fixed upstream yet;
  contained by `guard::contain`, and release builds now unwind instead of aborting.
- r2d2's connection reaper kept each dropped pool's three threads alive (a leak in
  tests and fuzz runs; the server opens one pool). `Db` turns the reaper off.

[cargo-fuzz]: https://rust-fuzz.github.io/book/cargo-fuzz.html

//! Preview images, generated on demand and cached in memory (and on disk too when the
//! optional thumbnail cache is on: see `thumbcache`). JPEGs are decoded at reduced
//! scale (1/2, 1/4 or 1/8) straight from the DCT data, or thumbnails are made from the
//! preview the camera embedded in the EXIF data, which makes them cheap.

use std::fs::File;
use std::io::{BufReader, Read};
use std::num::NonZeroUsize;
use std::path::Path;
use std::sync::{Arc, Mutex};

use anyhow::{Context, Result, bail};
use bytes::Bytes;
use fast_image_resize::images::Image;
use fast_image_resize::{FilterType, PixelType, ResizeAlg, ResizeOptions, Resizer};
use lru::LruCache;
use tokio::sync::Semaphore;

use crate::thumbcache::DiskCache;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Variant {
    /// Grid tiles: the shorter edge is at least this many pixels, so square crops
    /// stay sharp on high-DPI screens.
    Thumb,
    /// The viewer: the longer edge is at most this many pixels.
    Large,
}

impl Variant {
    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "thumb" => Some(Self::Thumb),
            "large" => Some(Self::Large),
            _ => None,
        }
    }

    fn target(self, width: u32, height: u32) -> (u32, u32) {
        let scale = match self {
            Variant::Thumb => THUMB_SHORT_EDGE as f64 / width.min(height) as f64,
            Variant::Large => LARGE_LONG_EDGE as f64 / width.max(height) as f64,
        };
        if scale >= 1.0 {
            return (width, height);
        }
        let w = ((width as f64 * scale).round() as u32).max(1);
        let h = ((height as f64 * scale).round() as u32).max(1);
        (w, h)
    }

    fn quality(self) -> u8 {
        match self {
            Variant::Thumb => 78,
            Variant::Large => 85,
        }
    }
}

const THUMB_SHORT_EDGE: u32 = 360;
const LARGE_LONG_EDGE: u32 = 2048;
/// Files that need no downscaling are served as-is below this size.
const SERVE_ORIGINAL_BELOW: u64 = 1_500_000;

/// What to send for a preview request.
pub enum Preview {
    Jpeg(Bytes),
    /// The original is small enough, or in a format we don't decode (AVIF, video).
    Original,
}

/// Generates previews with bounded concurrency and caches them in memory.
pub struct Previews {
    cache: Mutex<SizedLru>,
    permits: Semaphore,
    /// JPEG XL decodes one at a time: each holds the whole image in memory (a 12
    /// megapixel photo peaks around 200-450 MB) and already uses every core. Taken
    /// before `permits`, so a grid of them doesn't hold up other previews.
    jxl: Semaphore,
    /// Makes video thumbnails, when ffmpeg is installed.
    ffmpeg: Option<crate::video::Ffmpeg>,
    /// The optional thumbnail cache on disk.
    disk: Option<Arc<DiskCache>>,
}

/// Identifies a preview; the mtime and size make edits invalidate old entries.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Key {
    pub media_id: i64,
    pub variant: Variant,
    pub mtime: i64,
    pub size: i64,
}

impl Previews {
    pub fn new(cache_bytes: usize) -> Self {
        let workers = std::thread::available_parallelism().map_or(2, NonZeroUsize::get);
        Self {
            cache: Mutex::new(SizedLru::new(cache_bytes)),
            permits: Semaphore::new(workers),
            jxl: Semaphore::new(1),
            ffmpeg: None,
            disk: None,
        }
    }

    pub fn with_disk_cache(mut self, disk: Option<Arc<DiskCache>>) -> Self {
        self.disk = disk;
        self
    }

    pub fn disk_cache(&self) -> Option<&Arc<DiskCache>> {
        self.disk.as_ref()
    }

    /// A preview from memory or the disk cache, without making one.
    pub fn cached(&self, key: &Key, path: &Path) -> Option<Bytes> {
        if let Some(bytes) = self.cache.lock().unwrap().get(key) {
            return Some(bytes);
        }
        // Disk hits stay out of the memory cache: they're memory-mapped, so the page
        // cache already keeps the busy ones in memory.
        self.disk.as_ref()?.get(path, key)
    }

    /// Keeps a preview made elsewhere (the Android app makes video and HEIC thumbnails
    /// with the platform's decoders).
    pub fn store(&self, key: Key, path: &Path, jpeg: Bytes) {
        if let Some(disk) = &self.disk {
            disk.put(path, &key, &jpeg);
        }
        self.cache.lock().unwrap().put(key, jpeg);
    }

    pub fn with_ffmpeg(mut self, ffmpeg: Option<crate::video::Ffmpeg>) -> Self {
        self.ffmpeg = ffmpeg;
        self
    }

    pub fn has_ffmpeg(&self) -> bool {
        self.ffmpeg.is_some()
    }

    pub fn ffmpeg(&self) -> Option<&crate::video::Ffmpeg> {
        self.ffmpeg.as_ref()
    }

    /// A video's thumbnail (one frame, as JPEG), or `None` without ffmpeg.
    pub async fn video(
        self: &Arc<Self>,
        key: Key,
        path: &Path,
        duration_ms: Option<i64>,
    ) -> Result<Option<Bytes>> {
        if let Some(bytes) = self.cached(&key, path) {
            return Ok(Some(bytes));
        }
        let Some(ffmpeg) = &self.ffmpeg else {
            return Ok(None);
        };
        let _permit = self.permits.acquire().await?;
        if let Some(bytes) = self.cached(&key, path) {
            return Ok(Some(bytes));
        }
        let bytes = Bytes::from(ffmpeg.frame(path, key.variant, duration_ms).await?);
        self.store(key, path, bytes.clone());
        Ok(Some(bytes))
    }

    /// Whether a preview is already in memory (or in the disk cache).
    pub fn is_cached(&self, key: &Key, path: &Path) -> bool {
        self.cache.lock().unwrap().contains(key)
            || self.disk.as_ref().is_some_and(|d| d.contains(path, key))
    }

    pub async fn get(
        self: &Arc<Self>,
        key: Key,
        path: &Path,
        orientation: Option<u16>,
    ) -> Result<Preview> {
        if let Some(bytes) = self.cached(&key, path) {
            return Ok(Preview::Jpeg(bytes));
        }
        let _jxl = if crate::jxl::is_jxl(path) {
            Some(self.jxl.acquire().await?)
        } else {
            None
        };
        let _permit = self.permits.acquire().await?;
        // Another request may have produced it while we waited.
        if let Some(bytes) = self.cached(&key, path) {
            return Ok(Preview::Jpeg(bytes));
        }
        let owned = path.to_path_buf();
        let rendered = tokio::task::spawn_blocking(move || {
            render(&owned, key.variant, orientation.unwrap_or(1))
        })
        .await??;
        Ok(match rendered {
            Some(jpeg) => {
                let bytes = Bytes::from(jpeg);
                self.store(key, path, bytes.clone());
                Preview::Jpeg(bytes)
            }
            None => Preview::Original,
        })
    }
}

/// Renders a preview as JPEG, or `None` if the original should be served instead.
pub fn render(path: &Path, variant: Variant, orientation: u16) -> Result<Option<Vec<u8>>> {
    crate::guard::contain("preview decoder", path, || {
        render_unguarded(path, variant, orientation)
    })
    .unwrap_or_else(|| bail!("decoding {} failed", path.display()))
}

fn render_unguarded(
    path: &Path,
    variant: Variant,
    mut orientation: u16,
) -> Result<Option<Vec<u8>>> {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .map(str::to_ascii_lowercase)
        .unwrap_or_default();
    let file_size = std::fs::metadata(path)?.len();
    let pixels = match ext.as_str() {
        "jpg" | "jpeg" => match embedded_preview(path, variant)? {
            Some(preview) => decode_jpeg(std::io::Cursor::new(preview), variant)?,
            None => decode_jpeg(File::open(path)?, variant)?,
        },
        // Browsers mostly can't show JPEG XL, so it's always converted. Its orientation
        // is in the image header: rendered pixels come out upright, a rebuilt JPEG
        // needs turning.
        "jxl" => match crate::jxl::decode(path)? {
            crate::jxl::Decoded::Jpeg {
                jpeg,
                orientation: o,
            } => {
                orientation = o;
                decode_jpeg(std::io::Cursor::new(jpeg), variant)?
            }
            crate::jxl::Decoded::Rgb {
                width,
                height,
                data,
            } => Pixels {
                width,
                height,
                channels: 3,
                data,
            },
        },
        "png" | "webp" | "gif" => {
            if file_size < SERVE_ORIGINAL_BELOW
                && let Ok(size) = imagesize::size(path)
                && variant.target(size.width as u32, size.height as u32)
                    == (size.width as u32, size.height as u32)
            {
                return Ok(None);
            }
            decode_other(path)?
        }
        _ => return Ok(None),
    };
    let (width, height) = variant.target(pixels.width, pixels.height);
    if (width, height) == (pixels.width, pixels.height)
        && file_size < SERVE_ORIGINAL_BELOW
        && orientation == 1
        && ext != "jxl"
    {
        return Ok(None);
    }
    let resized = resize(pixels, width, height)?;
    let oriented = orient(resized, orientation);
    encode_jpeg(&oriented, variant.quality()).map(Some)
}

/// The preview JPEG a camera embedded in the EXIF data (IFD1), when it's big enough
/// for `variant` and has the same shape as the photo (some cameras letterbox a 4:3
/// preview of a 16:9 photo). Decoding it takes about a millisecond, against tens of
/// milliseconds for even a 1/8-scale decode of a 12-megapixel photo. Only thumbnails
/// use it: embedded previews are rarely sharp enough for the viewer.
fn embedded_preview(path: &Path, variant: Variant) -> Result<Option<Vec<u8>>> {
    if variant != Variant::Thumb {
        return Ok(None);
    }
    // EXIF lives in an APP1 segment (at most 64 KiB) at the start of the file.
    let mut head = Vec::with_capacity(EXIF_HEAD);
    std::io::Read::take(File::open(path)?, EXIF_HEAD as u64).read_to_end(&mut head)?;
    let Some(preview) = exif_thumbnail(&head) else {
        return Ok(None);
    };
    let (Ok(full), Ok(small)) = (imagesize::size(path), imagesize::blob_size(preview)) else {
        return Ok(None);
    };
    let (w, h) = (full.width as f64, full.height as f64);
    let (tw, th) = (small.width as f64, small.height as f64);
    let same_shape = w > 0.0 && h > 0.0 && th > 0.0 && ((tw / th) / (w / h) - 1.0).abs() < 0.02;
    let (target_w, target_h) = variant.target(full.width as u32, full.height as u32);
    let big_enough = tw >= f64::from(target_w) && th >= f64::from(target_h);
    Ok((same_shape && big_enough).then(|| preview.to_vec()))
}

const EXIF_HEAD: usize = 128 * 1024;

/// Finds the IFD1 thumbnail (JPEGInterchangeFormat) in the first bytes of a JPEG.
fn exif_thumbnail(jpeg: &[u8]) -> Option<&[u8]> {
    if !jpeg.starts_with(&[0xFF, 0xD8]) {
        return None;
    }
    let mut pos = 2;
    // Walk the segments before the image data, looking for APP1 "Exif\0\0".
    loop {
        let marker = *jpeg.get(pos..pos + 2)?.get(1)?;
        if jpeg[pos] != 0xFF || marker == 0xDA || marker == 0xD9 {
            return None;
        }
        let len = usize::from(u16::from_be_bytes(
            jpeg.get(pos + 2..pos + 4)?.try_into().ok()?,
        ));
        let segment = jpeg.get(pos + 4..(pos + 2).checked_add(len)?)?;
        if marker == 0xE1 && segment.starts_with(b"Exif\0\0") {
            return tiff_thumbnail(&segment[6..]);
        }
        pos = pos.checked_add(2 + len)?;
    }
}

fn tiff_thumbnail(tiff: &[u8]) -> Option<&[u8]> {
    let little = match tiff.get(..2)? {
        b"II" => true,
        b"MM" => false,
        _ => return None,
    };
    let u16_at = |at: usize| -> Option<u16> {
        let b: [u8; 2] = tiff.get(at..at.checked_add(2)?)?.try_into().ok()?;
        Some(if little {
            u16::from_le_bytes(b)
        } else {
            u16::from_be_bytes(b)
        })
    };
    let u32_at = |at: usize| -> Option<usize> {
        let b: [u8; 4] = tiff.get(at..at.checked_add(4)?)?.try_into().ok()?;
        let v = if little {
            u32::from_le_bytes(b)
        } else {
            u32::from_be_bytes(b)
        };
        usize::try_from(v).ok()
    };
    // IFD0, then the "next IFD" link after its entries leads to IFD1.
    let ifd0 = u32_at(4)?;
    let entries0 = usize::from(u16_at(ifd0)?);
    let ifd1 = u32_at(
        ifd0.checked_add(2)?
            .checked_add(entries0.checked_mul(12)?)?,
    )?;
    if ifd1 == 0 {
        return None;
    }
    let (mut offset, mut length) = (None, None);
    for i in 0..usize::from(u16_at(ifd1)?) {
        let entry = ifd1.checked_add(2)?.checked_add(i.checked_mul(12)?)?;
        match u16_at(entry)? {
            0x0201 => offset = u32_at(entry + 8),
            0x0202 => length = u32_at(entry + 8),
            _ => {}
        }
    }
    let (offset, length) = (offset?, length?);
    let thumb = tiff.get(offset..offset.checked_add(length)?)?;
    thumb.starts_with(&[0xFF, 0xD8]).then_some(thumb)
}

struct Pixels {
    width: u32,
    height: u32,
    /// 1 (grey) or 3 (RGB).
    channels: usize,
    data: Vec<u8>,
}

fn decode_jpeg(source: impl std::io::Read, variant: Variant) -> Result<Pixels> {
    let mut decoder = jpeg_decoder::Decoder::new(BufReader::new(source));
    decoder.read_info()?;
    let info = decoder.info().context("JPEG has no image info")?;
    let (w, h) = variant.target(u32::from(info.width), u32::from(info.height));
    // Ask for at least the target size; the decoder picks the smallest DCT scale that
    // still covers it.
    decoder.scale(w as u16, h as u16)?;
    let data = decoder.decode()?;
    let info = decoder.info().context("JPEG has no image info")?;
    let (width, height) = (u32::from(info.width), u32::from(info.height));
    let (channels, data) = match info.pixel_format {
        jpeg_decoder::PixelFormat::RGB24 => (3, data),
        jpeg_decoder::PixelFormat::L8 => (1, data),
        jpeg_decoder::PixelFormat::L16 => {
            (1, data.as_chunks::<2>().0.iter().map(|p| p[0]).collect())
        }
        jpeg_decoder::PixelFormat::CMYK32 => (3, cmyk_to_rgb(&data)),
    };
    Ok(Pixels {
        width,
        height,
        channels,
        data,
    })
}

fn cmyk_to_rgb(cmyk: &[u8]) -> Vec<u8> {
    // jpeg-decoder returns Adobe CMYK already inverted, so each channel is (1 - ink).
    cmyk.as_chunks::<4>()
        .0
        .iter()
        .flat_map(|p| {
            let k = u16::from(p[3]);
            [p[0], p[1], p[2]].map(|c| (u16::from(c) * k / 255) as u8)
        })
        .collect()
}

fn decode_other(path: &Path) -> Result<Pixels> {
    let image = image::ImageReader::open(path)?
        .with_guessed_format()?
        .decode()?;
    let (width, height) = (image.width(), image.height());
    // Flatten transparency onto white, the usual backdrop for screenshots and logos.
    let rgba = image.to_rgba8();
    let mut data = Vec::with_capacity(width as usize * height as usize * 3);
    for p in rgba.pixels() {
        let a = u16::from(p[3]);
        for c in &p.0[..3] {
            data.push(((u16::from(*c) * a + 255 * (255 - a)) / 255) as u8);
        }
    }
    Ok(Pixels {
        width,
        height,
        channels: 3,
        data,
    })
}

fn resize(src: Pixels, width: u32, height: u32) -> Result<Pixels> {
    if (width, height) == (src.width, src.height) {
        return Ok(src);
    }
    let pixel_type = if src.channels == 3 {
        PixelType::U8x3
    } else {
        PixelType::U8
    };
    let src_image = Image::from_vec_u8(src.width, src.height, src.data, pixel_type)?;
    let mut dst_image = Image::new(width, height, pixel_type);
    Resizer::new().resize(
        &src_image,
        &mut dst_image,
        &ResizeOptions::new().resize_alg(ResizeAlg::Convolution(FilterType::CatmullRom)),
    )?;
    Ok(Pixels {
        width,
        height,
        channels: src.channels,
        data: dst_image.into_vec(),
    })
}

/// Applies an EXIF orientation (1–8) so previews display upright without metadata.
fn orient(src: Pixels, orientation: u16) -> Pixels {
    if !(2..=8).contains(&orientation) {
        return src;
    }
    let (w, h, c) = (src.width as usize, src.height as usize, src.channels);
    let transposed = orientation >= 5;
    let (out_w, out_h) = if transposed { (h, w) } else { (w, h) };
    let mut data = vec![0u8; src.data.len()];
    for y in 0..h {
        for x in 0..w {
            // Destination coordinates for source pixel (x, y), per the EXIF definitions.
            let (dx, dy) = match orientation {
                2 => (w - 1 - x, y),
                3 => (w - 1 - x, h - 1 - y),
                4 => (x, h - 1 - y),
                5 => (y, x),
                6 => (h - 1 - y, x),
                7 => (h - 1 - y, w - 1 - x),
                _ => (y, w - 1 - x), // 8
            };
            let s = (y * w + x) * c;
            let d = (dy * out_w + dx) * c;
            data[d..d + c].copy_from_slice(&src.data[s..s + c]);
        }
    }
    Pixels {
        width: out_w as u32,
        height: out_h as u32,
        channels: c,
        data,
    }
}

fn encode_jpeg(pixels: &Pixels, quality: u8) -> Result<Vec<u8>> {
    let (Ok(width), Ok(height)) = (u16::try_from(pixels.width), u16::try_from(pixels.height))
    else {
        bail!("preview too large to encode");
    };
    let color = if pixels.channels == 3 {
        jpeg_encoder::ColorType::Rgb
    } else {
        jpeg_encoder::ColorType::Luma
    };
    let mut out = Vec::with_capacity(pixels.data.len() / 8);
    let mut encoder = jpeg_encoder::Encoder::new(&mut out, quality);
    encoder.set_progressive(true);
    encoder.encode(&pixels.data, width, height, color)?;
    Ok(out)
}

/// An LRU cache bounded by total bytes.
struct SizedLru {
    entries: LruCache<Key, Bytes>,
    bytes: usize,
    capacity: usize,
}

impl SizedLru {
    fn new(capacity: usize) -> Self {
        Self {
            entries: LruCache::unbounded(),
            bytes: 0,
            capacity,
        }
    }

    fn get(&mut self, key: &Key) -> Option<Bytes> {
        self.entries.get(key).cloned()
    }

    fn contains(&self, key: &Key) -> bool {
        self.entries.contains(key)
    }

    fn put(&mut self, key: Key, value: Bytes) {
        if value.len() > self.capacity {
            return;
        }
        self.bytes += value.len();
        if let Some(old) = self.entries.put(key, value) {
            self.bytes -= old.len();
        }
        while self.bytes > self.capacity {
            match self.entries.pop_lru() {
                Some((_, evicted)) => self.bytes -= evicted.len(),
                None => break,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A real (decodable) JPEG with a gradient, `w`×`h`.
    fn gradient_jpeg(w: u16, h: u16) -> Vec<u8> {
        let mut data = Vec::with_capacity(w as usize * h as usize * 3);
        for y in 0..h {
            for x in 0..w {
                data.extend_from_slice(&[(x % 256) as u8, (y % 256) as u8, 128]);
            }
        }
        let mut out = Vec::new();
        jpeg_encoder::Encoder::new(&mut out, 90)
            .encode(&data, w, h, jpeg_encoder::ColorType::Rgb)
            .unwrap();
        out
    }

    fn dims(jpeg: &[u8]) -> (usize, usize) {
        let size = imagesize::blob_size(jpeg).unwrap();
        (size.width, size.height)
    }

    #[test]
    fn jpeg_thumb_and_large() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("big.jpg");
        std::fs::write(&path, gradient_jpeg(4000, 3000)).unwrap();

        let thumb = render(&path, Variant::Thumb, 1).unwrap().unwrap();
        assert_eq!(dims(&thumb), (480, 360));
        let large = render(&path, Variant::Large, 1).unwrap().unwrap();
        assert_eq!(dims(&large), (2048, 1536));
    }

    /// `main` with `thumb` embedded as its EXIF (IFD1) thumbnail.
    fn with_exif_thumbnail(main: &[u8], thumb: &[u8]) -> Vec<u8> {
        let mut tiff = b"II\x2a\x00\x08\x00\x00\x00".to_vec();
        tiff.extend_from_slice(&[0, 0, 14, 0, 0, 0]); // IFD0: no entries, IFD1 at 14
        tiff.extend_from_slice(&2u16.to_le_bytes());
        for (tag, value) in [(0x0201u16, 44u32), (0x0202, thumb.len() as u32)] {
            tiff.extend_from_slice(&tag.to_le_bytes());
            tiff.extend_from_slice(&4u16.to_le_bytes()); // LONG
            tiff.extend_from_slice(&1u32.to_le_bytes());
            tiff.extend_from_slice(&value.to_le_bytes());
        }
        tiff.extend_from_slice(&0u32.to_le_bytes());
        tiff.extend_from_slice(thumb);
        let mut out = vec![0xFF, 0xD8, 0xFF, 0xE1];
        out.extend_from_slice(&((tiff.len() + 8) as u16).to_be_bytes());
        out.extend_from_slice(b"Exif\0\0");
        out.extend_from_slice(&tiff);
        out.extend_from_slice(&main[2..]);
        out
    }

    fn solid_jpeg(w: u16, h: u16, rgb: [u8; 3]) -> Vec<u8> {
        let data: Vec<u8> = (0..w as usize * h as usize).flat_map(|_| rgb).collect();
        let mut out = Vec::new();
        jpeg_encoder::Encoder::new(&mut out, 90)
            .encode(&data, w, h, jpeg_encoder::ColorType::Rgb)
            .unwrap();
        out
    }

    fn centre_pixel(jpeg: &[u8]) -> Vec<u8> {
        let mut decoder = jpeg_decoder::Decoder::new(jpeg);
        let pixels = decoder.decode().unwrap();
        let info = decoder.info().unwrap();
        let at = (info.height as usize / 2 * info.width as usize + info.width as usize / 2) * 3;
        pixels[at..at + 3].to_vec()
    }

    #[test]
    fn thumbnails_come_from_the_embedded_preview_when_it_fits() {
        let dir = tempfile::tempdir().unwrap();
        let main = solid_jpeg(4000, 3000, [0, 0, 255]);
        let path = dir.path().join("camera.jpg");
        std::fs::write(
            &path,
            with_exif_thumbnail(&main, &solid_jpeg(512, 384, [255, 0, 0])),
        )
        .unwrap();
        let thumb = render(&path, Variant::Thumb, 1).unwrap().unwrap();
        assert_eq!(dims(&thumb), (480, 360));
        assert!(
            centre_pixel(&thumb)[0] > 200,
            "made from the red embedded preview"
        );
        let large = render(&path, Variant::Large, 1).unwrap().unwrap();
        assert!(
            centre_pixel(&large)[2] > 200,
            "the viewer decodes the photo itself"
        );

        // Too small, or another shape (letterboxed): the photo itself is decoded.
        for embedded in [
            solid_jpeg(160, 120, [255, 0, 0]),
            solid_jpeg(640, 360, [255, 0, 0]),
        ] {
            std::fs::write(&path, with_exif_thumbnail(&main, &embedded)).unwrap();
            let thumb = render(&path, Variant::Thumb, 1).unwrap().unwrap();
            assert!(centre_pixel(&thumb)[2] > 200);
        }

        // Cut-off or scribbled-over EXIF finds nothing rather than reading past the end.
        let good = with_exif_thumbnail(&main, &solid_jpeg(512, 384, [255, 0, 0]));
        for end in 0..200 {
            assert!(exif_thumbnail(&good[..end]).is_none());
        }
        for at in 10..60 {
            let mut broken = good.clone();
            broken[at] = 0xFF;
            let _ = exif_thumbnail(&broken);
        }
    }

    #[test]
    fn orientation_rotates_preview() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("rotated.jpg");
        std::fs::write(&path, gradient_jpeg(4000, 3000)).unwrap();
        let thumb = render(&path, Variant::Thumb, 6).unwrap().unwrap();
        assert_eq!(dims(&thumb), (360, 480));
    }

    #[test]
    fn small_files_are_served_as_is() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("small.jpg");
        std::fs::write(&path, gradient_jpeg(300, 200)).unwrap();
        assert!(render(&path, Variant::Thumb, 1).unwrap().is_none());
        let avif = dir.path().join("x.avif");
        std::fs::write(&avif, b"whatever").unwrap();
        assert!(render(&avif, Variant::Thumb, 1).unwrap().is_none());
    }

    #[test]
    fn png_is_decoded_and_scaled() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("big.png");
        let img = image::RgbaImage::from_pixel(1600, 1200, image::Rgba([10, 200, 30, 128]));
        img.save(&path).unwrap();
        let thumb = render(&path, Variant::Thumb, 1).unwrap().unwrap();
        assert_eq!(dims(&thumb), (480, 360));
    }

    #[test]
    fn orient_matches_exif_semantics() {
        // 2×1 image: left pixel 1, right pixel 2 (grey).
        let src = || Pixels {
            width: 2,
            height: 1,
            channels: 1,
            data: vec![1, 2],
        };
        assert_eq!(orient(src(), 2).data, [2, 1]); // mirrored
        let rotated = orient(src(), 6); // 90° clockwise: becomes 1×2, top = left pixel
        assert_eq!(
            (rotated.width, rotated.height, rotated.data),
            (1, 2, vec![1, 2])
        );
        let rotated = orient(src(), 8); // 90° counter-clockwise: top = right pixel
        assert_eq!(rotated.data, [2, 1]);
    }

    #[test]
    fn cache_evicts_by_size() {
        let mut cache = SizedLru::new(10);
        let key = |id| Key {
            media_id: id,
            variant: Variant::Thumb,
            mtime: 0,
            size: 0,
        };
        cache.put(key(1), Bytes::from(vec![0; 6]));
        cache.put(key(2), Bytes::from(vec![0; 6]));
        assert!(cache.get(&key(1)).is_none());
        assert!(cache.get(&key(2)).is_some());
        assert_eq!(cache.bytes, 6);
        cache.put(key(3), Bytes::from(vec![0; 11]));
        assert!(
            cache.get(&key(3)).is_none(),
            "oversized entries are not cached"
        );
    }
}

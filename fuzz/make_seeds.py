"""Writes starting corpora for the fuzz targets into fuzz/corpus/<target>/.

Needs Pillow; uses ffmpeg (for MP4/MOV with location atoms) when it's on PATH.
Usage: python3 fuzz/make_seeds.py
"""
import io
import os
import shutil
import struct
import subprocess
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURES = os.path.join(HERE, "..", "tests", "fixtures")
CORPUS = os.path.join(HERE, "corpus")

# Must match EXTENSIONS in src/fuzz.rs.
EXTENSIONS = ["jpg", "png", "webp", "gif", "avif", "jxl", "mp4", "mov", "m4v", "webm", "mkv"]
PREVIEW_EXTENSIONS = ["jpg", "png", "webp", "gif", "jxl"]


def exif_with_gps():
    exif = Image.Exif()
    exif[0x010F] = "PixelCam"  # Make
    exif[0x0110] = "Model 1"  # Model
    exif[0x0112] = 6  # Orientation
    exif[0x0132] = "2023:06:15 14:30:00"
    gps = exif.get_ifd(0x8825)
    gps[1] = "N"
    gps[2] = (35.0, 0.0, 41.76)
    gps[3] = "E"
    gps[4] = (135.0, 46.0, 6.0)
    return exif


XMP = (b'<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description '
       b'exif:GPSLatitude="35,0.696N" exif:GPSLongitude="135,46.1E"/></rdf:RDF></x:xmpmeta>')


def image(fmt, size=(40, 24), mode="RGB", **kw):
    img = Image.new(mode, size)
    for x in range(size[0]):
        for y in range(size[1]):
            img.putpixel((x, y), (x * 6 % 256, y * 10 % 256, 128) if mode == "RGB"
                         else (x * 6 % 256, y * 10 % 256, 128, 200) if mode == "RGBA"
                         else x * 6 % 256)
    out = io.BytesIO()
    img.save(out, fmt, **kw)
    return out.getvalue()


def png_chunk(kind, body):
    import zlib
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))


def png_with_metadata():
    png = image("PNG", exif=exif_with_gps())
    itxt = png_chunk(b"iTXt", b"XML:com.adobe.xmp\0\0\0\0\0" + XMP)
    return png[:-12] + itxt + png[-12:]


def images():
    """(extension, bytes) pairs."""
    out = [
        ("jpg", image("JPEG", exif=exif_with_gps(), quality=60)),
        ("jpg", image("JPEG", mode="L", quality=50)),
        ("jpg", image("JPEG", size=(16, 16), mode="CMYK", quality=50)),
        ("jpg", image("JPEG", size=(30, 20), progressive=True, quality=50)),
        ("jpg", image("JPEG", size=(500, 8), quality=30)),
        ("png", png_with_metadata()),
        ("png", image("PNG", mode="RGBA")),
        ("png", image("PNG", size=(8, 400), mode="L")),
        ("webp", image("WEBP", exif=exif_with_gps(), quality=50)),
        ("webp", image("WEBP", mode="RGBA", lossless=True)),
        ("gif", image("GIF", mode="L")),
    ]
    for name in sorted(os.listdir(FIXTURES)):
        ext = name.rsplit(".", 1)[-1]
        with open(os.path.join(FIXTURES, name), "rb") as f:
            out.append((ext, f.read()))
    return out


def videos():
    if not shutil.which("ffmpeg"):
        return []
    out = []
    with tempfile.TemporaryDirectory() as tmp:
        for ext, extra in [
            ("mp4", ["-metadata", "location=+35.0116+135.7681/"]),
            ("mov", ["-metadata", "location=+35.0116+135.7681/",
                     "-metadata", "creation_time=2024-05-01T10:00:00Z"]),
            ("mp4", ["-movflags", "+faststart"]),
        ]:
            path = os.path.join(tmp, f"v.{ext}")
            subprocess.run(
                ["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                 "testsrc=size=32x24:rate=5:duration=0.4", "-c:v", "mjpeg",
                 *extra, path], check=True)
            with open(path, "rb") as f:
                out.append((ext, f.read()))
    return out


# Request targets for the HTTP target; ids are those of the template library.
HTTP = [
    ("GET", "/api/health"), ("GET", "/api/setup"), ("GET", "/api/auth/me"),
    ("POST", '/api/auth/login\n{"username":"admin","password":"admin-password"}'),
    ("POST", "/api/auth/logout"),
    ("POST", '/api/auth/password\n{"current":"admin-password","new":"another-password"}'),
    ("PUT", '/api/auth/show-hidden\n{"show_hidden":true}'),
    ("GET", "/api/users"),
    ("POST", '/api/users\n{"username":"x","password":"longenough1","role":"editor","folders":[]}'),
    ("PATCH", '/api/users/2\n{"role":"admin","disabled":true}'), ("DELETE", "/api/users/2"),
    ("GET", "/api/library/status"), ("GET", "/api/settings/roots"),
    ("POST", '/api/settings/roots\n{"path":"/"}'), ("DELETE", '/api/settings/roots\n{"path":"/"}'),
    ("GET", "/api/settings/directories?path=/"), ("GET", "/api/albums"),
    ("GET", "/api/folders/filter?q=tr"), ("GET", "/api/folders/1"),
    ("GET", "/api/folders/2/media?offset=0&limit=10&sort=name"),
    ("GET", "/api/media/batch?ids=1,2,3"), ("GET", "/api/media/1"),
    ("GET", "/api/media/1/preview/thumb"), ("GET", "/api/media/4/preview/large"),
    ("GET", "/api/media/1/file"), ("PUT", "/api/media/1/favorite"),
    ("DELETE", "/api/media/1/favorite"), ("POST", "/api/media/2/trash"),
    ("PUT", "/api/media/1/hidden"), ("DELETE", "/api/folders/4/hidden"),
    ("GET", "/api/favorites"), ("GET", "/api/trash"), ("DELETE", "/api/trash"),
    ("POST", "/api/trash/1/restore"), ("DELETE", "/api/trash/1"),
    ("GET", "/api/trash/1/preview/thumb"), ("GET", "/api/trash/1/file"),
    ("GET", "/api/storage"), ("GET", "/api/explore"), ("GET", "/api/explore/items?kind=camera&value=x"),
    ("GET", "/api/map/points?south=-90&west=-180&north=90&east=180"),
    ("GET", "/api/shares"),
    ("POST", '/api/shares\n{"media_id":1,"expires_in_days":3,"password":"pw","allow_download":false}'),
    ("PATCH", '/api/shares/1\n{"expires_in_days":null}'), ("DELETE", "/api/shares/1"),
    ("GET", "/api/s/{share}"), ("POST", '/api/s/{share}/unlock\n{"password":"pw"}'),
    ("GET", "/api/s/{share}/folder?folder=2"), ("GET", "/api/s/{share}/media/2"),
    ("GET", "/api/s/{share}/media/2/preview/thumb"), ("GET", "/api/s/{share}/media/2/file"),
    ("GET", "/api/search?q=day%202&offset=0&limit=5"),
    ("GET", "/api/timeline?before=9999999999&limit=50"), ("GET", "/api/timeline/months"),
    ("GET", "/api/files"), ("GET", "/api/files/2"), ("GET", "/"), ("GET", "/timeline"),
]
METHODS = ["GET", "POST", "PATCH", "DELETE", "PUT", "HEAD"]


def write(target, name, data):
    path = os.path.join(CORPUS, target)
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, name), "wb") as f:
        f.write(data)


def main():
    files = images() + videos()
    for i, (ext, data) in enumerate(files):
        write("metadata", f"{i:02}-{ext}", bytes([EXTENSIONS.index(ext)]) + data)
        if ext in PREVIEW_EXTENSIONS:
            # Every orientation and both preview sizes across the seeds.
            flags = PREVIEW_EXTENSIONS.index(ext) | (i & 1) << 3 | (i % 9) << 4
            write("preview", f"{i:02}-{ext}", bytes([flags]) + data)
        is_video = ext in ("mp4", "mov", "m4v")
        write("location", f"{i:02}-{ext}", bytes([1 if is_video else 0]) + data)
        if ext == "jxl":
            write("jxl", f"{i:02}", data)
    write("location_apply", "00", bytes([200, 3, 40, 90, 10, 0, 20, 30, 0, 5, 100, 1, 64]))
    for i, (method, request) in enumerate(HTTP):
        for session in range(3):
            first = METHODS.index(method) + 16 * session
            write("http", f"{i:02}-{session}", bytes([first]) + request.encode())
    print(f"wrote seeds to {CORPUS}")


if __name__ == "__main__":
    main()

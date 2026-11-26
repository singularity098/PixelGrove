package app.pixelg.thumbs

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.pixelg.core.Native
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * Loads previews for the grid and the viewer.
 *
 * 1. A bitmap cache in memory.
 * 2. The engine (Rust): its thumbnail cache on disk, memory-mapped; otherwise it makes
 *    one, from the EXIF preview or a DCT-scaled decode, in parallel, and keeps it.
 * 3. What the engine doesn't decode (HEIC, AVIF, video frames) comes from Android's own
 *    decoders, and the result goes back into the engine's disk cache, so it's quick
 *    next time too.
 */
object Thumbnails {
    const val THUMB = "thumb"
    const val LARGE = "large"

    // Sized from the phone's RAM (see [init]); 96 MB held only ~140 grid thumbnails, so
    // scrolling a few screens away and back meant decoding everything again.
    private var memory = cache(96)

    private fun cache(mb: Int) = object : LruCache<String, ImageBitmap>(mb * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** About 1/32 of the RAM: 128 MB on a 4 GB phone, 256 MB on 8 GB (at most 384 MB). */
    fun init(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        if (am.isLowRamDevice) return
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        memory = cache((info.totalMem / 32 / (1024 * 1024)).toInt().coerceIn(96, 384))
    }

    /** Gives memory back when the system asks (in the background, or when it runs low). */
    fun trim(level: Int) {
        @Suppress("DEPRECATION")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> memory.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> memory.trimToSize(memory.maxSize() / 4)
        }
    }

    // A few at a time: each holds a decoder's working memory, and the engine has its
    // own limit anyway. More would only queue in there while delaying visible tiles.
    private val decoding = Dispatchers.IO.limitedParallelism(4)

    private fun key(id: Long, variant: String, trash: Boolean, v: Long) = "$id/$variant/$trash/$v"

    fun cached(id: Long, variant: String, trash: Boolean, v: Long): ImageBitmap? =
        memory.get(key(id, variant, trash, v))

    suspend fun load(id: Long, kind: String, variant: String, trash: Boolean, v: Long): ImageBitmap? {
        val key = key(id, variant, trash, v)
        memory.get(key)?.let { return it }
        return withContext(decoding) {
            memory.get(key)?.let { return@withContext it }
            val bitmap = try {
                fromEngine(id, kind, variant, trash)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            bitmap?.also { memory.put(key, it) }
        }
    }

    private suspend fun fromEngine(id: Long, kind: String, variant: String, trash: Boolean): ImageBitmap? {
        val jpeg = Native.thumbnail(id, variant, trash)
        if (jpeg != null) {
            if (jpeg.isEmpty()) return null
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg))).asImageBitmap()
        }
        val path = Native.path(id, trash) ?: return null
        val file = File(path)
        val (made, store) = platform(file, kind, variant) ?: return null
        if (store) {
            // Kept as JPEG in the engine's cache: video frames and HEIC are slow to make.
            val out = ByteArrayOutputStream(64 * 1024)
            made.compress(Bitmap.CompressFormat.JPEG, if (variant == THUMB) 80 else 85, out)
            Native.storeThumbnail(id, variant, trash, out.toByteArray())
        }
        return made.asImageBitmap()
    }

    /** Decodes with Android's decoders. Returns the bitmap and whether it's worth keeping. */
    private suspend fun platform(file: File, kind: String, variant: String): Pair<Bitmap, Boolean>? {
        val thumb = variant == THUMB
        if (kind == "video") {
            val size = if (thumb) Size(480, 480) else Size(1920, 1920)
            return cancellable { signal -> ThumbnailUtils.createVideoThumbnail(file, size, signal) } to true
        }
        val heavy = file.extension.lowercase() in setOf("heic", "heif", "avif")
        if (thumb) {
            // Uses the embedded thumbnail when there's one, and applies EXIF orientation.
            val bitmap = cancellable { signal -> ThumbnailUtils.createImageThumbnail(file, Size(480, 480), signal) }
            return bitmap to heavy
        }
        val source = ImageDecoder.createSource(file)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = info.size.width to info.size.height
            val scale = min(1.0, 2048.0 / max(w, h))
            decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return bitmap to heavy
    }

    /** A full-resolution decode for zooming in (capped, so huge panoramas fit in memory). */
    suspend fun original(path: String, maxEdge: Int = 4096): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = min(1.0, maxEdge.toDouble() / max(w, h))
                if (scale < 1.0) decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            }.asImageBitmap()
        }.getOrNull()
    }

    private suspend fun cancellable(block: (CancellationSignal) -> Bitmap): Bitmap =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                continuation.resume(block(signal))
            } catch (e: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }
        }

    fun clear() = memory.evictAll()
}

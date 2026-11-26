package app.pixelg.ui.viewer

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.pixelg.core.Native
import app.pixelg.thumbs.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val MAX_SCALE = 8f

/**
 * A photo that zooms (pinch, double tap) and pans. It shows the 2048 px preview, and
 * switches to the full-resolution original once zoomed in. One finger only pans while
 * zoomed, so swiping between photos still works at normal size.
 */
@Composable
fun ZoomableImage(
    item: app.pixelg.core.MediaItem,
    onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
    /** A playing animation (GIF, animated WebP) shown in place of the still preview. */
    animation: Drawable? = null,
) {
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val thumb = remember(item.id) { Thumbnails.cached(item.id, Thumbnails.THUMB, item.trash, item.v) }
    val large by produceState<ImageBitmap?>(
        Thumbnails.cached(item.id, Thumbnails.LARGE, item.trash, item.v), item.id, item.v,
    ) {
        if (value == null) value = Thumbnails.load(item.id, item.kind, Thumbnails.LARGE, item.trash, item.v)
    }
    var original by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    val wantOriginal = scale > 1.6f
    LaunchedEffect(wantOriginal, item.id) {
        if (wantOriginal && original == null && animation == null) {
            Native.path(item.id, item.trash)?.let { original = Thumbnails.original(it) }
        }
    }
    LaunchedEffect(scale > 1.01f) { onZoomed(scale > 1.01f) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1) / 2
        val maxY = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun animateTo(targetScale: Float, focus: Offset) {
        val startScale = scale
        val startOffset = offset
        val center = Offset(size.width / 2f, size.height / 2f)
        val targetOffset = if (targetScale == 1f) Offset.Zero
        else clamp((center - focus) * (targetScale - 1), targetScale)
        scope.launch {
            animate(0f, 1f) { t, _ ->
                scale = startScale + (targetScale - startScale) * t
                offset = startOffset + (targetOffset - startOffset) * t
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos -> animateTo(if (scale > 1.01f) 1f else 2.5f, pos) },
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1.01f) {
                            val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            val centroid = event.calculateCentroid()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Keep the point under the fingers in place while zooming.
                            val focus = if (centroid == Offset.Unspecified) center else centroid
                            val zoomed = (offset - (focus - center)) * (newScale / scale) + (focus - center)
                            scale = newScale
                            offset = clamp(zoomed + pan, newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val layer = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        if (animation != null) {
            AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { it.setImageDrawable(animation) },
                modifier = layer,
            )
        } else {
            val bitmap = (if (wantOriginal) original else null) ?: large ?: thumb
            bitmap?.let { Image(it, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = layer) }
        }
    }
}

/** GIFs and WebPs may be animated; Android's decoder tells which really are. */
fun isAnimatable(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("gif", "webp")

/**
 * Decodes an animated GIF or WebP for playing, scaled so its longer side is at most
 * `maxEdge` (or, with `cover`, so its shorter side is about that: grid tiles). Returns
 * null for stills, and for files Android can't decode.
 */
suspend fun loadAnimation(path: String, maxEdge: Int, cover: Boolean = false): AnimatedImageDrawable? =
    withContext(Dispatchers.IO) {
        runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val edge = if (cover) min(w, h) else max(w, h)
                val scale = min(1.0, maxEdge.toDouble() / edge)
                if (scale < 1.0) decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            } as? AnimatedImageDrawable
        }.getOrNull()
    }

/** Plays an animation for as long as it's on screen. */
@Composable
fun rememberAnimation(item: app.pixelg.core.MediaItem, maxEdge: Int, cover: Boolean = false): AnimatedImageDrawable? {
    val animation by produceState<AnimatedImageDrawable?>(null, item.id, item.v) {
        value = Native.path(item.id, item.trash)?.let { loadAnimation(it, maxEdge, cover) }
    }
    DisposableEffect(animation) {
        animation?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
        onDispose { animation?.stop() }
    }
    return animation
}

/**
 * An animated GIF or WebP in the viewer: the still preview first, then the animation
 * once decoded, zoomable like any photo. (The previews themselves are stills.)
 */
@Composable
fun AnimatedImage(
    item: app.pixelg.core.MediaItem,
    onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
) {
    ZoomableImage(item, onTap, onZoomed, animation = rememberAnimation(item, 2048))
}

/**
 * A video, played with Media3 (ExoPlayer), which handles MP4, MOV, WebM and MKV
 * directly, so nothing needs repackaging as it does for browsers.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(item: app.pixelg.core.MediaItem, playing: Boolean, onEnded: (() -> Unit)? = null) {
    val context = LocalContext.current
    val path = remember(item.id) { Native.path(item.id, item.trash) }
    if (!playing || path == null) {
        ZoomableImage(item, onTap = {}, onZoomed = {})
        return
    }
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) onEnded?.invoke()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
        modifier = Modifier.fillMaxSize(),
    )
}

package app.pixelg.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.pixelg.core.Library
import app.pixelg.core.MediaItem
import app.pixelg.core.Prefs
import app.pixelg.thumbs.Thumbnails
import app.pixelg.ui.LocalNav
import app.pixelg.ui.components.PagedMedia
import app.pixelg.ui.components.offsetPaging
import kotlinx.coroutines.delay

private val intervals = listOf(3, 5, 10, 20, 60)

/**
 * Full-screen slideshow with a cross-fade, starting at the photo it was opened on.
 * Settings (remembered on this device): how long each photo shows, shuffle, whether
 * videos play (to the end), and whether a folder's subfolders are included. The next
 * photo is decoded before it's shown, and the screen stays on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideshowScreen() {
    val nav = LocalNav.current
    val source = ViewerSession.list ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val folderId = ViewerSession.folderId
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Immersive(true)
    BackHandler { nav.popBackStack() }

    // With "include subfolders", a folder's slideshow goes through its whole subtree.
    val list = remember(Prefs.slideSubfolders, folderId) {
        if (Prefs.slideSubfolders && folderId != null) {
            PagedMedia(fetch = offsetPaging { o, l -> Library.folderMedia(folderId, o, l, recursive = true) })
        } else source
    }
    LaunchedEffect(list) { if (!list.loaded) list.loadMore() }

    // The order shown: in sequence, or shuffled without repeats until all were shown.
    val history = remember(list, Prefs.slideShuffle) { mutableStateListOf<Long>() }
    var position by remember(list, Prefs.slideShuffle) { mutableIntStateOf(0) }
    var startIndex by remember { mutableIntStateOf(ViewerSession.index) }
    var paused by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    fun eligible(item: MediaItem) = Prefs.slideVideos || !item.isVideo

    suspend fun nextItem(): MediaItem? {
        val items = list.items
        if (Prefs.slideShuffle) {
            if (list.more && items.size < 2000) list.loadMore()
            val unseen = items.filter { eligible(it) && it.id !in history }
            val pick = unseen.randomOrNull() ?: run {
                history.clear()
                items.filter(::eligible).randomOrNull()
            }
            return pick
        }
        var i = (history.lastOrNull()?.let { id -> items.indexOfFirst { it.id == id } } ?: (startIndex - 1)) + 1
        while (true) {
            if (i >= items.size) {
                if (list.more && list.loadMore()) continue
                i = 0
            }
            val item = items.getOrNull(i) ?: return null
            if (eligible(item)) return item
            i++
            if (i > items.size * 2) return null
        }
    }

    var current by remember { mutableStateOf<MediaItem?>(null) }
    var videoEnded by remember { mutableStateOf(false) }

    suspend fun advance(forward: Boolean) {
        if (!forward) {
            if (position > 0) {
                position--
                current = list.items.firstOrNull { it.id == history[position] }
            }
            return
        }
        if (position < history.size - 1) {
            position++
            current = list.items.firstOrNull { it.id == history[position] }
            return
        }
        val next = nextItem() ?: return
        // Decode it before showing it, so the fade never shows a blank.
        if (!next.isVideo) Thumbnails.load(next.id, next.kind, Thumbnails.LARGE, false, next.v)
        history.add(next.id)
        position = history.size - 1
        current = next
        videoEnded = false
    }

    LaunchedEffect(list.loaded, Prefs.slideShuffle, list) {
        if (list.loaded && current == null) {
            if (!Prefs.slideShuffle) startIndex = startIndex.coerceIn(0, (list.items.size - 1).coerceAtLeast(0))
            advance(true)
        }
    }
    // The timer: photos for the chosen time; videos until they end.
    LaunchedEffect(current?.id, paused, videoEnded, Prefs.slideSeconds) {
        val item = current ?: return@LaunchedEffect
        if (paused) return@LaunchedEffect
        if (item.isVideo && !videoEnded) return@LaunchedEffect
        if (!item.isVideo) delay(Prefs.slideSeconds * 1000L)
        advance(true)
    }

    var pendingStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(pendingStep) {
        if (pendingStep != 0) {
            advance(pendingStep > 0)
            pendingStep = 0
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) }
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -80) pendingStep = 1 else if (total > 80) pendingStep = -1 },
                ) { _, amount -> total += amount }
            },
    ) {
        Crossfade(current, animationSpec = tween(700), label = "slide") { item ->
            if (item != null) {
                if (item.isVideo) {
                    VideoPlayer(item, playing = true, onEnded = { videoEnded = true })
                } else {
                    val bitmap by produceState<ImageBitmap?>(Thumbnails.cached(item.id, Thumbnails.LARGE, false, item.v), item.id) {
                        if (value == null) value = Thumbnails.load(item.id, item.kind, Thumbnails.LARGE, false, item.v)
                    }
                    bitmap?.let { Image(it, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
            }
        }
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0x88000000)).statusBarsPadding().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.Close, "End slideshow", tint = Color.White) }
                    Text(current?.name ?: "", color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "Slideshow settings", tint = Color.White) }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)
                        .background(Color(0x88000000), MaterialTheme.shapes.extraLarge),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = { pendingStep = -1 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", tint = Color.White) }
                    IconButton(onClick = { paused = !paused }) {
                        Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "Play" else "Pause", tint = Color.White)
                    }
                    IconButton(onClick = { pendingStep = 1 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = Color.White) }
                }
            }
        }
    }

    if (settings) {
        ModalBottomSheet(onDismissRequest = { settings = false }) {
            Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Slideshow", style = MaterialTheme.typography.titleLarge)
                Text("Each photo shows for", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    intervals.forEach { s ->
                        FilterChip(Prefs.slideSeconds == s, { Prefs.setSlideSeconds(s) }, label = { Text(if (s < 60) "${s}s" else "1 min") })
                    }
                }
                SettingRow("Shuffle", Prefs.slideShuffle, Prefs::setSlideShuffle)
                SettingRow("Play videos", Prefs.slideVideos, Prefs::setSlideVideos)
                if (folderId != null) SettingRow("Include subfolders", Prefs.slideSubfolders, Prefs::setSlideSubfolders)
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

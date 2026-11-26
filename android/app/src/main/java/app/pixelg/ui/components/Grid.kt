package app.pixelg.ui.components

import android.widget.ImageView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.viewinterop.AndroidView
import app.pixelg.core.Prefs
import app.pixelg.core.Subfolder
import app.pixelg.ui.formatCount
import app.pixelg.ui.viewer.isAnimatable
import app.pixelg.ui.viewer.rememberAnimation
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pixelg.core.Cover
import app.pixelg.core.MediaItem
import app.pixelg.thumbs.Thumbnails
import app.pixelg.ui.formatDay
import app.pixelg.ui.formatDuration
import app.pixelg.ui.localDate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A preview image, loaded through [Thumbnails]. */
@Composable
fun ThumbImage(
    id: Long,
    kind: String,
    v: Long,
    modifier: Modifier = Modifier,
    trash: Boolean = false,
    variant: String = Thumbnails.THUMB,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState(Thumbnails.cached(id, variant, trash, v), id, v, variant, trash) {
        // A photo that has only just been taken can still be being written when its
        // folder first shows it, and fail to decode: try again for a few seconds
        // rather than leave the tile (or the folder's cover) blank until it's reopened.
        for (wait in RETRY_DELAYS) {
            if (value != null) break
            value = Thumbnails.load(id, kind, variant, trash, v)
            if (value == null) delay(wait)
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

private val RETRY_DELAYS = longArrayOf(500, 1_000, 2_000, 4_000, 0)

/** A tile that plays an animated GIF or WebP (when "Play animations in grids" is on). */
@Composable
private fun AnimatedThumb(item: MediaItem, modifier: Modifier) {
    val animation = rememberAnimation(item, maxEdge = 360, cover = true)
    Box(modifier) {
        // The still preview underneath until the animation is decoded (and for stills).
        ThumbImage(item.id, item.kind, item.v, Modifier.fillMaxSize(), trash = item.trash)
        if (animation != null) {
            AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                update = { it.setImageDrawable(animation) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun CoverImage(cover: Cover?, modifier: Modifier = Modifier) {
    if (cover == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        ThumbImage(cover.id, cover.kind, cover.v, modifier)
    }
}

/**
 * One grid tile: the preview, with a length badge for videos and a mark when hidden.
 * While selecting, a check circle in the corner (filled when picked).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTile(
    item: MediaItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selecting: Boolean = false,
    picked: Boolean = false,
) {
    val inset by animateDpAsState(if (picked) 10.dp else 0.dp, label = "picked")
    Box(
        Modifier
            .aspectRatio(1f)
            .background(if (picked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = item.name }
    ) {
        val image = Modifier.fillMaxSize().padding(inset).then(if (item.hidden) Modifier.alpha(0.45f) else Modifier)
        if (Prefs.animateGrid && !item.trash && isAnimatable(item.name)) {
            AnimatedThumb(item, image)
        } else {
            ThumbImage(item.id, item.kind, item.v, image, trash = item.trash)
        }
        if (item.isVideo) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(formatDuration(item.durationMs), color = Color.White, fontSize = 11.sp)
            }
        }
        if (item.hidden) {
            Icon(
                Icons.Outlined.VisibilityOff, "Hidden", tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(16.dp),
            )
        }
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(if (picked) MaterialTheme.colorScheme.primary else Color(0x55000000), CircleShape)
                    .border(2.dp, if (picked) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (picked) Icon(Icons.Filled.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * The photo grid (`LazyVerticalGrid`): loads more as it nears the end, optional day
 * headers (Timeline), and a handle on the right to drag through long lists.
 */
@Composable
fun MediaGrid(
    paged: PagedMedia,
    columns: Int,
    onOpen: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    groupByDay: Boolean = false,
    /** Grid columns when a header needs a finer grid (tiles then span several). */
    gridColumns: Int = columns,
    /** Lets a long press start picking items (for copy, move, trash, delete). */
    selection: MediaSelection? = null,
    empty: @Composable () -> Unit = {},
    header: LazyGridScope.() -> Unit = {},
) {
    val items = paged.items
    @Composable
    fun Tile(index: Int, item: MediaItem) {
        val selecting = selection?.active == true
        MediaTile(
            item,
            onClick = {
                if (selecting) selection!!.toggle(item.id) else onOpen(index)
            },
            onLongClick = selection?.let { s -> { if (s.active) s.toggle(item.id) else s.start(item.id) } },
            selecting = selecting,
            picked = selecting && item.id in selection!!.ids,
        )
    }
    LaunchedEffect(paged) {
        if (!paged.loaded) paged.loadMore()
    }
    // Load the next page when the last loaded rows come into view.
    LaunchedEffect(paged, state) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - columns * 6
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd && paged.more && !paged.loading) paged.loadMore()
        }
    }
    // Once scrolling pauses, decodes the thumbnails of the screenful past each end
    // (ahead first), so tiles are already there when they scroll in or back in.
    LaunchedEffect(paged, state) {
        snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull { it.contentType == "tile" }?.key as? Long
            val last = visible.lastOrNull { it.contentType == "tile" }?.key as? Long
            first to last
        }.distinctUntilChanged().collectLatest { (first, last) ->
            if (first == null || last == null) return@collectLatest
            delay(150)
            val list = items.toList()
            val from = list.indexOfFirst { it.id == first }
            val to = list.indexOfFirst { it.id == last }
            if (from < 0 || to < from) return@collectLatest
            val screen = to - from + 1
            val order = (1..screen).flatMap { listOf(to + it, from - it) }.filter { it in list.indices }
            for (i in order) {
                val item = list[i]
                Thumbnails.load(item.id, item.kind, Thumbnails.THUMB, item.trash, item.v)
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        val span = (gridColumns / columns).coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            header()
            if (groupByDay) {
                var lastDay: java.time.LocalDate? = null
                items.forEachIndexed { index, item ->
                    val day = localDate(item.takenAt)
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                            Text(
                                formatDay(item.takenAt),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                    item(key = item.id, span = { GridItemSpan(span) }, contentType = "tile") {
                        Tile(index, item)
                    }
                }
            } else {
                items(items.size, key = { items[it].id }, span = { GridItemSpan(span) }, contentType = { "tile" }) { index ->
                    Tile(index, items[index])
                }
            }
            if (paged.loading && items.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
        if (paged.loaded && items.isEmpty() && !paged.loading) empty()
        if (!paged.loaded) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        FastScroller(state, Modifier.align(Alignment.TopEnd)) { gridIndex ->
            val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index >= gridIndex && it.contentType == "tile" }
            val item = (info?.key as? Long)?.let { key -> items.firstOrNull { it.id == key } }
            item?.let { formatDay(it.takenAt) }
        }
    }
}

/**
 * A handle on the right edge to drag through a long grid (like the web UI's
 * FastScroll). It appears while scrolling and shows the date being passed.
 */
@Composable
fun FastScroller(state: LazyGridState, modifier: Modifier = Modifier, label: (Int) -> String?) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val total = state.layoutInfo.totalItemsCount
    val visible = state.layoutInfo.visibleItemsInfo.size
    if (total <= visible * 3 || visible == 0) return
    val fraction = if (dragging) dragFraction else {
        state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
    }
    val showing = dragging || state.isScrollInProgress
    BoxWithConstraints(modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        val handle = with(LocalDensity.current) { 48.dp.toPx() }
        val y = (fraction.coerceIn(0f, 1f) * (height - handle)).roundToInt()
        AnimatedVisibility(
            visible = showing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (dragging) {
                    label(state.firstVisibleItemIndex)?.let { text ->
                        Text(
                            text,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset { IntOffset(-140, y) }
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, y) }
                        .padding(end = 2.dp)
                        .width(28.dp)
                        .size(width = 28.dp, height = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .pointerInput(total) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true; dragFraction = fraction },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, amount ->
                                change.consume()
                                dragFraction = (dragFraction + amount / (height - handle)).coerceIn(0f, 1f)
                                val target = (dragFraction * (state.layoutInfo.totalItemsCount - 1)).roundToInt()
                                scope.launch { state.scrollToItem(target) }
                            }
                        },
                ) {
                    Column(Modifier.align(Alignment.Center)) {
                        Box(Modifier.size(width = 12.dp, height = 2.dp).background(MaterialTheme.colorScheme.onPrimary))
                        Box(Modifier.size(4.dp))
                        Box(Modifier.size(width = 12.dp, height = 2.dp).background(MaterialTheme.colorScheme.onPrimary))
                    }
                }
            }
        }
    }
}

/**
 * A folder card: square cover (square corners, like the photo tiles), name and count
 * underneath, or written over the cover's bottom edge with "Folder names on covers".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    name: String,
    detail: String,
    cover: Cover?,
    hidden: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val overlay = Prefs.namesOnCovers
    Column(
        Modifier
            .padding(if (overlay) 2.dp else 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (hidden) Modifier.alpha(0.5f) else Modifier)
    ) {
        Box {
            CoverImage(cover, Modifier.fillMaxWidth().aspectRatio(1f))
            if (hidden) {
                Icon(
                    Icons.Outlined.VisibilityOff, "Hidden", tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
            if (overlay) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC0000000))))
                        .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 6.dp),
                ) {
                    Text(
                        name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Text(detail, maxLines = 1, color = Color(0xDDFFFFFF), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!overlay) {
            Text(
                name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 2.dp, top = 6.dp),
            )
            Text(
                detail, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * A folder's subfolders as a strip of small cards that scrolls sideways (like the web
 * UI), so the photos stay near the top instead of below a grid of big folder covers.
 */
@Composable
fun SubfolderStrip(subfolders: List<Subfolder>, onOpen: (Subfolder) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(subfolders, key = { it.id }) { sub ->
            Row(
                Modifier
                    .widthIn(max = 220.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .clickable { onOpen(sub) }
                    .then(if (sub.hidden) Modifier.alpha(0.5f) else Modifier)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(sub.cover, Modifier.size(44.dp))
                Column(Modifier.padding(start = 10.dp, end = 8.dp)) {
                    Text(
                        sub.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        formatCount(sub.count, "item"), maxLines = 1, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

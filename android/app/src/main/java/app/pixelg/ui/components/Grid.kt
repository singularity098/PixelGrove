package app.pixelg.ui.components

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
        if (value == null) value = Thumbnails.load(id, kind, variant, trash, v)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let {
            Image(it, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
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

/** One grid tile: the preview, with a length badge for videos and a mark when hidden. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTile(item: MediaItem, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = item.name }
    ) {
        ThumbImage(
            item.id, item.kind, item.v,
            Modifier.fillMaxSize().then(if (item.hidden) Modifier.alpha(0.45f) else Modifier),
            trash = item.trash,
        )
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
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp),
            )
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
    onLongPress: ((MediaItem) -> Unit)? = null,
    empty: @Composable () -> Unit = {},
    header: LazyGridScope.() -> Unit = {},
) {
    val items = paged.items
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
                        MediaTile(item, { onOpen(index) }, onLongPress?.let { { it(item) } })
                    }
                }
            } else {
                items(items.size, key = { items[it].id }, span = { GridItemSpan(span) }, contentType = { "tile" }) { index ->
                    val item = items[index]
                    MediaTile(item, { onOpen(index) }, onLongPress?.let { { it(item) } })
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

/** A folder card: square cover, name and count (Photos, folder pages, search). */
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
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (hidden) Modifier.alpha(0.5f) else Modifier)
    ) {
        Box {
            CoverImage(cover, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
            if (hidden) {
                Icon(
                    Icons.Outlined.VisibilityOff, "Hidden", tint = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                )
            }
        }
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

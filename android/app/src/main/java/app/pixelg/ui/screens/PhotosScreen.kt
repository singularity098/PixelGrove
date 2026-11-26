package app.pixelg.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pixelg.core.Album
import app.pixelg.core.Engine
import app.pixelg.core.Library
import app.pixelg.core.Prefs
import app.pixelg.ui.AppBar
import app.pixelg.ui.LocalNav
import app.pixelg.ui.components.ColumnsButton
import app.pixelg.ui.components.EmptyState
import app.pixelg.ui.components.FolderCard
import app.pixelg.ui.formatCount
import kotlinx.coroutines.flow.distinctUntilChanged

private val sorts = listOf("newest" to "Newest first", "oldest" to "Oldest first", "name" to "Name", "count" to "Most items")

/**
 * Photos: every folder with media, Android style, newest first by default; each shows
 * its newest item as the cover.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen() {
    val nav = LocalNav.current
    val albums = remember { mutableStateListOf<Album>() }
    var total by remember { mutableStateOf(-1L) }
    var loading by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    val sort = Prefs.albumSort
    val state = rememberLazyGridState()
    var scanning by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        runCatching {
            val offset = if (reset) 0 else albums.size
            val limit = if (reset) albums.size.coerceIn(200, 500) else 200
            val page = Library.albums(sort, offset, limit)
            if (reset) albums.clear()
            albums.addAll(page.items)
            total = page.total
        }
        loading = false
    }

    LaunchedEffect(sort, Engine.version) {
        load(reset = true)
        scanning = runCatching { Library.status().scanning }.getOrDefault(false)
    }
    LaunchedEffect(state) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= albums.size - 20 }
            .distinctUntilChanged()
            .collect { if (it && total > albums.size) load(reset = false) }
    }

    Scaffold(topBar = {
        AppBar("Photos", subtitle = if (total >= 0) formatCount(total, "folder") else null, actions = {
            Box {
                IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort") }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    sorts.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label, fontWeight = if (id == sort) FontWeight.Bold else null) },
                            onClick = { Prefs.setAlbumSort(id); sortOpen = false },
                        )
                    }
                }
            }
            ColumnsButton(Prefs.albumColumns, listOf(2, 3, 4), Prefs::setAlbumColumns)
        })
    }) { padding ->
        // Pull down to rescan every media folder now, then reload.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    runCatching {
                        Library.rescanAll()
                        val started = System.currentTimeMillis()
                        do {
                            delay(500)
                        } while (Library.status().scanning && System.currentTimeMillis() - started < 120_000)
                    }
                    load(reset = true)
                    Engine.changed()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(Prefs.albumColumns),
                state = state,
                contentPadding = PaddingValues(8.dp),
            ) {
                items(albums, key = { it.id }) { album ->
                    FolderCard(
                        album.name, formatCount(album.count, "item"), album.cover, album.hidden,
                        onClick = { nav.navigate("folder/${album.id}") },
                    )
                }
            }
            if (total == 0L) {
                EmptyState(
                    Icons.Outlined.PhotoLibrary,
                    if (scanning) "Looking for photos…" else "No photos yet",
                    "PixelG shows the photos and videos in DCIM, Pictures, Movies and Download. Add other folders in Settings.",
                )
            } else if (total < 0) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

package app.pixelg.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.pixelg.core.Engine
import app.pixelg.core.FolderHit
import app.pixelg.core.Library
import app.pixelg.core.Prefs
import app.pixelg.ui.AppBar
import app.pixelg.ui.LocalNav
import app.pixelg.ui.components.ColumnsButton
import app.pixelg.ui.components.ConfirmDialog
import app.pixelg.ui.components.EmptyState
import app.pixelg.ui.components.FolderCard
import app.pixelg.ui.components.MediaGrid
import app.pixelg.ui.components.PageResult
import app.pixelg.ui.components.PagedMedia
import app.pixelg.ui.components.SelectionBar
import app.pixelg.ui.components.rememberSelection
import app.pixelg.ui.components.offsetPaging
import app.pixelg.ui.formatCount
import app.pixelg.ui.viewer.ViewerSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen() {
    val nav = LocalNav.current
    val paged = remember { PagedMedia(fetch = offsetPaging { o, l -> Library.favorites(o, l) }) }
    val selection = rememberSelection()
    LaunchedEffect(Engine.version) { if (paged.loaded) paged.reload() }
    Scaffold(topBar = {
        if (selection.active) {
            SelectionBar(selection, paged)
        } else {
            AppBar("Favorites", subtitle = if (paged.total >= 0) formatCount(paged.total.toLong(), "item") else null, actions = {
                ColumnsButton(Prefs.mediaColumns, listOf(2, 3, 4, 5), Prefs::setMediaColumns)
            })
        }
    }) { padding ->
        MediaGrid(
            paged, Prefs.mediaColumns, { ViewerSession.open(nav, paged, it) }, Modifier.padding(padding),
            selection = selection,
            empty = { EmptyState(Icons.Outlined.FavoriteBorder, "No favorites yet", "Tap the heart on a photo to add it here.") },
        )
    }
}

/** Deleted items, restorable for 30 days. */
@Composable
fun TrashScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var confirmEmpty by remember { mutableStateOf(false) }
    val paged = remember {
        PagedMedia { _, offset, limit ->
            val page = Library.trashList(offset, limit)
            PageResult(page.items.map { it.asItem() }, page.total.toInt(), offset + page.items.size < page.total)
        }
    }
    LaunchedEffect(Engine.version) { if (paged.loaded) paged.reload() }
    Scaffold(topBar = {
        AppBar("Trash", subtitle = if (paged.total >= 0) formatCount(paged.total.toLong(), "item") else null, actions = {
            if (paged.count > 0) {
                IconButton(onClick = { confirmEmpty = true }) { Icon(Icons.Outlined.DeleteForever, "Empty trash") }
            }
        })
    }) { padding ->
        MediaGrid(
            paged, Prefs.mediaColumns, { ViewerSession.open(nav, paged, it, trash = true) }, Modifier.padding(padding),
            empty = { EmptyState(Icons.Outlined.Delete, "Trash is empty", "Deleted items stay here for 30 days.") },
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Items are deleted for good 30 days after they were moved here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
    if (confirmEmpty) {
        ConfirmDialog(
            "Empty trash?", "Everything in the trash is deleted from this phone for good.", "Empty trash",
            onDismiss = { confirmEmpty = false },
            onConfirm = {
                scope.launch {
                    runCatching { Library.emptyTrash() }
                    paged.reload()
                    Engine.changed()
                }
            },
        )
    }
}

/** Search names, folders, cameras, dates and places; every word must match. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    var query by rememberSaveable { mutableStateOf("") }
    var folders by remember { mutableStateOf<List<FolderHit>>(emptyList()) }
    var paged by remember { mutableStateOf<PagedMedia?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(query, Engine.version) {
        val q = query.trim()
        if (q.isEmpty()) {
            folders = emptyList(); paged = null
            return@LaunchedEffect
        }
        delay(250)
        val list = PagedMedia { _, offset, limit ->
            val results = Library.search(q, offset, limit)
            if (offset == 0) folders = results.folders
            PageResult(results.media.items, results.media.total.toInt(), offset + results.media.items.size < results.media.total)
        }
        list.loadMore()
        paged = list
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val selection = rememberSelection()
    Scaffold(topBar = {
        val selecting = paged
        if (selection.active && selecting != null) {
            SelectionBar(selection, selecting)
        } else {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            title = {
                TextField(
                    query, { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text("Search: kyoto, 2023, pixel 8…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, "Clear") }
                    },
                )
            },
        )
        }
    }) { padding ->
        val list = paged
        if (list == null) {
            Box(Modifier.padding(padding)) {
                EmptyState(Icons.Outlined.Search, "Search your library", "File and folder names, cameras, dates (2024-05) and places.")
            }
        } else {
            MediaGrid(
                list, Prefs.mediaColumns, { ViewerSession.open(nav, list, it) }, Modifier.padding(padding),
                selection = selection,
                empty = { if (folders.isEmpty()) EmptyState(Icons.Outlined.Search, "Nothing found") },
            ) {
                if (folders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Folders", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                            )
                            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                                items(folders, key = { it.id }) { folder ->
                                    Box(Modifier.width(130.dp)) {
                                        FolderCard(folder.name, formatCount(folder.count, "item"), folder.cover, onClick = {
                                            nav.navigate("folder/${folder.id}")
                                        })
                                    }
                                }
                            }
                            if (list.count > 0) {
                                Text(
                                    formatCount(list.count.toLong(), "photo or video", "photos and videos"),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

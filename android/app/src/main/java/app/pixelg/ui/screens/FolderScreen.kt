package app.pixelg.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import app.pixelg.core.Engine
import app.pixelg.core.FolderDetail
import app.pixelg.core.Library
import app.pixelg.core.Prefs
import app.pixelg.ui.AppBar
import app.pixelg.ui.LocalNav
import app.pixelg.ui.ShowHidden
import app.pixelg.ui.components.ColumnsButton
import app.pixelg.ui.components.EmptyState
import app.pixelg.ui.components.FolderCard
import app.pixelg.ui.components.MediaGrid
import app.pixelg.ui.components.SelectionBar
import app.pixelg.ui.components.SubfolderStrip
import app.pixelg.ui.components.rememberSelection
import app.pixelg.ui.components.PagedMedia
import app.pixelg.ui.components.offsetPaging
import app.pixelg.ui.formatCount
import app.pixelg.ui.viewer.ViewerSession
import kotlinx.coroutines.launch

/**
 * A folder: its subfolders above its photos and videos, newest first. From Photos they
 * are a sideways strip (the photos stay near the top); from File View ([tree]) they are
 * folder boxes like the Photos page. Long-press a photo to select; pull down to rescan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(id: Long, tree: Boolean = false) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf<FolderDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val paged = remember(id) { PagedMedia(fetch = offsetPaging { o, l -> Library.folderMedia(id, o, l) }) }
    val selection = rememberSelection()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(id, Engine.version) {
        runCatching { folder = Library.folder(id) }.onFailure { error = it.message }
        if (paged.loaded) paged.reload()
    }

    val mediaColumns = Prefs.mediaColumns
    val f = folder
    val boxes = tree && f != null && f.subfolders.isNotEmpty()
    // Folder boxes use the Photos page's column count, photos their own: a grid fine
    // enough for both, with each spanning several cells.
    val folderColumns = Prefs.albumColumns
    val gridColumns = if (boxes) lcm(folderColumns, mediaColumns) else mediaColumns
    fun go(target: Long) = nav.navigate(if (tree) "folder/$target?tree=true" else "folder/$target")

    Scaffold(topBar = {
        if (selection.active) {
            SelectionBar(selection, paged, folderId = id, writable = f?.writable ?: true)
        } else {
        AppBar(
            f?.name ?: "",
            subtitle = f?.let { formatCount(it.mediaCount, "item") + if (it.hidden != null) " · Hidden" else "" },
            back = true,
            actions = {
                if (paged.count > 0) {
                    IconButton(onClick = { ViewerSession.slideshow(nav, paged, 0, folderId = id) }) {
                        Icon(Icons.Outlined.Slideshow, "Slideshow")
                    }
                }
                ColumnsButton(mediaColumns, listOf(2, 3, 4, 5), Prefs::setMediaColumns)
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Select items") },
                        enabled = paged.count > 0,
                        onClick = { menu = false; selection.begin() },
                    )
                    val hidden = f?.hidden
                    DropdownMenuItem(
                        text = { Text(if (hidden == "this") "Unhide folder" else "Hide folder") },
                        enabled = f != null && hidden != "parent",
                        onClick = {
                            menu = false
                            scope.launch {
                                runCatching { Library.setFolderHidden(id, hidden != "this") }
                                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                                Engine.changed()
                                if (hidden != "this" && !ShowHidden.on) nav.popBackStack()
                            }
                        },
                    )
                }
            },
        )
        }
    }) { padding ->
        // Pull down to rescan this folder (and those below it) right away.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    runCatching { Library.rescanFolder(id) }
                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
                    runCatching { folder = Library.folder(id) }
                    paged.reload()
                    Engine.changed()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding),
        ) {
        MediaGrid(
            paged = paged,
            columns = mediaColumns,
            gridColumns = gridColumns,
            selection = selection,
            onOpen = { ViewerSession.open(nav, paged, it, folderId = id) },
            empty = {
                if (f != null && f.subfolders.isEmpty()) EmptyState(Icons.Outlined.PhotoLibrary, "Nothing here")
            },
        ) {
            f?.let { folder ->
                if (folder.breadcrumbs.size > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            folder.breadcrumbs.forEachIndexed { i, crumb ->
                                if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(
                                    onClick = { if (crumb.id != id) go(crumb.id) },
                                    enabled = crumb.id != id,
                                ) { Text(crumb.name) }
                            }
                        }
                    }
                }
                if (boxes) {
                    val span = gridColumns / folderColumns
                    items(folder.subfolders.size, key = { "sub-${folder.subfolders[it].id}" }, span = { GridItemSpan(span) }) { i ->
                        val sub = folder.subfolders[i]
                        FolderCard(sub.name, formatCount(sub.count, "item"), sub.cover, sub.hidden, onClick = { go(sub.id) })
                    }
                    if (paged.items.isNotEmpty()) {
                        item(key = "subfolders-end", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
                    }
                } else if (folder.subfolders.isNotEmpty()) {
                    item(key = "subfolders", span = { GridItemSpan(maxLineSpan) }) {
                        SubfolderStrip(folder.subfolders) { go(it.id) }
                    }
                }
            }
            error?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
        }
        }
    }
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

private fun lcm(a: Int, b: Int) = a / gcd(a, b) * b

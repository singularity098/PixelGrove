package app.pixelg.ui.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.pixelg.core.Engine
import app.pixelg.core.Files
import app.pixelg.core.Library
import app.pixelg.core.MediaDetail
import app.pixelg.core.MediaItem
import app.pixelg.core.Native
import app.pixelg.ui.LocalNav
import app.pixelg.ui.ShowHidden
import app.pixelg.ui.components.ConfirmDialog
import app.pixelg.ui.components.FolderPickerDialog
import app.pixelg.ui.formatBytes
import app.pixelg.ui.formatDateTime
import app.pixelg.ui.formatDuration
import app.pixelg.ui.formatDay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Hides the status and navigation bars while `immersive`. */
@Composable
fun Immersive(immersive: Boolean) {
    val view = LocalView.current
    DisposableEffect(immersive) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** The photo and video viewer: swipe between items, zoom, play, and act on them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen() {
    val nav = LocalNav.current
    val list = ViewerSession.list ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val trash = ViewerSession.trash
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = list.items
    val pager = rememberPagerState(ViewerSession.index.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var controls by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var info by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val current: MediaItem? = items.getOrNull(pager.currentPage)

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    Immersive(!controls)
    BackHandler { nav.popBackStack() }

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.distinctUntilChanged().collect { page ->
            ViewerSession.index = page
            detail = null
            if (page >= items.size - 3 && list.more) list.loadMore()
        }
    }
    LaunchedEffect(current?.id) {
        val item = current ?: return@LaunchedEffect
        if (!item.trash) detail = runCatching { Library.detail(item.id) }.getOrNull()
    }

    fun toast(message: String?) = Toast.makeText(context, message ?: "Something went wrong.", Toast.LENGTH_SHORT).show()
    fun removed(id: Long) {
        list.remove(id)
        Engine.changed()
    }
    fun act(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { toast(it.message) } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { items.getOrNull(it)?.id ?: it },
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                Box(Modifier.fillMaxSize()) {
                    VideoPlayer(item, playing = page == pager.currentPage)
                }
            } else {
                ZoomableImage(item, onTap = { controls = !controls }, onZoomed = { zoomed = it })
            }
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current?.name ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(
                            current?.let { formatDay(it.takenAt) },
                            if (list.count > 1) "${pager.currentPage + 1} of ${"%,d".format(list.count)}" else null,
                            if (detail?.hidden != null) "Hidden" else null,
                        ).joinToString(" · "),
                        color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    )
                }
                val item = current
                if (item != null && trash) {
                    IconButton(onClick = {
                        act {
                            Library.restore(item.id)
                            toast("Restored")
                            removed(item.id)
                        }
                    }) { Icon(Icons.Outlined.Restore, "Restore", tint = Color.White) }
                    IconButton(onClick = { confirm = "forever" }) { Icon(Icons.Outlined.DeleteForever, "Delete forever", tint = Color.White) }
                } else if (item != null) {
                    val d = detail
                    IconButton(onClick = {
                        if (d != null) act {
                            Library.setFavorite(d.id, !d.favorite)
                            detail = d.copy(favorite = !d.favorite)
                            Engine.changed()
                        }
                    }, enabled = d != null) {
                        Icon(if (d?.favorite == true) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favorite", tint = Color.White)
                    }
                    IconButton(onClick = {
                        if (d != null) act { Files.share(context, d.id, d.path, d.name, d.kind) }
                    }, enabled = d != null) { Icon(Icons.Outlined.Share, "Share", tint = Color.White) }
                    IconButton(onClick = { if (d != null) act { Files.edit(context, d.path, d.kind) } }, enabled = d != null) {
                        Icon(Icons.Outlined.Edit, "Edit", tint = Color.White)
                    }
                    IconButton(onClick = { info = true }, enabled = d != null) { Icon(Icons.Outlined.Info, "Info", tint = Color.White) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = Color.White) }
                        DropdownMenu(menu, { menu = false }) {
                            MenuEntry("Copy or move to folder…", Icons.AutoMirrored.Outlined.DriveFileMove, d != null) { menu = false; moving = true }
                            MenuEntry("Open with…", Icons.AutoMirrored.Outlined.OpenInNew, d != null) {
                                menu = false; if (d != null) act { Files.openWith(context, d.path, d.kind) }
                            }
                            if (!item.isVideo) {
                                MenuEntry("Set as…", Icons.Outlined.Wallpaper, d != null) {
                                    menu = false; if (d != null) act { Files.setAs(context, d.path) }
                                }
                            }
                            if (list.count > 1) {
                                MenuEntry("Slideshow", Icons.Outlined.Slideshow) {
                                    menu = false
                                    nav.popBackStack()
                                    ViewerSession.slideshow(nav, list, pager.currentPage, ViewerSession.folderId)
                                }
                            }
                            MenuEntry(
                                if (d?.hidden != null) "Unhide" else "Hide",
                                if (d?.hidden != null) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                d != null && d.hidden != "folder",
                            ) {
                                menu = false
                                if (d != null) act {
                                    val hide = d.hidden == null
                                    Library.setMediaHidden(d.id, hide)
                                    toast(if (hide) "Hidden" else "No longer hidden")
                                    if (hide && !ShowHidden.on) removed(d.id) else {
                                        detail = d.copy(hidden = if (hide) "photo" else null)
                                        Engine.changed()
                                    }
                                }
                            }
                            MenuEntry("Move to trash", Icons.Outlined.Delete, d != null) {
                                menu = false
                                if (d != null) act {
                                    Library.trash(d.id)
                                    Files.announce(context, d.path)
                                    toast("Moved to trash")
                                    removed(d.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (info) {
        detail?.let { d ->
            ModalBottomSheet(onDismissRequest = { info = false }) { InfoPanel(d) }
        }
    }
    if (moving) {
        detail?.let { d ->
            FolderPickerDialog(
                name = d.name,
                currentFolderId = d.folderId,
                onDismiss = { moving = false },
                onPick = { move, folderId, newFolder -> Library.transfer(d.id, move, folderId, newFolder) },
                onDone = { move, placed, folder ->
                    moving = false
                    val newPath = placed.id?.let { Native.path(it, false) }
                    Files.announce(context, *listOfNotNull(d.path.takeIf { move }, newPath).toTypedArray())
                    toast("${if (move) "Moved" else "Copied"} to $folder")
                    if (move) removed(d.id) else Engine.changed()
                },
            )
        }
    }
    if (confirm == "forever") {
        current?.let { item ->
            ConfirmDialog(
                "Delete forever?", "“${item.name}” is deleted from this phone for good.", "Delete",
                onDismiss = { confirm = null },
                onConfirm = { act { Library.deleteForever(item.id); removed(item.id) } },
            )
        }
    }
}

@Composable
private fun MenuEntry(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, enabled = enabled, onClick = onClick)
}

/** Date, file, camera and place details (the web viewer's info panel). */
@Composable
private fun InfoPanel(d: MediaDetail) {
    val context = LocalContext.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Info", style = MaterialTheme.typography.titleLarge)
        Row2("Taken", formatDateTime(d.takenAt) + (d.tzOffset?.let { " (UTC%+d:%02d)".format(it / 3600, kotlin.math.abs(it % 3600) / 60) } ?: ""))
        SelectionContainer { Row2("File", "${d.name}\n${d.path}") }
        Row2(
            "Details",
            listOfNotNull(
                formatBytes(d.size),
                if (d.width != null && d.height != null) "${d.width} × ${d.height}" else null,
                if (d.width != null && d.height != null && d.kind == "image") "%.1f MP".format(d.width * d.height / 1_000_000.0) else null,
                d.durationMs?.let { formatDuration(it) },
            ).joinToString(" · "),
        )
        val camera = listOfNotNull(d.cameraMake, d.cameraModel).joinToString(" ").trim()
        if (camera.isNotEmpty() || d.lens != null) Row2("Camera", listOfNotNull(camera.ifEmpty { null }, d.lens).joinToString("\n"))
        d.description?.let { Row2("Description", it) }
        if (d.latitude != null && d.longitude != null) {
            Row2("Location", "%.5f, %.5f".format(d.latitude, d.longitude))
            TextButton(onClick = {
                val uri = Uri.parse("geo:${d.latitude},${d.longitude}?q=${d.latitude},${d.longitude}")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }) { Text("Open in maps") }
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

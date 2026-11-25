package app.pixelg.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.os.Environment
import app.pixelg.BuildConfig
import app.pixelg.core.Directories
import app.pixelg.core.Engine
import app.pixelg.core.Library
import app.pixelg.core.LibraryStatus
import app.pixelg.core.MediaRoot
import app.pixelg.core.Prefs
import app.pixelg.core.ThumbnailCache
import app.pixelg.thumbs.Thumbnails
import app.pixelg.ui.AppBar
import app.pixelg.ui.ShowHidden
import app.pixelg.ui.formatBytes
import app.pixelg.ui.formatCount
import app.pixelg.ui.formatInstant
import app.pixelg.ui.theme.palettes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        content()
    }
    HorizontalDivider()
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<LibraryStatus?>(null) }
    var roots by remember { mutableStateOf<List<MediaRoot>>(emptyList()) }
    var cache by remember { mutableStateOf<ThumbnailCache?>(null) }
    var browsing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        runCatching { status = Library.status() }
        runCatching { roots = Library.roots() }
        runCatching { cache = Library.thumbnailCache() }
    }
    LaunchedEffect(Engine.version) {
        while (true) {
            refresh()
            delay(if (status?.scanning == true) 1000 else 5000)
        }
    }
    fun toast(message: String?) = Toast.makeText(context, message ?: "Done", Toast.LENGTH_SHORT).show()

    Scaffold(topBar = { AppBar("Settings") }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            Section("Library") {
                status?.let { s ->
                    Text("${formatCount(s.images, "photo")} · ${formatCount(s.videos, "video")} · ${formatCount(s.folders, "folder")}")
                    Text(
                        when {
                            s.scanning -> "Scanning…"
                            s.lastFullScanAt != null -> "Last full scan ${formatInstant(s.lastFullScanAt)}" +
                                (s.lastFullScan?.let { " (${it.durationMs} ms)" } ?: "")
                            else -> "Kept up to date as photos change"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (s.watching) "Watching folders for changes" else "Not watching folders (changes are picked up on return to the app)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = {
                    scope.launch { runCatching { app.pixelg.core.Api.call("POST", "/library/rescan") }; delay(300); refresh() }
                }, enabled = status?.scanning != true) { Text("Rescan now") }
            }

            Section("Media folders") {
                roots.forEach { root ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(root.path)
                            Text(
                                if (!root.available) "Not available" else "${formatCount(root.media, "item")} in ${formatCount(root.folders, "folder")}" +
                                    if (!root.writable) " · read-only" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!root.configured) {
                            IconButton(onClick = {
                                scope.launch {
                                    runCatching { roots = Library.removeRoot(root.path) }.onFailure { toast(it.message) }
                                    Engine.changed()
                                }
                            }) { Icon(Icons.Outlined.Close, "Remove") }
                        }
                    }
                }
                Text(
                    "Removing a folder only takes it out of PixelG; the files stay. Put an empty .nomedia file in a folder to leave it out.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { browsing = true }) { Text("Add folder") }
            }

            Section("Privacy") {
                SwitchRow("Show hidden items", "Hidden photos and folders are shown dimmed, and can be unhidden.", ShowHidden.on) { show ->
                    ShowHidden.on = show
                    scope.launch { runCatching { Library.setShowHidden(show) }; Engine.changed() }
                }
                SwitchRow(
                    "Remove location when sharing",
                    "Shared copies leave out GPS from photos and videos, like PixelGrove's share links.",
                    Prefs.stripLocation, Prefs::setStripLocation,
                )
            }

            Section("Thumbnails") {
                cache?.let { c ->
                    Text("${formatBytes(c.size)} of ${formatBytes(c.capacity)} used")
                    Text(
                        "Small JPEGs in the app's cache, read memory-mapped, so grids fill instantly. Made in the background for the whole library.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { Library.clearThumbnailCache() }
                        Thumbnails.clear()
                        refresh()
                        toast("Thumbnail cache cleared")
                    }
                }) { Text("Clear thumbnail cache") }
            }

            Section("Theme") {
                val choices = listOf("system" to "Follow system") + palettes.map { it.id to it.label }
                choices.forEach { (id, label) ->
                    val palette = palettes.firstOrNull { it.id == id }
                    Row(
                        Modifier.fillMaxWidth().clickable { Prefs.setTheme(id) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(Prefs.theme == id, { Prefs.setTheme(id) })
                        Text(label, Modifier.weight(1f))
                        if (palette != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(palette.bg, palette.surface2, palette.accent).forEach {
                                    Box(Modifier.size(16.dp).background(Color(it), CircleShape).border(1.dp, Color(palette.border), CircleShape))
                                }
                            }
                        }
                    }
                }
            }

            Section("About") {
                Text("PixelG ${BuildConfig.VERSION_NAME}")
                Text(
                    "The Android version of PixelGrove. The library engine is the same Rust code as the server, running inside the app: nothing leaves your phone (map tiles come from OpenStreetMap).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (browsing) {
        DirectoryPicker(onDismiss = { browsing = false }) { path ->
            browsing = false
            scope.launch {
                runCatching { roots = Library.addRoot(path) }.onFailure { toast(it.message) }
                Engine.changed()
            }
        }
    }
}

/** Picks a directory on the phone's storage to add as a media folder. */
@Composable
private fun DirectoryPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val start = Environment.getExternalStorageDirectory().absolutePath
    var path by remember { mutableStateOf(start) }
    var listing by remember { mutableStateOf<Directories?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        runCatching { listing = Library.directories(path); error = null }.onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a folder") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val parent = listing?.parent
                    IconButton(onClick = { parent?.let { path = it } }, enabled = parent != null && path != "/") {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                    }
                    Text(path, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(listing?.dirs ?: emptyList(), key = { it }) { name ->
                        Row(
                            Modifier.fillMaxWidth().clickable { path = "${path.trimEnd('/')}/$name" }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp))
                            Text(name, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text("Add this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

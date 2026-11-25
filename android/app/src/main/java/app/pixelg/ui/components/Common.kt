package app.pixelg.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pixelg.core.Library
import app.pixelg.core.Placed
import app.pixelg.core.TreeFolder
import kotlinx.coroutines.launch

@Composable
fun EmptyState(icon: ImageVector, title: String, detail: String? = null) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            detail?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 2, 3 or 4 per row (the web UI's columns button), remembered on this device. */
@Composable
fun ColumnsButton(current: Int, choices: List<Int>, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.GridView, "Columns") }
        DropdownMenu(open, { open = false }) {
            choices.forEach { n ->
                DropdownMenuItem(
                    text = { Text("$n per row", fontWeight = if (n == current) FontWeight.Bold else null) },
                    onClick = { onChange(n); open = false },
                )
            }
        }
    }
}

/**
 * Copy or move a photo or video into another folder of the library: browse the folder
 * tree, optionally name a new folder inside the one picked.
 */
@Composable
fun FolderPickerDialog(
    name: String,
    currentFolderId: Long?,
    onDismiss: () -> Unit,
    onPick: suspend (move: Boolean, folderId: Long, newFolder: String?) -> Placed,
    onDone: (move: Boolean, placed: Placed, folderName: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val trail = remember { mutableStateListOf<TreeFolder>() }
    var folders by remember { mutableStateOf<List<TreeFolder>>(emptyList()) }
    var newFolder by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val here = trail.lastOrNull()

    LaunchedEffect(here?.id) {
        error = null
        folders = runCatching {
            if (here == null) Library.treeTops() else Library.treeChildren(here.id)
        }.getOrElse { error = it.message; emptyList() }
    }

    fun run(move: Boolean) {
        val target = here ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val placed = onPick(move, target.id, newFolder.trim().ifEmpty { null })
                val folderName = if (newFolder.isBlank()) target.name else "${target.name}/${newFolder.trim()}"
                onDone(move, placed, folderName)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy or move “$name”", maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { trail.clear() }, enabled = here != null) { Text("Library") }
                    trail.forEachIndexed { i, folder ->
                        Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(
                            onClick = { while (trail.size > i + 1) trail.removeAt(trail.lastIndex) },
                            enabled = i < trail.lastIndex,
                        ) { Text(folder.name) }
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(folders, key = { it.id }) { folder ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { trail.add(folder); newFolder = "" }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp))
                            Text(
                                folder.name + if (folder.id == currentFolderId) " (current)" else "",
                                Modifier.weight(1f).padding(start = 12.dp),
                            )
                            if (folder.subfolderCount > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                    if (folders.isEmpty()) {
                        item {
                            Text(
                                "No folders inside.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
                if (here != null) {
                    OutlinedTextField(
                        newFolder, { newFolder = it }, singleLine = true,
                        label = { Text("New folder inside “${here.name}” (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("Open the folder to copy or move it into.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { run(false) }, enabled = here != null && !busy) { Text("Copy here") }
                TextButton(
                    onClick = { run(true) },
                    enabled = here != null && !busy && !(here.id == currentFolderId && newFolder.isBlank()),
                ) { Text("Move here") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

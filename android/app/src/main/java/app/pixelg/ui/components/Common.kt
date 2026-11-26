package app.pixelg.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.outlined.CreateNewFolder
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pixelg.core.Library
import app.pixelg.core.BatchResult
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
 * Picks where to copy or move photos and videos: browse the folder tree. "New folder"
 * adds folders that don't exist yet (inside each other, if wanted); they're made only
 * when the copy or move runs, so cancelling leaves nothing behind. Read-only folders
 * can be opened but not picked.
 */
@Composable
fun FolderPickerDialog(
    title: String,
    move: Boolean,
    currentFolderId: Long?,
    onDismiss: () -> Unit,
    onPick: suspend (folderId: Long, newFolder: String?) -> BatchResult,
    onDone: (result: BatchResult, folderName: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val trail = remember { mutableStateListOf<TreeFolder>() }
    var folders by remember { mutableStateOf<List<TreeFolder>>(emptyList()) }
    // New folders opened inside `here`, outermost first; made when the move runs.
    val pending = remember { mutableStateListOf<String>() }
    // The name being typed for a new folder, or null when not naming one.
    var naming by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val here = trail.lastOrNull()

    fun open(to: List<TreeFolder>) {
        if (to != trail.toList()) {
            trail.clear()
            trail.addAll(to)
        }
        pending.clear()
        naming = null
    }

    // Opens the new folder being named (or the existing one, if the name is taken).
    fun addFolder() {
        val name = naming.orEmpty().trim()
        if (name.isEmpty()) {
            naming = null
            return
        }
        if (name.startsWith('.') || name.contains('/') || name.contains('\\')) {
            error = "Folder names can't start with a dot or contain slashes."
            return
        }
        val existing = if (pending.isEmpty()) folders.find { it.name == name } else null
        naming = null
        error = null
        if (existing != null) open(trail + existing) else pending.add(name)
    }

    LaunchedEffect(here?.id) {
        error = null
        folders = runCatching {
            if (here == null) Library.treeTops() else Library.treeChildren(here.id)
        }.getOrElse { error = it.message; emptyList() }
    }

    fun run() {
        val target = here ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val result = onPick(target.id, pending.joinToString("/").ifEmpty { null })
                val folderName = (listOf(target.name) + pending).joinToString("/")
                onDone(result, folderName)
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { open(emptyList()) }, enabled = here != null) { Text("Library") }
                    trail.forEachIndexed { i, folder ->
                        Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(
                            onClick = { open(trail.take(i + 1)) },
                            enabled = i < trail.lastIndex || pending.isNotEmpty(),
                        ) { Text(folder.name) }
                    }
                    pending.forEachIndexed { i, name ->
                        Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(
                            onClick = { while (pending.size > i + 1) pending.removeAt(pending.lastIndex) },
                            enabled = i < pending.lastIndex,
                        ) { Text(name, fontStyle = FontStyle.Italic) }
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(if (pending.isEmpty()) folders else emptyList(), key = { it.id }) { folder ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { open(trail + folder) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp))
                            Text(
                                folder.name + when {
                                    folder.id == currentFolderId -> " (current)"
                                    !folder.writable -> " (read-only)"
                                    else -> ""
                                },
                                Modifier.weight(1f).padding(start = 12.dp),
                                color = if (folder.writable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (folder.subfolderCount > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                        }
                    }
                    if (here?.writable == true) {
                        item(key = "new") {
                            val name = naming
                            if (name == null) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { naming = ""; error = null }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("New folder", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.primary)
                                }
                            } else {
                                val focus = remember { FocusRequester() }
                                LaunchedEffect(Unit) { focus.requestFocus() }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        name, { naming = it }, singleLine = true,
                                        label = { Text("Folder name") },
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { addFolder() }),
                                        modifier = Modifier.weight(1f).focusRequester(focus),
                                    )
                                    TextButton(onClick = { addFolder() }) { Text("Create") }
                                }
                            }
                        }
                    } else if (folders.isEmpty()) {
                        item {
                            Text(
                                "No folders inside.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
                HorizontalDivider()
                when {
                    here == null -> Text("Open the folder to ${if (move) "move" else "copy"} into.", style = MaterialTheme.typography.bodySmall)
                    !here.writable -> Text("“${here.name}” is read-only. Open another folder.", style = MaterialTheme.typography.bodySmall)
                    pending.isNotEmpty() -> Text(
                        "“${pending.last()}” will be made when you ${if (move) "move" else "copy"} here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> {}
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { run() },
                enabled = here != null && here.writable && !busy && !(move && here.id == currentFolderId && pending.isEmpty()),
            ) { Text(if (move) "Move here" else "Copy here") }
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

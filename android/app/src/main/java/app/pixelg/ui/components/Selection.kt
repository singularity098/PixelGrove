package app.pixelg.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.pixelg.core.BatchResult
import app.pixelg.core.Engine
import app.pixelg.core.Files
import app.pixelg.core.Library
import app.pixelg.core.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photos and videos picked for copy, move, trash or delete. A long press on a tile
 * starts it; taps then add or remove items; Back or ✕ ends it.
 */
class MediaSelection {
    val ids = mutableStateSetOf<Long>()
    var active by mutableStateOf(false)
        private set

    val size: Int get() = ids.size

    /** Starts with nothing picked yet (the folder menu's "Select items"). */
    fun begin() {
        active = true
    }

    fun start(id: Long) {
        active = true
        ids.add(id)
    }

    fun toggle(id: Long) {
        if (!ids.remove(id)) ids.add(id)
        if (ids.isEmpty()) clear()
    }

    fun selectAll(all: List<Long>) {
        ids.addAll(all)
    }

    fun clear() {
        ids.clear()
        active = false
    }
}

@Composable
fun rememberSelection() = remember { MediaSelection() }

/** Whether any media folder can take new files; copying is greyed out otherwise. */
object LibraryWritable {
    var on by mutableStateOf(true)

    suspend fun refresh() {
        runCatching { on = Library.status().writable }
    }
}

private fun summary(verb: String, result: BatchResult): String {
    val ok = "$verb ${if (result.done == 1) "1 item" else "%,d items".format(result.done)}"
    if (result.failed.isEmpty()) return ok
    val failed = "%,d couldn't be: %s".format(result.failed.size, result.failed.first().error)
    return if (result.done > 0) "$ok. $failed" else failed
}

/**
 * The top bar while selecting: how many, select all, copy, move, trash, and delete
 * for good. Items that leave the list (moved, trashed, deleted) are dropped from `list`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(
    selection: MediaSelection,
    list: PagedMedia,
    folderId: Long? = null,
    /** False when the page's folder is read-only: no move, trash or delete. */
    writable: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // move = true, copy = false
    var confirm by remember { mutableStateOf<Boolean?>(null) } // forever = true, trash = false
    var busy by remember { mutableStateOf(false) }
    val count = selection.size

    BackHandler { selection.clear() }

    fun toast(message: String?) = Toast.makeText(context, message ?: "Something went wrong.", Toast.LENGTH_LONG).show()

    /** Drops what left the list; the ones that failed stay selected. */
    fun finish(ids: List<Long>, result: BatchResult, gone: Boolean) {
        val failed = result.failed.mapTo(HashSet()) { it.id }
        val done = ids.filter { it !in failed }
        if (gone) done.forEach { list.remove(it) }
        selection.ids.removeAll(done.toSet())
        if (selection.size == 0 || !gone) selection.clear()
        Engine.changed()
    }

    fun remove(forever: Boolean) {
        val ids = selection.ids.toList()
        busy = true
        scope.launch {
            try {
                val paths = withContext(Dispatchers.IO) { ids.mapNotNull { Native.path(it, false) } }
                val result = Library.removeMany(ids, forever)
                Files.announce(context, *paths.toTypedArray())
                finish(ids, result, gone = true)
                toast(summary(if (forever) "Deleted" else "Moved to trash:", result))
            } catch (e: Exception) {
                toast(e.message)
            } finally {
                busy = false
            }
        }
    }

    TopAppBar(
        title = { Text(if (count == 0) "Select items" else "%,d selected".format(count)) },
        navigationIcon = { IconButton(onClick = { selection.clear() }) { Icon(Icons.Outlined.Close, "Cancel selection") } },
        actions = {
            IconButton(onClick = { selection.selectAll(list.items.map { it.id }) }) { Icon(Icons.Outlined.SelectAll, "Select all") }
            IconButton(onClick = { picking = false }, enabled = count > 0 && !busy && LibraryWritable.on) {
                Icon(Icons.Outlined.CreateNewFolder, "Copy to folder")
            }
            IconButton(onClick = { picking = true }, enabled = count > 0 && !busy && writable) {
                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to folder")
            }
            IconButton(onClick = { confirm = false }, enabled = count > 0 && !busy && writable) {
                Icon(Icons.Outlined.Delete, "Move to trash")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy to folder…") },
                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                        enabled = count > 0 && !busy && LibraryWritable.on,
                        onClick = { menu = false; picking = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to folder…") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
                        enabled = count > 0 && !busy && writable,
                        onClick = { menu = false; picking = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete permanently") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) },
                        enabled = count > 0 && !busy && writable,
                        onClick = { menu = false; confirm = true },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )

    picking?.let { move ->
        val ids = selection.ids.toList()
        FolderPickerDialog(
            title = "${if (move) "Move" else "Copy"} ${if (ids.size == 1) "1 item" else "%,d items".format(ids.size)} to…",
            move = move,
            currentFolderId = folderId,
            onDismiss = { picking = null },
            onPick = { folder, newFolder ->
                val sources = if (move) withContext(Dispatchers.IO) { ids.mapNotNull { Native.path(it, false) } } else emptyList()
                val result = Library.transferMany(ids, move, folder, newFolder)
                if (result.done == 0 && result.failed.isNotEmpty()) error(result.failed.first().error)
                val made = withContext(Dispatchers.IO) { result.placed.mapNotNull { p -> p.id?.let { Native.path(it, false) } } }
                Files.announce(context, *(sources + made).toTypedArray())
                result
            },
            onDone = { result, folderName ->
                picking = null
                finish(ids, result, gone = move)
                toast(summary(if (move) "Moved to $folderName:" else "Copied to $folderName:", result))
            },
        )
    }
    confirm?.let { forever ->
        val what = if (count == 1) "1 item" else "%,d items".format(count)
        ConfirmDialog(
            if (forever) "Delete permanently?" else "Move to trash?",
            if (forever) "$what will be deleted from this phone for good. They skip the trash and can't be restored."
            else "$what will go to the trash. You can restore them for 30 days.",
            if (forever) "Delete" else "Move to trash",
            onDismiss = { confirm = null },
            onConfirm = { remove(forever) },
        )
    }
}

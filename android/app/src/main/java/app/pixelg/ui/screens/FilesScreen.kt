package app.pixelg.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pixelg.core.Engine
import app.pixelg.core.FolderHit
import app.pixelg.core.Library
import app.pixelg.core.TreeFolder
import app.pixelg.ui.AppBar
import app.pixelg.ui.LocalNav
import app.pixelg.ui.formatCount
import app.pixelg.ui.formatDay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** File View: the real folder tree (empty folders too), with a filter box. */
@Composable
fun FilesScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val tops = remember { mutableStateListOf<TreeFolder>() }
    val children = remember { mutableStateMapOf<Long, List<TreeFolder>>() }
    val open = remember { mutableStateMapOf<Long, Boolean>() }
    var filter by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<FolderHit>?>(null) }

    LaunchedEffect(Engine.version) {
        runCatching {
            val list = Library.treeTops()
            tops.clear(); tops.addAll(list)
            for (id in open.filterValues { it }.keys) children[id] = Library.treeChildren(id)
        }
    }
    LaunchedEffect(filter) {
        if (filter.isBlank()) { hits = null; return@LaunchedEffect }
        delay(200)
        hits = runCatching { Library.filterFolders(filter.trim()) }.getOrNull()
    }

    fun toggle(folder: TreeFolder) {
        val now = !(open[folder.id] ?: false)
        open[folder.id] = now
        if (now && children[folder.id] == null) {
            scope.launch { runCatching { children[folder.id] = Library.treeChildren(folder.id) } }
        }
    }

    // The visible rows of the tree, depth-first.
    val rows = buildList {
        fun add(list: List<TreeFolder>, depth: Int) {
            for (folder in list) {
                add(folder to depth)
                if (open[folder.id] == true) children[folder.id]?.let { add(it, depth + 1) }
            }
        }
        add(tops, 0)
    }

    Scaffold(topBar = { AppBar("File View") }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                filter, { filter = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Filter folders") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            LazyColumn {
                val found = hits
                if (found != null) {
                    items(found, key = { "hit-${it.id}" }) { hit ->
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.navigate("folder/${hit.id}?tree=true") }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(hit.name)
                                Text(hit.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    items(rows, key = { it.first.id }) { (folder, depth) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate("folder/${folder.id}?tree=true") }
                                .padding(start = (8 + depth * 20).dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
                                .then(if (folder.hidden) Modifier.alpha(0.5f) else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (folder.subfolderCount > 0) {
                                IconButton(onClick = { toggle(folder) }) {
                                    Icon(
                                        if (open[folder.id] == true) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        if (open[folder.id] == true) "Collapse" else "Expand",
                                    )
                                }
                            } else {
                                Spacer(Modifier.width(48.dp))
                            }
                            Icon(if (open[folder.id] == true) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null, Modifier.size(20.dp))
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        formatCount(folder.mediaCount, "item"),
                                        if (folder.totalCount != folder.mediaCount) "${folder.totalCount} with subfolders" else null,
                                        folder.latestTakenAt?.let(::formatDay),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

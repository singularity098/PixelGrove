package app.pixelg.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pixelg.core.Engine
import app.pixelg.core.Library
import app.pixelg.core.Month
import app.pixelg.core.Prefs
import app.pixelg.ui.AppBar
import app.pixelg.ui.LocalNav
import app.pixelg.ui.components.ColumnsButton
import app.pixelg.ui.components.EmptyState
import app.pixelg.ui.components.MediaGrid
import app.pixelg.ui.components.PageResult
import app.pixelg.ui.components.PagedMedia
import app.pixelg.ui.formatMonth
import app.pixelg.ui.formatMonthKey
import app.pixelg.ui.viewer.ViewerSession
import java.time.LocalDate
import java.time.ZoneOffset

/** Seconds at the start of the month after `key` ("2024-05"): where that month ends. */
private fun endOfMonth(key: String): Long? = runCatching {
    LocalDate.parse("$key-01").plusMonths(1).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
}.getOrNull()

/** Every photo and video, newest first, by day, with a list of months to jump to. */
@Composable
fun TimelineScreen() {
    val nav = LocalNav.current
    // Where the list starts: the newest item, or the end of a month jumped to.
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    val paged = remember(start) {
        val from = start?.let(::endOfMonth)
        PagedMedia(pageSize = 200) { loaded, _, limit ->
            val last = loaded.lastOrNull()
            val page = if (last != null) Library.timeline(last.takenAt, last.id, limit) else Library.timeline(from, null, limit)
            PageResult(page.items, null, page.more)
        }
    }
    val state = remember(start) { androidx.compose.foundation.lazy.grid.LazyGridState() }
    var months by remember { mutableStateOf<List<Month>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(Engine.version) {
        runCatching { months = Library.months() }
        if (paged.loaded) paged.reload()
    }

    val first = paged.items.getOrNull(state.firstVisibleItemIndex.coerceAtMost(paged.items.size - 1).coerceAtLeast(0))
    Scaffold(topBar = {
        AppBar("Timeline", subtitle = first?.let { formatMonth(it.takenAt) }, actions = {
            if (months.isNotEmpty()) {
                IconButton(onClick = { picking = true }) { Icon(Icons.Outlined.DateRange, "Jump to month") }
            }
            ColumnsButton(Prefs.mediaColumns, listOf(2, 3, 4, 5), Prefs::setMediaColumns)
        })
    }) { padding ->
        MediaGrid(
            paged = paged,
            columns = Prefs.mediaColumns,
            state = state,
            groupByDay = true,
            modifier = Modifier.padding(padding),
            onOpen = { ViewerSession.open(nav, paged, it) },
            empty = { EmptyState(Icons.Outlined.CalendarMonth, "No photos yet") },
        )
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Jump to") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Text(
                            "Newest", Modifier.fillMaxWidth().clickable { start = null; picking = false }.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(months, key = { it.month }) { month ->
                        Text(
                            "${formatMonthKey(month.month)}  ·  ${month.count}",
                            Modifier.fillMaxWidth().clickable { start = month.month; picking = false }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
}

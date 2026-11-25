package app.pixelg.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pixelg.core.Api
import app.pixelg.core.Engine
import app.pixelg.core.Explore
import app.pixelg.core.Filter
import app.pixelg.core.Group
import app.pixelg.core.Library
import app.pixelg.core.Prefs
import app.pixelg.ui.AppBar
import app.pixelg.ui.LocalNav
import app.pixelg.ui.components.ColumnsButton
import app.pixelg.ui.components.EmptyState
import app.pixelg.ui.components.FolderCard
import app.pixelg.ui.components.MediaGrid
import app.pixelg.ui.components.PagedMedia
import app.pixelg.ui.components.offsetPaging
import app.pixelg.ui.formatCount
import app.pixelg.ui.viewer.ViewerSession
import androidx.compose.foundation.layout.Box

/** Explore: by place, country, camera, year and type, worked out offline. */
@Composable
fun ExploreScreen() {
    val nav = LocalNav.current
    var explore by remember { mutableStateOf<Explore?>(null) }
    LaunchedEffect(Engine.version) { runCatching { explore = Library.explore() } }

    fun open(group: Group) {
        val filter = Api.q(Api.json.encodeToString(Filter.serializer(), group.filter))
        nav.navigate("explore/items?filter=$filter&title=${Api.q(group.label)}")
    }

    Scaffold(topBar = { AppBar("Explore") }) { padding ->
        val e = explore
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                e == null -> CircularProgressIndicator(Modifier.padding(32.dp))
                listOf(e.places, e.countries, e.cameras, e.years, e.kinds).all { it.isEmpty() } ->
                    EmptyState(Icons.Outlined.Explore, "Nothing to explore yet")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    listOf(
                        "Places" to e.places, "Countries" to e.countries, "Cameras" to e.cameras,
                        "Years" to e.years, "Types" to e.kinds,
                    ).filter { it.second.isNotEmpty() }.forEach { (title, groups) ->
                        item(key = title) {
                            Column {
                                Text(
                                    title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    items(groups, key = { it.label + (it.detail ?: "") }) { group ->
                                        Box(Modifier.width(140.dp)) {
                                            FolderCard(
                                                group.label,
                                                listOfNotNull(group.detail, formatCount(group.count, "item")).joinToString(" · "),
                                                group.cover,
                                                onClick = { open(group) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The photos in one Explore group. */
@Composable
fun ExploreItemsScreen(filterJson: String, title: String) {
    val nav = LocalNav.current
    val filter = remember(filterJson) { Api.json.decodeFromString(Filter.serializer(), filterJson) }
    val paged = remember(filterJson) { PagedMedia(fetch = offsetPaging { o, l -> Library.exploreItems(filter, o, l) }) }
    LaunchedEffect(Engine.version) { if (paged.loaded) paged.reload() }
    Scaffold(topBar = {
        AppBar(title, subtitle = if (paged.total >= 0) formatCount(paged.total.toLong(), "item") else null, back = true, actions = {
            ColumnsButton(Prefs.mediaColumns, listOf(2, 3, 4, 5), Prefs::setMediaColumns)
        })
    }) { padding ->
        MediaGrid(paged, Prefs.mediaColumns, { ViewerSession.open(nav, paged, it) }, Modifier.padding(padding))
    }
}

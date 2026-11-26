package app.pixelg.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pixelg.R
import app.pixelg.core.Engine
import app.pixelg.core.Files
import app.pixelg.core.Library
import app.pixelg.core.Volume
import app.pixelg.ui.components.LibraryWritable
import app.pixelg.ui.screens.ExploreItemsScreen
import app.pixelg.ui.screens.ExploreScreen
import app.pixelg.ui.screens.FavoritesScreen
import app.pixelg.ui.screens.FilesScreen
import app.pixelg.ui.screens.FolderScreen
import app.pixelg.ui.screens.MapScreen
import app.pixelg.ui.screens.PhotosScreen
import app.pixelg.ui.screens.SearchScreen
import app.pixelg.ui.screens.SettingsScreen
import app.pixelg.ui.screens.TimelineScreen
import app.pixelg.ui.screens.TrashScreen
import app.pixelg.ui.viewer.SlideshowScreen
import app.pixelg.ui.viewer.ViewerScreen
import kotlinx.coroutines.launch

val LocalNav = compositionLocalOf<NavHostController> { error("no nav") }
val LocalOpenDrawer = compositionLocalOf<() -> Unit> { {} }

/** Whether hidden items are shown (marked); mirrors the user setting in the engine. */
object ShowHidden {
    var on by mutableStateOf(false)
}

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("photos", "Photos", Icons.Outlined.PhotoLibrary),
    Destination("timeline", "Timeline", Icons.Outlined.CalendarMonth),
    Destination("explore", "Explore", Icons.Outlined.Explore),
    Destination("map", "Map", Icons.Outlined.Map),
    Destination("favorites", "Favorites", Icons.Outlined.FavoriteBorder),
    Destination("files", "File View", Icons.Outlined.Folder),
    Destination("trash", "Trash", Icons.Outlined.Delete),
    Destination("settings", "Settings", Icons.Outlined.Settings),
)

@Composable
fun PixelGApp() {
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val fullScreen = route == "viewer" || route == "slideshow"

    LaunchedEffect(Unit) {
        runCatching { ShowHidden.on = Library.me().showHidden }
        LibraryWritable.refresh()
    }

    CompositionLocalProvider(
        LocalNav provides nav,
        LocalOpenDrawer provides { scope.launch { drawer.open() } },
    ) {
        ModalNavigationDrawer(
            drawerState = drawer,
            // On the map a sideways drag pans the map: the drawer's own swipe would catch
            // it first (the map is a View inside Compose). The menu button still opens it.
            gesturesEnabled = !fullScreen && (route != "map" || drawer.isOpen),
            drawerContent = {
                DrawerContent(route) { target ->
                    scope.launch { drawer.close() }
                    if (target != route) {
                        nav.navigate(target) {
                            popUpTo("photos") { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            },
        ) {
            NavHost(nav, startDestination = "photos") {
                composable("photos") { PhotosScreen() }
                composable(
                    "folder/{id}?tree={tree}",
                    listOf(
                        navArgument("id") { type = NavType.LongType },
                        navArgument("tree") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) {
                    FolderScreen(it.arguments!!.getLong("id"), tree = it.arguments!!.getBoolean("tree"))
                }
                composable("timeline") { TimelineScreen() }
                composable("explore") { ExploreScreen() }
                composable(
                    "explore/items?filter={filter}&title={title}",
                    listOf(
                        navArgument("filter") { type = NavType.StringType; defaultValue = "{}" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    ExploreItemsScreen(it.arguments!!.getString("filter")!!, it.arguments!!.getString("title")!!)
                }
                composable("map") { MapScreen() }
                composable("search") { SearchScreen() }
                composable("favorites") { FavoritesScreen() }
                composable("files") { FilesScreen() }
                composable("trash") { TrashScreen() }
                composable("settings") { SettingsScreen() }
                composable("viewer") { ViewerScreen() }
                composable("slideshow") { SlideshowScreen() }
            }
        }
    }
}

@Composable
private fun DrawerContent(route: String?, onGo: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var volumes by remember { mutableStateOf<List<Volume>>(emptyList()) }
    LaunchedEffect(Engine.version) {
        runCatching { volumes = Library.storage() }
    }
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(36.dp))
                Text(
                    "PixelG", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            destinations.forEach { d ->
                NavigationDrawerItem(
                    label = { Text(d.label) },
                    icon = { Icon(d.icon, null) },
                    selected = route == d.route,
                    onClick = { onGo(d.route) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show hidden", Modifier.weight(1f))
                Switch(ShowHidden.on, { show ->
                    ShowHidden.on = show
                    scope.launch {
                        runCatching { Library.setShowHidden(show) }
                        Engine.changed()
                    }
                })
            }
            volumes.forEach { volume ->
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        volume.paths.joinToString(", "), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { if (volume.total > 0) volume.used.toFloat() / volume.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatBytes(volume.free)} free of ${formatBytes(volume.total)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The top bar every page shares: menu (or back), title, the page's own actions, then
 * search and the camera button (opens the phone's camera app).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(
    title: String,
    subtitle: String? = null,
    back: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val nav = LocalNav.current
    val openDrawer = LocalOpenDrawer.current
    val context = LocalContext.current
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            if (back) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            } else {
                IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, "Menu") }
            }
        },
        actions = {
            actions()
            IconButton(onClick = { nav.navigate("search") { launchSingleTop = true } }) {
                Icon(Icons.Outlined.Search, "Search")
            }
            IconButton(onClick = { Files.openCamera(context) }) {
                Icon(Icons.Outlined.PhotoCamera, "Camera")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

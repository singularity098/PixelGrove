package app.pixelg.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.pixelg.BuildConfig
import app.pixelg.core.Engine
import app.pixelg.core.Library
import app.pixelg.core.MapPoint
import app.pixelg.core.Prefs
import app.pixelg.ui.AppBar
import app.pixelg.ui.LocalNav
import app.pixelg.ui.components.EmptyState
import app.pixelg.ui.components.MediaGrid
import app.pixelg.ui.components.PageResult
import app.pixelg.ui.components.PagedMedia
import app.pixelg.ui.formatCount
import app.pixelg.ui.viewer.ViewerSession
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

private fun configureOsmdroid(context: Context) {
    Configuration.getInstance().apply {
        userAgentValue = BuildConfig.APPLICATION_ID
        // Map tiles are cached in the app's own cache, never on shared storage.
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
}

/** Photos with a GPS location, clustered; tap a cluster to see its photos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    var points by remember { mutableStateOf<List<MapPoint>?>(null) }
    var selected by remember { mutableStateOf<List<Long>?>(null) }
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val onAccent = MaterialTheme.colorScheme.onPrimary.toArgb()
    LaunchedEffect(Engine.version) { runCatching { points = Library.mapPoints() } }

    val map = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.0
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(85.0, -85.0, 0)
        }
    }
    val clusters = remember { FolderOverlay().also { map.overlays.add(it) } }
    val bubbles = remember { mutableMapOf<String, BitmapDrawable>() }
    var framed by remember { mutableStateOf(false) }

    fun bubble(count: Int): BitmapDrawable {
        val label = if (count >= 1000) "${count / 1000}k" else count.toString()
        return bubbles.getOrPut(label) {
            val density = context.resources.displayMetrics.density
            val size = ((if (label.length > 2) 44 else 36) * density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0x55000000
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = accent
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2 * density, paint)
            paint.color = onAccent
            paint.textSize = 13 * density
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(label, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
            BitmapDrawable(context.resources, bitmap)
        }
    }

    // Groups points that are close together on screen (64 dp cells) into one marker.
    fun recluster() {
        val all = points ?: return
        val projection = map.projection ?: return
        val box = map.boundingBox
        val cell = 64 * context.resources.displayMetrics.density
        val groups = HashMap<Long, MutableList<MapPoint>>()
        val pixel = android.graphics.Point()
        for (p in all) {
            if (p.lat < box.latSouth || p.lat > box.latNorth) continue
            if (box.lonWest <= box.lonEast && (p.lon < box.lonWest || p.lon > box.lonEast)) continue
            projection.toPixels(GeoPoint(p.lat, p.lon), pixel)
            val key = ((pixel.x / cell).toLong() shl 32) or ((pixel.y / cell).toLong() and 0xffffffffL)
            groups.getOrPut(key) { mutableListOf() }.add(p)
        }
        clusters.items.clear()
        for (group in groups.values) {
            val marker = Marker(map)
            marker.position = GeoPoint(group.sumOf { it.lat } / group.size, group.sumOf { it.lon } / group.size)
            marker.icon = bubble(group.size)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.setOnMarkerClickListener { _, _ ->
                selected = group.sortedByDescending { it.takenAt }.map { it.id }
                true
            }
            clusters.add(marker)
        }
        map.invalidate()
    }

    DisposableEffect(map) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { recluster(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { recluster(); return false }
        }
        map.addMapListener(listener)
        map.onResume()
        onDispose {
            map.removeMapListener(listener)
            map.onPause()
        }
    }
    LaunchedEffect(points) {
        val all = points ?: return@LaunchedEffect
        if (!framed && all.isNotEmpty()) {
            framed = true
            map.post {
                if (all.size == 1) {
                    map.controller.setZoom(12.0)
                    map.controller.setCenter(GeoPoint(all[0].lat, all[0].lon))
                } else {
                    val box = BoundingBox(all.maxOf { it.lat }, all.maxOf { it.lon }, all.minOf { it.lat }, all.minOf { it.lon })
                    map.zoomToBoundingBox(box.increaseByScale(1.3f), false, 48)
                }
                recluster()
            }
        } else {
            recluster()
        }
    }

    Scaffold(topBar = {
        AppBar("Map", subtitle = points?.let { formatCount(it.size.toLong(), "photo with a location", "photos with a location") })
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView({ map }, Modifier.fillMaxSize())
            if (points?.isEmpty() == true) {
                EmptyState(Icons.Outlined.Map, "No photos with a location", "Photos taken with location on show up here.")
            }
            Text(
                "© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
    }

    selected?.let { ids ->
        val list = remember(ids) {
            PagedMedia(pageSize = 300) { _, offset, limit ->
                val slice = ids.drop(offset).take(limit)
                PageResult(if (slice.isEmpty()) emptyList() else Library.batch(slice), ids.size, offset + slice.size < ids.size)
            }
        }
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState()) {
            Text(
                formatCount(ids.size.toLong(), "photo"), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.fillMaxHeight(0.75f)) {
                MediaGrid(list, Prefs.mediaColumns, { ViewerSession.open(nav, list, it) })
            }
        }
    }
}

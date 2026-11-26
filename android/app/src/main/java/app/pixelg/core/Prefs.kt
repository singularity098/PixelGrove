package app.pixelg.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Per-device display preferences (like the web UI's localStorage): theme, columns,
 * sort order, slideshow settings. Everything about the library itself lives in the
 * engine's database.
 */
object Prefs {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("pixelg", Context.MODE_PRIVATE)
        theme = prefs.getString("theme", "system") ?: "system"
        albumColumns = prefs.getInt("albumColumns", 2)
        mediaColumns = prefs.getInt("mediaColumns", 4)
        albumSort = prefs.getString("albumSort", "newest") ?: "newest"
        stripLocation = prefs.getBoolean("stripLocation", true)
        slideSeconds = prefs.getInt("slideSeconds", 5)
        slideShuffle = prefs.getBoolean("slideShuffle", false)
        slideVideos = prefs.getBoolean("slideVideos", true)
        slideSubfolders = prefs.getBoolean("slideSubfolders", false)
        askedLocation = prefs.getBoolean("askedLocation", false)
        namesOnCovers = prefs.getBoolean("namesOnCovers", false)
        animateGrid = prefs.getBoolean("animateGrid", true)
    }

    @set:JvmName("assignTheme")
    var theme by mutableStateOf("system")
        private set
    @set:JvmName("assignAlbumColumns")
    var albumColumns by mutableIntStateOf(2)
        private set
    @set:JvmName("assignMediaColumns")
    var mediaColumns by mutableIntStateOf(4)
        private set
    @set:JvmName("assignAlbumSort")
    var albumSort by mutableStateOf("newest")
        private set
    @set:JvmName("assignStripLocation")
    var stripLocation by mutableStateOf(true)
        private set
    @set:JvmName("assignSlideSeconds")
    var slideSeconds by mutableIntStateOf(5)
        private set
    @set:JvmName("assignSlideShuffle")
    var slideShuffle by mutableStateOf(false)
        private set
    @set:JvmName("assignSlideVideos")
    var slideVideos by mutableStateOf(true)
        private set
    @set:JvmName("assignSlideSubfolders")
    var slideSubfolders by mutableStateOf(false)
        private set
    /** Folder names written over the bottom of their covers instead of underneath. */
    @set:JvmName("assignNamesOnCovers")
    var namesOnCovers by mutableStateOf(false)
        private set
    /** Animated GIFs and WebPs play in grids, not just in the viewer. */
    @set:JvmName("assignAnimateGrid")
    var animateGrid by mutableStateOf(true)
        private set

    fun setTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun setAlbumColumns(value: Int) { albumColumns = value; prefs.edit().putInt("albumColumns", value).apply() }
    fun setMediaColumns(value: Int) { mediaColumns = value; prefs.edit().putInt("mediaColumns", value).apply() }
    fun setAlbumSort(value: String) { albumSort = value; prefs.edit().putString("albumSort", value).apply() }
    fun setStripLocation(value: Boolean) { stripLocation = value; prefs.edit().putBoolean("stripLocation", value).apply() }
    fun setSlideSeconds(value: Int) { slideSeconds = value; prefs.edit().putInt("slideSeconds", value).apply() }
    fun setSlideShuffle(value: Boolean) { slideShuffle = value; prefs.edit().putBoolean("slideShuffle", value).apply() }
    fun setSlideVideos(value: Boolean) { slideVideos = value; prefs.edit().putBoolean("slideVideos", value).apply() }
    fun setSlideSubfolders(value: Boolean) { slideSubfolders = value; prefs.edit().putBoolean("slideSubfolders", value).apply() }
    fun setNamesOnCovers(value: Boolean) { namesOnCovers = value; prefs.edit().putBoolean("namesOnCovers", value).apply() }
    fun setAnimateGrid(value: Boolean) { animateGrid = value; prefs.edit().putBoolean("animateGrid", value).apply() }

    /** MediaStore bookkeeping for [MediaSync]. */
    var syncedGeneration: Long
        get() = prefs.getLong("syncedGeneration", -1)
        set(value) = prefs.edit().putLong("syncedGeneration", value).apply()
    var syncedVersion: String?
        get() = prefs.getString("syncedVersion", null)
        set(value) = prefs.edit().putString("syncedVersion", value).apply()
    var syncedCount: Long
        get() = prefs.getLong("syncedCount", -1)
        set(value) = prefs.edit().putLong("syncedCount", value).apply()

    /** Whether the location permission was asked about (the second onboarding step). */
    @set:JvmName("assignAskedLocation")
    var askedLocation by mutableStateOf(false)
        private set

    fun setAskedLocation() { askedLocation = true; prefs.edit().putBoolean("askedLocation", true).apply() }
}

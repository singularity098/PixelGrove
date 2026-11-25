package app.pixelg.core

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the index current without rescanning the library.
 *
 * While PixelG runs, the engine watches the media folders with inotify (the mechanism
 * behind FileObserver), so new photos appear within a second or two. Changes made while
 * it wasn't running are found through MediaStore's generation numbers (Android 11+):
 * on start and on every return to the app it asks "what changed since generation N?"
 * and rescans just those folders. Deletions don't show up in that query, so a drop in
 * the number of photos and videos triggers a full (stat-only) pass instead. A changed
 * MediaStore version (the volume was reformatted or replaced) does too. A content
 * observer on MediaStore backs up the watcher while the app is open.
 */
object MediaSync {
    private val lock = Mutex()
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    private val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    private val files get() = MediaStore.Files.getContentUri(volume)
    private const val MEDIA = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
        "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    suspend fun sync(context: Context) = lock.withLock {
        withContext(Dispatchers.IO) { runCatching { syncLocked(context) } }
    }

    private fun syncLocked(context: Context) {
        val version = MediaStore.getVersion(context, volume)
        val generation = MediaStore.getGeneration(context, volume)
        val count = count(context, MEDIA, emptyArray())
        val last = Prefs.syncedGeneration
        when {
            Prefs.syncedVersion != version || last < 0 -> Native.fullScan()
            generation > last -> {
                val since = last.toString()
                val dirs = mutableSetOf<String>()
                context.contentResolver.query(
                    files,
                    arrayOf(MediaStore.MediaColumns.DATA),
                    "($MEDIA) AND (${MediaStore.MediaColumns.GENERATION_MODIFIED} > ? OR " +
                        "${MediaStore.MediaColumns.GENERATION_ADDED} > ?)",
                    arrayOf(since, since),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { File(it).parent }?.let(dirs::add)
                    }
                }
                val added = count(context, "($MEDIA) AND ${MediaStore.MediaColumns.GENERATION_ADDED} > ?", arrayOf(since))
                if (Prefs.syncedCount >= 0 && count < Prefs.syncedCount + added) {
                    Native.fullScan()
                } else if (dirs.isNotEmpty()) {
                    Native.rescan(dirs.toTypedArray())
                }
            }
        }
        Prefs.syncedVersion = version
        Prefs.syncedGeneration = generation
        Prefs.syncedCount = count
    }

    private fun count(context: Context, selection: String, args: Array<String>): Long =
        context.contentResolver.query(files, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
            ?.use { it.count.toLong() } ?: 0

    /** Watches MediaStore while the app is in the foreground. */
    fun observe(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                pending?.cancel()
                pending = Engine.scope.launch {
                    delay(1500)
                    sync(app)
                }
            }
        }.also { app.contentResolver.registerContentObserver(files, true, it) }
    }

    fun stopObserving(context: Context) {
        observer?.let { context.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}

package app.pixelg.core

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Starts the engine and tells the UI when the library changed. */
object Engine {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var started by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Bumped when a scan changed the library or the app changed it; screens reload. */
    var version by mutableLongStateOf(0)
        private set

    private var poller: Job? = null

    /** Folders indexed the first time: where phones keep photos and videos. */
    private fun defaultRoots(): Array<String> {
        val storage = Environment.getExternalStorageDirectory()
        return listOf(
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DOWNLOADS,
        ).map { File(storage, it).absolutePath }.toTypedArray()
    }

    suspend fun start(context: Context) {
        if (started) return
        val result = withContext(Dispatchers.IO) {
            // Loading the engine library can fail (an incompatible build): say so on screen
            // rather than crash, which leaves the app closing at every launch.
            try {
                Native.start(
                    File(context.filesDir, "library").absolutePath,
                    File(context.cacheDir, "thumbnails").absolutePath,
                    defaultRoots(),
                    1024,
                )
            } catch (e: LinkageError) {
                "The engine library couldn't be loaded: ${e.message ?: e}"
            }
        }
        if (result != null) {
            error = result
            return
        }
        started = true
    }

    /** Polls the engine's change counter while the app is in the foreground. */
    fun resume() {
        if (!started || poller?.isActive == true) return
        poller = scope.launch {
            var seen = Native.generation()
            while (isActive) {
                delay(1200)
                val now = Native.generation()
                if (now != seen) {
                    seen = now
                    changed()
                }
            }
        }
    }

    fun pause() {
        poller?.cancel()
        poller = null
    }

    /** The app changed something itself (favorites, trash, hiding...). */
    fun changed() {
        version++
    }
}

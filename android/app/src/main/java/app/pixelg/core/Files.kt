package app.pixelg.core

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Handing photos to other apps: camera, share, edit, open with. */
object Files {
    fun mime(path: String, kind: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "jxl" -> "image/jxl"
                "mkv" -> "video/x-matroska"
                "heic", "heif" -> "image/heic"
                else -> if (kind == "video") "video/*" else "image/*"
            }
    }

    /** Opens the default camera app (the camera button in the top bar). */
    fun openCamera(context: Context) {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No camera app found.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The MediaStore URI of a file when MediaStore knows it (editors can then save over
     * it, or next to it), otherwise a FileProvider URI.
     */
    private suspend fun uriFor(context: Context, path: String, kind: String): Uri = withContext(Dispatchers.IO) {
        val collection = if (kind == "video") MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val id = context.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        id?.let { ContentUris.withAppendedId(collection, it) }
            ?: FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    }

    /** "Edit": opens the photo or video in another app's editor. */
    suspend fun edit(context: Context, path: String, kind: String) {
        val uri = uriFor(context, path, kind)
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mime(path, kind))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        start(context, Intent.createChooser(intent, "Edit with"), "No app on this phone can edit this.")
    }

    suspend fun openWith(context: Context, path: String, kind: String) {
        val uri = uriFor(context, path, kind)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime(path, kind))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, Intent.createChooser(intent, "Open with"), "No other app can open this.")
    }

    suspend fun setAs(context: Context, path: String) {
        val uri = uriFor(context, path, "image")
        val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
            setDataAndType(uri, mime(path, "image"))
            putExtra("mimeType", mime(path, "image"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, Intent.createChooser(intent, "Set as"), "No app can use this.")
    }

    /**
     * Shares through the system share sheet. Like PixelGrove's share links, the shared
     * copy has its location removed unless that's turned off in Settings; the copy is
     * a temporary file in the app's cache.
     */
    suspend fun share(context: Context, id: Long, path: String, name: String, kind: String) {
        val uri = withContext(Dispatchers.IO) {
            if (Prefs.stripLocation) {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, name)
                Native.copyWithoutLocation(id, out.absolutePath)?.let { error ->
                    throw IllegalStateException(error)
                }
                FileProvider.getUriForFile(context, "${context.packageName}.files", out)
            } else {
                uriFor(context, path, kind)
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime(path, kind)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, Intent.createChooser(intent, "Share"), "No app to share with.")
    }

    private fun start(context: Context, intent: Intent, missing: String) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
        }
    }

    /** Tells MediaStore (and so other apps) about files PixelG added, moved or removed. */
    fun announce(context: Context, vararg paths: String) {
        MediaScannerConnection.scanFile(context.applicationContext, paths, null, null)
    }
}

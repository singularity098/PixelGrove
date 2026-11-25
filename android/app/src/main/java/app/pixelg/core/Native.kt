package app.pixelg.core

/**
 * PixelGrove's library engine (Rust, `android/core`), running in this process: the
 * same database, scanner, inotify watcher, previews and JSON API as the server.
 */
object Native {
    init {
        System.loadLibrary("pixelg")
    }

    /** Starts the engine once per process; returns null, or an error message. */
    external fun start(dataDir: String, thumbnailDir: String, defaultRoots: Array<String>, thumbnailCacheMb: Int): String?

    /** Calls the JSON API: three ASCII digits of status, then the body. */
    external fun request(method: String, uri: String, body: ByteArray?): ByteArray

    /** A preview as JPEG; null when the app should decode the file itself; empty if gone. */
    external fun thumbnail(id: Long, variant: String, trash: Boolean): ByteArray?

    /** Keeps a preview the app decoded itself, in memory and in the disk cache. */
    external fun storeThumbnail(id: Long, variant: String, trash: Boolean, jpeg: ByteArray)

    /** Where an item (or trashed item) is on disk. */
    external fun path(id: Long, trash: Boolean): String?

    /** Rescans the folders holding these paths, in the background. */
    external fun rescan(paths: Array<String>)

    /** Rescans every media folder (stat only for unchanged files), in the background. */
    external fun fullScan()

    /** Goes up whenever a scan changed the library. */
    external fun generation(): Long

    /** Writes a copy of an item without its location to [out]; null, or an error. */
    external fun copyWithoutLocation(id: Long, out: String): String?
}

package app.pixelg.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// The engine's JSON shapes (see src/api/*.rs in the PixelGrove server).

@Serializable
data class Cover(val id: Long, val kind: String, val v: Long)

@Serializable
data class Album(
    val id: Long,
    val name: String,
    val path: String,
    val count: Long,
    @SerialName("latest_taken_at") val latestTakenAt: Long? = null,
    val cover: Cover? = null,
    val hidden: Boolean = false,
)

@Serializable
data class Paged<T>(val items: List<T>, val total: Long)

@Serializable
data class Crumb(val id: Long, val name: String)

@Serializable
data class Subfolder(
    val id: Long,
    val name: String,
    val count: Long,
    @SerialName("latest_taken_at") val latestTakenAt: Long? = null,
    val cover: Cover? = null,
    val hidden: Boolean = false,
)

@Serializable
data class FolderDetail(
    val id: Long,
    val name: String,
    val path: String,
    @SerialName("media_count") val mediaCount: Long,
    val breadcrumbs: List<Crumb>,
    val hidden: String? = null,
    val subfolders: List<Subfolder>,
)

@Serializable
data class MediaItem(
    val id: Long,
    val name: String,
    val kind: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("taken_at") val takenAt: Long,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val size: Long = 0,
    val v: Long = 0,
    val hidden: Boolean = false,
    /** Set for items in the trash (their previews come from the trash). */
    val trash: Boolean = false,
) {
    val isVideo get() = kind == "video"
}

@Serializable
data class MediaDetail(
    val id: Long,
    @SerialName("folder_id") val folderId: Long,
    val name: String,
    val path: String,
    val kind: String,
    val size: Long,
    @SerialName("modified_at") val modifiedAt: Long,
    @SerialName("taken_at") val takenAt: Long,
    @SerialName("tz_offset") val tzOffset: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("camera_make") val cameraMake: String? = null,
    @SerialName("camera_model") val cameraModel: String? = null,
    val lens: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val description: String? = null,
    val favorite: Boolean = false,
    val hidden: String? = null,
    val v: Long = 0,
)

@Serializable
data class TimelinePage(val items: List<MediaItem>, val more: Boolean)

@Serializable
data class Month(val month: String, val count: Long)

@Serializable
data class FolderHit(val id: Long, val name: String, val path: String, val count: Long, val cover: Cover? = null)

@Serializable
data class SearchResults(val folders: List<FolderHit>, val media: Paged<MediaItem>)

@Serializable
data class Filter(
    val country: String? = null,
    val place: String? = null,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val kind: String? = null,
) {
    fun query(): String = listOfNotNull(
        country?.let { "country=${Api.q(it)}" },
        place?.let { "place=${Api.q(it)}" },
        make?.let { "make=${Api.q(it)}" },
        model?.let { "model=${Api.q(it)}" },
        year?.let { "year=$it" },
        kind?.let { "kind=${Api.q(it)}" },
    ).joinToString("&")
}

@Serializable
data class Group(
    val filter: Filter,
    val label: String,
    val detail: String? = null,
    val count: Long,
    val cover: Cover? = null,
)

@Serializable
data class Explore(
    val places: List<Group>,
    val countries: List<Group>,
    val cameras: List<Group>,
    val years: List<Group>,
    val kinds: List<Group>,
)

@Serializable
data class TrashEntry(
    val id: Long,
    val name: String,
    val kind: String,
    val original: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("taken_at") val takenAt: Long,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val size: Long,
    @SerialName("deleted_at") val deletedAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    val v: Long,
) {
    fun asItem() = MediaItem(
        id = id, name = name, kind = kind, width = width, height = height, takenAt = takenAt,
        durationMs = durationMs, size = size, v = v, trash = true,
    )
}

@Serializable
data class TreeFolder(
    val id: Long,
    val name: String,
    val path: String,
    @SerialName("media_count") val mediaCount: Long,
    @SerialName("total_count") val totalCount: Long,
    @SerialName("subfolder_count") val subfolderCount: Long,
    @SerialName("latest_taken_at") val latestTakenAt: Long? = null,
    val hidden: Boolean = false,
)

@Serializable
data class ScanStats(
    @SerialName("media_added") val mediaAdded: Long = 0,
    @SerialName("media_updated") val mediaUpdated: Long = 0,
    @SerialName("media_removed") val mediaRemoved: Long = 0,
    @SerialName("media_unchanged") val mediaUnchanged: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
)

@Serializable
data class LibraryStatus(
    val folders: Long,
    val images: Long,
    val videos: Long,
    val scanning: Boolean,
    val watching: Boolean,
    @SerialName("last_full_scan_at") val lastFullScanAt: Long? = null,
    @SerialName("last_full_scan") val lastFullScan: ScanStats? = null,
)

@Serializable
data class Volume(val paths: List<String>, val total: Long, val free: Long, val used: Long)

@Serializable
data class MediaRoot(
    val path: String,
    val configured: Boolean,
    val folders: Long,
    val media: Long,
    val available: Boolean,
    val writable: Boolean,
)

@Serializable
data class Directories(val path: String, val parent: String? = null, val dirs: List<String>)

@Serializable
data class ThumbnailCache(val enabled: Boolean, val dir: String? = null, val size: Long, val capacity: Long)

@Serializable
data class Me(val id: Long, val username: String, val role: String, @SerialName("show_hidden") val showHidden: Boolean)

@Serializable
data class Placed(val id: Long? = null, val name: String, @SerialName("folder_id") val folderId: Long? = null)

/** A geotagged item on the map: `[id, lat, lon, taken_at, v, is_video]`. */
data class MapPoint(val id: Long, val lat: Double, val lon: Double, val takenAt: Long, val v: Long, val video: Boolean)

/** Typed calls to the engine's API. */
object Library {
    suspend fun albums(sort: String, offset: Int, limit: Int) =
        Api.get<Paged<Album>>("/albums?sort=$sort&offset=$offset&limit=$limit")

    suspend fun folder(id: Long) = Api.get<FolderDetail>("/folders/$id")

    suspend fun folderMedia(id: Long, offset: Int, limit: Int, recursive: Boolean = false) =
        Api.get<Paged<MediaItem>>("/folders/$id/media?offset=$offset&limit=$limit&recursive=$recursive")

    suspend fun timeline(before: Long?, beforeId: Long?, limit: Int) = Api.get<TimelinePage>(
        "/timeline?limit=$limit" + (before?.let { "&before=$it" } ?: "") + (beforeId?.let { "&before_id=$it" } ?: "")
    )

    suspend fun months() = Api.get<List<Month>>("/timeline/months")

    suspend fun search(q: String, offset: Int, limit: Int) =
        Api.get<SearchResults>("/search?q=${Api.q(q)}&offset=$offset&limit=$limit")

    suspend fun filterFolders(q: String) = Api.get<List<FolderHit>>("/folders/filter?q=${Api.q(q)}&limit=200")

    suspend fun explore() = Api.get<Explore>("/explore")

    suspend fun exploreItems(filter: Filter, offset: Int, limit: Int) =
        Api.get<Paged<MediaItem>>("/explore/items?${filter.query()}&offset=$offset&limit=$limit")

    suspend fun mapPoints(): List<MapPoint> = Api.get<List<JsonArray>>("/map/points").map {
        MapPoint(
            it[0].jsonPrimitive.long, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double,
            it[3].jsonPrimitive.long, it[4].jsonPrimitive.long, it[5].jsonPrimitive.int == 1,
        )
    }

    suspend fun batch(ids: List<Long>) = Api.get<List<MediaItem>>("/media/batch?ids=${ids.joinToString(",")}")

    suspend fun favorites(offset: Int, limit: Int) = Api.get<Paged<MediaItem>>("/favorites?offset=$offset&limit=$limit")

    suspend fun detail(id: Long) = Api.get<MediaDetail>("/media/$id")

    suspend fun setFavorite(id: Long, on: Boolean) = Api.call(if (on) "PUT" else "DELETE", "/media/$id/favorite")

    suspend fun setMediaHidden(id: Long, on: Boolean) = Api.call(if (on) "PUT" else "DELETE", "/media/$id/hidden")

    suspend fun setFolderHidden(id: Long, on: Boolean) = Api.call(if (on) "PUT" else "DELETE", "/folders/$id/hidden")

    suspend fun trash(id: Long) = Api.call("POST", "/media/$id/trash")

    suspend fun transfer(id: Long, move: Boolean, folderId: Long, newFolder: String?) = Api.send<Placed>(
        "POST", "/media/$id/${if (move) "move" else "copy"}",
        Api.obj("folder_id" to folderId, "new_folder" to newFolder?.takeIf { it.isNotBlank() }),
    )

    suspend fun trashList(offset: Int, limit: Int) = Api.get<Paged<TrashEntry>>("/trash?offset=$offset&limit=$limit")

    suspend fun restore(id: Long) = Api.call("POST", "/trash/$id/restore")

    suspend fun deleteForever(id: Long) = Api.call("DELETE", "/trash/$id")

    suspend fun emptyTrash() = Api.call("DELETE", "/trash")

    suspend fun treeTops() = Api.get<List<TreeFolder>>("/files")

    suspend fun treeChildren(id: Long) = Api.get<List<TreeFolder>>("/files/$id")

    suspend fun status() = Api.get<LibraryStatus>("/library/status")

    suspend fun storage() = Api.get<List<Volume>>("/storage")

    suspend fun roots() = Api.get<List<MediaRoot>>("/settings/roots")

    suspend fun addRoot(path: String) = Api.send<List<MediaRoot>>("POST", "/settings/roots", Api.obj("path" to path))

    suspend fun removeRoot(path: String) = Api.send<List<MediaRoot>>("DELETE", "/settings/roots", Api.obj("path" to path))

    suspend fun directories(path: String) = Api.get<Directories>("/settings/directories?path=${Api.q(path)}")

    suspend fun thumbnailCache() = Api.get<ThumbnailCache>("/settings/thumbnail-cache")

    suspend fun clearThumbnailCache() = Api.call("DELETE", "/settings/thumbnail-cache")

    suspend fun me() = Api.get<Me>("/auth/me")

    suspend fun setShowHidden(show: Boolean) = Api.call("PUT", "/auth/show-hidden", Api.obj("show" to show))
}

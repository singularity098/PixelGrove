package app.pixelg.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.pixelg.core.MediaItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One page of media: the items, the total when known, and whether there's more. */
class PageResult(val items: List<MediaItem>, val total: Int?, val more: Boolean)

/**
 * A list of media loaded page by page (grids, the viewer and slideshows share it, so
 * paging through the viewer keeps loading the grid's list).
 */
class PagedMedia(
    private val pageSize: Int = 150,
    private val fetch: suspend (loaded: List<MediaItem>, offset: Int, limit: Int) -> PageResult,
) {
    val items = mutableStateListOf<MediaItem>()
    var total by mutableIntStateOf(-1)
        private set
    var more by mutableStateOf(true)
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val mutex = Mutex()

    /** How many items there are in all (as far as known). */
    val count: Int get() = if (total >= 0) total else items.size

    /** Loads the next page; returns whether anything was added. */
    suspend fun loadMore(): Boolean = mutex.withLock {
        if (!more) return false
        loading = true
        try {
            val page = fetch(items.toList(), items.size, pageSize)
            val seen = items.mapTo(HashSet()) { it.id }
            items.addAll(page.items.filter { it.id !in seen })
            total = page.total ?: -1
            more = page.more && page.items.isNotEmpty()
            error = null
            page.items.isNotEmpty()
        } catch (e: Exception) {
            error = e.message
            false
        } finally {
            loading = false
            loaded = true
        }
    }

    /** Loads again from the start (after the library changed), keeping as many items. */
    suspend fun reload() = mutex.withLock {
        try {
            val limit = items.size.coerceIn(pageSize, 500)
            val page = fetch(emptyList(), 0, limit)
            items.clear()
            items.addAll(page.items)
            total = page.total ?: -1
            more = page.more && page.items.isNotEmpty()
            error = null
        } catch (e: Exception) {
            error = e.message
        } finally {
            loaded = true
        }
    }

    fun remove(id: Long) {
        if (items.removeAll { it.id == id } && total > 0) total--
    }
}

/** For offset-based endpoints that return `Paged`. */
fun offsetPaging(load: suspend (offset: Int, limit: Int) -> app.pixelg.core.Paged<MediaItem>): suspend (List<MediaItem>, Int, Int) -> PageResult =
    { _, offset, limit ->
        val page = load(offset, limit)
        PageResult(page.items, page.total.toInt(), offset + page.items.size < page.total)
    }

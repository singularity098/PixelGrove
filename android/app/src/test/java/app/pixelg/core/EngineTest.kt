package app.pixelg.core

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.file.Files

/**
 * Runs the real engine (libpixelg built for this machine, see `hostEngine` in
 * build.gradle.kts) through JNI, and every typed call the app makes, against a small
 * generated library: catches mismatches between the Kotlin models and the engine's JSON.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EngineTest {
    companion object {
        private lateinit var dir: File
        private lateinit var media: File

        /** Copies a fixture JPEG (src/test/resources) into the library. */
        private fun jpeg(file: File, fixture: String) {
            file.parentFile.mkdirs()
            EngineTest::class.java.getResourceAsStream("/$fixture")!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }

        /** Width and height from a JPEG's SOF marker. */
        fun jpegSize(data: ByteArray): Pair<Int, Int> {
            var i = 2
            while (i + 9 < data.size) {
                val marker = data[i + 1].toInt() and 0xFF
                val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
                if (marker in 0xC0..0xC2) {
                    val h = ((data[i + 5].toInt() and 0xFF) shl 8) or (data[i + 6].toInt() and 0xFF)
                    val w = ((data[i + 7].toInt() and 0xFF) shl 8) or (data[i + 8].toInt() and 0xFF)
                    return w to h
                }
                i += 2 + len
            }
            error("no SOF")
        }

        @BeforeClass
        @JvmStatic
        fun start() {
            dir = Files.createTempDirectory("pixelg-test").toFile()
            media = File(dir, "storage/DCIM")
            jpeg(File(media, "Camera/IMG_1.jpg"), "red.jpg")
            jpeg(File(media, "Camera/IMG_2.jpg"), "green.jpg")
            jpeg(File(media, "Holiday/beach.jpg"), "blue.jpg")
            File(media, "Empty").mkdirs()
            val error = Native.start(
                File(dir, "data").path, File(dir, "thumbs").path, arrayOf(media.path, File(dir, "missing").path), 64,
            )
            assertNull(error)
            // The first start indexes the default folders in the background.
            runBlocking {
                repeat(200) {
                    val status = Library.status()
                    if (status.images == 3L && !status.scanning) return@runBlocking
                    Thread.sleep(25)
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a_browsing() = runBlocking {
        val status = Library.status()
        assertEquals(3L, status.images)
        assertTrue(Native.generation() > 0)

        val albums = Library.albums("newest", 0, 50)
        assertEquals(setOf("Camera", "Holiday"), albums.items.map { it.name }.toSet())
        val camera = albums.items.first { it.name == "Camera" }
        assertEquals(2L, camera.count)
        assertNotNull(camera.cover)

        val folder = Library.folder(camera.id)
        assertEquals("Camera", folder.name)
        assertTrue(folder.breadcrumbs.isNotEmpty())
        val items = Library.folderMedia(camera.id, 0, 10)
        assertEquals(2L, items.total)
        val portrait = items.items.first { it.name == "IMG_2.jpg" }
        assertEquals(900, portrait.width)

        val detail = Library.detail(portrait.id)
        assertTrue(detail.path.endsWith("Camera/IMG_2.jpg"))
        assertFalse(detail.favorite)

        val timeline = Library.timeline(null, null, 2)
        assertEquals(2, timeline.items.size)
        assertTrue(timeline.more)
        val last = timeline.items.last()
        assertEquals(1, Library.timeline(last.takenAt, last.id, 10).items.size)
        assertTrue(Library.months().isNotEmpty())

        val tops = Library.treeTops()
        assertEquals(1, tops.size)
        val children = Library.treeChildren(tops[0].id).map { it.name }
        assertEquals(listOf("Camera", "Empty", "Holiday"), children)

        val search = Library.search("beach", 0, 10)
        assertEquals(1L, search.media.total)
        assertEquals("Holiday", Library.filterFolders("holi").single().name)
        Library.explore()
        assertEquals(3, Library.exploreItems(Filter(kind = "image"), 0, 10).items.size)
        assertTrue(Library.mapPoints().isEmpty())
        assertEquals(1, Library.batch(listOf(portrait.id)).size)
        assertTrue(Library.storage().isNotEmpty())
    }

    @Test
    fun b_thumbnails() {
        val id = runBlocking { Library.search("img_1", 0, 1).media.items.single().id }
        val thumb = Native.thumbnail(id, "thumb", false)
        assertNotNull(thumb)
        assertTrue(thumb!!.size > 100)
        assertEquals(0xFF.toByte(), thumb[0])
        assertEquals(480 to 360, jpegSize(thumb))
        assertEquals(0, Native.thumbnail(999_999, "thumb", false)!!.size)
        Native.storeThumbnail(id, "large", false, thumb)
        assertEquals(thumb.size, Native.thumbnail(id, "large", false)!!.size)
        assertTrue(Native.path(id, false)!!.endsWith("IMG_1.jpg"))
        val cache = runBlocking { Library.thumbnailCache() }
        assertTrue(cache.enabled)
        assertTrue(cache.size > 0)

        val copy = File(dir, "shared.jpg")
        assertNull(Native.copyWithoutLocation(id, copy.path))
        assertEquals(File(Native.path(id, false)!!).length(), copy.length())
    }

    @Test
    fun c_changes() = runBlocking {
        val photo = Library.search("img_1", 0, 1).media.items.single()
        Library.setFavorite(photo.id, true)
        assertEquals(1L, Library.favorites(0, 10).total)

        // Moving keeps the favorite; copying makes a new item.
        val holiday = Library.albums("name", 0, 10).items.first { it.name == "Holiday" }
        val moved = Library.transfer(photo.id, move = true, folderId = holiday.id, newFolder = "Day 1")
        assertNotNull(moved.id)
        assertEquals("IMG_1.jpg", moved.name)
        assertTrue(File(media, "Holiday/Day 1/IMG_1.jpg").isFile)
        assertFalse(File(media, "Camera/IMG_1.jpg").exists())
        assertTrue(Library.detail(moved.id!!).favorite)
        val copied = Library.transfer(moved.id!!, move = false, folderId = holiday.id, newFolder = null)
        assertTrue(File(media, "Holiday/IMG_1.jpg").isFile)
        assertEquals(4L, Library.status().images)

        Library.setMediaHidden(copied.id!!, true)
        assertEquals(3L, Library.status().images)
        // Hidden items can only be unhidden while they're shown.
        Library.setShowHidden(true)
        assertTrue(Library.me().showHidden)
        assertTrue(Library.folderMedia(holiday.id, 0, 10).items.single { it.id == copied.id }.hidden)
        Library.setMediaHidden(copied.id!!, false)
        Library.setShowHidden(false)
        assertEquals(4L, Library.status().images)

        Library.trash(copied.id!!)
        val trash = Library.trashList(0, 10)
        assertEquals(1L, trash.total)
        val entry = trash.items.single()
        assertTrue(entry.asItem().trash)
        assertNotNull(Native.thumbnail(entry.id, "thumb", true))
        Library.restore(entry.id)
        assertTrue(File(media, "Holiday/IMG_1.jpg").isFile)
        Library.trash(Library.search("beach", 0, 1).media.items.single().id)
        Library.emptyTrash()
        assertEquals(0L, Library.trashList(0, 10).total)

        try {
            Library.transfer(999_999, true, holiday.id, null)
            error("expected an error")
        } catch (e: ApiException) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun d_settings() = runBlocking {
        val roots = Library.roots()
        assertEquals(1, roots.size)
        assertFalse(roots[0].configured)
        val listing = Library.directories(media.path)
        assertTrue("Camera" in listing.dirs)
        val extra = File(dir, "storage/Pictures").apply { mkdirs() }
        assertEquals(2, Library.addRoot(extra.path).size)
        assertEquals(1, Library.removeRoot(extra.path).size)
        Library.clearThumbnailCache()
        Native.rescan(arrayOf(media.path))
        Native.fullScan()
    }
}

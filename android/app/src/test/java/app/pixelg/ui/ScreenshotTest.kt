package app.pixelg.ui

import android.os.Environment
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.pixelg.core.Engine
import app.pixelg.core.Library
import app.pixelg.core.Prefs
import app.pixelg.ui.theme.PixelGTheme
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the app's main screens with the real engine and writes them to
 * build/outputs/roborazzi. Uses the library in `-Dpixelg.demo=<dir>` (a folder holding
 * DCIM, e.g. from scripts/screenshots/demo_library.py) or the small test fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = File("build/outputs/roborazzi")

    @Before
    fun start() {
        val storage = Environment.getExternalStorageDirectory()
        val demo = System.getProperty("pixelg.demo")?.let(::File)
        if (demo != null && demo.isDirectory) {
            demo.copyRecursively(storage, overwrite = true)
        } else {
            val dcim = File(storage, "DCIM/Camera").apply { mkdirs() }
            listOf("red", "green", "blue").forEachIndexed { i, name ->
                javaClass.getResourceAsStream("/$name.jpg")!!.use { input ->
                    File(dcim, "IMG_$i.jpg").outputStream().use { input.copyTo(it) }
                }
            }
        }
        runBlocking {
            Engine.start(ApplicationProvider.getApplicationContext())
            check(Engine.started) { Engine.error ?: "not started" }
            repeat(400) {
                val status = Library.status()
                if (status.images > 0 && !status.scanning) return@runBlocking
                Thread.sleep(50)
            }
        }
    }

    private fun settle(ms: Long = 1500) {
        // Previews load on background threads; give them time, then let Compose catch up.
        repeat((ms / 100).toInt()) {
            Thread.sleep(100)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage(File(out, "$name.png").path)

    @Test
    fun screens() {
        Prefs.setTheme("dark")
        compose.setContent { PixelGTheme { PixelGApp() } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Photos")).fetchSemanticsNodes().isNotEmpty() }
        settle(3000)
        shot("photos")

        val firstAlbum = runBlocking { Library.albums("newest", 0, 1).items.first() }
        compose.onAllNodes(hasText(firstAlbum.name))[0].performClick()
        settle(3000)
        shot("folder")

        val firstItem = runBlocking { Library.folderMedia(firstAlbum.id, 0, 1).items.first() }
        // A long press starts selecting; the top bar turns into the selection's actions.
        compose.onAllNodes(hasContentDescription(firstItem.name))[0].performTouchInput { longClick() }
        settle(800)
        shot("selecting")
        compose.onAllNodes(hasContentDescription("Cancel selection"))[0].performClick()
        settle(500)

        compose.onAllNodes(hasContentDescription(firstItem.name))[0].performClick()
        settle(3000)
        shot("viewer")
        compose.onAllNodes(hasContentDescription("Info"))[0].performClick()
        settle(1500)
        shot("info")
    }

    @Test
    fun drawerAndPages() {
        Prefs.setTheme("light")
        compose.setContent { PixelGTheme { PixelGApp() } }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Photos")).fetchSemanticsNodes().isNotEmpty() }
        Prefs.setNamesOnCovers(true)
        settle(1500)
        shot("photos-names")
        Prefs.setNamesOnCovers(false)
        for ((label, name) in listOf("Timeline" to "timeline", "Explore" to "explore", "Settings" to "settings", "File View" to "files")) {
            compose.onAllNodes(hasContentDescription("Menu"))[0].performClick()
            settle(800)
            if (name == "timeline") shot("drawer")
            compose.onAllNodes(hasText(label))[0].performClick()
            settle(3000)
            shot(name)
        }
    }
}

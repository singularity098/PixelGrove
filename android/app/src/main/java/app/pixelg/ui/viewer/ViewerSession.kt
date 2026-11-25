package app.pixelg.ui.viewer

import androidx.navigation.NavHostController
import app.pixelg.ui.components.PagedMedia

/** What the viewer (or a slideshow) shows: a list shared with the grid it came from. */
object ViewerSession {
    var list: PagedMedia? = null
        private set
    var index = 0
    var trash = false
        private set
    /** Slideshow source: the folder to take photos from with "include subfolders". */
    var folderId: Long? = null
        private set

    fun open(nav: NavHostController, list: PagedMedia, index: Int, trash: Boolean = false, folderId: Long? = null) {
        this.list = list
        this.index = index
        this.trash = trash
        this.folderId = folderId
        nav.navigate("viewer")
    }

    fun slideshow(nav: NavHostController, list: PagedMedia, index: Int = 0, folderId: Long? = null) {
        this.list = list
        this.index = index
        this.trash = false
        this.folderId = folderId
        nav.navigate("slideshow")
    }
}

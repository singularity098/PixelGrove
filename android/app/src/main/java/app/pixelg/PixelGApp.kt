package app.pixelg

import android.app.Application
import app.pixelg.core.Prefs
import app.pixelg.thumbs.Thumbnails

class PixelGApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Thumbnails.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Thumbnails.trim(level)
    }
}

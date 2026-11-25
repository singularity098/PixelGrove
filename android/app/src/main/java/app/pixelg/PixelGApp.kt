package app.pixelg

import android.app.Application
import app.pixelg.core.Prefs

class PixelGApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}

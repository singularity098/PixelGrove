package app.pixelg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.pixelg.core.Engine
import app.pixelg.core.MediaSync
import app.pixelg.core.Prefs
import app.pixelg.ui.PixelGApp
import app.pixelg.ui.theme.PixelGTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var hasStorage by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelGTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    when {
                        !hasStorage -> PermissionScreen()
                        !Prefs.askedLocation -> LocationScreen()
                        Engine.error != null -> Message("PixelG couldn't start", Engine.error!!)
                        !Engine.started -> {
                            LaunchedEffect(Unit) { startEngine() }
                            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                        }
                        else -> PixelGApp()
                    }
                }
            }
        }
    }

    private suspend fun startEngine() {
        Engine.start(applicationContext)
        if (Engine.started) {
            Engine.resume()
            MediaSync.observe(this)
            MediaSync.sync(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        hasStorage = Environment.isExternalStorageManager()
        if (Engine.started) {
            Engine.resume()
            MediaSync.observe(this)
            // Back from the camera, an editor, or anything else: catch up with MediaStore.
            lifecycleScope.launch { MediaSync.sync(applicationContext) }
        }
    }

    override fun onPause() {
        super.onPause()
        Engine.pause()
        MediaSync.stopObserving(this)
    }

    @Composable
    private fun PermissionScreen() {
        Onboarding(
            title = "Allow access to your photos",
            text = "PixelG reads your photos and videos straight from storage, watches folders for new ones, " +
                "and moves, copies or trashes files when you ask. Android calls this “All files access”. " +
                "Nothing leaves your phone.",
            button = "Allow access",
        ) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(intent) }
                .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    @Composable
    private fun LocationScreen() {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            Prefs.setAskedLocation()
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            LaunchedEffect(Unit) { Prefs.setAskedLocation() }
            return
        }
        Onboarding(
            title = "Show where photos were taken?",
            text = "Without this, Android hides the location in photos from apps, so the map and places in " +
                "Explore and search stay empty. The location never leaves your phone, and is removed from " +
                "photos you share.",
            button = "Allow",
            secondary = "Not now" to { Prefs.setAskedLocation() },
        ) { launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
    }

    @Composable
    private fun Onboarding(
        title: String,
        text: String,
        button: String,
        secondary: Pair<String, () -> Unit>? = null,
        onClick: () -> Unit,
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(96.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onClick) { Text(button) }
                secondary?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
            }
        }
    }

    @Composable
    private fun Message(title: String, text: String) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(text, textAlign = TextAlign.Center)
            }
        }
    }
}

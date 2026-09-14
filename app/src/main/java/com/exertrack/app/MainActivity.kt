package com.exertrack.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.exertrack.app.ui.AppRoot
import com.exertrack.app.ui.theme.ExertrackTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single activity. Edge-to-edge, hands shared/viewed file URIs (ACTION_SEND EXTRA_STREAM /
 * ACTION_VIEW data, also via onNewIntent) to [AppRoot] as pendingImport, keeps the screen on
 * while a session is active and Settings.keepScreenOn, and asks for POST_NOTIFICATIONS on a
 * cold launch and again whenever a session starts while it is still missing (the platform
 * shows the dialog at most twice per install, so this never nags).
 */
class MainActivity : ComponentActivity() {

    /** A file handed to the app that the Import screen has not picked up yet. */
    private var pendingImport by mutableStateOf<Uri?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result is not needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the system bar icons must always be light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, process restart) gets the same intent again; the
        // original creation already handed that file over.
        if (savedInstanceState == null) {
            pendingImport = importUriFrom(intent)
            // Ask up front so even the first workout has its lock-screen controls; a
            // recreated activity does not ask again.
            requestNotificationPermissionIfMissing()
        }
        observeNotificationPermission()
        observeKeepScreenOn()
        setContent {
            ExertrackTheme {
                AppRoot(pendingImport) { pendingImport = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importUriFrom(intent)?.let { pendingImport = it }
    }

    private fun importUriFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }

    /**
     * Without POST_NOTIFICATIONS the foreground service still runs but its notification (and
     * the Pause / Skip / End actions on the lock screen) is not shown. Once the user has denied
     * twice, launch() reports the denial without showing any dialog.
     */
    private fun requestNotificationPermissionIfMissing() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Asks again each time a session starts while the permission is still missing. */
    private fun observeNotificationPermission() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AppGraph.engine.state
                    .map { it.isActive }
                    .distinctUntilChanged()
                    // The value already present when the activity resumes is not a start: only
                    // a rising edge seen while resumed counts. Otherwise coming back from the
                    // permission dialog itself (which pauses the activity) would ask straight away.
                    .drop(1)
                    .filter { it }
                    .collect { requestNotificationPermissionIfMissing() }
            }
        }
    }

    /** FLAG_KEEP_SCREEN_ON follows "session active AND Settings.keepScreenOn" while the activity is started. */
    private fun observeKeepScreenOn() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(AppGraph.engine.state, AppGraph.repository.settings) { state, settings ->
                    state.isActive && settings.keepScreenOn
                }
                    .distinctUntilChanged()
                    .collect { keepOn ->
                        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
            }
        }
    }
}

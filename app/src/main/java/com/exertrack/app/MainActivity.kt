package com.exertrack.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

/**
 * Single activity. To be completed by the core UI module: edge-to-edge, hand shared/viewed file
 * URIs (ACTION_SEND EXTRA_STREAM / ACTION_VIEW data, also via onNewIntent) to AppRoot as
 * pendingImport, keep the screen on while a session is active and Settings.keepScreenOn, and
 * request POST_NOTIFICATIONS when a session starts.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Exertrack") }
    }
}

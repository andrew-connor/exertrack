package com.exertrack.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that keeps the process alive while a session is active. It observes
 * AppGraph.engine.state, holds a partial wake lock only while a countdown is running and not
 * paused, and posts/updates the session notification (see docs/DESIGN.md). Stops itself when the
 * engine leaves the active phases. Notification actions call the engine on the main thread.
 */
class WorkoutService : Service() {
    companion object {
        const val CHANNEL_ID = "workout"
        const val NOTIFICATION_ID = 1
        const val ACTION_PRIMARY = "com.exertrack.app.action.PRIMARY"
        const val ACTION_SKIP = "com.exertrack.app.action.SKIP"
        const val ACTION_END = "com.exertrack.app.action.END"

        /** Start (or keep) the service in the foreground. Safe to call repeatedly. */
        fun ensureRunning(context: Context): Unit = TODO("implemented by the audio/service module")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

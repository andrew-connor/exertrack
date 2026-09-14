package com.exertrack.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.exertrack.app.AppGraph
import com.exertrack.app.MainActivity
import com.exertrack.app.R
import com.exertrack.app.engine.Phase
import com.exertrack.app.engine.SessionState
import com.exertrack.app.ui.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

        private const val TAG = "WorkoutService"
        private const val CHANNEL_NAME = "Workout in progress"
        private const val WAKE_LOCK_TAG = "Exertrack:workout"
        /**
         * Safety net so a lock can never outlive the service by much; while a countdown runs the
         * ticker refreshes it long before this elapses.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val WAKE_LOCK_REFRESH_MS = WAKE_LOCK_TIMEOUT_MS / 2
        private const val REQUEST_CONTENT = 0
        private const val REQUEST_PRIMARY = 1
        private const val REQUEST_SKIP = 2
        private const val REQUEST_END = 3

        /** Start (or keep) the service in the foreground. Safe to call repeatedly. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, WorkoutService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the workout service", e)
            }
        }
    }

    /** The parts of the state that change what the notification shows. */
    private data class NotificationKey(
        val phase: Phase,
        val paused: Boolean,
        val announcing: Boolean,
        val stepIndex: Int,
        val pausedRemainingSec: Int,
        val rowName: String?,
        val durationSec: Int?,
        val restSec: Int?,
        val totalSteps: Int,
        val rounds: Int,
    )

    private var collectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAt = 0L
    private var lastKey: NotificationKey? = null
    /**
     * phaseElapsedMs of the last state that was posted. It only ever decreases when the engine
     * re-enters a phase, which restarts the clock the notification's chronometer was built from.
     */
    private var lastPhaseElapsedMs = 0L
    /** True once startForeground has succeeded; state updates are ignored before that. */
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = try {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?.apply { setReferenceCounted(false) }
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock unavailable", e)
            null
        }
        collectJob = AppGraph.mainScope.launch {
            AppGraph.engine.state.collect { state -> onState(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = AppGraph.engine
        when (intent?.action) {
            ACTION_PRIMARY -> engine.primaryTap()
            ACTION_SKIP -> engine.skip()
            ACTION_END -> engine.endSession(keepLog = true)
        }
        val state = engine.state.value
        if (state.isActive) {
            if (promote(state)) updateWakeLock(state) else stopSelf()
        } else {
            // Started with startForegroundService for a session that has already ended: the
            // platform still requires one startForeground call before the service may stop.
            if (!foregroundStarted) promote(state)
            stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        collectJob = null
        releaseWakeLock()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Could not leave the foreground", e)
        }
        super.onDestroy()
    }

    // ---- state handling ----

    private fun onState(state: SessionState) {
        if (!foregroundStarted) return
        if (!state.isActive) {
            stopService()
            return
        }
        updateWakeLock(state)
        val key = keyOf(state)
        // A phase re-entered on the same step (Back on step 0, "Start from here" on the current
        // step) yields an identical key but resets the phase clock; the chronometer must be
        // rebuilt or it keeps counting to the old deadline.
        val phaseRestarted = state.phaseElapsedMs < lastPhaseElapsedMs
        lastPhaseElapsedMs = state.phaseElapsedMs
        if (key != lastKey || phaseRestarted) {
            lastKey = key
            notify(buildNotification(state))
        }
    }

    /** Calls startForeground with a notification for [state]; false when the platform refused. */
    private fun promote(state: SessionState): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            foregroundStarted = true
            lastKey = keyOf(state)
            lastPhaseElapsedMs = state.phaseElapsedMs
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            false
        }
    }

    private fun stopService() {
        releaseWakeLock()
        foregroundStarted = false
        lastKey = null
        lastPhaseElapsedMs = 0L
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Could not leave the foreground", e)
        }
        stopSelf()
    }

    private fun keyOf(state: SessionState): NotificationKey = NotificationKey(
        phase = state.phase,
        paused = state.paused,
        announcing = state.announcing,
        stepIndex = state.stepIndex,
        pausedRemainingSec = if (state.paused) state.remainingSec else -1,
        rowName = state.currentRow?.name,
        durationSec = state.currentRow?.durationSec,
        restSec = state.previousRow?.triggerSec,
        totalSteps = state.totalSteps,
        rounds = state.rounds,
    )

    // ---- wake lock ----

    private fun updateWakeLock(state: SessionState) {
        val lock = wakeLock ?: return
        if (!state.isCountingDown) {
            releaseWakeLock()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val held = try { lock.isHeld } catch (e: Exception) { false }
        if (held && now - wakeLockAcquiredAt < WAKE_LOCK_REFRESH_MS) return
        try {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLockAcquiredAt = now
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire the wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the wake lock", e)
        }
    }

    // ---- notification ----

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows the running exercise and its clock"
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
        }
        try {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create the notification channel", e)
        }
    }

    private fun notify(notification: Notification) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification update refused", e)
        }
    }

    private fun buildNotification(state: SessionState): Notification {
        val row = state.currentRow
        val name = row?.name?.takeIf { it.isNotBlank() } ?: state.workout?.name?.takeIf { it.isNotBlank() } ?: "Workout"
        val title = when (state.phase) {
            Phase.REST, Phase.WAITING, Phase.LEAD_IN -> "Next: $name"
            else -> name
        }
        val remaining = state.remainingMs
        val paused = state.paused && state.isActive
        // While the announcement plays the clock is held at its full value, so no chronometer.
        val announcing = state.announcing && state.isActive && !paused
        val parts = mutableListOf(Format.counter(state))
        Format.roundLabel(state)?.let { parts += it }
        parts += when {
            paused -> "Paused"
            announcing -> "Get ready"
            else -> phaseWord(state)
        }
        if (paused && remaining != null) parts += "${Format.countdown(remaining)} left"
        if (announcing && remaining != null) parts += Format.countdown(remaining)
        val text = parts.joinToString(" · ")

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent())

        val now = System.currentTimeMillis()
        when {
            paused || announcing -> builder.setUsesChronometer(false).setShowWhen(false)
            remaining != null -> builder
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(now + remaining)
                .setShowWhen(true)
            state.phase == Phase.WAITING || state.isUntimedExercise -> builder
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
                .setWhen(now - state.phaseElapsedMs)
                .setShowWhen(true)
            else -> builder.setUsesChronometer(false).setShowWhen(false)
        }

        builder.addAction(0, primaryLabel(state), servicePendingIntent(ACTION_PRIMARY, REQUEST_PRIMARY))
        builder.addAction(0, "Skip", servicePendingIntent(ACTION_SKIP, REQUEST_SKIP))
        builder.addAction(0, "End", servicePendingIntent(ACTION_END, REQUEST_END))
        return builder.build()
    }

    private fun phaseWord(state: SessionState): String = when (state.phase) {
        Phase.LEAD_IN -> "Get ready"
        Phase.EXERCISE -> "Exercise"
        Phase.REST -> "Rest"
        Phase.WAITING -> "Tap when ready"
        Phase.IDLE -> "Ready"
        Phase.FINISHED -> "Finished"
    }

    private fun primaryLabel(state: SessionState): String = when {
        state.phase == Phase.WAITING -> "Start next"
        state.announcing && !state.paused -> "Start now"
        state.isUntimedExercise -> "Done"
        state.paused -> "Resume"
        else -> "Pause"
    }

    private fun contentIntent(): PendingIntent {
        val open = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, WorkoutService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

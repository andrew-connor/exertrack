package com.exertrack.app

import android.app.Application
import android.os.SystemClock
import com.exertrack.app.audio.AudioCues
import com.exertrack.app.data.Repository
import com.exertrack.app.engine.Clock
import com.exertrack.app.engine.WorkoutEngine
import com.exertrack.app.service.WorkoutService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ExertrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

/** Process-wide singletons. Initialised once in [ExertrackApp.onCreate]; used by the UI and the service. */
object AppGraph {
    lateinit var app: Application
        private set
    lateinit var repository: Repository
        private set
    lateinit var cues: AudioCues
        private set
    lateinit var engine: WorkoutEngine
        private set

    /** Main-thread scope that lives as long as the process. */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(application: Application) {
        if (this::app.isInitialized) return
        app = application
        repository = Repository(application.filesDir).also { it.load() }
        cues = AudioCues(application, repository.settings)
        engine = WorkoutEngine(
            clock = AndroidClock,
            cues = cues,
            store = repository,
            settings = { repository.settings.value },
            scope = mainScope,
        )
        // The foreground service starts whenever a session becomes active and stops itself when it ends.
        mainScope.launch {
            engine.state.map { it.isActive }.distinctUntilChanged().collect { active ->
                if (active) WorkoutService.ensureRunning(application)
            }
        }
    }
}

object AndroidClock : Clock {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    override fun wallTime(): Long = System.currentTimeMillis()
}

package com.exertrack.app.data

import com.exertrack.app.engine.SessionSnapshot
import com.exertrack.app.engine.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/**
 * All persisted app data. In-memory StateFlows are the source of truth for the UI; every change
 * is serialised on the calling thread and written to disk on a single background thread, so
 * writes to one file never reorder.
 */
class Repository(private val dir: File) : SessionStore {

    companion object {
        const val MAX_ROUNDS = 99
        const val MAX_LEAD_IN_SEC = 60
    }

    private val workoutsFile = File(dir, "workouts.json")
    private val logFile = File(dir, "log.json")
    private val settingsFile = File(dir, "settings.json")
    private val sessionFile = File(dir, "session.json")
    /** Photos and videos attached to exercises; see [MediaFiles]. */
    val mediaDir = File(dir, "media")

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "exertrack-io").apply { isDaemon = true } }

    private val _workouts = MutableStateFlow<List<Workout>>(emptyList())
    val workouts: StateFlow<List<Workout>> = _workouts.asStateFlow()

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    /** Newest first. */
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @Volatile private var snapshot: SessionSnapshot? = null

    /** Read everything from disk. Call once at startup before anything else. Seeds the sample workout on first launch. */
    fun load(now: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        _settings.value = readText(settingsFile)?.let { runCatching { Json.parseSettingsDocument(it) }.getOrNull() } ?: Settings()
        _log.value = readText(logFile)?.let { runCatching { Json.parseLogDocument(it) }.getOrNull() }?.sortedByDescending { it.startedAt } ?: emptyList()
        val loaded = readText(workoutsFile)?.let { runCatching { Json.parseWorkoutsFile(it) }.getOrNull() }
        if (loaded == null) {
            _workouts.value = listOf(SampleWorkout.create(now))
            persistWorkouts()
        } else if (!loaded.manuallyOrdered) {
            // Files from before manual ordering: freeze the order the home screen used to show
            // (most recently used first, then by name) so nothing appears to jump.
            _workouts.value = loaded.workouts.sortedWith(
                compareByDescending<Workout> { it.lastUsedAt ?: Long.MIN_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
            persistWorkouts()
        } else {
            _workouts.value = loaded.workouts
        }
        snapshot = readText(sessionFile)?.let { runCatching { Json.parseSnapshotDocument(it) }.getOrNull() }
        mediaDir.mkdirs()
        // Nothing can still be waiting for a save after a restart, and rows must not point at
        // pending files that will never be committed.
        enqueue { MediaFiles.clearPending(mediaDir) }
        val orphans = _workouts.value.any { w -> w.rows.any { it.media?.isPending == true } }
        if (orphans) {
            _workouts.value = _workouts.value.map { w -> w.copy(rows = w.rows.map { r -> if (r.media?.isPending == true) r.copy(media = null) else r }) }
            persistWorkouts()
        }
        sweepMedia()
    }

    // ---- workouts ----

    fun workout(id: String): Workout? = _workouts.value.firstOrNull { it.id == id }

    /** Insert or replace. Names are trimmed, rows normalised, rounds/lead-in clamped. Returns the stored copy. */
    fun saveWorkout(w: Workout, now: Long = System.currentTimeMillis()): Workout {
        val stored = w.copy(
            name = w.name.trim().ifEmpty { "Untitled workout" },
            rows = w.rows.map { it.normalized() },
            rounds = w.rounds.coerceIn(1, MAX_ROUNDS),
            leadInSec = w.leadInSec.coerceIn(0, MAX_LEAD_IN_SEC),
            updatedAt = now,
        )
        _workouts.update { list ->
            if (list.any { it.id == stored.id }) list.map { if (it.id == stored.id) stored else it } else list + stored
        }
        persistWorkouts()
        sweepMedia()
        return stored
    }

    fun deleteWorkout(id: String) {
        _workouts.update { list -> list.filterNot { it.id == id } }
        persistWorkouts()
        sweepMedia()
    }

    /**
     * Delete media files (and previews) that no exercise references any more. Runs on the
     * writer thread after the current writes, so it never races a save. Pending picks are left
     * alone: they are committed or discarded by the row editor.
     */
    fun sweepMedia() {
        val referenced = HashSet<String>()
        for (w in _workouts.value) for (r in w.rows) r.media?.let { referenced += it.file; referenced += it.thumbFile }
        enqueue { MediaFiles.sweep(mediaDir, referenced) }
    }

    /** Every media file the workouts reference, for backups. */
    fun referencedMedia(): List<RowMedia> =
        _workouts.value.flatMap { w -> w.rows.mapNotNull { it.media } }.filterNot { it.isPending }

    /**
     * Put the workout [fromId] where [toId] currently sits (the list order is the home screen
     * order). Everything in between shifts by one. Unknown ids are ignored.
     */
    fun moveWorkout(fromId: String, toId: String) {
        if (fromId == toId) return
        _workouts.update { list ->
            val from = list.indexOfFirst { it.id == fromId }
            val to = list.indexOfFirst { it.id == toId }
            if (from < 0 || to < 0) return@update list
            list.toMutableList().apply { add(to, removeAt(from)) }
        }
        persistWorkouts()
    }

    fun renameWorkout(id: String, name: String, now: Long = System.currentTimeMillis()) {
        val w = workout(id) ?: return
        saveWorkout(w.copy(name = name), now)
    }

    /**
     * Copy a workout (new ids for it and its rows) under a unique "name (2)" style name.
     * [copyMedia] duplicates an attached photo or video so the two workouts never share a file;
     * it returns null when the copy fails, in which case the new row has no media.
     */
    fun duplicateWorkout(
        id: String,
        now: Long = System.currentTimeMillis(),
        copyMedia: (RowMedia) -> RowMedia? = { null },
    ): Workout? {
        val w = workout(id) ?: return null
        val copy = w.copy(
            id = newId(),
            name = uniqueName(w.name),
            rows = w.rows.map { it.copy(id = newId(), media = it.media?.let(copyMedia)) },
            createdAt = now,
            updatedAt = now,
            lastUsedAt = null,
        )
        val saved = saveWorkout(copy, now)
        // The list order is the user's order: a copy belongs right under its original.
        _workouts.update { list ->
            val original = list.indexOfFirst { it.id == id }
            val added = list.indexOfFirst { it.id == saved.id }
            if (original < 0 || added < 0) list
            else list.toMutableList().apply { add(original + 1, removeAt(added)) }
        }
        persistWorkouts()
        return saved
    }

    /** "name", or "name (2)", "name (3)" ... whichever is not taken (case-insensitive). */
    fun uniqueName(base: String): String {
        val trimmed = base.trim().ifEmpty { "Untitled workout" }
        val taken = _workouts.value.map { it.name.lowercase() }.toSet()
        if (trimmed.lowercase() !in taken) return trimmed
        val stem = Regex("""^(.*?)\s\((\d+)\)$""").find(trimmed)?.groupValues?.get(1) ?: trimmed
        var n = 2
        while ("${stem.lowercase()} ($n)" in taken) n++
        return "$stem ($n)"
    }

    fun newWorkout(name: String = "New workout", now: Long = System.currentTimeMillis()): Workout =
        saveWorkout(Workout(name = uniqueName(name), rows = emptyList(), createdAt = now, updatedAt = now), now)

    // ---- settings ----

    fun updateSettings(transform: (Settings) -> Settings) {
        _settings.update(transform)
        val text = Json.settingsDocument(_settings.value)
        enqueue { writeAtomic(settingsFile, text) }
    }

    // ---- log ----

    fun updateLogNotes(id: String, notes: String) {
        _log.update { list -> list.map { if (it.id == id) it.copy(notes = notes) else it } }
        persistLog()
    }

    fun deleteLogEntry(id: String) = deleteLog(id)

    override fun upsertLog(entry: LogEntry) {
        _log.update { list ->
            val without = list.filterNot { it.id == entry.id }
            (without + entry).sortedByDescending { it.startedAt }
        }
        persistLog()
    }

    override fun deleteLog(entryId: String) {
        _log.update { list -> list.filterNot { it.id == entryId } }
        persistLog()
    }

    override fun findLog(entryId: String): LogEntry? = _log.value.firstOrNull { it.id == entryId }

    override fun touchWorkoutUsed(workoutId: String, at: Long) {
        _workouts.update { list -> list.map { if (it.id == workoutId) it.copy(lastUsedAt = at) else it } }
        persistWorkouts()
    }

    // ---- session snapshot ----

    fun currentSnapshot(): SessionSnapshot? = snapshot

    override fun saveSnapshot(snapshot: SessionSnapshot?) {
        this.snapshot = snapshot
        if (snapshot == null) {
            enqueue { sessionFile.delete() }
        } else {
            val text = Json.snapshotDocument(snapshot)
            enqueue { writeAtomic(sessionFile, text) }
        }
    }

    // ---- backup ----

    /**
     * Replace all data (restore from backup). A backup written before manual ordering existed
     * ([manuallyOrdered] false) is put in the order its home screen used to show, most recently
     * used first, instead of its file order.
     */
    fun replaceAll(workouts: List<Workout>, log: List<LogEntry>, settings: Settings, manuallyOrdered: Boolean = true) {
        val ordered = if (manuallyOrdered) workouts else workouts.sortedWith(
            compareByDescending<Workout> { it.lastUsedAt ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        _workouts.value = ordered.map { w -> w.copy(rows = w.rows.map { it.normalized() }) }
        _log.value = log.sortedByDescending { it.startedAt }
        _settings.value = settings
        persistWorkouts()
        persistLog()
        val text = Json.settingsDocument(settings)
        enqueue { writeAtomic(settingsFile, text) }
        sweepMedia()
    }

    /** Block until every queued write has completed (tests, and before sharing a backup). */
    fun flush() {
        writer.submit { }.get()
    }

    // ---- disk ----

    private fun persistWorkouts() {
        val text = Json.workoutsDocument(_workouts.value)
        enqueue { writeAtomic(workoutsFile, text) }
    }

    private fun persistLog() {
        val text = Json.logDocument(_log.value)
        enqueue { writeAtomic(logFile, text) }
    }

    private fun enqueue(block: () -> Unit) {
        writer.execute {
            try { block() } catch (e: Exception) { android.util.Log.e("Exertrack", "write failed", e) }
        }
    }

    private fun readText(file: File): String? =
        if (file.isFile) runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() else null

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, StandardCharsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

object SampleWorkout {
    fun create(now: Long): Workout = Workout(
        name = "Sample circuit",
        rounds = 2,
        leadInSec = 3,
        createdAt = now,
        updatedAt = now,
        rows = listOf(
            ExerciseRow(name = "Jumping jacks", durationSec = 40, triggerSec = 15, notes = "Warm-up pace"),
            ExerciseRow(name = "Push-ups", durationSec = null, triggerSec = null, notes = "10 reps, tap Done when finished"),
            ExerciseRow(name = "Squats", durationSec = 45, triggerSec = 15),
            ExerciseRow(name = "Plank", durationSec = 30, triggerSec = null, notes = "Keep hips level"),
            ExerciseRow(name = "Lunges", durationSec = 45, triggerSec = 15, notes = "Alternate legs"),
            ExerciseRow(name = "Mountain climbers", durationSec = 30, triggerSec = 20),
            ExerciseRow(name = "Glute bridge", durationSec = 40, triggerSec = null),
        ),
    )
}

package com.exertrack.app.ui

import com.exertrack.app.engine.SessionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Text formatting shared by all screens. */
object Format {
    /** "m:ss" (or "h:mm:ss" from one hour). Negative values clamp to 0:00. */
    fun clock(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** Countdown display: rounds up so 40.0 s shows 0:40 and the clock reaches 0:00 exactly at the end. */
    fun countdown(ms: Long): String = clock(((ms.coerceAtLeast(0) + 999) / 1000).toInt())

    /** Count-up display: rounds down. */
    fun elapsed(ms: Long): String = clock((ms.coerceAtLeast(0) / 1000).toInt())

    /** "45 s", "24 min", "1 h 05 min" for estimates and cards. */
    fun shortDuration(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        if (s < 60) return "$s s"
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) String.format(Locale.US, "%d h %02d min", h, m) else "$m min"
    }

    /** Row-based duration text for cards and lists: "0:40", "untimed". */
    fun rowDuration(durationSec: Int?): String = if (durationSec == null || durationSec <= 0) "untimed" else clock(durationSec)

    /** "manual" or "auto 15 s". */
    fun rowTrigger(triggerSec: Int?): String = if (triggerSec == null || triggerSec <= 0) "manual" else "auto ${triggerSec}s"

    /** "5/10" */
    fun counter(state: SessionState): String {
        val rows = state.rows.size
        if (rows == 0) return "0/0"
        val idx = if (state.phase == com.exertrack.app.engine.Phase.FINISHED) rows else state.rowIndex + 1
        return "$idx/$rows"
    }

    /** "Round 2 of 3", or null when the workout has a single round. */
    fun roundLabel(state: SessionState): String? {
        if (state.rounds <= 1) return null
        val r = if (state.phase == com.exertrack.app.engine.Phase.FINISHED) state.rounds else state.round + 1
        return "Round $r of ${state.rounds}"
    }

    private val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val isoDate = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val isoTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    private fun zone(zoneId: String): ZoneId = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())

    fun date(epochMs: Long, zoneId: String = ZoneId.systemDefault().id): String =
        Instant.ofEpochMilli(epochMs).atZone(zone(zoneId)).format(dateFmt)

    fun time(epochMs: Long, zoneId: String = ZoneId.systemDefault().id): String =
        Instant.ofEpochMilli(epochMs).atZone(zone(zoneId)).format(timeFmt)

    fun dateTime(epochMs: Long, zoneId: String = ZoneId.systemDefault().id): String =
        "${date(epochMs, zoneId)} ${time(epochMs, zoneId)}"

    fun isoDate(epochMs: Long, zoneId: String): String = Instant.ofEpochMilli(epochMs).atZone(zone(zoneId)).format(isoDate)
    fun isoTime(epochMs: Long, zoneId: String): String = Instant.ofEpochMilli(epochMs).atZone(zone(zoneId)).format(isoTime)

    /** "exertrack-log-2026-09-14.csv" */
    fun exportFileName(prefix: String, ext: String, epochMs: Long): String =
        "$prefix-${isoDate(epochMs, ZoneId.systemDefault().id)}.$ext"

    /** Safe file-name stem from a workout name. */
    fun fileStem(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "workout" }
}

# Exertrack design

Personal Android interval-workout timer. Kotlin + Jetpack Compose, single activity, no third-party
runtime libraries (AndroidX and kotlinx-coroutines only). Package `com.exertrack.app`. minSdk 34,
target/compile 36. Storage is JSON files in app-private storage. The active workout runs in a
foreground service so chimes fire with the screen off.

## Concepts

- **Workout**: a name, a list of **rows** (exercises), a `rounds` count (the whole row list is
  repeated `rounds` times), and a `leadInSec` "get ready" countdown.
- **Row**: `name`, `durationSec` (null = untimed, ends when the user taps Done), `triggerSec`
  (null = manual: after the exercise ends the app waits for a tap; N > 0 = automatic rest of N
  seconds, then the next exercise starts by itself), `notes` (free text shown while the row is
  pending or running). A trigger of 0 is normalised to null (manual) everywhere.
- **Step**: one (round, row) pair. `stepIndex` runs from 0 to `rows.size * rounds - 1`.
  `rowIndex = stepIndex % rows.size`, `round = stepIndex / rows.size` (0-based).
  The counter on screen reads `"{rowIndex+1}/{rows.size}"` plus `"Round {round+1} of {rounds}"`
  when rounds > 1. The log records `completedSteps / totalSteps` (steps, not rows).
- **Session**: one run of a workout. Every started session gets a log entry immediately
  (status `IN_PROGRESS`) that is updated on every transition and closed with `COMPLETED`,
  `STOPPED` (user ended early and kept the log) or `INTERRUPTED` (process died and the user
  discarded the resume offer).

## Engine state machine (`engine/WorkoutEngine.kt`)

Phases: `IDLE`, `LEAD_IN`, `EXERCISE`, `REST`, `WAITING`, `FINISHED`. A boolean `paused` applies
to the countdown phases `LEAD_IN`, `EXERCISE` (timed only) and `REST`. `stepIndex` always points at
the step that is running (`EXERCISE`) or about to run (`LEAD_IN`, `REST`, `WAITING`).

| Phase | Clock | Big button label | Primary tap | Skip | Back |
|---|---|---|---|---|---|
| IDLE | shows first row's duration, static | START | start session, go to LEAD_IN(0) (or EXERCISE(0) if leadInSec = 0) | - | - |
| LEAD_IN | countdown leadInSec | PAUSE / RESUME | pause / resume | start the exercise now | previous step's LEAD_IN |
| EXERCISE timed | countdown durationSec | PAUSE / RESUME | pause / resume | complete the step now | previous step's LEAD_IN |
| EXERCISE untimed | count-up | DONE | complete the step | complete the step | previous step's LEAD_IN |
| REST | countdown triggerSec of the previous step's row | PAUSE / RESUME | pause / resume | end rest now, GO chime, EXERCISE | previous step's LEAD_IN (the step just completed) |
| WAITING | count-up (time waited) | START NEXT | go to LEAD_IN(stepIndex) | same as primary | previous step's LEAD_IN |
| FINISHED | total elapsed, static | - | nothing | - | - |

Back from step 0 restarts step 0's LEAD_IN. Skip and Back always leave the new phase running
(not paused). `jumpTo(step)` goes to that step's LEAD_IN (starting a session first if IDLE).

Completing a step (clock reaches 0, Done, or Skip during EXERCISE) adds `stepIndex` to the set of
completed steps and plays the DONE chime, then:
- if it was the last step: `FINISHED`, COMPLETE chime, speak "Workout complete", log entry closed
  as COMPLETED with `finishedAt = now`.
- else if the row's `triggerSec` > 0: `REST` for that many seconds with `stepIndex + 1`.
- else: `WAITING` with `stepIndex + 1`.

`REST` ending and `LEAD_IN` ending both play the GO chime and enter `EXERCISE`. The GO chime plays
at every exercise start however it was reached, so one sound always means "go".

Warning ticks: a short TICK sound when the displayed remaining seconds change to 3, 2 and 1 in any
countdown phase (suppressed while paused, and never in the first second of a phase).

Speech (only when `Settings.speechEnabled`):
- LEAD_IN start: `"{name}, {duration}"` (duration spoken as "40 seconds", "1 minute 30 seconds",
  or "until done" for untimed rows), interrupting anything queued.
- WAITING start: `"Next: {name}, {duration}. Tap when ready."`
- REST start: `"Rest, {n} seconds. Next: {name}, {duration}."`
- EXERCISE start reached from REST (no lead-in): `"{name}, {duration}"`.
- FINISHED: `"Workout complete"`. Nothing is spoken on pause, resume, skip or back.

Input hygiene: a primary tap arriving within 600 ms after an *automatic* phase change (a clock
reaching zero) is ignored, so a pause tap does not become an advance when the state flips under
the thumb. Skip, Back, Reset and End are deliberate controls (confirmed or long-pressed in the UI).

Live edits (`updateWorkout`): the engine swaps in the new `Workout` object. If the current
EXERCISE row's duration changed, `remaining = newDuration - elapsedInPhase`; if that is <= 0 the
step completes immediately. If a timed row became untimed mid-exercise, switch to count-up. During
REST, if the previous row's trigger changed, remaining is recomputed the same way; if it became
manual, switch to WAITING. Structural edits (add/delete/reorder rows, rounds) are blocked by the
UI while a session is in progress.

Timing: countdown phases store a deadline in `SystemClock.elapsedRealtime()`. A 100 ms ticker
coroutine runs only while a countdown is running. Displayed remaining seconds are
`ceil(remainingMs / 1000)`. Total elapsed is wall-clock since session start including pauses;
`pausedMs` accumulates separately. Estimated remaining = sum of durations and automatic rests of
the steps not yet completed (current phase's remaining used for the current clock).

Persistence: a `SessionSnapshot` is written on every transition, pause/resume, and at least every
5 seconds while a clock runs; cleared when the session ends. On launch with a snapshot present the
UI offers "Resume {workout} at {step}?" / "Discard". Resume restores the state **paused** with the
saved remaining time. Discard closes the log entry as INTERRUPTED.

Ending: `endSession(keepLog = true)` moves to FINISHED with status STOPPED (the completion dialog
appears with a notes field, same as a full completion). `endSession(keepLog = false)` deletes the
session's log entry and returns to IDLE. `acknowledgeFinished()` returns from FINISHED to IDLE with
the same workout loaded. Reset in the UI = the "End workout?" sheet: Keep going / End and log /
Discard.

## Audio (`audio/`)

- Four synthesized sounds (no asset files) played through `AudioTrack` on `USAGE_MEDIA` /
  `CONTENT_TYPE_SONIFICATION` so they reach Bluetooth earbuds: DONE (one ~400 ms decaying tone),
  GO (two short rising tones), TICK (~60 ms blip), COMPLETE (three ascending tones). Each buffer
  starts with ~150 ms of silence so Bluetooth links do not clip the attack.
- Audio focus: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` requested before a sound or utterance when
  `Settings.duckOtherAudio` is on, abandoned about 1.5 s after the last one (debounced so a 3-2-1
  count does not pump the music). Never pause other audio; never request Do Not Disturb access.
- Vibration via `VibratorManager`: DONE = one 400 ms pulse, GO = two 120 ms pulses, COMPLETE =
  three pulses, TICK = none. `VibrationMode.ALWAYS` / `WHEN_SILENT` (ringer not normal, or DND
  active, or media volume 0) / `NEVER`.
- Speech via the platform `TextToSpeech`, `USAGE_MEDIA` attributes, warmed up with an empty
  utterance when a session starts. `speak(text, interrupt)` uses QUEUE_FLUSH when interrupt.
- `Settings.chimesMuted` silences sounds and speech but not vibration.

## Foreground service (`service/WorkoutService.kt`)

Started when a session starts, stopped when the engine returns to IDLE or FINISHED. Type
`specialUse`. Holds a partial wake lock only while a countdown is running and not paused. Posts a
low-importance, silent notification: title = current/upcoming exercise name, text =
"5/10 · Round 2 of 3 · Rest" style status, a chronometer (count-down for countdown phases,
count-up for WAITING/untimed, static text when paused), and actions Pause/Resume, Skip, End.
End from the notification = `endSession(keepLog = true)`. Tapping the notification opens the app.

## Storage (`data/Repository.kt`)

Files in `context.filesDir`: `workouts.json`, `log.json`, `settings.json`, `session.json`.
Written atomically (temp file + rename) on `Dispatchers.IO`; in-memory `StateFlow`s are the
source of truth for the UI. Each file is `{ "schemaVersion": 1, ... }`. Workouts and rows have
UUID ids. A sample workout is seeded on first launch. Deleting a workout never deletes its log
entries (the log shows the snapshotted name).

## Import (`io/`)

Formats: `.xlsx` (own minimal reader: shared strings, inline strings, numbers, booleans, formula
cached values; every sheet in workbook order) and CSV (BOM stripped, UTF-8 with windows-1252
fallback, delimiter auto-detected among comma/semicolon/tab, RFC 4180 quoting, any line ending).
File type is detected by content (PK zip signature = xlsx), not extension or MIME type.

Table rules (`WorkoutTableParser`): columns by position A-D = name, duration, trigger, notes;
extra columns ignored; missing trailing columns blank. Row 1 is a header iff its column-B cell is
non-blank and does not parse as a duration, or its column-A cell (lower-cased, trimmed) is one of
"exercise", "exercise name", "name". All-blank rows are skipped. Duration: blank, "0", "-",
"untimed", "reps" = untimed; integer seconds; decimals rounded; "m:ss" and "h:mm:ss"; a number
strictly between 0 and 1 is an Excel time fraction (x 86400) and gets a *warning* ("looks like an
Excel time"), as does any duration over 3600 s. Trigger: blank, "manual", "m", "-", "0" = manual;
a positive number or m:ss = seconds. A blank name with any other content, or an unparseable
duration/trigger, is a blocking *problem* for that row. Name and notes are trimmed; notes keep
internal line breaks.

Import flow (`ImportScreen`): pick a file (ACTION_OPEN_DOCUMENT with `*/*`, persistable read
permission) or receive one via ACTION_SEND/VIEW. Every sheet becomes an item with its parsed row
count and status. For each sheet the user picks a target: **New workout** (name defaults to the
sheet name, or the file name for CSV), **Replace rows of** an existing workout (default when a
workout with the same name exists, case-insensitive), or **Skip**. Tapping a sheet shows the parsed
preview with per-row problems and warnings. Import is enabled when at least one non-skipped sheet
has no problems; sheets with problems are forced to Skip. Replace keeps the workout id, name,
rounds and lead-in, so log history stays linked. The source URI and sheet name are stored on the
workout for the editor's "Re-import from file" action.

## Export

- Workout as CSV in the import layout (header row `Exercise,Duration (s),Trigger (s),Notes`;
  untimed = blank duration; manual = blank trigger).
- Log as CSV (UTF-8 with BOM, columns: date, start_time, end_time, elapsed_min, paused_min,
  workout, completed, total, rounds, status, notes, session_id, workout_id) and as formatted
  `.xlsx` (bold header, frozen top row, autofilter, column widths, real date/time cells, notes
  wrapped).
- Backup: one JSON file with everything; restore replaces all data after confirmation.
- Files are written through ACTION_CREATE_DOCUMENT (Save as) and can also be shared through the
  system share sheet via a `FileProvider` cache file.

## Screens (`ui/`)

Single-activity, hand-rolled back stack (`Screen` sealed class). Dark theme, large type. The activity is
locked to portrait (the timer layout does not fit a phone in landscape). POST_NOTIFICATIONS is requested on
first launch and again whenever a session starts while it is still missing.

- **Home**: workout cards sorted by last used then name (name, "10 exercises · 3 rounds · ~24 min",
  last used). Tap = open Active screen in IDLE. Card menu: Edit, Duplicate ("{name} (2)"),
  Rename, Export CSV, Delete (confirm). Top actions: Import, Log, Settings. FAB: New workout.
- **Editor**: name, rounds, lead-in seconds; row cards with up/down/duplicate/delete; tap a row to
  edit its four fields in `RowEditSheet`; Add exercise; Replace rows from file; Re-import from
  source (when known). Autosaves every change. Structural controls disabled with a hint while a
  session for this workout is in progress.
- **Active**: counter line, phase label (READY / GET READY / REST / TAP WHEN READY / PAUSED),
  big clock, exercise name, notes, secondary line "elapsed · ~remaining"; scrollable step list
  (round headers when rounds > 1; completed steps dimmed; current highlighted) that auto-follows
  the current step unless the user scrolled in the last 10 s, with a "jump to current" button when
  the current step is off-screen; tap a step to edit it (pauses any running clock; stays paused on
  close); long-press a step for "Start from here?"; bottom bar Back / big primary button / Skip;
  top bar: mute, speech toggle, Reset (opens the End workout sheet). Completion dialog on
  FINISHED: title, steps, elapsed, notes field, Save. Screen kept on while a session is active and
  `Settings.keepScreenOn`.
- **Import**: as above.
- **Log**: entries newest first with date, workout, completed/total, status, notes preview; filter
  by workout; tap for detail (edit notes, delete); top actions Export CSV, Export XLSX, Share.
- **Settings**: speech, duck other audio, vibration mode, warning ticks, keep screen on, mute;
  Test buttons for each sound and for speech; Backup export / restore; version.

## Module ownership

| Module | Files |
|---|---|
| contracts (fixed) | `data/Models.kt`, `engine/SessionState.kt`, `engine/Cues.kt` |
| storage | `data/Json.kt`, `data/Repository.kt`, `io/Backup.kt` |
| engine | `engine/WorkoutEngine.kt` |
| parsing | `io/CsvParser.kt`, `io/XlsxReader.kt`, `io/WorkoutTableParser.kt` |
| writing | `io/XlsxWriter.kt`, `io/Exporters.kt` |
| audio + service | `audio/Tones.kt`, `audio/Speaker.kt`, `audio/AudioCues.kt`, `service/WorkoutService.kt` |
| app + core UI | `ExertrackApp.kt`, `MainActivity.kt`, `ui/AppRoot.kt`, `ui/theme/Theme.kt`, `ui/Format.kt`, `ui/Common.kt`, `ui/HomeScreen.kt`, `ui/SettingsScreen.kt` |
| editor + import UI | `ui/EditorScreen.kt`, `ui/RowEditSheet.kt`, `ui/ImportScreen.kt` |
| active + log UI | `ui/ActiveWorkoutScreen.kt`, `ui/EndDialogs.kt`, `ui/LogScreen.kt` |

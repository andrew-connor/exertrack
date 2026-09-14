# Exertrack

A personal Android interval-workout timer. Build a workout as a table of exercises (name, duration,
trigger for the next exercise, notes), run it with big glanceable controls and audible cues, and keep
a log of every session.

## Features

- **Timer screen**: large countdown, current exercise and notes, a step counter (`5/10 · Round 2 of 3`),
  the full exercise list that follows along, and Back / Start-Pause / Skip controls.
- **Triggers**: each exercise either waits for a tap before the next one starts (manual) or starts the
  next one automatically after a rest of N seconds. Untimed, rep-based exercises end when you tap Done.
- **Rounds**: repeat the whole list any number of times.
- **Cues**: distinct chimes for "exercise done" and "go", 3-2-1 warning ticks, a "get ready" lead-in,
  optional spoken announcements of the next exercise, and vibration when the phone is silenced.
  Chimes duck other audio rather than pausing it.
- **Runs in the background**: a foreground service keeps the timer and chimes going with the screen
  off or another app in front, with Pause / Skip / End actions in the notification.
- **Edits anywhere**: change an exercise from the timer screen or the table editor; edits persist and
  a running countdown adjusts immediately.
- **Import**: read `.xlsx` or `.csv` files from the phone. Every sheet in a workbook becomes a workout
  (or replaces an existing one) in a single import, with a preview that flags rows it could not read.
  Header rows are detected automatically.
- **Log**: start and finish times, elapsed time, completed steps, status and notes for every session,
  with CSV and formatted XLSX export and a share sheet.
- **Backup**: export and restore all workouts, log entries and settings as one JSON file.

## Spreadsheet layout

Four columns, with or without a header row:

| Exercise | Duration (s) | Trigger (s) | Notes |
|---|---|---|---|
| Jumping jacks | 40 | 15 | Warm-up pace |
| Push-ups | | | 10 reps |
| Plank | 1:30 | | Keep hips level |

- Duration: seconds, or `m:ss`. Blank means untimed (ends on Done).
- Trigger: blank means wait for a tap; a number is the automatic rest in seconds before the next exercise.
- Notes are shown while the exercise is pending or running.

## Building

Open the project in Android Studio, let Gradle sync, connect a phone with USB debugging enabled and
press Run. The project keeps build output in `D:/gradle-builds/exertrack/` because the source tree lives
in a cloud-synced folder; adjust or remove that block in `build.gradle.kts` on another machine.

Command line (Windows, using the JDK bundled with Android Studio):

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

## Stack

Kotlin, Jetpack Compose (Material 3), kotlinx-coroutines. No third-party runtime libraries: JSON
storage uses the platform `org.json`, and the `.xlsx` reader and writer are small in-house
implementations over `java.util.zip` and `javax.xml`. Design notes are in `docs/DESIGN.md`.

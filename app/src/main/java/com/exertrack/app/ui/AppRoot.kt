package com.exertrack.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import com.exertrack.app.AppGraph
import com.exertrack.app.data.Workout
import com.exertrack.app.engine.SessionSnapshot
import com.exertrack.app.ui.theme.ExColors

/** Navigation destinations. The back stack is a plain list kept in [AppRoot]. */
sealed class Screen {
    data object Home : Screen()
    data class Editor(val workoutId: String) : Screen()
    data class Active(val workoutId: String) : Screen()
    /** [uri] null = show the picker; [replaceWorkoutId] preselects "Replace rows of" that workout for its source sheet (the first sheet when unknown). */
    data class Import(val uri: Uri?, val replaceWorkoutId: String? = null) : Screen()
    data object Log : Screen()
    data object Settings : Screen()
}

/**
 * Root composable: theme, hand-rolled back stack with BackHandler, the resume-interrupted-session
 * dialog on launch, and routing of a file shared into the app ([pendingImport]) to the Import
 * screen (call [onImportConsumed] once navigated).
 */
@Composable
fun AppRoot(pendingImport: Uri?, onImportConsumed: () -> Unit) {
    // The stack survives rotation and process recreation; screens are encoded as strings.
    val stack = rememberSaveable(saver = ScreenStackSaver) { mutableListOf<Screen>(Screen.Home).toMutableStateList() }
    val stateHolder = rememberSaveableStateHolder()

    fun keyFor(index: Int): String = "$index:${encodeScreen(stack[index])}"

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size <= 1) return
        stateHolder.removeState(keyFor(stack.lastIndex))
        stack.removeAt(stack.lastIndex)
    }

    /** Push, or swap the top entry when it is already a screen of the same kind (a second shared file). */
    fun showImport(uri: Uri?, replaceWorkoutId: String?) {
        if (stack.last() is Screen.Import) pop()
        push(Screen.Import(uri, replaceWorkoutId))
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // ---- resume an interrupted session ----
    var resumeOffer by remember { mutableStateOf<Pair<SessionSnapshot, Workout>?>(null) }
    LaunchedEffect(Unit) {
        val snapshot = AppGraph.repository.currentSnapshot() ?: return@LaunchedEffect
        if (AppGraph.engine.state.value.isActive) return@LaunchedEffect
        val workout = AppGraph.repository.workout(snapshot.workoutId)
        if (workout == null) {
            AppGraph.engine.discardSnapshot(snapshot)
        } else {
            resumeOffer = snapshot to workout
        }
    }

    // ---- a file shared or opened with the app ----
    LaunchedEffect(pendingImport) {
        if (pendingImport != null) {
            showImport(pendingImport, null)
            onImportConsumed()
        }
    }

    Scaffold(
        containerColor = ExColors.background,
        contentColor = ExColors.text,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .safeDrawingPadding()
        ) {
            val topIndex = stack.lastIndex
            val top = stack[topIndex]
            stateHolder.SaveableStateProvider(key = keyFor(topIndex)) {
                when (top) {
                    Screen.Home -> HomeScreen(
                        onOpenWorkout = { push(Screen.Active(it)) },
                        onEditWorkout = { push(Screen.Editor(it)) },
                        onImport = { showImport(null, null) },
                        onLog = { push(Screen.Log) },
                        onSettings = { push(Screen.Settings) },
                    )
                    is Screen.Editor -> EditorScreen(
                        workoutId = top.workoutId,
                        onBack = { pop() },
                        onImportFile = { uri -> showImport(uri, top.workoutId) },
                    )
                    is Screen.Import -> ImportScreen(
                        initialUri = top.uri,
                        replaceWorkoutId = top.replaceWorkoutId,
                        onDone = { pop() },
                    )
                    is Screen.Active -> ActiveWorkoutScreen(
                        workoutId = top.workoutId,
                        onBack = { pop() },
                        onEditWorkout = { push(Screen.Editor(it)) },
                    )
                    Screen.Log -> LogScreen(onBack = { pop() })
                    Screen.Settings -> SettingsScreen(onBack = { pop() })
                }
            }
        }
    }

    resumeOffer?.let { (snapshot, workout) ->
        ResumeDialog(
            snapshot = snapshot,
            workout = workout,
            onResume = {
                resumeOffer = null
                AppGraph.engine.restore(snapshot, workout)
                if (stack.last() != Screen.Active(workout.id)) push(Screen.Active(workout.id))
            },
            onDiscard = {
                resumeOffer = null
                AppGraph.engine.discardSnapshot(snapshot)
            },
        )
    }
}

@Composable
private fun ResumeDialog(
    snapshot: SessionSnapshot,
    workout: Workout,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        // The user has to choose: leaving the snapshot behind would keep an open log entry around.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Resume ${workout.name}?") },
        text = { Text("You were at step ${snapshot.stepIndex + 1} of ${workout.totalSteps}.") },
        confirmButton = { TextButton(onClick = onResume) { Text("Resume") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Discard", color = ExColors.danger) } },
    )
}

// ---- back-stack persistence ----

private const val SEP = '\n'

private fun encodeScreen(screen: Screen): String = when (screen) {
    Screen.Home -> "home"
    is Screen.Editor -> "editor$SEP${screen.workoutId}"
    is Screen.Active -> "active$SEP${screen.workoutId}"
    is Screen.Import -> "import$SEP${screen.uri?.toString().orEmpty()}$SEP${screen.replaceWorkoutId.orEmpty()}"
    Screen.Log -> "log"
    Screen.Settings -> "settings"
}

private fun decodeScreen(text: String): Screen? {
    val parts = text.split(SEP)
    val workoutExists: (String) -> Boolean = { AppGraph.repository.workout(it) != null }
    return when (parts[0]) {
        "home" -> Screen.Home
        "editor" -> parts.getOrNull(1)?.takeIf(workoutExists)?.let { Screen.Editor(it) }
        "active" -> parts.getOrNull(1)?.takeIf(workoutExists)?.let { Screen.Active(it) }
        "import" -> Screen.Import(
            uri = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(Uri::parse),
            replaceWorkoutId = parts.getOrNull(2)?.takeIf { it.isNotEmpty() && workoutExists(it) },
        )
        "log" -> Screen.Log
        "settings" -> Screen.Settings
        else -> null
    }
}

private val ScreenStackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map(::encodeScreen) },
    restore = { saved ->
        val screens = saved.mapNotNull(::decodeScreen).toMutableList()
        if (screens.firstOrNull() != Screen.Home) screens.add(0, Screen.Home)
        screens.toMutableStateList()
    },
)

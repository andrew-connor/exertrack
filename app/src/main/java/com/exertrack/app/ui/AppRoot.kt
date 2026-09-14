package com.exertrack.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable

/** Navigation destinations. The back stack is a plain list kept in [AppRoot]. */
sealed class Screen {
    data object Home : Screen()
    data class Editor(val workoutId: String) : Screen()
    data class Active(val workoutId: String) : Screen()
    /** [uri] null = show the picker; [replaceWorkoutId] preselects "Replace rows of" that workout for the first sheet. */
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
fun AppRoot(pendingImport: Uri?, onImportConsumed: () -> Unit): Unit = TODO("implemented by the core UI module")

package com.exertrack.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable

/**
 * Workout table editor (see docs/DESIGN.md). Autosaves through the repository. "Replace rows from
 * file" opens the document picker and hands the chosen URI to [onImportFile]; "Re-import from
 * source" does the same with the stored sourceUri. Structural edits are disabled while a session
 * for this workout is active.
 */
@Composable
fun EditorScreen(
    workoutId: String,
    onBack: () -> Unit,
    onImportFile: (uri: Uri) -> Unit,
): Unit = TODO("implemented by the editor/import UI module")

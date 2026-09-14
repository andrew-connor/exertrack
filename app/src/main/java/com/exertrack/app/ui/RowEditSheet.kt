package com.exertrack.app.ui

import androidx.compose.runtime.Composable
import com.exertrack.app.data.ExerciseRow

/**
 * Bottom sheet editing one exercise: name, duration (seconds or m:ss; blank = untimed), trigger
 * (seconds or m:ss; blank = manual), notes (multi-line). Save is disabled until the name is
 * non-blank and both numeric fields parse. Used by both the editor and the active screen.
 */
@Composable
fun RowEditSheet(
    row: ExerciseRow,
    title: String,
    onDismiss: () -> Unit,
    onSave: (ExerciseRow) -> Unit,
    onDelete: (() -> Unit)? = null,
): Unit = TODO("implemented by the editor/import UI module")

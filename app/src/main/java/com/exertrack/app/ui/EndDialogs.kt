package com.exertrack.app.ui

import androidx.compose.runtime.Composable
import com.exertrack.app.data.LogEntry

/** "End workout?" sheet: Keep going / End and log / Discard. */
@Composable
fun EndWorkoutSheet(
    onKeepGoing: () -> Unit,
    onEndAndLog: () -> Unit,
    onDiscard: () -> Unit,
): Unit = TODO("implemented by the active/log UI module")

/** Shown when the engine is FINISHED: steps done, elapsed, notes field, Save. */
@Composable
fun CompletionDialog(
    entry: LogEntry,
    onSave: (notes: String) -> Unit,
): Unit = TODO("implemented by the active/log UI module")

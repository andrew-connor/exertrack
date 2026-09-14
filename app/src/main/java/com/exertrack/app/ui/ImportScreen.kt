package com.exertrack.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable

/**
 * Spreadsheet import (see docs/DESIGN.md): pick or receive a file, parse every sheet, let the user
 * map each sheet to New workout / Replace rows of an existing workout / Skip, preview rows with
 * problems and warnings, then apply all mappings in one go and call [onDone].
 */
@Composable
fun ImportScreen(
    initialUri: Uri?,
    replaceWorkoutId: String?,
    onDone: () -> Unit,
): Unit = TODO("implemented by the editor/import UI module")

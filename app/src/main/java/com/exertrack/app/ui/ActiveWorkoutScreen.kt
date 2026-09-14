package com.exertrack.app.ui

import androidx.compose.runtime.Composable

/**
 * The workout screen (see docs/DESIGN.md): counter, phase label, big clock, exercise name and
 * notes, step list that follows the current step, Back / primary / Skip buttons, edit sheet,
 * End-workout sheet, completion dialog. Loads the workout into the engine when shown in IDLE.
 * [onEditWorkout] opens the table editor (only offered while no session is active).
 */
@Composable
fun ActiveWorkoutScreen(
    workoutId: String,
    onBack: () -> Unit,
    onEditWorkout: (workoutId: String) -> Unit,
): Unit = TODO("implemented by the active/log UI module")

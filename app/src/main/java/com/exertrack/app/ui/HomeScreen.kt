package com.exertrack.app.ui

import androidx.compose.runtime.Composable

/**
 * Workout list (see docs/DESIGN.md). "New workout" creates one through the repository and calls
 * [onEditWorkout] with its id. Card menu: Edit, Duplicate, Rename, Export CSV, Delete (confirm).
 */
@Composable
fun HomeScreen(
    onOpenWorkout: (workoutId: String) -> Unit,
    onEditWorkout: (workoutId: String) -> Unit,
    onImport: () -> Unit,
    onLog: () -> Unit,
    onSettings: () -> Unit,
): Unit = TODO("implemented by the core UI module")

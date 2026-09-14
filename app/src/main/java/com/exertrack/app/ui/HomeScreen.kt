package com.exertrack.app.ui

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exertrack.app.AppGraph
import com.exertrack.app.data.Workout
import com.exertrack.app.io.Exporters
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    val repository = AppGraph.repository
    val workouts by repository.workouts.collectAsState()
    val session by AppGraph.engine.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val sorted = remember(workouts) {
        workouts.sortedWith(
            compareByDescending<Workout> { it.lastUsedAt ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    // Ids rather than Workout objects: the activity can be recreated (rotation, background kill)
    // while the rename dialog or the Save-as picker is up, and only saveable state survives that.
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Workout?>(null) }
    var exportTargetId by rememberSaveable { mutableStateOf<String?>(null) }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Exporters.MIME_CSV)
    ) { uri ->
        val target = exportTargetId?.let(repository::workout)
        exportTargetId = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (target == null) {
                // The picker has already created the document; do not leave an empty file behind.
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                snackbar.showSnackbar("Export failed: the workout could not be found")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { Files.writeTo(context, uri, Exporters.workoutCsv(target)) }
            }
            snackbar.showSnackbar(
                result.fold(
                    onSuccess = { "Exported ${target.name} as CSV" },
                    onFailure = { "Export failed: ${it.message ?: "could not write the file"}" },
                )
            )
        }
    }

    Scaffold(
        containerColor = ExColors.background,
        contentColor = ExColors.text,
        topBar = {
            ExTopBar(
                title = "Exertrack",
                actions = {
                    TextButton(onClick = onImport) { Text("Import") }
                    IconButton(onClick = onLog) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Workout log")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val created = repository.newWorkout()
                    onEditWorkout(created.id)
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New workout") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (sorted.isEmpty()) {
            EmptyState(
                text = "No workouts yet.\nTap New workout to build one, or Import a spreadsheet.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sorted, key = { it.id }) { workout ->
                    WorkoutCard(
                        workout = workout,
                        onOpen = { onOpenWorkout(workout.id) },
                        onEdit = { onEditWorkout(workout.id) },
                        onDuplicate = {
                            val copy = repository.duplicateWorkout(workout.id)
                            if (copy != null) notify("Created ${copy.name}")
                        },
                        onRename = { renameTargetId = workout.id },
                        onExport = {
                            exportTargetId = workout.id
                            exportLauncher.launch(Format.fileStem(workout.name) + ".csv")
                        },
                        onDelete = {
                            val inUse = session.isActive && session.workout?.id == workout.id
                            if (inUse) {
                                notify("${workout.name} is running. End the workout before deleting it.")
                            } else {
                                deleteTarget = workout
                            }
                        },
                    )
                }
            }
        }
    }

    renameTargetId?.let { id -> workouts.firstOrNull { it.id == id } }?.let { workout ->
        RenameDialog(
            workout = workout,
            onConfirm = { newName ->
                renameTargetId = null
                repository.renameWorkout(workout.id, newName)
            },
            onDismiss = { renameTargetId = null },
        )
    }

    deleteTarget?.let { workout ->
        ConfirmDialog(
            title = "Delete ${workout.name}?",
            text = "The workout is removed from this list. Its entries in the log are kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                deleteTarget = null
                repository.deleteWorkout(workout.id)
                notify("Deleted ${workout.name}")
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun WorkoutCard(
    workout: Workout,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ExColors.surface, contentColor = ExColors.text),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = workout.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = ExColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = summaryLine(workout),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ExColors.textDim,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = lastUsedLine(workout),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ExColors.textDim,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More options for ${workout.name}",
                        tint = ExColors.text,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = ExColors.surfaceHigh,
                ) {
                    MenuEntry("Edit") { menuOpen = false; onEdit() }
                    MenuEntry("Duplicate") { menuOpen = false; onDuplicate() }
                    MenuEntry("Rename") { menuOpen = false; onRename() }
                    MenuEntry("Export CSV") { menuOpen = false; onExport() }
                    DropdownMenuItem(
                        text = { Text("Delete", color = ExColors.danger) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, color = ExColors.text) }, onClick = onClick)
}

/** "10 exercises · 3 rounds · ~24 min" (rounds omitted when 1, estimate omitted when 0). */
private fun summaryLine(workout: Workout): String {
    val parts = ArrayList<String>(3)
    parts += if (workout.rows.size == 1) "1 exercise" else "${workout.rows.size} exercises"
    if (workout.rounds > 1) parts += "${workout.rounds} rounds"
    val estimate = workout.estimatedSeconds()
    if (estimate > 0) parts += "~" + Format.shortDuration(estimate)
    return parts.joinToString(" · ")
}

private fun lastUsedLine(workout: Workout): String =
    workout.lastUsedAt?.let { "Last used " + Format.date(it) } ?: "Never used"

@Composable
private fun RenameDialog(
    workout: Workout,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(workout.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(workout.name, selection = TextRange(0, workout.name.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val canSave = value.text.isNotBlank()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename workout") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) onConfirm(value.text.trim()) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = canSave) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

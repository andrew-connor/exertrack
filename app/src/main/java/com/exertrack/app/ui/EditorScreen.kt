package com.exertrack.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exertrack.app.AppGraph
import com.exertrack.app.data.ExerciseRow
import com.exertrack.app.data.MediaFiles
import com.exertrack.app.data.Repository
import com.exertrack.app.data.RowMedia
import com.exertrack.app.data.Workout
import com.exertrack.app.data.newId
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOCKED_HINT = "Finish or reset the running workout to change the list"
private const val ROUNDS_HINT = "Rounds repeat the whole list; the last exercise's trigger is used between rounds"

/**
 * Workout table editor (see docs/DESIGN.md). Autosaves through the repository, workout notes
 * included. Row cards are numbered "3/12" and show a preview of an attached photo or video;
 * duplicating such a row copies the file so no two rows share one. "Replace rows from file" opens
 * the document picker and hands the chosen URI to [onImportFile]; "Re-import from source" does the
 * same with the stored sourceUri. Structural edits are disabled while a session for this workout
 * is active; field edits, notes and media stay editable.
 */
@Composable
fun EditorScreen(
    workoutId: String,
    onBack: () -> Unit,
    onImportFile: (uri: Uri) -> Unit,
) {
    val repository = AppGraph.repository
    val engine = AppGraph.engine
    val workouts by repository.workouts.collectAsState()
    val session by engine.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val workout = workouts.firstOrNull { it.id == workoutId }
    if (workout == null) {
        // Deleted while the editor was open (or a stale back-stack entry): nothing to edit.
        LaunchedEffect(workoutId) { onBack() }
        return
    }

    val locked = session.isActive && session.workout?.id == workoutId

    /** Save a change built on the latest stored copy and let a loaded engine follow it. */
    fun persist(transform: (Workout) -> Workout) {
        val current = repository.workout(workoutId) ?: return
        val saved = repository.saveWorkout(transform(current))
        if (engine.state.value.workout?.id == saved.id) engine.updateWorkout(saved)
    }

    fun updateRows(transform: (List<ExerciseRow>) -> List<ExerciseRow>) {
        persist { it.copy(rows = transform(it.rows)) }
    }

    fun moveRow(index: Int, delta: Int) {
        updateRows { rows ->
            val target = index + delta
            if (index !in rows.indices || target !in rows.indices) return@updateRows rows
            rows.toMutableList().apply { add(target, removeAt(index)) }
        }
    }

    /** The row whose photo or video is being copied for a duplicate; its card shows a spinner. */
    var duplicatingRowId by remember(workoutId) { mutableStateOf<String?>(null) }

    fun insertDuplicate(rowId: String, media: RowMedia?) {
        updateRows { rows ->
            val index = rows.indexOfFirst { it.id == rowId }
            // If the row went away meanwhile the save below still runs and its sweep drops the copy.
            if (index < 0) return@updateRows rows
            rows.toMutableList().apply { add(index + 1, rows[index].copy(id = newId(), media = media)) }
        }
    }

    fun duplicateRow(row: ExerciseRow) {
        val media = row.media
        if (media == null) {
            insertDuplicate(row.id, null)
            return
        }
        if (duplicatingRowId != null) return
        // Two rows never share one file: copy it first, off the main thread.
        duplicatingRowId = row.id
        scope.launch {
            val copied = try {
                withContext(Dispatchers.IO) { MediaFiles.copy(context, media) }
            } finally {
                duplicatingRowId = null
            }
            val state = engine.state.value
            if (state.isActive && state.workout?.id == workoutId) {
                // A session started while the file was copying: structural edits are blocked now.
                copied?.let { c -> withContext(Dispatchers.IO) { MediaFiles.discard(context, c) } }
                return@launch
            }
            insertDuplicate(row.id, copied)
        }
    }

    fun deleteRow(rowId: String) {
        updateRows { rows -> rows.filterNot { it.id == rowId } }
    }

    // ---- header fields: local text so trimming and clamping in the repository never fight the cursor ----
    var nameText by rememberSaveable(workoutId) { mutableStateOf(workout.name) }
    var roundsText by rememberSaveable(workoutId) { mutableStateOf(workout.rounds.toString()) }
    var leadInText by rememberSaveable(workoutId) { mutableStateOf(workout.leadInSec.toString()) }
    var notesText by rememberSaveable(workoutId) { mutableStateOf(workout.notes) }
    val roundsValue = roundsText.trim().toIntOrNull()?.takeIf { it in 1..Repository.MAX_ROUNDS }
    val leadInValue = leadInText.trim().toIntOrNull()?.takeIf { it in 0..Repository.MAX_LEAD_IN_SEC }

    // ---- sheets and dialogs ----
    var editingRowId by rememberSaveable(workoutId) { mutableStateOf<String?>(null) }
    var newRowId by rememberSaveable(workoutId) { mutableStateOf<String?>(null) }
    var deleteRowId by rememberSaveable(workoutId) { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(PersistableOpenDocument) { uri ->
        if (uri != null) {
            takePersistableRead(context, uri)
            onImportFile(uri)
        }
    }

    Scaffold(
        containerColor = ExColors.background,
        contentColor = ExColors.text,
        topBar = { ExTopBar(title = "Edit workout", onBack = onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!locked) newRowId = newId() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add exercise") },
                containerColor = if (locked) ExColors.surfaceHigh else MaterialTheme.colorScheme.primary,
                contentColor = if (locked) ExColors.textDim else MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "fields") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (locked) {
                        Text(
                            text = LOCKED_HINT,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ExColors.paused,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { text ->
                            nameText = text
                            persist { it.copy(name = text) }
                        },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = roundsText,
                            onValueChange = { text ->
                                roundsText = text
                                text.trim().toIntOrNull()?.takeIf { it in 1..Repository.MAX_ROUNDS }?.let { v -> persist { it.copy(rounds = v) } }
                            },
                            label = { Text("Rounds") },
                            singleLine = true,
                            enabled = !locked,
                            isError = roundsValue == null,
                            supportingText = if (roundsValue == null) ({ Text("1 to ${Repository.MAX_ROUNDS}") }) else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = leadInText,
                            onValueChange = { text ->
                                leadInText = text
                                text.trim().toIntOrNull()?.takeIf { it in 0..Repository.MAX_LEAD_IN_SEC }?.let { v -> persist { it.copy(leadInSec = v) } }
                            },
                            label = { Text("Lead-in seconds") },
                            singleLine = true,
                            isError = leadInValue == null,
                            supportingText = if (leadInValue == null) ({ Text("0 to ${Repository.MAX_LEAD_IN_SEC}") }) else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = ROUNDS_HINT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ExColors.textDim,
                    )
                    OutlinedTextField(
                        value = notesText,
                        onValueChange = { text ->
                            notesText = text
                            persist { it.copy(notes = text.trim()) }
                        },
                        label = { Text("Workout notes") },
                        minLines = 2,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (workout.rows.size == 1) "1 exercise" else "${workout.rows.size} exercises",
                        style = MaterialTheme.typography.titleMedium,
                        color = ExColors.text,
                    )
                }
            }

            if (workout.rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "No exercises yet. Tap Add exercise, or replace the rows from a spreadsheet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = ExColors.textDim,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }

            itemsIndexed(workout.rows, key = { _, row -> row.id }) { index, row ->
                RowCard(
                    row = row,
                    position = index + 1,
                    total = workout.rows.size,
                    canMoveUp = !locked && index > 0,
                    canMoveDown = !locked && index < workout.rows.lastIndex,
                    structuralEnabled = !locked && duplicatingRowId == null,
                    duplicating = duplicatingRowId == row.id,
                    onEdit = { editingRowId = row.id },
                    onMoveUp = { moveRow(index, -1) },
                    onMoveDown = { moveRow(index, +1) },
                    onDuplicate = { duplicateRow(row) },
                    onDelete = { deleteRowId = row.id },
                )
            }

            item(key = "file-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = !locked,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                    ) {
                        Text("Replace rows from file")
                    }
                    val sourceUri = workout.sourceUri
                    if (sourceUri != null) {
                        OutlinedButton(
                            onClick = { onImportFile(Uri.parse(sourceUri)) },
                            enabled = !locked,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                        ) {
                            Text("Re-import from source")
                        }
                        workout.sourceSheet?.let { sheet ->
                            Text(
                                text = "Source sheet: $sheet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ExColors.textDim,
                            )
                        }
                    }
                    if (locked) {
                        Text(
                            text = LOCKED_HINT,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ExColors.paused,
                        )
                    }
                }
            }
        }
    }

    // ---- edit an existing row ----
    val editingIndex = editingRowId?.let { id -> workout.rows.indexOfFirst { it.id == id } } ?: -1
    val editingRow = workout.rows.getOrNull(editingIndex)
    editingRow?.let { row ->
        RowEditSheet(
            row = row,
            title = "Edit exercise ${editingIndex + 1}/${workout.rows.size}",
            onDismiss = { editingRowId = null },
            onSave = { saved ->
                updateRows { rows -> rows.map { if (it.id == row.id) saved.copy(id = row.id) else it } }
                editingRowId = null
            },
            onDelete = if (locked) null else {
                {
                    deleteRow(row.id)
                    editingRowId = null
                }
            },
        )
    }

    // ---- add a new row ----
    // The id is fixed while the sheet is open, so its field state survives recomposition.
    val newRow = newRowId?.let { id -> ExerciseRow(id = id, name = "") }
    newRow?.let { blank ->
        RowEditSheet(
            row = blank,
            title = "New exercise ${workout.rows.size + 1}",
            // "Save and add another" has already moved on to a fresh id by the time the sheet
            // reports this dismissal, and that fresh sheet must stay open.
            onDismiss = { if (newRowId == blank.id) newRowId = null },
            onSave = { saved ->
                updateRows { rows -> rows + saved.copy(id = blank.id) }
                newRowId = null
            },
            onSaveAndAddAnother = { saved ->
                updateRows { rows -> rows + saved.copy(id = blank.id) }
                // A new id re-keys the sheet's field state, so the next one opens blank.
                newRowId = if (locked) null else newId()
            },
        )
    }

    // ---- delete from the card button ----
    val deleteRow = deleteRowId?.let { id -> workout.rows.firstOrNull { it.id == id } }
    deleteRow?.let { row ->
        ConfirmDialog(
            title = "Delete ${row.name.ifBlank { "this exercise" }}?",
            text = "The exercise is removed from the workout.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                deleteRowId = null
                deleteRow(row.id)
            },
            onDismiss = { deleteRowId = null },
        )
    }
}

@Composable
private fun RowCard(
    row: ExerciseRow,
    /** 1-based place in the list, shown as "3/12" with [total]. */
    position: Int,
    total: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    structuralEnabled: Boolean,
    /** This row's photo or video is being copied for its duplicate. */
    duplicating: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ExColors.surface, contentColor = ExColors.text),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "$position/$total",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = ExColors.textDim,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = row.name.ifBlank { "(unnamed)" },
                        style = MaterialTheme.typography.titleMedium,
                        color = ExColors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${Format.rowDuration(row.durationSec)} · ${Format.rowTrigger(row.triggerSec)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ExColors.textDim,
                    )
                    if (row.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = row.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ExColors.textDim,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                row.media?.let { media ->
                    Spacer(Modifier.width(12.dp))
                    MediaThumbnail(
                        media = media,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDuplicate, enabled = structuralEnabled && !duplicating) {
                    if (duplicating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Duplicate")
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete, enabled = structuralEnabled) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete ${row.name}",
                        tint = if (structuralEnabled) ExColors.danger else ExColors.textDim,
                    )
                }
            }
        }
    }
}

package com.exertrack.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exertrack.app.AppGraph
import com.exertrack.app.data.Workout
import com.exertrack.app.io.ParsedRow
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stages of the import screen after a file has been chosen. */
private sealed class LoadState {
    data object Idle : LoadState()
    data object Loading : LoadState()
    data class Failed(val message: String) : LoadState()
    data class Loaded(val model: ImportModel) : LoadState()
}

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
) {
    val context = LocalContext.current
    val repository = AppGraph.repository
    val engine = AppGraph.engine
    val workouts by repository.workouts.collectAsState()
    val session by engine.state.collectAsState()

    var uri by rememberSaveable { mutableStateOf(initialUri) }
    // Guards the automatic picker launch so a recreated activity does not open a second picker.
    var pickerLaunched by rememberSaveable { mutableStateOf(initialUri != null) }
    var load by remember { mutableStateOf<LoadState>(LoadState.Idle) }
    // The file is re-read after recreation; the per-sheet choices and the completion dialog are
    // kept in saveable state and put back on the re-parsed model.
    val choices = rememberSaveable(saver = SheetChoicesSaver) { mutableStateMapOf<Int, SheetChoice>() }
    var outcome by rememberSaveable(stateSaver = ImportOutcomeSaver) { mutableStateOf<ImportOutcome?>(null) }

    val picker = rememberLauncherForActivityResult(PersistableOpenDocument) { picked ->
        if (picked != null) {
            takePersistableRead(context, picked)
            // Choices belong to the sheets of one file; a different file starts from the defaults.
            if (picked != uri) choices.clear()
            uri = picked
        } else if (uri == null) {
            onDone()
        }
    }

    LaunchedEffect(Unit) {
        if (uri == null && !pickerLaunched) {
            pickerLaunched = true
            picker.launch(arrayOf("*/*"))
        }
    }

    LaunchedEffect(uri) {
        val target = uri ?: return@LaunchedEffect
        load = LoadState.Loading
        val known = repository.workouts.value
        val result = withContext(Dispatchers.IO) { runCatching { readImportFile(context, target) } }
        load = result.fold(
            onSuccess = { file ->
                val built = buildImportModel(target, file, replaceWorkoutId, known)
                built.items.forEach { item -> choices[item.index]?.let(item::applyChoice) }
                LoadState.Loaded(built)
            },
            onFailure = { e -> LoadState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Could not read the file") },
        )
    }

    val model = (load as? LoadState.Loaded)?.model

    // Mirror every target, name and preview toggle into the saveable choice map as it changes.
    LaunchedEffect(model) {
        val current = model ?: return@LaunchedEffect
        snapshotFlow { current.items.map(SheetItem::currentChoice) }
            .collect { list -> list.forEachIndexed { i, choice -> choices[current.items[i].index] = choice } }
    }

    // One resolution pass shared by the cards, the Import button and applyImport.
    val resolvedAll = model?.let { resolveAll(it.items, workouts, session) }.orEmpty()
    val canImport = resolvedAll.any { it.target != ImportTarget.Skip }

    Scaffold(
        containerColor = ExColors.background,
        contentColor = ExColors.text,
        topBar = {
            ExTopBar(
                title = "Import",
                onBack = onDone,
                actions = {
                    if (load is LoadState.Loaded) {
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Change file") }
                    }
                },
            )
        },
        bottomBar = {
            if (model != null) {
                Button(
                    onClick = {
                        val current = model
                        outcome = applyImport(current, repository, engine)
                    },
                    enabled = canImport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .heightIn(min = 56.dp),
                ) {
                    Text("Import", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    ) { padding ->
        when (val stage = load) {
            LoadState.Idle -> PickPrompt(
                modifier = Modifier.padding(padding),
                onPick = { picker.launch(arrayOf("*/*")) },
            )
            LoadState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Reading file…", style = MaterialTheme.typography.bodyLarge, color = ExColors.textDim)
                }
            }
            is LoadState.Failed -> ErrorCard(
                message = stage.message,
                modifier = Modifier.padding(padding),
                onPickAnother = { picker.launch(arrayOf("*/*")) },
            )
            is LoadState.Loaded -> MappingList(
                model = stage.model,
                resolved = resolvedAll,
                workouts = workouts,
                modifier = Modifier.padding(padding),
            )
        }
    }

    outcome?.let { result ->
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text(if (result.isEmpty) "Nothing imported" else "Import complete") },
            text = { Text(result.summary) },
            confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
        )
    }
}

@Composable
private fun PickPrompt(modifier: Modifier, onPick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Choose a .xlsx or .csv file.\nColumns: exercise, duration, trigger, notes.",
            style = MaterialTheme.typography.bodyLarge,
            color = ExColors.textDim,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick, modifier = Modifier.heightIn(min = 56.dp)) { Text("Pick a file") }
    }
}

@Composable
private fun ErrorCard(message: String, modifier: Modifier, onPickAnother: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ExColors.surface, contentColor = ExColors.text),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Could not import this file", style = MaterialTheme.typography.titleMedium, color = ExColors.danger)
                Text(message, style = MaterialTheme.typography.bodyLarge, color = ExColors.text)
                OutlinedButton(
                    onClick = onPickAnother,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) {
                    Text("Pick another file")
                }
            }
        }
    }
}

@Composable
private fun MappingList(
    model: ImportModel,
    /** One entry per sheet in sheet order, from [resolveAll]. */
    resolved: List<ResolvedTarget>,
    workouts: List<Workout>,
    modifier: Modifier,
) {
    val replaceOptions = remember(workouts) { workouts.sortedBy { it.name.lowercase() } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "file") {
            Column {
                Text(model.fileName, style = MaterialTheme.typography.titleMedium, color = ExColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (model.isXlsx) plural(model.items.size, "sheet") else "CSV file",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ExColors.textDim,
                )
            }
        }
        if (model.items.isEmpty()) {
            item(key = "no-sheets") {
                Text("This file has no sheets to import.", style = MaterialTheme.typography.bodyLarge, color = ExColors.textDim)
            }
        }
        items(model.items, key = { it.index }) { item ->
            SheetCard(item = item, resolved = resolved[item.index], workouts = workouts, replaceOptions = replaceOptions)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetCard(
    item: SheetItem,
    resolved: ResolvedTarget,
    workouts: List<Workout>,
    replaceOptions: List<Workout>,
) {
    val blocked = item.isBlocked
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ExColors.surface, contentColor = ExColors.text),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { item.expanded = !item.expanded }
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.table.sheetName.ifBlank { "Sheet ${item.index + 1}" },
                        style = MaterialTheme.typography.titleLarge,
                        color = ExColors.text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.statusLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            item.table.problemCount > 0 -> ExColors.danger
                            item.table.warningCount > 0 -> ExColors.paused
                            else -> ExColors.textDim
                        },
                    )
                    Text(
                        text = item.headerVerdict,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ExColors.textDim,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (item.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (item.expanded) "Hide preview" else "Show preview",
                    tint = ExColors.textDim,
                )
            }

            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExposedDropdownMenuBox(
                    expanded = menuOpen && !blocked,
                    onExpandedChange = { if (!blocked) menuOpen = it },
                ) {
                    OutlinedTextField(
                        value = if (blocked) "Skip" else targetLabel(item.target, workouts),
                        onValueChange = {},
                        readOnly = true,
                        enabled = !blocked,
                        label = { Text("Import as") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen && !blocked) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = !blocked)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = menuOpen && !blocked,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("New workout", color = ExColors.text) },
                            onClick = { item.target = ImportTarget.NewWorkout; menuOpen = false },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                        replaceOptions.forEach { workout ->
                            DropdownMenuItem(
                                text = { Text("Replace rows of ${workout.name}", color = ExColors.text) },
                                onClick = { item.target = ImportTarget.Replace(workout.id); menuOpen = false },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Skip", color = ExColors.text) },
                            onClick = { item.target = ImportTarget.Skip; menuOpen = false },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }

                if (!blocked && item.target == ImportTarget.NewWorkout) {
                    OutlinedTextField(
                        value = item.newName,
                        onValueChange = { item.newName = it },
                        label = { Text("Workout name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                resolved.reason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (blocked && item.table.problemCount == 0) ExColors.textDim else ExColors.danger,
                    )
                }
            }

            if (item.expanded) {
                HorizontalDivider(color = ExColors.surfaceHigh)
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                    if (item.table.rows.isEmpty()) {
                        Text("No rows.", style = MaterialTheme.typography.bodyMedium, color = ExColors.textDim)
                    }
                    item.table.rows.forEach { parsed -> PreviewRow(parsed) }
                }
            }
        }
    }
}

@Composable
private fun PreviewRow(parsed: ParsedRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = parsed.rowNumber.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = ExColors.textDim,
            modifier = Modifier.width(40.dp),
        )
        Column(Modifier.weight(1f)) {
            val row = parsed.row
            if (row != null) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ExColors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${Format.rowDuration(row.durationSec)} · ${Format.rowTrigger(row.triggerSec)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ExColors.textDim,
                )
                val firstLine = row.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                if (!firstLine.isNullOrEmpty()) {
                    Text(
                        text = firstLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ExColors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    text = "Row skipped",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ExColors.textDim,
                )
            }
            parsed.problem?.let { problem ->
                Text(problem, style = MaterialTheme.typography.bodyMedium, color = ExColors.danger)
            }
            parsed.warning?.let { warning ->
                Text(warning, style = MaterialTheme.typography.bodyMedium, color = ExColors.paused)
            }
        }
    }
}

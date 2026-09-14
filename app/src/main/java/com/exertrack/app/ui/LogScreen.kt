package com.exertrack.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exertrack.app.AppGraph
import com.exertrack.app.data.LogEntry
import com.exertrack.app.io.Exporters
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Workout log list, filter, detail (notes edit, delete), Export CSV / XLSX / Share (see docs/DESIGN.md). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit): Unit {
    val repository = AppGraph.repository
    val entries by repository.log.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    /** Workout name to show, or null for all. */
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<LogEntry?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var filterOpen by remember { mutableStateOf(false) }

    val names = remember(entries) {
        entries.map { it.workoutName }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    LaunchedEffect(names) {
        // The filtered workout's last entry was deleted: fall back to everything.
        val current = filter
        if (current != null && names.none { it == current }) filter = null
    }
    val filtered = remember(entries, filter) {
        val name = filter
        if (name == null) entries else entries.filter { it.workoutName == name }
    }
    val listItems = remember(filtered) { groupByDay(filtered) }
    val selected = selectedId?.let { id -> entries.firstOrNull { it.id == id } }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    suspend fun buildBytes(entriesToExport: List<LogEntry>, xlsx: Boolean): ByteArray = withContext(Dispatchers.Default) {
        if (xlsx) Exporters.logXlsx(entriesToExport) else Exporters.logCsv(entriesToExport)
    }

    fun exportTo(uri: Uri, xlsx: Boolean) {
        val toExport = filtered
        scope.launch {
            val result = runCatching {
                val bytes = buildBytes(toExport, xlsx)
                withContext(Dispatchers.IO) { Files.writeTo(context, uri, bytes) }
            }
            snackbar.showSnackbar(
                result.fold(
                    onSuccess = { "Saved" },
                    onFailure = { "Export failed: ${it.message ?: "could not write the file"}" },
                )
            )
        }
    }

    fun share(xlsx: Boolean) {
        val toExport = filtered
        scope.launch {
            val result = runCatching {
                val bytes = buildBytes(toExport, xlsx)
                val name = Format.exportFileName("exertrack-log", if (xlsx) "xlsx" else "csv", System.currentTimeMillis())
                Files.share(context, bytes, name, if (xlsx) Exporters.MIME_XLSX else Exporters.MIME_CSV)
            }
            result.onFailure { snackbar.showSnackbar("Share failed: ${it.message ?: "could not build the file"}") }
        }
    }

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Exporters.MIME_CSV)) { uri ->
        if (uri != null) exportTo(uri, xlsx = false)
    }
    val exportXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Exporters.MIME_XLSX)) { uri ->
        if (uri != null) exportTo(uri, xlsx = true)
    }

    Scaffold(
        containerColor = ExColors.background,
        contentColor = ExColors.text,
        topBar = {
            ExTopBar(
                title = "Log",
                onBack = onBack,
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Export options")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = ExColors.surfaceHigh,
                        ) {
                            val hasEntries = filtered.isNotEmpty()
                            MenuEntry("Export CSV", hasEntries) {
                                menuOpen = false
                                exportCsv.launch(Format.exportFileName("exertrack-log", "csv", System.currentTimeMillis()))
                            }
                            MenuEntry("Export XLSX", hasEntries) {
                                menuOpen = false
                                exportXlsx.launch(Format.exportFileName("exertrack-log", "xlsx", System.currentTimeMillis()))
                            }
                            MenuEntry("Share CSV", hasEntries) {
                                menuOpen = false
                                share(xlsx = false)
                            }
                            MenuEntry("Share XLSX", hasEntries) {
                                menuOpen = false
                                share(xlsx = true)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                text = "No sessions logged yet.\nEvery workout you start shows up here.",
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ExposedDropdownMenuBox(
                    expanded = filterOpen,
                    onExpandedChange = { filterOpen = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    OutlinedTextField(
                        value = filter ?: "All workouts",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Show") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = filterOpen) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = filterOpen,
                        onDismissRequest = { filterOpen = false },
                        containerColor = ExColors.surfaceHigh,
                    ) {
                        DropdownMenuItem(
                            text = { Text("All workouts", color = ExColors.text) },
                            onClick = {
                                filter = null
                                filterOpen = false
                            },
                        )
                        names.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name, color = ExColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    filter = name
                                    filterOpen = false
                                },
                            )
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    EmptyState("No sessions for this workout yet.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(count = listItems.size, key = { listItems[it].key }) { index ->
                            when (val item = listItems[index]) {
                                is LogListItem.DayHeader -> Text(
                                    text = item.day,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ExColors.textDim,
                                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
                                )
                                is LogListItem.Row -> LogRow(entry = item.entry, onClick = { selectedId = item.entry.id })
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        LogDetailDialog(
            entry = entry,
            onSave = { notes ->
                repository.updateLogNotes(entry.id, notes)
                selectedId = null
                notify("Notes saved")
            },
            onDelete = { deleteTarget = entry },
            onClose = { selectedId = null },
        )
    }

    deleteTarget?.let { entry ->
        ConfirmDialog(
            title = "Delete this entry?",
            text = "${entry.workoutName} on ${Format.date(entry.startedAt, entry.zoneId)} is removed from the log. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                deleteTarget = null
                if (selectedId == entry.id) selectedId = null
                repository.deleteLogEntry(entry.id)
                notify("Entry deleted")
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

// ---- list ----

private sealed class LogListItem(val key: String) {
    class DayHeader(val day: String) : LogListItem("day:$day")
    class Row(val entry: LogEntry) : LogListItem(entry.id)
}

/** Entries (already newest first) with a header before the first entry of each day. */
private fun groupByDay(entries: List<LogEntry>): List<LogListItem> {
    val items = ArrayList<LogListItem>(entries.size + 8)
    var lastDay: String? = null
    for (entry in entries) {
        val day = Format.date(entry.startedAt, entry.zoneId)
        if (day != lastDay) {
            items += LogListItem.DayHeader(day)
            lastDay = day
        }
        items += LogListItem.Row(entry)
    }
    return items
}

@Composable
private fun LogRow(entry: LogEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ExColors.surface, contentColor = ExColors.text),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = entry.workoutName,
                style = MaterialTheme.typography.titleMedium,
                color = ExColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summaryLine(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = ExColors.textDim,
            )
            val preview = notesPreview(entry.notes)
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ExColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** "7:05 AM - 7:31 AM · 14/14 · completed" */
private fun summaryLine(entry: LogEntry): String {
    val end = entry.finishedAt?.let { Format.time(it, entry.zoneId) } ?: "in progress"
    return "${Format.time(entry.startedAt, entry.zoneId)} - $end · ${entry.completedSteps}/${entry.totalSteps} · ${statusText(entry)}"
}

private fun statusText(entry: LogEntry): String = entry.status.name.lowercase().replace('_', ' ')

private fun notesPreview(notes: String): String =
    notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

// ---- detail ----

@Composable
private fun LogDetailDialog(
    entry: LogEntry,
    onSave: (notes: String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var notes by remember(entry.id) { mutableStateOf(entry.notes) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(entry.workoutName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DetailRow("Date", Format.date(entry.startedAt, entry.zoneId))
                DetailRow("Start", Format.time(entry.startedAt, entry.zoneId))
                DetailRow("End", entry.finishedAt?.let { Format.time(it, entry.zoneId) } ?: "in progress")
                DetailRow("Elapsed", Format.clock(entry.elapsedSec))
                DetailRow("Paused", Format.clock(entry.pausedSec))
                DetailRow("Steps", "${entry.completedSteps}/${entry.totalSteps}")
                DetailRow("Rounds", entry.rounds.toString())
                DetailRow("Status", statusText(entry))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(notes) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = ExColors.danger) }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ExColors.textDim,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = ExColors.text,
        )
    }
}

@Composable
private fun MenuEntry(label: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (enabled) ExColors.text else ExColors.textDim) },
        enabled = enabled,
        onClick = onClick,
    )
}

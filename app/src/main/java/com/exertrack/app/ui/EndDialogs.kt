package com.exertrack.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.exertrack.app.data.LogEntry
import com.exertrack.app.data.SessionStatus
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.launch

/** "End workout?" sheet: Keep going / End and log / Discard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndWorkoutSheet(
    onKeepGoing: () -> Unit,
    onEndAndLog: () -> Unit,
    onDiscard: () -> Unit,
): Unit {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Slide the sheet away before handing the choice back, so the dismissal animates.
    fun choose(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { cause -> if (cause == null) action() }
    }

    ModalBottomSheet(
        onDismissRequest = onKeepGoing,
        sheetState = sheetState,
        containerColor = ExColors.surface,
        contentColor = ExColors.text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("End workout?", style = MaterialTheme.typography.headlineMedium, color = ExColors.text)
            Text(
                text = "End and log keeps a log entry with what you completed; Discard deletes it.",
                style = MaterialTheme.typography.bodyMedium,
                color = ExColors.textDim,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { choose(onKeepGoing) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
            ) {
                Text("Keep going", style = MaterialTheme.typography.titleLarge)
            }
            OutlinedButton(
                onClick = { choose(onEndAndLog) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ExColors.text),
            ) {
                Text("End and log", style = MaterialTheme.typography.titleLarge)
            }
            TextButton(
                onClick = { choose(onDiscard) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Discard", style = MaterialTheme.typography.titleLarge, color = ExColors.danger)
            }
        }
    }
}

/** Shown when the engine is FINISHED: steps done, elapsed, notes field, Save. */
@Composable
fun CompletionDialog(
    entry: LogEntry,
    onSave: (notes: String) -> Unit,
): Unit {
    var notes by rememberSaveable(entry.id) { mutableStateOf(entry.notes) }
    val title = if (entry.status == SessionStatus.COMPLETED) "Workout complete" else "Workout stopped"

    AlertDialog(
        // The session's log entry is waiting for its notes: the only way out is Save.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.workoutName, style = MaterialTheme.typography.bodyLarge, color = ExColors.text)
                Text(
                    text = "${entry.completedSteps}/${entry.totalSteps} steps · ${Format.clock(entry.elapsedSec)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ExColors.text,
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(notes) }) {
                Text("Save", style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

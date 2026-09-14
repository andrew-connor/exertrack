package com.exertrack.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.exertrack.app.data.ExerciseRow
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.delay

/**
 * Bottom sheet editing one exercise: name, duration (seconds or m:ss; blank = untimed), trigger
 * (seconds or m:ss; blank = manual), notes (multi-line). Save is disabled until the name is
 * non-blank and both numeric fields parse. Used by both the editor and the active screen.
 *
 * Save calls [onSave] with the edited row (same id as [row]) and then [onDismiss]; Delete asks for
 * confirmation, then calls [onDelete] and [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowEditSheet(
    row: ExerciseRow,
    title: String,
    onDismiss: () -> Unit,
    onSave: (ExerciseRow) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by rememberSaveable(row.id) { mutableStateOf(row.name) }
    var durationText by rememberSaveable(row.id) { mutableStateOf(secondsText(row.durationSec)) }
    var triggerText by rememberSaveable(row.id) { mutableStateOf(secondsText(row.triggerSec)) }
    var notes by rememberSaveable(row.id) { mutableStateOf(row.notes) }
    var confirmDelete by rememberSaveable(row.id) { mutableStateOf(false) }

    val duration = parseTimeField(durationText)
    val trigger = parseTimeField(triggerText)
    val canSave = name.isNotBlank() && duration is TimeField.Value && trigger is TimeField.Value

    val nameFocus = remember { FocusRequester() }
    // A blank row is a new exercise: open with the keyboard on the name field.
    LaunchedEffect(row.id) {
        if (row.name.isBlank()) {
            delay(200)
            runCatching { nameFocus.requestFocus() }
        }
    }

    fun save() {
        val d = duration as? TimeField.Value ?: return
        val t = trigger as? TimeField.Value ?: return
        if (name.isBlank()) return
        onSave(
            ExerciseRow(
                id = row.id,
                name = name.trim(),
                durationSec = d.seconds,
                triggerSec = t.seconds,
                notes = notes.trim(),
            )
        )
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ExColors.surface,
        contentColor = ExColors.text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = ExColors.text)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Exercise name") },
                singleLine = true,
                isError = name.isBlank(),
                supportingText = if (name.isBlank()) ({ Text("Required") }) else null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocus),
            )

            OutlinedTextField(
                value = durationText,
                onValueChange = { durationText = it },
                label = { Text("Duration") },
                singleLine = true,
                isError = duration is TimeField.Invalid,
                supportingText = {
                    Text(
                        if (duration is TimeField.Invalid) "Enter seconds like 90 or a time like 1:30"
                        else "seconds or m:ss, blank = untimed"
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = triggerText,
                onValueChange = { triggerText = it },
                label = { Text("Trigger") },
                singleLine = true,
                isError = trigger is TimeField.Invalid,
                supportingText = {
                    Text(
                        if (trigger is TimeField.Invalid) "Enter seconds like 15 or a time like 0:45"
                        else "rest seconds or m:ss before the next exercise, blank = manual"
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) {
                        Text("Delete", color = ExColors.danger)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 52.dp),
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { save() },
                    enabled = canSave,
                    modifier = Modifier.heightIn(min = 52.dp),
                ) {
                    Text("Save")
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Delete ${row.name.ifBlank { "this exercise" }}?",
            text = "The exercise is removed from the workout.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
                onDismiss()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Result of parsing a duration / trigger field. */
private sealed class TimeField {
    /** Parsed seconds; null for blank or zero (untimed / manual). */
    data class Value(val seconds: Int?) : TimeField()
    data object Invalid : TimeField()
}

private val PLAIN_SECONDS = Regex("""^\d{1,6}$""")
private val MINUTES_SECONDS = Regex("""^(\d{1,4}):(\d{1,2})$""")
private val HOURS_MINUTES_SECONDS = Regex("""^(\d{1,2}):(\d{1,2}):(\d{1,2})$""")

/** blank -> null, "90" -> 90, "1:30" -> 90, "0:45" -> 45, "1:02:03" -> 3723, 0 -> null; anything else is invalid. */
private fun parseTimeField(text: String): TimeField {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return TimeField.Value(null)
    val seconds: Long = when {
        PLAIN_SECONDS.matches(trimmed) -> trimmed.toLong()
        else -> {
            val hms = HOURS_MINUTES_SECONDS.matchEntire(trimmed)
            val ms = MINUTES_SECONDS.matchEntire(trimmed)
            when {
                hms != null -> {
                    val h = hms.groupValues[1].toLong()
                    val m = hms.groupValues[2].toLong()
                    val s = hms.groupValues[3].toLong()
                    if (m > 59 || s > 59) return TimeField.Invalid
                    h * 3600 + m * 60 + s
                }
                ms != null -> {
                    val m = ms.groupValues[1].toLong()
                    val s = ms.groupValues[2].toLong()
                    if (s > 59) return TimeField.Invalid
                    m * 60 + s
                }
                else -> return TimeField.Invalid
            }
        }
    }
    if (seconds > Int.MAX_VALUE) return TimeField.Invalid
    return TimeField.Value(seconds.toInt().takeIf { it > 0 })
}

/** Plain seconds for pre-filling a field ("90"); blank for untimed / manual. */
private fun secondsText(seconds: Int?): String = if (seconds == null || seconds <= 0) "" else seconds.toString()

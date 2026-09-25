package com.exertrack.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.exertrack.app.AppGraph
import com.exertrack.app.data.ExerciseRow
import com.exertrack.app.data.MediaFiles
import com.exertrack.app.data.MediaKind
import com.exertrack.app.data.RowMedia
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet editing one exercise: name, duration (seconds or m:ss; blank = untimed), trigger
 * (seconds or m:ss; blank = manual), notes (multi-line) and an optional photo or video. Save is
 * disabled until the name is non-blank and both numeric fields parse. Used by both the editor and
 * the active screen.
 *
 * Save commits a newly picked photo or video (see [MediaFiles]) off the main thread, then calls
 * [onSave] with the edited row (same id as [row]) and [onDismiss]. Cancelling discards a pick that
 * was never saved. Delete asks for confirmation, then calls [onDelete] and [onDismiss]. When
 * [onSaveAndAddAnother] is given, a "Save and add another" button saves the same way but calls
 * that instead of [onSave], so the caller can open the next blank sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowEditSheet(
    row: ExerciseRow,
    title: String,
    onDismiss: () -> Unit,
    onSave: (ExerciseRow) -> Unit,
    onDelete: (() -> Unit)? = null,
    onSaveAndAddAnother: ((ExerciseRow) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by rememberSaveable(row.id) { mutableStateOf(row.name) }
    var durationText by rememberSaveable(row.id) { mutableStateOf(secondsText(row.durationSec)) }
    var triggerText by rememberSaveable(row.id) { mutableStateOf(secondsText(row.triggerSec)) }
    var notes by rememberSaveable(row.id) { mutableStateOf(row.notes) }
    var confirmDelete by rememberSaveable(row.id) { mutableStateOf(false) }

    // The photo or video the row will have after Save: the stored one, a fresh pick still in the
    // pending area, or null after Remove. Kept as two strings so it survives recreation.
    var draftMedia by rememberSaveable(row.id, stateSaver = MediaDraftSaver) { mutableStateOf(row.media) }
    var importing by remember(row.id) { mutableStateOf(false) }
    var saving by remember(row.id) { mutableStateOf(false) }
    var mediaError by remember(row.id) { mutableStateOf<String?>(null) }
    val busy = importing || saving

    val duration = parseTimeField(durationText)
    val trigger = parseTimeField(triggerText)
    val canSave = name.isNotBlank() && duration is TimeField.Value && trigger is TimeField.Value && !busy

    val nameFocus = remember { FocusRequester() }
    // A blank row is a new exercise: open with the keyboard on the name field.
    LaunchedEffect(row.id) {
        if (row.name.isBlank()) {
            delay(200)
            runCatching { nameFocus.requestFocus() }
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val previous = draftMedia?.takeIf { it.isPending }
        importing = true
        mediaError = null
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { MediaFiles.importPending(context, uri) }
                draftMedia = imported
                // The pick it replaces was never saved, so nothing else can reference it.
                previous?.let { discardLater(context, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mediaError = e.message ?: "Could not add that file"
            } finally {
                importing = false
            }
        }
    }

    fun pick() {
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    fun removeMedia() {
        val current = draftMedia ?: return
        draftMedia = null
        mediaError = null
        if (current.isPending) discardLater(context, current)
    }

    /** Close without saving; a pick that was never saved is deleted again. */
    fun dismiss() {
        // While a save is committing, that coroutine owns the pending file.
        if (!saving) draftMedia?.takeIf { it.isPending }?.let { discardLater(context, it) }
        onDismiss()
    }

    fun save(andAddAnother: Boolean) {
        val d = duration as? TimeField.Value ?: return
        val t = trigger as? TimeField.Value ?: return
        if (name.isBlank() || busy) return
        val rowName = name.trim()
        val rowNotes = notes.trim()
        val draft = draftMedia
        saving = true
        mediaError = null
        scope.launch {
            val committed: RowMedia? = try {
                withContext(Dispatchers.IO) {
                    if (draft != null && draft.isPending && !MediaFiles.file(context, draft).isFile) {
                        throw MissingMediaException()
                    }
                    draft?.let { MediaFiles.commit(context, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MissingMediaException) {
                // The pending area was cleared by a restart while the sheet was open.
                draftMedia = null
                mediaError = "The photo or video is no longer available; add it again"
                saving = false
                return@launch
            } catch (e: Exception) {
                mediaError = e.message ?: "Could not keep the photo or video"
                saving = false
                return@launch
            }
            draftMedia = committed
            val edited = ExerciseRow(
                id = row.id,
                name = rowName,
                durationSec = d.seconds,
                triggerSec = t.seconds,
                notes = rowNotes,
                media = committed,
            )
            saving = false
            if (andAddAnother && onSaveAndAddAnother != null) onSaveAndAddAnother(edited) else onSave(edited)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { dismiss() },
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

            // ---- photo or video ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Photo or video", style = MaterialTheme.typography.titleMedium, color = ExColors.text)
                Text(
                    text = "Shown with the notes while the exercise is coming up or running.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ExColors.textDim,
                )
                val media = draftMedia
                if (media != null) {
                    MediaThumbnail(
                        media = media,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                mediaError?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = ExColors.danger)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (media == null) {
                        OutlinedButton(
                            onClick = { pick() },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Add photo or video")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { pick() },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Replace")
                        }
                        TextButton(
                            onClick = { removeMedia() },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Remove", color = if (busy) ExColors.textDim else ExColors.danger)
                        }
                    }
                    if (importing) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // The long label gets a row of its own so it never truncates on a narrow phone.
            if (onSaveAndAddAnother != null) {
                OutlinedButton(
                    onClick = { save(andAddAnother = true) },
                    enabled = canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text("Save and add another")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = !saving,
                        modifier = Modifier.heightIn(min = 52.dp),
                    ) {
                        Text("Delete", color = if (saving) ExColors.textDim else ExColors.danger)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(
                    onClick = { dismiss() },
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = 52.dp),
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { save(andAddAnother = false) },
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
                dismiss()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Thrown when a pending pick's file vanished before Save (a restart clears the pending area). */
private class MissingMediaException : Exception()

/**
 * Delete a pick nobody will save. Runs on the process scope because the sheet that made the pick
 * is usually leaving composition at this point, which cancels its own scope.
 */
private fun discardLater(context: Context, media: RowMedia) {
    val app = context.applicationContext
    AppGraph.mainScope.launch(Dispatchers.IO) { MediaFiles.discard(app, media) }
}

/** Saves the media draft as [file, kind]; the state saver keeps a null draft null. */
private val MediaDraftSaver = listSaver<RowMedia?, String>(
    save = { media -> if (media == null) emptyList() else listOf(media.file, media.kind.name) },
    restore = { saved ->
        val file = saved.getOrNull(0).orEmpty()
        val kind = runCatching { MediaKind.valueOf(saved.getOrNull(1).orEmpty()) }.getOrNull()
        if (file.isEmpty() || kind == null) null else RowMedia(file, kind)
    },
)

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

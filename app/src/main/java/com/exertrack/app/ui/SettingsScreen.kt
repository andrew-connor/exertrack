package com.exertrack.app.ui

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.exertrack.app.AppGraph
import com.exertrack.app.data.VibrationMode
import com.exertrack.app.engine.ChimeKind
import com.exertrack.app.io.Backup
import com.exertrack.app.io.Exporters
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Toggles, sound/speech test buttons, backup export and restore (see docs/DESIGN.md). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val repository = AppGraph.repository
    val settings by repository.settings.collectAsState()
    val session by AppGraph.engine.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // The picked backup file is saveable state, so the restore confirmation survives recreation
    // (rotation, background kill) while the file picker or the dialog is up; the parsed contents
    // are rebuilt from it below rather than kept across recreation.
    var restoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingRestore by remember { mutableStateOf<Backup.Data?>(null) }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Exporters.MIME_JSON)
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val now = System.currentTimeMillis()
                        val text = Backup.toJson(
                            repository.workouts.value,
                            repository.log.value,
                            repository.settings.value,
                            now,
                        )
                        Files.writeTo(context, uri, text.toByteArray(Charsets.UTF_8))
                    }
                }
                snackbar.showSnackbar(
                    result.fold(
                        onSuccess = { "Backup saved" },
                        onFailure = { "Could not save the backup: ${it.message ?: "write failed"}" },
                    )
                )
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri
    }

    LaunchedEffect(restoreUri) {
        val uri = restoreUri
        if (uri == null) {
            pendingRestore = null
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { Backup.parse(String(Files.readBytes(context, uri), Charsets.UTF_8)) }
        }
        result.fold(
            onSuccess = { pendingRestore = it },
            onFailure = {
                // notify() runs on the screen scope, so clearing the key here cannot cancel it.
                notify("Could not read the backup: ${it.message ?: "unreadable file"}")
                restoreUri = null
            },
        )
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        }.getOrNull() ?: "unknown"
    }

    Scaffold(
        containerColor = ExColors.background,
        contentColor = ExColors.text,
        topBar = { ExTopBar(title = "Settings", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            SectionHeader("Sounds and speech")
            SwitchRow(
                title = "Spoken announcements",
                subtitle = "Says each exercise and its duration",
                checked = settings.speechEnabled,
            ) { on -> repository.updateSettings { it.copy(speechEnabled = on) } }
            SwitchRow(
                title = "Duck other audio for chimes",
                subtitle = "Briefly lowers music while a chime or announcement plays",
                checked = settings.duckOtherAudio,
            ) { on -> repository.updateSettings { it.copy(duckOtherAudio = on) } }
            SwitchRow(
                title = "3-2-1 warning ticks",
                checked = settings.warningTicks,
            ) { on -> repository.updateSettings { it.copy(warningTicks = on) } }
            SwitchRow(
                title = "Mute chimes and speech",
                subtitle = "Vibration still works while muted",
                checked = settings.chimesMuted,
            ) { on -> repository.updateSettings { it.copy(chimesMuted = on) } }

            SectionHeader("Vibration")
            VibrationSelector(selected = settings.vibrationMode) { mode ->
                repository.updateSettings { it.copy(vibrationMode = mode) }
            }
            Text(
                text = "When silent: ringer off or on vibrate, Do Not Disturb on, or media volume at zero.",
                style = MaterialTheme.typography.bodyMedium,
                color = ExColors.textDim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionHeader("Screen")
            SwitchRow(
                title = "Keep screen on during workouts",
                checked = settings.keepScreenOn,
            ) { on -> repository.updateSettings { it.copy(keepScreenOn = on) } }

            SectionHeader("Test sounds")
            Text(
                text = "Plays even while muted, so you can check the volume.",
                style = MaterialTheme.typography.bodyMedium,
                color = ExColors.textDim,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TestButton("Done") { AppGraph.cues.test(ChimeKind.DONE) }
                TestButton("Go") { AppGraph.cues.test(ChimeKind.GO) }
                TestButton("Tick") { AppGraph.cues.test(ChimeKind.TICK) }
                TestButton("Complete") { AppGraph.cues.test(ChimeKind.COMPLETE) }
                TestButton("Speech") { AppGraph.cues.testSpeech() }
            }

            SectionHeader("Backup")
            Text(
                text = "One file with every workout, the whole log and these settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = ExColors.textDim,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    exportLauncher.launch(Format.exportFileName("exertrack-backup", "json", System.currentTimeMillis()))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 48.dp),
            ) { Text("Export backup") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (session.isActive) {
                        notify("End the running workout before restoring a backup.")
                    } else {
                        restoreLauncher.launch(arrayOf("application/json", "*/*"))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 48.dp),
            ) { Text("Restore backup") }

            SectionHeader("About")
            Text(
                text = "Exertrack",
                style = MaterialTheme.typography.bodyLarge,
                color = ExColors.text,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = "Version $versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = ExColors.textDim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    pendingRestore?.let { data ->
        val workoutsText = if (data.workouts.size == 1) "1 workout" else "${data.workouts.size} workouts"
        val logText = if (data.log.size == 1) "1 log entry" else "${data.log.size} log entries"
        val dateText = data.exportedAt?.let { " from " + Format.date(it) } ?: ""
        ConfirmDialog(
            title = "Restore backup?",
            text = "Replace all data with $workoutsText and $logText$dateText? Everything currently in the app is overwritten.",
            confirmLabel = "Replace",
            destructive = true,
            onConfirm = {
                restoreUri = null
                pendingRestore = null
                repository.replaceAll(data.workouts, data.log, data.settings)
                notify("Backup restored")
            },
            onDismiss = {
                restoreUri = null
                pendingRestore = null
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    HorizontalDivider(
        modifier = Modifier.padding(top = 16.dp),
        color = MaterialTheme.colorScheme.outline,
    )
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A full-width toggle row; the whole row is the touch target (min 56 dp tall). */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ExColors.text)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ExColors.textDim)
            }
        }
        Spacer(Modifier.width(16.dp))
        // The row handles the click; the switch only reflects the value.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VibrationSelector(selected: VibrationMode, onSelect: (VibrationMode) -> Unit) {
    val options = listOf(
        VibrationMode.ALWAYS to "Always",
        VibrationMode.WHEN_SILENT to "When silent",
        VibrationMode.NEVER to "Never",
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(48.dp),
    ) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    activeBorderColor = MaterialTheme.colorScheme.outline,
                    inactiveContainerColor = ExColors.surface,
                    inactiveContentColor = ExColors.text,
                    inactiveBorderColor = MaterialTheme.colorScheme.outline,
                ),
                // Selection is shown by colour; the check icon would crowd "When silent" on a phone.
                icon = {},
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TestButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text(label) }
}

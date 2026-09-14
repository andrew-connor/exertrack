package com.exertrack.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.exertrack.app.AppGraph
import com.exertrack.app.data.ExerciseRow
import com.exertrack.app.engine.Phase
import com.exertrack.app.engine.SessionState
import com.exertrack.app.ui.theme.ExColors
import kotlinx.coroutines.launch

/** The list stops following the current step for this long after the user scrolls it. */
private const val FOLLOW_PAUSE_MS = 10_000L

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
): Unit {
    val engine = AppGraph.engine
    val repository = AppGraph.repository
    val state by engine.state.collectAsState()
    val workouts by repository.workouts.collectAsState()
    val settings by repository.settings.collectAsState()
    val workout = remember(workouts, workoutId) { workouts.firstOrNull { it.id == workoutId } }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Keep the engine in step with the repository copy of this workout: load it when the screen
    // opens (or after another workout's session ends), push edits made elsewhere into a running
    // session. The engine ignores load() while a session for a different workout is active.
    LaunchedEffect(workoutId, workout, state.workout?.id, state.isActive) {
        val target = workout
        if (target == null) {
            onBack()
            return@LaunchedEffect
        }
        val current = engine.state.value
        val loaded = current.workout
        when {
            loaded?.id != target.id -> engine.load(target)
            loaded != target -> if (current.isActive) engine.updateWorkout(target) else engine.load(target)
        }
    }

    // The workout the engine is showing, in its freshest repository form (edits are based on it).
    val loadedWorkout = state.workout?.let { shown -> workouts.firstOrNull { it.id == shown.id } ?: shown }
    val otherWorkoutRunning = state.isActive && state.workout != null && state.workout?.id != workoutId

    var showEndSheet by remember { mutableStateOf(false) }
    var editStep by remember { mutableStateOf<Int?>(null) }
    var jumpStep by remember { mutableStateOf<Int?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var notesExpanded by remember(state.stepIndex) { mutableStateOf(false) }

    val rows = state.rows
    val rounds = state.rounds
    val stepItems = remember(rows.size, rounds) { buildStepItems(rows.size, rounds) }
    val currentItemIndex = stepItemIndex(state.stepIndex, rows.size, rounds)
    val currentItemIndexState = rememberUpdatedState(currentItemIndex)

    // ---- step list auto-follow ----
    val listState = rememberLazyListState()
    var lastUserScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start || interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) {
                lastUserScrollAt = SystemClock.elapsedRealtime()
            }
        }
    }
    LaunchedEffect(currentItemIndex, stepItems.size) {
        if (stepItems.isEmpty()) return@LaunchedEffect
        if (SystemClock.elapsedRealtime() - lastUserScrollAt >= FOLLOW_PAUSE_MS) {
            listState.scrollToCurrent(currentItemIndex)
        }
    }
    val currentVisible by remember {
        derivedStateOf {
            val target = currentItemIndexState.value
            val info = listState.layoutInfo
            // Before the first layout pass nothing is visible yet; do not offer a jump then.
            info.totalItemsCount == 0 || info.visibleItemsInfo.any { it.index == target }
        }
    }
    val showJump = stepItems.isNotEmpty() && !currentVisible

    fun primaryTap() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        engine.primaryTap()
    }

    fun leave() {
        if (state.isActive) showEndSheet = true else onBack()
    }

    BackHandler(enabled = state.isActive) { showEndSheet = true }

    val title = state.workout?.name ?: workout?.name ?: "Workout"

    Scaffold(
        containerColor = ExColors.background,
        contentColor = ExColors.text,
        topBar = {
            ExTopBar(
                title = title,
                onBack = { leave() },
                actions = {
                    TextButton(
                        onClick = { repository.updateSettings { it.copy(chimesMuted = !it.chimesMuted) } },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            text = if (settings.chimesMuted) "Unmute" else "Mute",
                            color = if (settings.chimesMuted) ExColors.paused else ExColors.text,
                        )
                    }
                    TextButton(
                        onClick = { repository.updateSettings { it.copy(speechEnabled = !it.speechEnabled) } },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            text = if (settings.speechEnabled) "Voice on" else "Voice off",
                            color = if (settings.speechEnabled) ExColors.text else ExColors.textDim,
                        )
                    }
                    IconButton(onClick = { showEndSheet = true }, enabled = state.isActive) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = ExColors.surfaceHigh,
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Edit workout",
                                        color = if (state.isActive) ExColors.textDim else ExColors.text,
                                    )
                                },
                                enabled = !state.isActive,
                                onClick = {
                                    menuOpen = false
                                    onEditWorkout(workoutId)
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ControlBar(
                state = state,
                onBackStep = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    engine.back()
                },
                onPrimary = { primaryTap() },
                onSkip = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    engine.skip()
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (otherWorkoutRunning) {
                Text(
                    text = "${state.workout?.name} is still running. Use Reset to end it before starting ${workout?.name ?: "this workout"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ExColors.paused,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ExColors.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            SessionHeader(
                state = state,
                notesExpanded = notesExpanded,
                onToggleNotes = { notesExpanded = !notesExpanded },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (stepItems.isEmpty()) {
                    EmptyState("This workout has no exercises yet.\nUse Edit workout in the menu to add some.")
                } else {
                    StepList(
                        listState = listState,
                        stepItems = stepItems,
                        state = state,
                        onStepClick = { step ->
                            if (state.isCountingDown) engine.pause()
                            editStep = step
                        },
                        onStepLongClick = { step -> jumpStep = step },
                    )
                }
                if (showJump) {
                    AssistChip(
                        onClick = {
                            lastUserScrollAt = 0L
                            scope.launch { listState.scrollToCurrent(currentItemIndex) }
                        },
                        label = { Text("Jump to current") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = ExColors.surfaceHigh,
                            labelColor = ExColors.text,
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    )
                }
            }
        }
    }

    // ---- dialogs and sheets ----

    if (showEndSheet) {
        EndWorkoutSheet(
            onKeepGoing = { showEndSheet = false },
            onEndAndLog = {
                showEndSheet = false
                engine.endSession(keepLog = true)
            },
            onDiscard = {
                showEndSheet = false
                engine.endSession(keepLog = false)
                onBack()
            },
        )
    }

    val finished = state.finishedEntry
    if (state.phase == Phase.FINISHED && finished != null) {
        CompletionDialog(
            entry = finished,
            onSave = { notes ->
                repository.updateLogNotes(finished.id, notes)
                engine.acknowledgeFinished()
                onBack()
            },
        )
    }

    jumpStep?.let { step ->
        val row = rows.getOrNull(if (rows.isEmpty()) 0 else step % rows.size)
        val roundText = if (rounds > 1) ", round ${step / rows.size.coerceAtLeast(1) + 1}" else ""
        ConfirmDialog(
            title = "Start from here?",
            text = "Go to step ${step + 1}$roundText: ${row?.name ?: ""}. " +
                if (state.isActive) "Steps already completed stay marked as done." else "This starts the workout at that step.",
            confirmLabel = "Start",
            onConfirm = {
                jumpStep = null
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                engine.jumpTo(step)
            },
            onDismiss = { jumpStep = null },
        )
    }

    editStep?.let { step ->
        val base = loadedWorkout
        val rowIndex = if (base == null || base.rows.isEmpty()) 0 else step % base.rows.size
        val row = base?.rows?.getOrNull(rowIndex)
        if (base != null && row != null) {
            RowEditSheet(
                row = row,
                title = "Edit exercise ${rowIndex + 1}",
                onDismiss = { editStep = null },
                onSave = { edited ->
                    val updatedRows = base.rows.mapIndexed { i, r -> if (i == rowIndex) edited.copy(id = r.id) else r }
                    val saved = repository.saveWorkout(base.copy(rows = updatedRows))
                    engine.updateWorkout(saved)
                    editStep = null
                },
                // Structural edits are blocked while a session runs.
                onDelete = if (state.isActive) null else {
                    {
                        val remaining = base.rows.filterIndexed { i, _ -> i != rowIndex }
                        val saved = repository.saveWorkout(base.copy(rows = remaining))
                        engine.updateWorkout(saved)
                        editStep = null
                    }
                },
            )
        }
    }
}

// ---- header ----

@Composable
private fun SessionHeader(
    state: SessionState,
    notesExpanded: Boolean,
    onToggleNotes: () -> Unit,
) {
    val accent = phaseColor(state)
    val row = state.currentRow
    val upNext = state.phase == Phase.LEAD_IN || state.phase == Phase.REST || state.phase == Phase.WAITING
    val name = when {
        state.phase == Phase.FINISHED -> state.workout?.name ?: ""
        else -> row?.name ?: ""
    }
    val notes = if (state.phase == Phase.FINISHED) "" else row?.notes.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val counter = buildString {
            append(Format.counter(state))
            Format.roundLabel(state)?.let { append(" · ").append(it) }
        }
        Text(counter, style = MaterialTheme.typography.titleMedium, color = ExColors.textDim)

        if (state.paused && state.isActive) {
            val blink = rememberInfiniteTransition(label = "paused")
            val alpha by blink.animateFloat(
                initialValue = 1f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 550), RepeatMode.Reverse),
                label = "pausedAlpha",
            )
            Text(
                text = "PAUSED",
                style = MaterialTheme.typography.titleLarge,
                color = ExColors.paused,
                letterSpacing = 2.sp,
                modifier = Modifier.alpha(alpha),
            )
        } else {
            Text(
                text = phaseLabel(state),
                style = MaterialTheme.typography.titleLarge,
                color = accent,
                letterSpacing = 2.sp,
            )
        }

        val clock = clockText(state)
        val clockStyle = if (clock.length > 5) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge
        Text(
            text = clock,
            style = clockStyle.copy(fontFeatureSettings = "tnum"),
            color = accent,
            maxLines = 1,
            softWrap = false,
        )

        if (upNext) {
            Text("UP NEXT", style = MaterialTheme.typography.labelLarge, color = ExColors.textDim, letterSpacing = 2.sp)
        }
        Text(
            text = name,
            style = MaterialTheme.typography.headlineLarge,
            color = ExColors.text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (notes.isNotBlank()) {
            Text(
                text = notes,
                style = MaterialTheme.typography.bodyLarge,
                color = ExColors.text,
                textAlign = TextAlign.Center,
                maxLines = if (notesExpanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(onClick = onToggleNotes),
            )
        }
        val secondary = secondaryLine(state)
        if (secondary.isNotEmpty()) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodyMedium,
                color = ExColors.textDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun phaseLabel(state: SessionState): String = when (state.phase) {
    Phase.IDLE -> "READY"
    Phase.LEAD_IN -> "GET READY"
    Phase.EXERCISE -> if (state.isUntimedExercise) "UNTIL DONE" else "GO"
    Phase.REST -> "REST"
    Phase.WAITING -> "TAP WHEN READY"
    Phase.FINISHED -> "DONE"
}

private fun phaseColor(state: SessionState): Color = when {
    state.paused && state.isActive -> ExColors.paused
    state.phase == Phase.EXERCISE -> ExColors.go
    state.phase == Phase.REST -> ExColors.rest
    state.phase == Phase.LEAD_IN || state.phase == Phase.WAITING -> ExColors.waiting
    else -> ExColors.text
}

private fun clockText(state: SessionState): String = when (state.phase) {
    Phase.IDLE -> state.rows.firstOrNull()?.durationSec?.takeIf { it > 0 }?.let { Format.clock(it) } ?: "0:00"
    Phase.FINISHED -> Format.elapsed(state.totalElapsedMs)
    else -> state.remainingMs?.let { Format.countdown(it) } ?: Format.elapsed(state.phaseElapsedMs)
}

private fun secondaryLine(state: SessionState): String = when (state.phase) {
    Phase.IDLE -> {
        val w = state.workout
        if (w == null || w.rows.isEmpty()) "" else "${w.totalSteps} steps · ~${Format.shortDuration(w.estimatedSeconds())}"
    }
    Phase.FINISHED -> "Elapsed ${Format.elapsed(state.totalElapsedMs)}"
    else -> "Elapsed ${Format.elapsed(state.totalElapsedMs)} · ~${Format.shortDuration(state.estimatedRemainingSec())} left"
}

// ---- step list ----

private sealed class StepItem(val key: String) {
    class RoundHeader(val round: Int) : StepItem("round-$round")
    class Step(val index: Int, val rowIndex: Int) : StepItem("step-$index")
}

private fun buildStepItems(rowCount: Int, rounds: Int): List<StepItem> {
    if (rowCount <= 0) return emptyList()
    val items = ArrayList<StepItem>(rowCount * rounds + rounds)
    for (round in 0 until rounds) {
        if (rounds > 1) items += StepItem.RoundHeader(round)
        for (rowIndex in 0 until rowCount) items += StepItem.Step(round * rowCount + rowIndex, rowIndex)
    }
    return items
}

/** Position of [step]'s item in the list built by [buildStepItems] (round headers shift it). */
private fun stepItemIndex(step: Int, rowCount: Int, rounds: Int): Int = when {
    rowCount <= 0 -> 0
    rounds > 1 -> step + step / rowCount + 1
    else -> step
}

/** Bring the current step into view with the item above it for context. */
private suspend fun LazyListState.scrollToCurrent(itemIndex: Int) {
    animateScrollToItem((itemIndex - 1).coerceAtLeast(0))
}

@Composable
private fun StepList(
    listState: LazyListState,
    stepItems: List<StepItem>,
    state: SessionState,
    onStepClick: (step: Int) -> Unit,
    onStepLongClick: (step: Int) -> Unit,
) {
    val accent = phaseColor(state)
    val highlightCurrent = state.phase != Phase.FINISHED
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(stepItems, key = { it.key }) { item ->
            when (item) {
                is StepItem.RoundHeader -> Text(
                    text = "Round ${item.round + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = ExColors.textDim,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                )
                is StepItem.Step -> {
                    val row = state.rows.getOrNull(item.rowIndex) ?: return@items
                    StepRow(
                        number = item.rowIndex + 1,
                        row = row,
                        isCurrent = highlightCurrent && item.index == state.stepIndex,
                        isCompleted = item.index in state.completedSteps,
                        accent = if (state.phase == Phase.IDLE) ExColors.waiting else accent,
                        onClick = { onStepClick(item.index) },
                        onLongClick = { onStepLongClick(item.index) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StepRow(
    number: Int,
    row: ExerciseRow,
    isCurrent: Boolean,
    isCompleted: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val dimmed = isCompleted && !isCurrent
    val background = if (isCurrent) ExColors.surfaceHigh else ExColors.surface
    val titleColor = if (dimmed) ExColors.done else ExColors.text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.65f else 1f)
            .clip(shape)
            .background(background, shape)
            .then(if (isCurrent) Modifier.border(2.dp, accent, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "$number. ${row.name}",
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${Format.rowDuration(row.durationSec)} · ${Format.rowTrigger(row.triggerSec)}",
                style = MaterialTheme.typography.bodyMedium,
                color = ExColors.textDim,
            )
        }
        if (isCompleted) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = "Completed", tint = ExColors.go)
        }
    }
}

// ---- bottom bar ----

@Composable
private fun ControlBar(
    state: SessionState,
    onBackStep: () -> Unit,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
) {
    val paused = state.paused && state.isActive
    val primaryEnabled = when (state.phase) {
        Phase.IDLE -> state.rows.isNotEmpty()
        Phase.FINISHED -> false
        else -> true
    }
    val primaryContainer = when {
        paused -> ExColors.paused
        state.phase == Phase.WAITING -> ExColors.waiting
        else -> ExColors.go
    }
    val shape = RoundedCornerShape(20.dp)
    val sideColors = ButtonDefaults.outlinedButtonColors(contentColor = ExColors.text, disabledContentColor = ExColors.textDim)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ExColors.background)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onBackStep,
            enabled = state.isActive,
            shape = shape,
            colors = sideColors,
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier
                .width(84.dp)
                .height(96.dp),
        ) {
            Text("Back", style = MaterialTheme.typography.titleLarge)
        }
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryContainer,
                contentColor = ExColors.background,
                disabledContainerColor = ExColors.surfaceHigh,
                disabledContentColor = ExColors.textDim,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier
                .weight(1f)
                .height(96.dp),
        ) {
            Text(
                text = primaryLabel(state),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
        OutlinedButton(
            onClick = onSkip,
            enabled = state.isActive,
            shape = shape,
            colors = sideColors,
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier
                .width(84.dp)
                .height(96.dp),
        ) {
            Text("Skip", style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun primaryLabel(state: SessionState): String = when (state.phase) {
    Phase.IDLE -> "START"
    Phase.LEAD_IN, Phase.REST -> if (state.paused) "RESUME" else "PAUSE"
    Phase.EXERCISE -> when {
        state.isUntimedExercise -> "DONE"
        state.paused -> "RESUME"
        else -> "PAUSE"
    }
    Phase.WAITING -> "START NEXT"
    Phase.FINISHED -> "FINISHED"
}

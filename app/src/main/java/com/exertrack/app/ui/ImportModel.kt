package com.exertrack.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.exertrack.app.data.Repository
import com.exertrack.app.data.Workout
import com.exertrack.app.engine.SessionState
import com.exertrack.app.engine.WorkoutEngine
import com.exertrack.app.io.ParsedTable
import com.exertrack.app.io.SpreadsheetImport
import com.exertrack.app.io.WorkoutTableParser
import com.exertrack.app.io.XlsxReader

/**
 * State and rules behind the import screen: what the picked file parsed into, which target every
 * sheet is mapped to, and how the mappings are applied to the repository in one pass.
 */

/** ACTION_OPEN_DOCUMENT with the persistable-permission flag so re-import from source keeps working. */
internal object PersistableOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

/** Keep read access to [uri] across restarts. Fails quietly for providers that do not offer it. */
internal fun takePersistableRead(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: SecurityException) {
        // Shared files and some providers do not grant persistable access; the one-off read still works.
    } catch (e: IllegalArgumentException) {
        // Same: nothing to persist for this URI.
    }
}

/** Where the rows of one sheet go. */
internal sealed class ImportTarget {
    data object NewWorkout : ImportTarget()
    data class Replace(val workoutId: String) : ImportTarget()
    data object Skip : ImportTarget()
}

/** One sheet of the picked file together with the user's choice for it. */
internal class SheetItem(
    val index: Int,
    val table: ParsedTable,
    /** Default name for a new workout: the sheet name, or the file name stem for CSV. */
    val defaultName: String,
    initialTarget: ImportTarget,
) {
    var target: ImportTarget by mutableStateOf(initialTarget)
    var newName: String by mutableStateOf(defaultName)
    var expanded: Boolean by mutableStateOf(false)

    /** Why this sheet can never be imported (chooser disabled), or null when it is importable. */
    val blockedReason: String? = when {
        table.isEmpty -> "Empty sheet, skipped"
        table.problemCount > 0 ->
            "${plural(table.problemCount, "problem")} in this sheet; fix the file and import again"
        else -> null
    }

    val isBlocked: Boolean get() = blockedReason != null

    /** "12 exercises · 2 warnings", "3 problems", or "empty". */
    val statusLine: String
        get() {
            if (table.isEmpty) return "empty"
            val parts = ArrayList<String>(3)
            if (table.exercises.isNotEmpty()) parts += plural(table.exercises.size, "exercise")
            if (table.problemCount > 0) parts += plural(table.problemCount, "problem")
            if (table.warningCount > 0) parts += plural(table.warningCount, "warning")
            return parts.joinToString(" · ")
        }

    val headerVerdict: String get() = if (table.hasHeader) "Header row detected" else "No header row"
}

/** The parsed file and its sheets. */
internal class ImportModel(
    val uri: Uri,
    val fileName: String,
    val isXlsx: Boolean,
    val items: List<SheetItem>,
)

/** What a sheet's choice amounts to right now, with the reason when it was overridden to Skip. */
internal class ResolvedTarget(val target: ImportTarget, val reason: String?)

/** Raw parse output of a picked file, produced on a background thread. */
internal class ImportFile(val name: String, val isXlsx: Boolean, val tables: List<ParsedTable>)

/** Read, sniff and parse every sheet of [uri]. Throws when the file cannot be read or is not a spreadsheet. */
internal fun readImportFile(context: Context, uri: Uri): ImportFile {
    takePersistableRead(context, uri)
    val bytes = Files.readBytes(context, uri)
    val name = Files.displayName(context, uri)
    val isXlsx = XlsxReader.isXlsx(bytes)
    val tables = SpreadsheetImport.readAll(bytes, name).map(WorkoutTableParser::parse)
    return ImportFile(name, isXlsx, tables)
}

/**
 * Build the mapping list with the defaults from docs/DESIGN.md. With [replaceWorkoutId] (the
 * editor's "Replace rows from file" and "Re-import from source"), exactly one sheet replaces that
 * workout, chosen by [sheetIndexFor], and every other sheet defaults to Skip so a single-workout
 * re-import never creates or replaces anything else. Without it, a sheet named like an existing
 * workout replaces that workout and everything else becomes a new workout. Empty sheets and sheets
 * with problems are always skipped.
 */
internal fun buildImportModel(
    uri: Uri,
    file: ImportFile,
    replaceWorkoutId: String?,
    workouts: List<Workout>,
): ImportModel {
    val csvStem = SpreadsheetImport.sheetNameFor(file.name)
    val replaceTarget = replaceWorkoutId?.let { id -> workouts.firstOrNull { it.id == id } }
    val replaceIndex = replaceTarget?.let { sheetIndexFor(it, file) }
    val items = file.tables.mapIndexed { index, table ->
        val defaultName = if (file.isXlsx) table.sheetName.trim().ifEmpty { "Sheet ${index + 1}" } else csvStem
        val blocked = table.isEmpty || table.problemCount > 0
        val target: ImportTarget = when {
            blocked -> ImportTarget.Skip
            replaceTarget != null ->
                if (index == replaceIndex) ImportTarget.Replace(replaceTarget.id) else ImportTarget.Skip
            else -> {
                val sameName = workouts.firstOrNull { it.name.trim().equals(defaultName.trim(), ignoreCase = true) }
                if (sameName != null) ImportTarget.Replace(sameName.id) else ImportTarget.NewWorkout
            }
        }
        SheetItem(index = index, table = table, defaultName = defaultName, initialTarget = target)
    }
    return ImportModel(uri = uri, fileName = file.name, isXlsx = file.isXlsx, items = items)
}

/**
 * The sheet whose rows should replace [target]: the sheet it was imported from when [file] is a
 * workbook that still has it, else a sheet named like the workout, else the first sheet (CSV, or a
 * different file chosen via "Replace rows from file").
 */
private fun sheetIndexFor(target: Workout, file: ImportFile): Int {
    val names = file.tables.map { it.sheetName.trim() }
    val stored = target.sourceSheet?.trim().orEmpty()
    if (file.isXlsx && stored.isNotEmpty()) {
        val bySource = names.indexOfFirst { it.equals(stored, ignoreCase = true) }
        if (bySource >= 0) return bySource
    }
    val byName = names.indexOfFirst { it.equals(target.name.trim(), ignoreCase = true) }
    return if (byName >= 0) byName else 0
}

/** Apply the block / running-workout guards to the user's choice. */
internal fun SheetItem.resolve(workouts: List<Workout>, session: SessionState): ResolvedTarget {
    blockedReason?.let { return ResolvedTarget(ImportTarget.Skip, it) }
    return when (val chosen = target) {
        is ImportTarget.Replace -> {
            val existing = workouts.firstOrNull { it.id == chosen.workoutId }
            when {
                existing == null -> ResolvedTarget(ImportTarget.Skip, "That workout no longer exists; choose another target")
                session.isActive && session.workout?.id == existing.id ->
                    ResolvedTarget(ImportTarget.Skip, "${existing.name} is running; finish or reset it before replacing its rows")
                else -> ResolvedTarget(chosen, null)
            }
        }
        ImportTarget.NewWorkout, ImportTarget.Skip -> ResolvedTarget(chosen, null)
    }
}

/**
 * Resolve every sheet in order. A later sheet that would replace a workout an earlier sheet already
 * replaces is skipped with a visible reason, so one workout is never written twice in a pass and the
 * cards, the Import button and [applyImport] all agree on what will happen.
 */
internal fun resolveAll(items: List<SheetItem>, workouts: List<Workout>, session: SessionState): List<ResolvedTarget> {
    val claimedBy = HashMap<String, SheetItem>() // workout id -> first sheet replacing it
    return items.map { item ->
        val resolved = item.resolve(workouts, session)
        val target = resolved.target as? ImportTarget.Replace ?: return@map resolved
        val first = claimedBy.putIfAbsent(target.workoutId, item) ?: return@map resolved
        val name = workouts.first { it.id == target.workoutId }.name
        ResolvedTarget(ImportTarget.Skip, "$name is already replaced by sheet ${first.index + 1}; choose another target")
    }
}

/** Label shown in the target chooser for a resolved or chosen target. */
internal fun targetLabel(target: ImportTarget, workouts: List<Workout>): String = when (target) {
    ImportTarget.NewWorkout -> "New workout"
    is ImportTarget.Replace -> "Replace rows of " + (workouts.firstOrNull { it.id == target.workoutId }?.name ?: "(deleted workout)")
    ImportTarget.Skip -> "Skip"
}

internal class ImportOutcome(val created: Int, val replaced: Int) {
    val isEmpty: Boolean get() = created == 0 && replaced == 0

    /** "Imported 2 workouts, replaced 1" */
    val summary: String
        get() = when {
            isEmpty -> "Nothing was imported"
            created > 0 && replaced > 0 -> "Imported ${plural(created, "workout")}, replaced $replaced"
            created > 0 -> "Imported ${plural(created, "workout")}"
            else -> "Replaced rows of ${plural(replaced, "workout")}"
        }
}

/** Saves the completion dialog's counts so it survives recreation; nothing is saved while null. */
internal val ImportOutcomeSaver = listSaver<ImportOutcome?, Int>(
    save = { outcome -> if (outcome == null) emptyList() else listOf(outcome.created, outcome.replaced) },
    restore = { saved -> if (saved.size == 2) ImportOutcome(saved[0], saved[1]) else null },
)

/**
 * One sheet's choices, kept outside the parsed model so they survive activity recreation: the file
 * is cheap to re-read, the user's mapping is not.
 */
internal class SheetChoice(val target: ImportTarget, val newName: String, val expanded: Boolean)

internal fun SheetItem.currentChoice(): SheetChoice = SheetChoice(target, newName, expanded)

/** Put a saved choice back on a freshly parsed sheet. A blocked sheet keeps its forced Skip. */
internal fun SheetItem.applyChoice(choice: SheetChoice) {
    if (!isBlocked) {
        target = choice.target
        newName = choice.newName
    }
    expanded = choice.expanded
}

private const val CHOICE_FIELDS = 5

/** Flat string list, [CHOICE_FIELDS] entries per sheet: index, kind, workout id, expanded, name (last, so any text is safe). */
internal val SheetChoicesSaver = listSaver<SnapshotStateMap<Int, SheetChoice>, String>(
    save = { map ->
        map.entries.flatMap { (index, choice) ->
            val (kind, workoutId) = when (val target = choice.target) {
                ImportTarget.NewWorkout -> "new" to ""
                is ImportTarget.Replace -> "replace" to target.workoutId
                ImportTarget.Skip -> "skip" to ""
            }
            listOf(index.toString(), kind, workoutId, choice.expanded.toString(), choice.newName)
        }
    },
    restore = { flat ->
        mutableStateMapOf<Int, SheetChoice>().apply {
            flat.chunked(CHOICE_FIELDS).forEach { fields ->
                if (fields.size != CHOICE_FIELDS) return@forEach
                val (index, kind, workoutId, expanded, name) = fields
                val target = when (kind) {
                    "replace" -> ImportTarget.Replace(workoutId)
                    "skip" -> ImportTarget.Skip
                    else -> ImportTarget.NewWorkout
                }
                index.toIntOrNull()?.let { this[it] = SheetChoice(target, name, expanded.toBoolean()) }
            }
        }
    },
)

/**
 * Apply every mapping in one pass, as resolved by [resolveAll]. New workouts get a unique name and
 * remember the source file and sheet; replaced workouts keep their id, name, rounds and lead-in. A
 * replaced workout that is loaded (idle or finished) in the engine is reloaded so the active screen
 * shows the new rows.
 */
internal fun applyImport(model: ImportModel, repository: Repository, engine: WorkoutEngine): ImportOutcome {
    val now = System.currentTimeMillis()
    val source = model.uri.toString()
    var created = 0
    var replaced = 0
    // One resolution pass, the same one the screen showed: within this synchronous loop the only
    // changes are our own saves, which add or rewrite workouts and never change a guard's outcome.
    val session = engine.state.value
    val resolvedAll = resolveAll(model.items, repository.workouts.value, session)
    for ((item, resolved) in model.items.zip(resolvedAll)) {
        val sheet = if (model.isXlsx) item.table.sheetName else null
        val rows = item.table.exercises
        when (val target = resolved.target) {
            ImportTarget.NewWorkout -> {
                repository.saveWorkout(
                    Workout(
                        name = repository.uniqueName(item.newName),
                        rows = rows,
                        createdAt = now,
                        updatedAt = now,
                        sourceUri = source,
                        sourceSheet = sheet,
                    ),
                    now,
                )
                created++
            }
            is ImportTarget.Replace -> {
                val existing = repository.workout(target.workoutId) ?: continue
                val saved = repository.saveWorkout(
                    existing.copy(rows = rows, sourceUri = source, sourceSheet = sheet),
                    now,
                )
                if (session.workout?.id == saved.id && !session.isActive) engine.load(saved)
                replaced++
            }
            ImportTarget.Skip -> Unit
        }
    }
    return ImportOutcome(created, replaced)
}

internal fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.data.repo.TestInput
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.AppTextField
import com.itexpert120.attendanceManager.ui.components.DateField
import com.itexpert120.attendanceManager.ui.components.DropdownField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Edits the Test. Once any result is entered the class and date are locked, and Total Marks cannot go below
 * the highest recorded mark ([TestDetailData.highestMarks]); the repository enforces both again.
 */
@Composable
fun TestEditSheet(
    data: TestDetailData,
    actions: TestDetailActions,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val test = data.test
    val locked = data.hasResults
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(test.name) }
    var subjectId by rememberSaveable { mutableStateOf(test.subjectId) }
    var classId by rememberSaveable { mutableStateOf(test.classGroupId) }
    var dateEpoch by rememberSaveable { mutableStateOf(test.date.toEpochDays()) }
    var totalText by rememberSaveable { mutableStateOf(formatMarks(test.totalMarks)) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var serverError by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmTotal by rememberSaveable { mutableStateOf(false) }

    val subjects = selectableSubjects(data.subjects, test.subjectId)
    val fields = TestFormFields(name, subjectId, classId, LocalDate.fromEpochDays(dateEpoch), totalText)
    val errors = if (showErrors) validateTestForm(fields, data.highestMarks).errors else TestFormErrors()
    val totalChanged = totalText.trim() != formatMarks(test.totalMarks)

    fun submit(input: TestInput) {
        saving = true
        scope.launch {
            when (val result = actions.updateTest(input)) {
                is Attempt.Ok -> onSaved()
                is Attempt.Failed -> {
                    serverError = result.message
                    saving = false
                }
            }
        }
    }

    AppSheet(
        title = "Edit test",
        description = if (locked) {
            "Class and date are locked because result entry has begun."
        } else {
            "Changing the class or date rebuilds the roster."
        },
        onDismiss = onDismiss,
        footer = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                enabled = !saving,
                onClick = {
                    showErrors = true
                    serverError = null
                    val input = validateTestForm(fields, data.highestMarks).input ?: return@Button
                    // As the web app: a new Total Marks changes every percentage, so ask first.
                    if (input.totalMarks != test.totalMarks) confirmTotal = true else submit(input)
                },
            ) { Text("Save changes") }
        },
    ) {
        AppTextField(
            value = name,
            onValueChange = { name = it },
            label = "Test name",
            isError = errors.name != null,
            supportingText = errors.name,
        )
        DropdownField(
            label = "Subject",
            options = subjects,
            selected = subjects.firstOrNull { it.id == subjectId },
            optionLabel = { if (it.isArchived) "${it.name} · Archived" else it.name },
            onSelect = { subjectId = it.id },
        )
        ChoiceField(
            label = "Class",
            choices = data.classes.map { Choice(it.id, classLabel(it, long = true)) },
            value = classId,
            onChange = { classId = it },
            enabled = !locked,
        )
        DateField(
            label = "Test date",
            date = LocalDate.fromEpochDays(dateEpoch),
            onChange = { dateEpoch = it.toEpochDays() },
            enabled = !locked,
            supportingText = if (locked) "Locked after result entry begins." else null,
        )
        AppTextField(
            value = totalText,
            onValueChange = { totalText = sanitizeMarksInput(it) },
            label = "Total marks",
            keyboardType = KeyboardType.Decimal,
            isError = errors.totalMarks != null,
            supportingText = errors.totalMarks ?: when {
                totalChanged -> "Percentages are recalculated with the new Total Marks."
                data.highestMarks > 0 -> "Highest recorded mark: ${formatMarks(data.highestMarks)}."
                else -> null
            },
        )
        serverError?.let { ErrorBanner(it) }
    }

    if (confirmTotal) {
        AlertDialog(
            onDismissRequest = { confirmTotal = false },
            title = { Text("Change Total Marks?") },
            text = { Text("Changing Total Marks will recalculate every percentage. Continue?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTotal = false
                        validateTestForm(fields, data.highestMarks).input?.let(::submit)
                    },
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmTotal = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Corrects the Test Roster: refresh it from the current enrollments while no result is entered, add a late
 * joiner from the class, or remove a student whose result is still Not Entered. Removing offers Undo (the web
 * app asked "Remove {name} from this Test Roster?"); the student also shows under "Add from class" afterwards.
 */
@Composable
fun TestRosterSheet(
    data: TestDetailData,
    actions: TestDetailActions,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var removedId by rememberSaveable { mutableLongStateOf(NO_ENROLLMENT) }
    var removedName by rememberSaveable { mutableStateOf("") }
    // The Undo offer lasts as long as a Snackbar's would.
    LaunchedEffect(removedId) {
        if (removedId != NO_ENROLLMENT) {
            delay(UNDO_MILLIS)
            removedId = NO_ENROLLMENT
        }
    }
    val onRoster = data.rows.mapTo(HashSet()) { it.enrollment.id }
    val candidates = data.classRows.filter { it.enrollment.id !in onRoster }
    val colors = MaterialTheme.colorScheme

    AppSheet(
        title = "Correct roster",
        description = "Add a student of ${classLabel(data.group, long = true)}, or remove one whose result is not entered.",
        onDismiss = onDismiss,
    ) {
        error?.let { ErrorBanner(it) }
        if (removedId != NO_ENROLLMENT) {
            val undoId = removedId
            UndoBar(
                message = "Removed $removedName from this Test Roster.",
                onUndo = {
                    removedId = NO_ENROLLMENT
                    scope.launch {
                        val result = actions.addToRoster(undoId)
                        if (result is Attempt.Failed) error = result.message
                    }
                },
            )
        }

        OutlinedButton(
            enabled = !data.hasResults,
            onClick = {
                error = null
                scope.launch {
                    when (val result = actions.refreshRoster()) {
                        is Attempt.Ok -> {
                            onMessage("Roster refreshed from current enrollments")
                            onDismiss()
                        }
                        is Attempt.Failed -> error = result.message
                    }
                }
            },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Text("Refresh from current enrollments", modifier = Modifier.padding(start = 8.dp))
        }
        if (data.hasResults) {
            Text(
                "Refreshing is locked once any result is entered.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }

        Text("On this test · ${data.rows.size}", style = MaterialTheme.typography.titleSmall, color = colors.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            data.rows.forEach { row ->
                key(row.enrollment.id) {
                    val removable = row.result == TestResultStatus.NOT_ENTERED
                    SheetPersonRow(
                        roll = row.enrollment.rollNumber,
                        name = row.student.name,
                        detail = if (removable) "Not entered" else "Has a result. Clear it to remove.",
                    ) {
                        IconButton(
                            enabled = removable,
                            onClick = {
                                error = null
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                scope.launch {
                                    when (val result = actions.removeFromRoster(row.enrollment.id)) {
                                        is Attempt.Ok -> {
                                            removedName = row.student.name
                                            removedId = row.enrollment.id
                                        }
                                        is Attempt.Failed -> error = result.message
                                    }
                                }
                            },
                        ) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "Remove ${row.student.name} from roster")
                        }
                    }
                }
            }
        }

        Text("Add from class · ${candidates.size}", style = MaterialTheme.typography.titleSmall, color = colors.primary)
        if (candidates.isEmpty()) {
            Text(
                "Every enrollment of this class is already on the roster.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            candidates.forEach { candidate ->
                key(candidate.enrollment.id) {
                    SheetPersonRow(
                        roll = candidate.enrollment.rollNumber,
                        name = candidate.student.name,
                        detail = candidate.enrollment.struckOffOn?.let { "Left on ${shortDateLabel(it)}" }
                            ?: candidate.student.admissionNumber,
                    ) {
                        FilledTonalButton(
                            onClick = {
                                error = null
                                scope.launch {
                                    val result = actions.addToRoster(candidate.enrollment.id)
                                    if (result is Attempt.Failed) error = result.message
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = null)
                            Text("Add", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

private const val NO_ENROLLMENT = -1L
private const val UNDO_MILLIS = 10_000L

/** A Snackbar-like row inside a sheet (a screen Snackbar would sit behind the sheet's scrim). */
@Composable
private fun UndoBar(message: String, onUndo: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.inverseSurface,
        contentColor = colors.inverseOnSurface,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            TextButton(
                onClick = onUndo,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Undo") }
        }
    }
}

@Composable
private fun SheetPersonRow(roll: String, name: String, detail: String, trailing: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RollBadge(roll)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing()
        }
    }
}

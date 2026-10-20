package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.domain.TestProgress
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.AppTextField
import com.itexpert120.attendanceManager.ui.components.DateField
import com.itexpert120.attendanceManager.ui.components.DropdownField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

private const val NONE = -1L

/** Create-a-Test sheet. On success it hands the new Test's id to [onCreated] (the caller opens its detail). */
@Composable
fun NewTestSheet(
    subjects: List<Subject>,
    classes: List<ClassGroup>,
    defaultClassId: Long?,
    actions: TestsTabActions,
    onDismiss: () -> Unit,
    onCreated: (testId: Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var subjectId by rememberSaveable { mutableStateOf(NONE) }
    var classId by rememberSaveable { mutableStateOf(defaultClassId ?: classes.firstOrNull()?.id ?: NONE) }
    var dateEpoch by rememberSaveable { mutableStateOf(today().toEpochDays()) }
    var totalText by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var serverError by rememberSaveable { mutableStateOf<String?>(null) }
    var addingSubject by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // A freshly archived or never-chosen Subject falls back to the first active one, like the web form did.
    val subject = subjects.firstOrNull { it.id == subjectId } ?: subjects.firstOrNull()
    val fields = TestFormFields(
        name = name,
        subjectId = subject?.id,
        classGroupId = classId.takeIf { it != NONE },
        date = LocalDate.fromEpochDays(dateEpoch),
        totalMarksText = totalText,
    )
    val errors = if (showErrors) validateTestForm(fields).errors else TestFormErrors()

    AppSheet(
        title = "New test",
        description = "Students enrolled in the class on the test date are added automatically.",
        onDismiss = onDismiss,
        footer = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                enabled = !saving,
                onClick = {
                    showErrors = true
                    serverError = null
                    val validation = validateTestForm(fields)
                    val input = validation.input ?: return@Button
                    saving = true
                    scope.launch {
                        when (val result = actions.createTest(input)) {
                            is Attempt.Ok -> onCreated(result.value.id)
                            is Attempt.Failed -> {
                                serverError = result.message
                                saving = false
                            }
                        }
                    }
                },
            ) { Text("Create and enter marks") }
        },
    ) {
        AppTextField(
            value = name,
            onValueChange = { name = it },
            label = "Test name",
            placeholder = "e.g. Midterm or Weekly Test 3",
            isError = errors.name != null,
            supportingText = errors.name,
        )

        if (subjects.isEmpty() || addingSubject) {
            InlineSubjectForm(
                actions = actions,
                showCancel = subjects.isNotEmpty(),
                onCancel = { addingSubject = false },
                onAdded = {
                    subjectId = it.id
                    addingSubject = false
                },
            )
        } else {
            DropdownField(
                label = "Subject",
                options = subjects,
                selected = subject,
                optionLabel = { it.name },
                onSelect = { subjectId = it.id },
            )
            TextButton(
                onClick = { addingSubject = true },
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
            ) { Text("Add subject") }
        }
        if (errors.subject != null && subjects.isEmpty()) ErrorBanner("Add a subject first. Subjects are shared by all classes.")

        ChoiceField(
            label = "Class",
            choices = classes.map { Choice(it.id, classLabel(it, long = true)) },
            value = classId,
            onChange = { classId = it },
        )
        DateField(
            label = "Test date",
            date = LocalDate.fromEpochDays(dateEpoch),
            onChange = { dateEpoch = it.toEpochDays() },
        )
        AppTextField(
            value = totalText,
            onValueChange = { totalText = sanitizeMarksInput(it) },
            label = "Total marks",
            placeholder = "40",
            keyboardType = KeyboardType.Decimal,
            isError = errors.totalMarks != null,
            supportingText = errors.totalMarks,
        )
        serverError?.let { ErrorBanner(it) }
    }
}

/** Adds a Subject without leaving the form that needs one. */
@Composable
private fun InlineSubjectForm(
    actions: TestsTabActions,
    showCancel: Boolean,
    onCancel: () -> Unit,
    onAdded: (Subject) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var subjectName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTextField(
            value = subjectName,
            onValueChange = {
                subjectName = it
                error = null
            },
            label = "New subject",
            placeholder = "e.g. Mathematics",
            isError = error != null,
            supportingText = error ?: "Subjects are shared by tests and homework in every class.",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (showCancel) TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                enabled = !saving && subjectName.isNotBlank(),
                onClick = {
                    saving = true
                    scope.launch {
                        when (val result = actions.addSubject(subjectName)) {
                            is Attempt.Ok -> onAdded(result.value)
                            is Attempt.Failed -> error = result.message
                        }
                        saving = false
                    }
                },
            ) { Text("Add subject") }
        }
    }
}

/** Filters of the Tests list, applied live; the footer button shows how many Tests currently match. */
@Composable
fun TestsFilterSheet(
    filter: TestsFilter,
    onFilterChange: (TestsFilter) -> Unit,
    classes: List<ClassGroup>,
    subjects: List<Subject>,
    academicYears: List<Int>,
    academicYearStartMonth: Int,
    today: LocalDate,
    matchCount: Int,
    onDismiss: () -> Unit,
) {
    val currentYear = com.itexpert120.attendanceManager.domain.academicYearStartYear(today, academicYearStartMonth)
    val yearChoices = buildList {
        add(Choice(YEAR_CURRENT, "This academic year (${academicYearLabel(currentYear)})"))
        add(Choice(YEAR_ALL, "All time"))
        add(Choice(YEAR_CUSTOM, "Custom date range"))
        academicYears.filter { it != currentYear }.forEach { add(Choice(it, academicYearLabel(it))) }
    }
    AppSheet(
        title = "Filter tests",
        description = "The list updates as you change filters.",
        onDismiss = onDismiss,
        footer = {
            TextButton(onClick = { onFilterChange(TestsFilter(query = filter.query)) }) { Text("Reset") }
            Button(onClick = onDismiss) { Text("Show ${pluralTests(matchCount)}") }
        },
    ) {
        ChoiceField(
            label = "Date",
            choices = yearChoices,
            value = filter.yearChoice,
            onChange = { onFilterChange(filter.copy(yearChoice = it)) },
        )
        if (filter.yearChoice == YEAR_CUSTOM) {
            DateField(
                label = "From",
                date = filter.from,
                onChange = { onFilterChange(filter.copy(from = it)) },
            )
            DateField(
                label = "To",
                date = filter.to,
                onChange = { onFilterChange(filter.copy(to = it)) },
            )
        }
        ChoiceField(
            label = "Class",
            choices = listOf(Choice<Long?>(null, "All classes")) + classes.map { Choice<Long?>(it.id, classLabel(it, long = true)) },
            value = filter.classGroupId,
            onChange = { onFilterChange(filter.copy(classGroupId = it)) },
        )
        ChoiceField(
            label = "Subject",
            choices = listOf(Choice<Long?>(null, "All subjects")) +
                subjects.map { Choice<Long?>(it.id, if (it.isArchived) "${it.name} · Archived" else it.name) },
            value = filter.subjectId,
            onChange = { onFilterChange(filter.copy(subjectId = it)) },
        )
        ChoiceField(
            label = "Progress",
            choices = listOf(Choice<TestProgress?>(null, "All progress")) +
                TestProgress.entries.map { Choice<TestProgress?>(it, progressLabel(it)) },
            value = filter.progress,
            onChange = { onFilterChange(filter.copy(progress = it)) },
        )
    }
}


/** The Subject catalogue from the Tests tab, with each Subject's Test count. */
@Composable
fun SubjectsSheet(
    subjects: List<Subject>,
    testCounts: Map<Long, Int>,
    actions: TestsTabActions,
    onDismiss: () -> Unit,
) {
    SubjectCatalogSheet(
        subjects = subjects,
        description = "Shared by every class. Archive instead of deleting so past tests keep their subject.",
        rowInfo = { subject ->
            SubjectRowInfo(pluralTests(testCounts[subject.id] ?: 0) + if (subject.isArchived) " · Archived" else "")
        },
        onAdd = { name -> actions.addSubject(name).failure() },
        onRename = { id, name -> actions.renameSubject(id, name).failure() },
        onSetArchived = { id, archived -> actions.setSubjectArchived(id, archived).failure() },
        onDismiss = onDismiss,
    )
}

private fun Attempt<*>.failure(): String? = (this as? Attempt.Failed)?.message

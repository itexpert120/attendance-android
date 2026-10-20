package com.itexpert120.attendanceManager.ui.features.students

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.data.repo.PhotoChange
import com.itexpert120.attendanceManager.data.repo.SavedStudent
import com.itexpert120.attendanceManager.data.repo.StudentInput
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.DateField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.components.ListGroup
import com.itexpert120.attendanceManager.ui.components.ListRow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

private const val MAX_RESULTS = 25

/**
 * "Add existing student": enrols a student who is already in the school (for example one who was in another
 * class last year) into this class as a new enrollment, keeping their photo, phone and admission number.
 */
@Composable
fun StudentExistingSheet(
    classText: String,
    classGroupId: Long,
    allStudents: List<Student>,
    classRows: List<EnrollmentRow>,
    /** The "Admitted on" the sheet starts with. */
    defaultAdmittedOn: LocalDate,
    suggestedRoll: String,
    onAdd: suspend (StudentInput) -> Result<SavedStudent>,
    onAdded: (name: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableLongStateOf(NO_STUDENT) }
    var roll by rememberSaveable { mutableStateOf(suggestedRoll) }
    var admittedOn by rememberSaveable(stateSaver = DateSaver) { mutableStateOf(defaultAdmittedOn) }
    var errors by remember { mutableStateOf(StudentFormErrors.NONE) }
    var saving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val selected = allStudents.firstOrNull { it.id == selectedId }
    val candidates = studentsAvailableToAdd(allStudents, classRows, query)

    fun submit() {
        val student = selected ?: return
        if (saving) return
        if (roll.isBlank()) {
            errors = StudentFormErrors(rollNumber = "Roll number is required.")
            return
        }
        errors = StudentFormErrors.NONE
        saving = true
        val input = StudentInput(
            classGroupId = classGroupId,
            admissionNumber = student.admissionNumber,
            rollNumber = roll,
            name = student.name,
            phone = student.phone,
            admittedOn = admittedOn,
            studentId = student.id,
            enrollmentId = null,
            dateOfBirth = student.dateOfBirth,
            struckOffOn = null,
            photo = PhotoChange.Keep,
        )
        scope.launch {
            val result = onAdd(input)
            saving = false
            result
                .onSuccess { onAdded(student.name) }
                .onFailure { errors = StudentFormErrors.fromRepositoryMessage(it.message ?: "Could not add the student.") }
        }
    }

    AppSheet(
        title = "Add existing student",
        description = "Enrol a student who is already in the school into $classText.",
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
            Button(onClick = ::submit, enabled = selected != null && !saving) { Text(if (saving) "Adding…" else "Add to class") }
        },
    ) {
        errors.general?.let { ErrorBanner(it) }
        if (selected == null) {
            StudentField(
                value = query,
                onValueChange = { query = it },
                label = "Search by name or admission number",
                imeAction = ImeAction.Search,
            )
            when {
                allStudents.isEmpty() -> Text(
                    "There are no other students in the school yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                candidates.isEmpty() -> Text(
                    if (query.isBlank()) "Everyone in the school is already in this class." else "No student matches “${query.trim()}”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> ListGroup(
                    footer = if (candidates.size > MAX_RESULTS) "Showing the first $MAX_RESULTS. Type to narrow the list." else null,
                ) {
                    items(candidates.take(MAX_RESULTS)) { student ->
                        ListRow(
                            label = student.name,
                            detail = "Adm. ${student.admissionNumber}",
                            leading = { StudentAvatar(student.name, student.photoFile, size = 40.dp, textStyle = MaterialTheme.typography.titleSmall) },
                            onClick = { selectedId = student.id },
                        )
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StudentAvatar(selected.name, selected.photoFile, size = 48.dp)
                Text(selected.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { selectedId = NO_STUDENT }) { Text("Change") }
            }
            StudentField(
                value = roll,
                onValueChange = { roll = it; errors = errors.copy(rollNumber = null) },
                label = "Roll number in this class",
                error = errors.rollNumber,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                imeAction = ImeAction.Done,
            )
            DateField(label = "Admitted on", date = admittedOn, onChange = { admittedOn = it })
        }
    }
}

private const val NO_STUDENT = -1L

private val DateSaver = Saver<LocalDate, String>(save = { it.toString() }, restore = LocalDate::parse)

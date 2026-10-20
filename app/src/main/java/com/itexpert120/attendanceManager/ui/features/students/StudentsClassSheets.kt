package com.itexpert120.attendanceManager.ui.features.students

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.DateField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** "New class": a class name and a section, which together must be unique. */
@Composable
fun NewClassSheet(
    onCreate: suspend (className: String, section: String) -> Result<ClassGroup>,
    onCreated: (ClassGroup) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var className by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (saving) return
        if (className.isBlank() || section.isBlank()) {
            error = "Class and section are required."
            return
        }
        error = null
        saving = true
        scope.launch {
            val result = onCreate(className, section)
            saving = false
            result
                .onSuccess(onCreated)
                .onFailure { error = it.message ?: "Could not add the class." }
        }
    }

    AppSheet(
        title = "New class",
        description = "Students, registers and tests belong to a class and its section.",
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
            Button(onClick = ::submit, enabled = !saving) { Text(if (saving) "Creating…" else "Create class") }
        },
    ) {
        error?.let { ErrorBanner(it) }
        StudentField(
            value = className,
            onValueChange = { className = it; error = null },
            label = "Class",
            placeholder = "e.g. 8",
            capitalization = KeyboardCapitalization.Characters,
        )
        StudentField(
            value = section,
            onValueChange = { section = it; error = null },
            label = "Section",
            placeholder = "e.g. B",
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done,
            onDone = ::submit,
        )
    }
}

/**
 * "Duplicate class": starts a new class from [source], typically when a year ends and the students move up.
 * Only the students on the roll on the chosen date are copied (see `ClassRepository.duplicateClass`).
 */
@Composable
fun DuplicateClassSheet(
    source: ClassGroup,
    sourceRows: List<EnrollmentRow>,
    today: LocalDate,
    onDuplicate: suspend (className: String, section: String, admittedOn: LocalDate) -> Result<ClassGroup>,
    onDuplicated: (ClassGroup) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var className by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("") }
    var startsOn by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<LocalDate, String>(
        save = { it.toString() },
        restore = LocalDate::parse,
    )) { mutableStateOf(today) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val copied = countCopiedByDuplicate(sourceRows, startsOn)
    val buttonText = when {
        saving -> "Duplicating…"
        copied == 1 -> "Duplicate 1 student"
        else -> "Duplicate $copied students"
    }

    fun submit() {
        if (saving) return
        if (className.isBlank() || section.isBlank()) {
            error = "Class and section are required."
            return
        }
        error = null
        saving = true
        scope.launch {
            val result = onDuplicate(className, section, startsOn)
            saving = false
            result
                .onSuccess(onDuplicated)
                .onFailure { error = it.message ?: "Could not duplicate the class." }
        }
    }

    AppSheet(
        title = "Duplicate class",
        description = "Start a new class from ${classLabel(source)}, for example when students move up a year.",
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
            Button(onClick = ::submit, enabled = !saving) { Text(buttonText) }
        },
    ) {
        error?.let { ErrorBanner(it) }

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExplainerLine(Icons.Rounded.CheckCircle, "Copies the students who are on the roll on the date below, with their roll numbers and contact details.")
                ExplainerLine(Icons.Rounded.CheckCircle, "Students struck off before that date stay behind.")
                ExplainerLine(Icons.Rounded.Info, "Attendance, fees and tests are not copied. The new class starts with empty registers.")
            }
        }

        StudentField(
            value = className,
            onValueChange = { className = it; error = null },
            label = "New class",
            placeholder = "e.g. 8",
            capitalization = KeyboardCapitalization.Characters,
        )
        StudentField(
            value = section,
            onValueChange = { section = it; error = null },
            label = "New section",
            placeholder = "e.g. A",
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done,
        )
        DateField(
            label = "Students join the new class on",
            date = startsOn,
            onChange = { startsOn = it },
            supportingText = if (copied == 1) "1 student will be copied." else "$copied students will be copied.",
        )
    }
}

@Composable
private fun ExplainerLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Preview(showBackground = true)
@Composable
private fun ExplainerPreview() {
    AttendanceTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                ExplainerLine(Icons.Rounded.CheckCircle, "Students struck off before that date stay behind.")
            }
        }
    }
}

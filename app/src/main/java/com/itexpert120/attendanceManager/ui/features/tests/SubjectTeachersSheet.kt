package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.data.repo.TEACHER_NAME_MAX_LENGTH
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.SubjectTeacher
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.AppTextField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import kotlinx.coroutines.launch

/**
 * The changed names among [drafts] (typed text per Subject id) compared with the saved [teachers] of
 * [classGroupId]; trimmed, so retyping the same name or adding spaces is no change.
 */
fun changedTeacherNames(classGroupId: Long, drafts: Map<Long, String>, teachers: List<SubjectTeacher>): Map<Long, String> {
    val saved = teachers.filter { it.classGroupId == classGroupId }.associate { it.subjectId to it.teacherName }
    return drafts.filter { (subjectId, text) -> text.trim() != saved[subjectId].orEmpty().trim() }
}

/**
 * Who teaches each active Subject in one class: pick the class, type a name per Subject (blank removes the
 * assignment), Save. The Teacher performance report groups results by these names.
 */
@Composable
fun SubjectTeachersSheet(
    classes: List<ClassGroup>,
    subjects: List<Subject>,
    teachers: List<SubjectTeacher>,
    initialClassId: Long?,
    onSave: suspend (classGroupId: Long, names: Map<Long, String>) -> String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var classId by rememberSaveable { mutableStateOf(initialClassId?.takeIf { id -> classes.any { it.id == id } } ?: classes.firstOrNull()?.id) }
    // Only edited fields are kept; the rest show the saved name. A HashMap survives rotation (Serializable).
    var drafts by rememberSaveable(classId) { mutableStateOf(HashMap<Long, String>()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    val active = subjects.filterNot { it.isArchived }
    val currentClass = classId
    val changes = if (currentClass == null) emptyMap() else changedTeacherNames(currentClass, drafts, teachers)

    AppSheet(
        title = "Subject teachers",
        description = "Who teaches each subject in a class. Teacher performance reports group results by these names.",
        onDismiss = onDismiss,
        footer = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = currentClass != null && changes.isNotEmpty() && !saving,
                onClick = {
                    val target = currentClass ?: return@Button
                    error = null
                    saving = true
                    scope.launch {
                        val failure = onSave(target, changes)
                        saving = false
                        if (failure == null) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onDismiss()
                        } else {
                            error = failure
                        }
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Save") }
        },
    ) {
        if (classes.isEmpty()) {
            Text(
                "Add a class first, then assign its teachers here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            return@AppSheet
        }
        ChoiceField(
            label = "Class",
            choices = classes.map { Choice<Long?>(it.id, classLabel(it, long = true)) },
            value = classId,
            onChange = {
                error = null
                classId = it
            },
        )
        error?.let { ErrorBanner(it) }
        if (active.isEmpty()) {
            Text(
                "No subjects yet. Add subjects from the Tests tab first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        val saved = teachers.filter { it.classGroupId == classId }.associate { it.subjectId to it.teacherName }
        active.forEach { subject ->
            key(subject.id) {
                val text = drafts[subject.id] ?: saved[subject.id].orEmpty()
                AppTextField(
                    value = text,
                    onValueChange = { value ->
                        drafts = HashMap(drafts).apply { put(subject.id, value.take(TEACHER_NAME_MAX_LENGTH + 10)) }
                    },
                    label = subject.name,
                    placeholder = "No teacher assigned",
                    isError = text.trim().length > TEACHER_NAME_MAX_LENGTH,
                    supportingText = if (text.trim().length > TEACHER_NAME_MAX_LENGTH) {
                        "At most $TEACHER_NAME_MAX_LENGTH characters"
                    } else {
                        null
                    },
                )
            }
        }
    }
}

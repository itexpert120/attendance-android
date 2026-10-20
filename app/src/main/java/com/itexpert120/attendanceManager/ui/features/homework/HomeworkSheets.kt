package com.itexpert120.attendanceManager.ui.features.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.features.setup.SetupTextField
import com.itexpert120.attendanceManager.ui.features.tests.SubjectCatalogSheet
import com.itexpert120.attendanceManager.ui.features.tests.SubjectRowInfo
import kotlinx.coroutines.launch

/**
 * Picks the Subject of one homework item from radio rows; Subjects already on the report are shown but
 * disabled. A new Subject can be added here and is selected straight away (the web app's "Add & use").
 */
@Composable
fun SubjectPickerSheet(
    choices: List<SubjectChoice>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onAddSubject: suspend (String) -> HomeworkResult<Subject>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var newName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    fun add() {
        if (newName.isBlank() || adding) return
        error = null
        adding = true
        scope.launch {
            when (val result = onAddSubject(newName)) {
                is HomeworkResult.Ok -> {
                    newName = ""
                    onSelect(result.value.id)
                }
                is HomeworkResult.Failed -> error = result.message
            }
            adding = false
        }
    }

    AppSheet(
        title = "Choose subject",
        description = "Subjects are shared by tests and homework across every class.",
        onDismiss = onDismiss,
    ) {
        if (choices.isEmpty()) {
            Text(
                "No subjects yet. Add the first one below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.forEach { choice ->
                    SubjectRadioRow(
                        choice = choice,
                        selected = choice.subject.id == selectedId,
                        onClick = { onSelect(choice.subject.id) },
                    )
                }
            }
        }

        Text(
            "New subject",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        SetupTextField(
            value = newName,
            onValueChange = { newName = it },
            label = "Subject name",
            placeholder = "e.g. Computer Science",
            imeAction = ImeAction.Done,
            onDone = ::add,
        )
        error?.let { ErrorBanner(it) }
        Button(
            onClick = ::add,
            enabled = newName.isNotBlank() && !adding,
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
        ) { Text("Add & use") }
    }
}

@Composable
private fun SubjectRadioRow(choice: SubjectChoice, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = selected || !choice.usedElsewhere
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp)
                .alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    choice.subject.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val note = when {
                    choice.usedElsewhere -> "Already on this report"
                    choice.subject.isArchived -> "Archived"
                    else -> null
                }
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * The web app's "Manage Subjects" from the Homework tab and editor: add, rename, archive and restore, with each
 * Subject's homework report count. In the editor ([inEditor]) a new Subject also becomes a homework row
 * ("Add & use", done by [onAdd]), and a Subject on the draft ([draftSubjectIds]) can't be archived.
 */
@Composable
fun HomeworkSubjectsSheet(
    subjects: List<Subject>,
    reportCounts: Map<Long, Int>,
    actions: HomeworkSubjectActions,
    onDismiss: () -> Unit,
    inEditor: Boolean = false,
    draftSubjectIds: Set<Long> = emptySet(),
    onAdd: suspend (String) -> HomeworkResult<Subject> = actions::addSubject,
) {
    SubjectCatalogSheet(
        subjects = subjects,
        title = "Manage subjects",
        description = if (inEditor) {
            "Add a Subject and it becomes a new homework row immediately. Renames update every Test and report."
        } else {
            "Shared by Tests and homework across every class. Renames update every Test and report."
        },
        addLabel = if (inEditor) "Add & use" else "Add subject",
        rowInfo = { subject ->
            SubjectRowInfo(
                usage = homeworkSubjectUsage(subject, reportCounts[subject.id] ?: 0),
                archiveBlockedReason = if (subject.id in draftSubjectIds) "On this report, so it can't be archived" else null,
            )
        },
        onAdd = { name -> onAdd(name).failure() },
        onRename = { id, name -> actions.renameSubject(id, name).failure() },
        onSetArchived = { id, archived -> actions.setSubjectArchived(id, archived).failure() },
        onDismiss = onDismiss,
    )
}

private fun HomeworkResult<*>.failure(): String? = (this as? HomeworkResult.Failed)?.message

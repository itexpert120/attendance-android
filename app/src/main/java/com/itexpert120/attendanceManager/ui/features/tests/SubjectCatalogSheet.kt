package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.AppTextField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import kotlinx.coroutines.launch

private const val NOT_EDITING = -1L

/** What a row of [SubjectCatalogSheet] says under the Subject's name, and whether it may be archived now. */
data class SubjectRowInfo(
    /** e.g. "3 tests" or "Active · 2 homework reports". */
    val usage: String,
    /** Non-null disables Archive and is shown as the reason (the web app blocked archiving a Subject in use on the open draft). */
    val archiveBlockedReason: String? = null,
)

/**
 * The shared Subject catalogue manager used by Tests and Homework: add, rename, archive and restore.
 * Archiving is reversible in place (the row turns into a Restore row), so it needs no confirmation.
 * Each command returns null on success or the message to show inline.
 */
@Composable
fun SubjectCatalogSheet(
    subjects: List<Subject>,
    description: String,
    rowInfo: (Subject) -> SubjectRowInfo,
    onAdd: suspend (name: String) -> String?,
    onRename: suspend (subjectId: Long, name: String) -> String?,
    onSetArchived: suspend (subjectId: Long, archived: Boolean) -> String?,
    onDismiss: () -> Unit,
    title: String = "Subjects",
    addLabel: String = "Add subject",
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var newName by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf(NOT_EDITING) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AppSheet(title = title, description = description, onDismiss = onDismiss) {
        AppTextField(
            value = newName,
            onValueChange = { newName = it },
            label = "New subject",
            placeholder = "e.g. Computer Science",
        )
        Button(
            enabled = newName.isNotBlank(),
            onClick = {
                error = null
                scope.launch {
                    val failure = onAdd(newName)
                    if (failure == null) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        newName = ""
                    } else {
                        error = failure
                    }
                }
            },
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
        ) { Text(addLabel) }

        error?.let { ErrorBanner(it) }

        if (subjects.isEmpty()) {
            Text(
                "No subjects yet. Add the first one above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            subjects.forEach { subject ->
                key(subject.id) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (editingId == subject.id) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                AppTextField(
                                    value = editingName,
                                    onValueChange = { editingName = it },
                                    label = "Rename subject",
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                    TextButton(onClick = { editingId = NOT_EDITING }) { Text("Cancel") }
                                    Button(
                                        enabled = editingName.isNotBlank(),
                                        onClick = {
                                            error = null
                                            scope.launch {
                                                val failure = onRename(subject.id, editingName)
                                                if (failure == null) editingId = NOT_EDITING else error = failure
                                            }
                                        },
                                    ) { Text("Save") }
                                }
                            }
                        } else {
                            CatalogSubjectRow(
                                subject = subject,
                                info = rowInfo(subject),
                                onRename = {
                                    error = null
                                    editingId = subject.id
                                    editingName = subject.name
                                },
                                onToggleArchive = {
                                    error = null
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    scope.launch {
                                        onSetArchived(subject.id, !subject.isArchived)?.let { error = it }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogSubjectRow(subject: Subject, info: SubjectRowInfo, onRename: () -> Unit, onToggleArchive: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val blocked = !subject.isArchived && info.archiveBlockedReason != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                subject.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (subject.isArchived) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(info.usage, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (blocked) {
                Text(info.archiveBlockedReason.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Rounded.Edit, contentDescription = "Rename ${subject.name}")
        }
        IconButton(onClick = onToggleArchive, enabled = !blocked) {
            if (subject.isArchived) {
                Icon(Icons.Rounded.Unarchive, contentDescription = "Restore ${subject.name}")
            } else {
                Icon(Icons.Rounded.Archive, contentDescription = "Archive ${subject.name}")
            }
        }
    }
}

package com.itexpert120.attendanceManager.ui.features.settings

import androidx.compose.foundation.clickable
import com.itexpert120.attendanceManager.data.db.ClassUsage
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.features.setup.SetupTextField
import kotlinx.coroutines.launch

private const val NONE = -1L

/** Lists the school's classes and adds new ones. */
@Composable
internal fun ClassesSheet(
    classes: List<ClassGroup>,
    actions: CatalogActions,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var className by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf(NONE) }

    val add: () -> Unit = add@{
        if (saving) return@add
        error = null
        saving = true
        scope.launch {
            val failure = actions.addClass(className, section)
            saving = false
            if (failure == null) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                className = ""
                section = ""
            } else {
                error = failure
            }
        }
    }

    AppSheet(
        title = "Classes",
        description = "A class is a class name and a section, such as 7 and A. Each has its own roll and registers. Tap a class to rename or delete it.",
        onDismiss = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupTextField(
                value = className,
                onValueChange = { className = it; error = null },
                label = "Class",
                placeholder = "e.g. 7",
                capitalization = KeyboardCapitalization.Characters,
                modifier = Modifier.weight(1f),
            )
            SetupTextField(
                value = section,
                onValueChange = { section = it; error = null },
                label = "Section",
                placeholder = "e.g. A",
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
                onDone = add,
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = add,
            enabled = !saving && className.isNotBlank() && section.isNotBlank(),
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add class")
        }
        error?.let { ErrorBanner(it) }

        if (classes.isEmpty()) {
            EmptyNote("No classes yet. Add the first one above.")
        } else {
            CatalogList {
                classes.forEach { group ->
                    key(group.id) {
                        if (editingId == group.id) {
                            EditClassRow(
                                group = group,
                                canDelete = classes.size > 1,
                                actions = actions,
                                onDone = { editingId = NONE },
                            )
                        } else {
                            CatalogRow(onClick = { error = null; editingId = group.id }, onClickLabel = "Edit ${classTitle(group)}") {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(classTitle(group), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Tap to rename or delete",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The shared Subject catalogue: add, rename (tap a row) and archive. Subjects are archived rather than deleted
 * so past Tests and homework keep their Subject; an archived row turns into a Restore row, which is the undo.
 */
@Composable
internal fun SubjectsSheet(
    subjects: List<Subject>,
    actions: CatalogActions,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var newName by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf(NONE) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val add: () -> Unit = add@{
        if (newName.isBlank()) return@add
        error = null
        scope.launch {
            val failure = actions.addSubject(newName)
            if (failure == null) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                newName = ""
            } else {
                error = failure
            }
        }
    }

    AppSheet(
        title = "Subjects",
        description = "Shared by Tests and homework in every class. Archive a subject you no longer teach; past records keep it.",
        onDismiss = onDismiss,
    ) {
        SetupTextField(
            value = newName,
            onValueChange = { newName = it; error = null },
            label = "New subject",
            placeholder = "e.g. Mathematics",
            imeAction = ImeAction.Done,
            onDone = add,
        )
        Button(
            onClick = add,
            enabled = newName.isNotBlank(),
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add subject")
        }
        error?.let { ErrorBanner(it) }

        if (subjects.isEmpty()) {
            EmptyNote("No subjects yet. Add the first one above.")
        } else {
            CatalogList {
                subjects.forEach { subject ->
                    key(subject.id) {
                        if (editingId == subject.id) {
                            RenameSubjectRow(
                                name = editingName,
                                onNameChange = { editingName = it; error = null },
                                onCancel = { editingId = NONE },
                                onSave = {
                                    error = null
                                    scope.launch {
                                        val failure = actions.renameSubject(subject.id, editingName)
                                        if (failure == null) editingId = NONE else error = failure
                                    }
                                },
                            )
                        } else {
                            SubjectRow(
                                subject = subject,
                                onRename = {
                                    error = null
                                    editingId = subject.id
                                    editingName = subject.name
                                },
                                onToggleArchive = {
                                    error = null
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    scope.launch {
                                        actions.setSubjectArchived(subject.id, archived = !subject.isArchived)
                                            ?.let { error = it }
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
private fun SubjectRow(subject: Subject, onRename: () -> Unit, onToggleArchive: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    CatalogRow(onClick = if (subject.isArchived) null else onRename, onClickLabel = "Rename ${subject.name}") {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                subject.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (subject.isArchived) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (subject.isArchived) "Archived · hidden from pickers" else "Tap to rename",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        if (subject.isArchived) {
            TextButton(onClick = onToggleArchive, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Restore")
            }
        } else {
            IconButton(onClick = onToggleArchive) {
                Icon(Icons.Rounded.Archive, contentDescription = "Archive ${subject.name}")
            }
        }
    }
}

@Composable
private fun RenameSubjectRow(name: String, onNameChange: (String) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SetupTextField(
                value = name,
                onValueChange = onNameChange,
                label = "Subject name",
                imeAction = ImeAction.Done,
                onDone = { if (name.isNotBlank()) onSave() },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = onSave, enabled = name.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun CatalogList(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun CatalogRow(
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val clickable = if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier
        Row(
            modifier = clickable.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

/**
 * Inline editor for one class: rename it, or delete it after a confirmation that says exactly what goes
 * with it (its roll, registers, Tests and homework; the students themselves stay).
 */
@Composable
private fun EditClassRow(group: ClassGroup, canDelete: Boolean, actions: CatalogActions, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    var className by rememberSaveable(group.id) { mutableStateOf(group.className) }
    var section by rememberSaveable(group.id) { mutableStateOf(group.section) }
    var error by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable(group.id) { mutableStateOf(false) }
    var usage by remember(group.id) { mutableStateOf<ClassUsage?>(null) }
    var busy by remember(group.id) { mutableStateOf(false) }
    val changed = className.trim() != group.className || section.trim() != group.section

    LaunchedEffect(confirming) {
        if (confirming && usage == null) usage = actions.classUsage(group.id)
    }

    val save: () -> Unit = save@{
        if (busy || !changed || className.isBlank() || section.isBlank()) return@save
        busy = true
        error = null
        scope.launch {
            val failure = actions.renameClass(group.id, className, section)
            busy = false
            if (failure == null) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onDone()
            } else {
                error = failure
            }
        }
    }

    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SetupTextField(
                        value = className,
                        onValueChange = { className = it; error = null },
                        label = "Class",
                        capitalization = KeyboardCapitalization.Characters,
                        modifier = Modifier.weight(1f),
                    )
                    SetupTextField(
                        value = section,
                        onValueChange = { section = it; error = null },
                        label = "Section",
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                        onDone = save,
                        modifier = Modifier.weight(1f),
                    )
                }
                error?.let { ErrorBanner(it) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (canDelete) {
                        TextButton(
                            onClick = { error = null; confirming = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    Button(
                        onClick = save,
                        enabled = !busy && changed && className.isNotBlank() && section.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Save") }
                }
            } else {
                Text("Delete ${classTitle(group)}?", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Text(deleteClassWarning(usage), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                error?.let { ErrorBanner(it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { confirming = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep class") }
                    Button(
                        onClick = {
                            if (!busy) {
                                busy = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    val failure = actions.deleteClass(group.id)
                                    busy = false
                                    if (failure == null) onDone() else error = failure
                                }
                            }
                        },
                        enabled = !busy && usage != null,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Delete class") }
                }
            }
        }
    }
}

/** The confirmation text: what the delete removes, in plain words. */
internal fun deleteClassWarning(usage: ClassUsage?): String {
    if (usage == null) return "Checking what belongs to this class…"
    if (usage.isEmpty) return "This class has no students, registers, Tests or homework. This can't be undone."
    val parts = listOfNotNull(
        usage.enrollments.takeIf { it > 0 }?.let { plural(it, "student on its roll", "students on its roll") },
        usage.registers.takeIf { it > 0 }?.let { plural(it, "register", "registers") + " with attendance and fees" },
        usage.tests.takeIf { it > 0 }?.let { plural(it, "Test", "Tests") + " with results" },
        usage.homeworkReports.takeIf { it > 0 }?.let { plural(it, "homework report", "homework reports") },
    )
    val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    return "This permanently deletes $list. Students stay in the app and can be enrolled in another class. " +
        "This can't be undone; make a backup first if you may need this data."
}

private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

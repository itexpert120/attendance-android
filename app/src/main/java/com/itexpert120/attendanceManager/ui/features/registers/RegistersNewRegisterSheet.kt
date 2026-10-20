package com.itexpert120.attendanceManager.ui.features.registers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.domain.monthName
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.DropdownField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.features.setup.SetupTextField
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * "New register" bottom sheet: class, month and year, then Open register. "New class" swaps the sheet's
 * content for a two-field class form (Back returns to the register form) and selects the class it creates.
 * Repository messages arrive through [action] and are shown inline above the buttons.
 */
@Composable
internal fun RegistersNewRegisterSheet(
    classes: List<ClassGroup>,
    today: LocalDate,
    initialClassId: Long?,
    action: RegistersAction,
    onCreateRegister: (classGroupId: Long, year: Int, month: Int) -> Unit,
    onAddClass: (className: String, section: String, onAdded: (classGroupId: Long) -> Unit) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
) {
    var classId by rememberSaveable { mutableStateOf(initialClassId) }
    var month by rememberSaveable { mutableIntStateOf(today.month.number) }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    var addingClass by rememberSaveable { mutableStateOf(false) }
    var newClassName by rememberSaveable { mutableStateOf("") }
    var newSection by rememberSaveable { mutableStateOf("") }

    val selected = classes.firstOrNull { it.id == classId } ?: classes.firstOrNull()
    // A school with no class at all can only add one, so the class form is the whole sheet.
    val showClassForm = addingClass || classes.isEmpty()
    val years = newRegisterYears(today)

    fun leaveClassForm() {
        onClearError()
        addingClass = false
        newClassName = ""
        newSection = ""
    }

    fun submitClass() {
        onAddClass(newClassName, newSection) { id ->
            classId = id
            addingClass = false
            newClassName = ""
            newSection = ""
        }
    }

    BackHandler(enabled = addingClass && classes.isNotEmpty()) { leaveClassForm() }

    AppSheet(
        title = if (showClassForm) "New class" else "New register",
        description = if (showClassForm) {
            "Students, registers and tests belong to a class and section."
        } else {
            "Students on the roll during the chosen month are included automatically."
        },
        onDismiss = onDismiss,
        footer = {
            if (showClassForm) {
                TextButton(onClick = { if (classes.isEmpty()) onDismiss() else leaveClassForm() }) {
                    Text(if (classes.isEmpty()) "Cancel" else "Back")
                }
                Button(
                    onClick = ::submitClass,
                    enabled = !action.busy && newClassName.isNotBlank() && newSection.isNotBlank(),
                ) { Text("Create class") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        onClearError()
                        selected?.let { onCreateRegister(it.id, year, month) }
                    },
                    enabled = selected != null && !action.busy,
                ) { Text("Open register") }
            }
        },
    ) {
        if (showClassForm) {
            SetupTextField(
                value = newClassName,
                onValueChange = { newClassName = it; onClearError() },
                label = "Class",
                placeholder = "e.g. 8",
                capitalization = KeyboardCapitalization.Characters,
            )
            SetupTextField(
                value = newSection,
                onValueChange = { newSection = it; onClearError() },
                label = "Section",
                placeholder = "e.g. B",
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done,
                onDone = { if (newClassName.isNotBlank() && newSection.isNotBlank() && !action.busy) submitClass() },
            )
        } else {
            DropdownField(
                label = "Class",
                options = classes,
                selected = selected,
                optionLabel = ::classOptionLabel,
                onSelect = { classId = it.id; onClearError() },
            )
            TextButton(
                onClick = { onClearError(); addingClass = true },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("New class", modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownField(
                    label = "Month",
                    options = (1..12).toList(),
                    selected = month,
                    optionLabel = ::monthName,
                    onSelect = { month = it; onClearError() },
                    modifier = Modifier.weight(1f),
                )
                DropdownField(
                    label = "Year",
                    options = years,
                    selected = year,
                    optionLabel = Int::toString,
                    onSelect = { year = it; onClearError() },
                    modifier = Modifier.weight(0.7f),
                )
            }
        }
        action.error?.let { ErrorBanner(it) }
    }
}

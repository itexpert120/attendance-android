package com.itexpert120.attendanceManager.ui.features.registers

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.domain.longDateLabel
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import kotlinx.datetime.LocalDate

/**
 * Declares a school holiday; a reason is required, as in the PWA. When the day already has marks the sheet warns first,
 * because declaring a holiday deletes them and they are not restored when the holiday is removed.
 */
@Composable
internal fun AttendanceHolidaySheet(
    date: LocalDate,
    hasMarks: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val submit = {
        if (reason.isBlank()) error = "Enter a reason for the holiday." else onConfirm(reason.trim())
    }
    AppSheet(
        title = "Declare holiday",
        description = longDateLabel(date),
        onDismiss = onDismiss,
        footer = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = submit) { Text("Declare holiday") }
        },
    ) {
        if (hasMarks) {
            ErrorBanner("This day already has attendance marks. Declaring a holiday deletes them, and removing the holiday later does not bring them back.")
        }
        OutlinedTextField(
            value = reason,
            onValueChange = {
                reason = it
                error = null
            },
            label = { Text("Reason") },
            isError = error != null,
            supportingText = error?.let { message -> { Text(message) } },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Weekends are already treated as holidays, so only declare days the school is closed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

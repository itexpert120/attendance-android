package com.itexpert120.attendanceManager.ui.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.itexpert120.attendanceManager.data.backup.AppBackupSummary
import com.itexpert120.attendanceManager.data.backup.BackupSummary
import com.itexpert120.attendanceManager.data.backup.BackupTables
import com.itexpert120.attendanceManager.data.backup.PwaTables
import com.itexpert120.attendanceManager.domain.longDateLabel
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Shows whatever the backup flow needs right now: a progress dialog, a confirmation sheet, or nothing. */
@Composable
internal fun BackupPhaseUi(
    phase: BackupPhase,
    onConfirmRestore: () -> Unit,
    onConfirmWebImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (phase) {
        BackupPhase.Idle -> Unit
        is BackupPhase.Working -> BackupProgressDialog(phase.message)
        is BackupPhase.ConfirmRestore -> RestoreConfirmSheet(phase.summary, onConfirmRestore, onDismiss)
        is BackupPhase.ConfirmWebImport -> WebImportConfirmSheet(phase.summary, onConfirmWebImport, onDismiss)
    }
}

/** Can't be dismissed: a restore must run to its end (it is all-or-nothing anyway). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackupProgressDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoadingIndicator(Modifier.size(48.dp))
                Column {
                    Text(message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Keep the app open until this finishes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun RestoreConfirmSheet(summary: AppBackupSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val made = summary.createdAt.toLocalDateTime(TimeZone.currentSystemDefault()).date
    AppSheet(
        title = "Restore this backup?",
        onDismiss = onDismiss,
        description = "Backup from ${longDateLabel(made)}",
        footer = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            DestructiveButton("Replace all data", onConfirm)
        },
    ) {
        ErrorBanner("This replaces all data on this phone with the backup. What is on the phone now will be lost.")
        val counts = summary.counts
        SummaryFacts(
            buildList {
                summary.schoolName?.let { add("School" to it) }
                add("Classes" to "${counts[BackupTables.CLASS_GROUPS] ?: 0}")
                add("Students" to "${counts[BackupTables.STUDENTS] ?: 0}")
                add("Registers" to "${counts[BackupTables.REGISTERS] ?: 0}")
                add("Tests" to "${counts[BackupTables.TESTS] ?: 0}")
                add("Homework reports" to "${counts[BackupTables.HOMEWORK_REPORTS] ?: 0}")
                add("Photos" to "${summary.photoCount}")
            },
        )
    }
}

@Composable
private fun WebImportConfirmSheet(summary: BackupSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppSheet(
        title = "Import web app backup?",
        onDismiss = onDismiss,
        description = summary.schoolName,
        footer = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            DestructiveButton("Replace all data", onConfirm)
        },
    ) {
        ErrorBanner("This replaces all data on this phone with the web app backup. What is on the phone now will be lost.")
        val counts = summary.counts
        SummaryFacts(
            listOf(
                "Classes" to "${counts[PwaTables.CLASS_GROUPS] ?: 0}",
                "Students" to "${counts[PwaTables.STUDENTS] ?: 0}",
                "Registers" to "${counts[PwaTables.REGISTERS] ?: 0}",
                "Tests" to "${counts[PwaTables.TESTS] ?: 0}",
                "Homework reports" to "${counts[PwaTables.HOMEWORK_REPORTS] ?: 0}",
            ),
        )
        if (summary.warnings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Some rows will be fixed or skipped",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val shown = summary.warnings.take(MAX_WARNINGS)
                shown.forEach { warning ->
                    Text(
                        "• $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (summary.warnings.size > shown.size) {
                    Text(
                        "…and ${summary.warnings.size - shown.size} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryFacts(facts: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        facts.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun DestructiveButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) { Text(label) }
}

private const val MAX_WARNINGS = 5

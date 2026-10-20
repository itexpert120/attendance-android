package com.itexpert120.attendanceManager.ui.features.students

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.itexpert120.attendanceManager.ui.components.StoredPhoto
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.domain.dateLabel
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.BadgeTone
import com.itexpert120.attendanceManager.ui.components.ListGroup
import com.itexpert120.attendanceManager.ui.components.ListRow
import com.itexpert120.attendanceManager.ui.components.StatusBadge
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * A student at a glance: the photo, ways to reach the parent (each a real intent) and the enrollment details,
 * with edit and strike-off / restore at the bottom where a thumb reaches them.
 */
@Composable
fun StudentDetailSheet(
    row: EnrollmentRow,
    phoneDisplay: String,
    onContact: (ContactAction) -> Unit,
    /** Null when opened read-only (for example from the attendance register): no Edit or roll actions. */
    onEdit: (() -> Unit)?,
    onStrikeOff: (() -> Unit)?,
    onRestore: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val student = row.student
    val enrollment = row.enrollment
    val struckOff = enrollment.struckOffOn
    val hasPhone = student.phone.isNotBlank()
    var photoExpanded by rememberSaveable(student.id) { mutableStateOf(false) }
    val photoSaver = rememberStudentPhotoSaver()

    AppSheet(
        title = student.name,
        description = studentSummaryLine(row),
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            if (struckOff == null) {
                if (onStrikeOff != null) {
                    TextButton(onClick = onStrikeOff) { Text("Strike off", color = MaterialTheme.colorScheme.error) }
                }
            } else if (onRestore != null) {
                TextButton(onClick = onRestore) { Text("Put back on roll") }
            }
            if (onEdit != null) {
                Button(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Edit")
                }
            }
        },
    ) {
        val photoFile = student.photoFile
        if (photoFile != null && photoExpanded) {
            // The photo at a readable size; tapping it shrinks it back to the avatar.
            StoredPhoto(
                fileName = photoFile,
                contentDescription = "Photo of ${student.name}. Tap to make it smaller.",
                contentScale = ContentScale.Fit,
                maxEdgePx = 1280,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { photoExpanded = false },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (photoFile == null || !photoExpanded) {
                StudentAvatar(
                    name = student.name,
                    photoFile = photoFile,
                    size = 72.dp,
                    textStyle = MaterialTheme.typography.headlineSmall,
                    modifier = if (photoFile != null) {
                        Modifier
                            .clip(CircleShape)
                            .clickable(onClickLabel = "View photo") { photoExpanded = true }
                    } else {
                        Modifier
                    },
                )
            }
            if (struckOff != null) {
                StatusBadge(struckOffBadgeText(struckOff), tone = BadgeTone.WARNING)
            } else {
                StatusBadge("On the roll", tone = BadgeTone.SUCCESS)
            }
        }

        if (photoFile != null) {
            ListGroup(header = "Photo") {
                item {
                    ListRow(
                        label = if (photoSaver.isSaving) "Saving photo…" else "Save photo",
                        detail = "Copies it to the gallery (Pictures/Attendance)",
                        icon = Icons.Rounded.Download,
                        enabled = !photoSaver.isSaving,
                        onClick = { photoSaver.save(student) },
                    )
                }
            }
        }

        if (hasPhone) {
            ListGroup(header = "Contact the parent") {
                item {
                    ListRow(
                        label = "Call",
                        detail = phoneDisplay,
                        icon = Icons.Rounded.Call,
                        onClick = { onContact(ContactAction.CALL) },
                    )
                }
                item {
                    ListRow(
                        label = "Send SMS",
                        detail = phoneDisplay,
                        icon = Icons.Rounded.Sms,
                        onClick = { onContact(ContactAction.SMS) },
                    )
                }
                item {
                    ListRow(
                        label = "WhatsApp",
                        detail = phoneDisplay,
                        icon = Icons.AutoMirrored.Rounded.Chat,
                        onClick = { onContact(ContactAction.WHATSAPP) },
                    )
                }
            }
        } else {
            ListGroup(header = "Contact the parent") {
                item {
                    ListRow(
                        label = "No phone number saved",
                        detail = "Add one to call or message the parent",
                        icon = Icons.Rounded.Call,
                        chevron = onEdit != null,
                        onClick = onEdit,
                    )
                }
            }
        }

        ListGroup(header = "Details") {
            item { DetailLine("B-Form number", student.admissionNumber) }
            item { DetailLine("Roll number", enrollment.rollNumber) }
            item { DetailLine("Date of birth", student.dateOfBirth?.let(::dateLabel) ?: "Not recorded") }
            item { DetailLine("Admitted on", dateLabel(enrollment.admittedOn)) }
            if (struckOff != null) item { DetailLine("Struck off on", dateLabel(struckOff)) }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(showBackground = true)
@Composable
private fun DetailLinePreview() {
    AttendanceTheme(dynamicColor = false) {
        val row = EnrollmentRow(
            Enrollment(1, 1, 1, "12", LocalDate(2026, 4, 1)),
            Student(1, "1042", "Ayaan S/O Awaan", "+923001234567", LocalDate(2015, 5, 9), null, Instant.fromEpochMilliseconds(0)),
        )
        Column(Modifier.padding(16.dp)) {
            ListGroup(header = "Details") {
                item { DetailLine("B-Form number", row.student.admissionNumber) }
                item { DetailLine("Date of birth", row.student.dateOfBirth?.let(::dateLabel) ?: "Not recorded") }
            }
        }
    }
}

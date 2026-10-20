package com.itexpert120.attendanceManager.ui.features.students

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.ui.components.BadgeTone
import com.itexpert120.attendanceManager.ui.components.StatusBadge
import com.itexpert120.attendanceManager.ui.components.StoredPhoto
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

private val OuterCorner = 20.dp
private val InnerCorner = 4.dp

/** The shape of item [index] of [count] in a run of segmented rows: big outer corners, small inner ones. */
fun segmentedShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) OuterCorner else InnerCorner,
    topEnd = if (index == 0) OuterCorner else InnerCorner,
    bottomStart = if (index == count - 1) OuterCorner else InnerCorner,
    bottomEnd = if (index == count - 1) OuterCorner else InnerCorner,
)

/** A round avatar: the stored photo when there is one, otherwise the student's initials. */
@Composable
fun StudentAvatar(
    name: String,
    photoFile: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(colors.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(studentInitials(name), style = textStyle, color = colors.onSecondaryContainer, maxLines = 1)
        // Drawn over the initials once the file has been decoded; empty (so the initials show) until then.
        if (photoFile != null) {
            StoredPhoto(
                fileName = photoFile,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                maxEdgePx = 256,
            )
        }
    }
}

/** The one-line summary under a student's name: "Roll 12 · B-Form 12345-1234567-1". */
fun studentSummaryLine(row: EnrollmentRow): String =
    "Roll ${row.enrollment.rollNumber} · B-Form ${row.student.admissionNumber}"

/**
 * One student in the class list. Tap opens the details sheet; swipe right to call the parent, swipe left to
 * message them. The swipe returns the row to rest and opens the dialer / messaging app, so a stray swipe never
 * changes data. A struck-off row is dimmed and carries a "Struck off <date>" badge.
 */
@Composable
fun StudentListItem(
    row: EnrollmentRow,
    shape: Shape,
    phoneDisplay: String,
    onClick: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val latestCall by rememberUpdatedState(onCall)
    val latestMessage by rememberUpdatedState(onMessage)
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val struckOff = row.enrollment.struckOffOn
    val hasPhone = row.student.phone.isNotBlank()

    SwipeToDismissBox(
        state = swipe,
        modifier = modifier.clip(shape),
        onDismiss = { target ->
            when (target) {
                SwipeToDismissBoxValue.StartToEnd -> latestCall()
                SwipeToDismissBoxValue.EndToStart -> latestMessage()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // The swipe is a gesture shortcut, not a dismissal: slide the row back into place.
            scope.launch { swipe.reset() }
        },
        backgroundContent = {
            when (swipe.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeBackground(
                    container = colors.primaryContainer,
                    content = colors.onPrimaryContainer,
                    alignment = Alignment.CenterStart,
                    label = "Call",
                    icon = Icons.Rounded.Call,
                )

                SwipeToDismissBoxValue.EndToStart -> SwipeBackground(
                    container = colors.tertiaryContainer,
                    content = colors.onTertiaryContainer,
                    alignment = Alignment.CenterEnd,
                    label = "Message",
                    icon = Icons.Rounded.Sms,
                )

                SwipeToDismissBoxValue.Settled -> Box(Modifier.fillMaxSize())
            }
        },
    ) {
        Surface(
            shape = shape,
            color = colors.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(onClickLabel = "Open student details", onClick = onClick)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (hasPhone) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StudentAvatar(
                    name = row.student.name,
                    photoFile = row.student.photoFile,
                    modifier = Modifier.alpha(if (struckOff != null) 0.55f else 1f),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        row.student.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (struckOff != null) colors.onSurfaceVariant else colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        studentSummaryLine(row),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (struckOff != null) {
                        StatusBadge(struckOffBadgeText(struckOff), tone = BadgeTone.WARNING, modifier = Modifier.padding(top = 4.dp))
                    } else if (phoneDisplay.isNotEmpty()) {
                        Text(
                            phoneDisplay,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (hasPhone) {
                    IconButton(onClick = onCall, modifier = Modifier.semantics { contentDescription = "Call parent of ${row.student.name}" }) {
                        Icon(Icons.Rounded.Call, contentDescription = null, tint = colors.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeBackground(
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    alignment: Alignment,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(container).padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = content)
            Text(label, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

// ---- Previews -----------------------------------------------------------------------------------------

private val PreviewStudent = Student(
    id = 1,
    admissionNumber = "1042",
    name = "Ayaan S/O Awaan",
    phone = "+923001234567",
    createdAt = Instant.fromEpochMilliseconds(0),
)

private fun previewRow(struckOffOn: LocalDate? = null) = EnrollmentRow(
    Enrollment(1, 1, 1, "12", LocalDate(2026, 4, 1), struckOffOn),
    PreviewStudent,
)

@Preview(showBackground = true)
@Composable
private fun StudentListItemPreview() {
    AttendanceTheme(dynamicColor = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            StudentListItem(previewRow(), segmentedShape(0, 2), "+92 300 1234567", {}, {}, {})
            StudentListItem(previewRow(LocalDate(2026, 8, 3)), segmentedShape(1, 2), "+92 300 1234567", {}, {}, {})
        }
    }
}

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, fontScale = 1.6f)
@Composable
private fun StudentListItemDarkLargeFontPreview() {
    AttendanceTheme(dynamicColor = false, darkTheme = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            StudentListItem(previewRow(), segmentedShape(0, 2), "+92 300 1234567", {}, {}, {})
            StudentListItem(previewRow(LocalDate(2026, 8, 3)), segmentedShape(1, 2), "", {}, {}, {})
        }
    }
}

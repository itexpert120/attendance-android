package com.itexpert120.attendanceManager.ui.features.registers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.itexpert120.attendanceManager.ui.components.SheetContentMotion
import com.itexpert120.attendanceManager.ui.components.SheetMotion
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.data.repo.AbsenceMessageRecipient
import com.itexpert120.attendanceManager.data.repo.absenceMessageRecipients
import com.itexpert120.attendanceManager.data.repo.absenceSessionLabel
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.ui.components.BadgeTone
import com.itexpert120.attendanceManager.ui.components.StatusBadge
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.coroutines.launch

/** What a contact button asked for; the sheet turns the result into a tick or a snackbar. */
internal enum class ReportsContactChannel { SMS, WHATSAPP, CALL }

/**
 * The "Message absentees" sheet: one card per absent student's parent with SMS, WhatsApp and Call
 * buttons, the prefilled message, and a "Messaged" tick. Opening SMS or WhatsApp ticks the parent
 * (the teacher can untick it); nothing is ever sent without the teacher confirming in the other app.
 * A snackbar inside the sheet says so when no app on the phone can handle the request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportsMessageSheet(
    periodLabel: String,
    recipients: List<AbsenceMessageRecipient>,
    messagedIds: Set<Long>,
    onMessagedChange: (enrollmentId: Long, messaged: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Shows a stored (E.164) phone number the way people read it. */
    phoneDisplay: (String) -> String = { it },
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val messagedCount = recipients.count { it.student.row.enrollment.id in messagedIds }

    val onContact: (AbsenceMessageRecipient, ReportsContactChannel) -> Unit = { recipient, channel ->
        val phone = recipient.student.row.student.phone
        val opened = when (channel) {
            ReportsContactChannel.SMS -> ParentContact.sms(context, phone, recipient.message)
            ReportsContactChannel.WHATSAPP -> ParentContact.whatsapp(context, phone, recipient.message)
            ReportsContactChannel.CALL -> ParentContact.dial(context, phone)
        }
        if (opened) {
            if (channel != ReportsContactChannel.CALL) {
                onMessagedChange(recipient.student.row.enrollment.id, true)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            scope.launch { snackbar.showSnackbar(noAppMessage(channel)) }
        }
    }

    SheetMotion {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            SheetContentMotion {
            Box(Modifier.fillMaxWidth().imePadding()) {
                Column(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                        Text("Message absentees", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "$periodLabel · ${messagedProgressLabel(messagedCount, recipients.size)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            "Messages open prefilled in SMS or WhatsApp. Nothing is sent automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(recipients, key = { it.student.row.enrollment.id }) { recipient ->
                            val id = recipient.student.row.enrollment.id
                            val messaged = id in messagedIds
                            ReportsRecipientCard(
                                recipient = recipient,
                                messaged = messaged,
                                onMessagedChange = { value ->
                                    haptics.performHapticFeedback(if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                    onMessagedChange(id, value)
                                },
                                onContact = { channel -> onContact(recipient, channel) },
                                phoneDisplay = phoneDisplay,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            onClick = {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    if (!sheetState.isVisible) onDismiss()
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Done") }
                    }
                }
                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
                )
            }
            }
        }
    }
}

private fun noAppMessage(channel: ReportsContactChannel): String = when (channel) {
    ReportsContactChannel.SMS -> "No messaging app on this phone can send an SMS."
    ReportsContactChannel.WHATSAPP -> "WhatsApp is not installed on this phone."
    ReportsContactChannel.CALL -> "No app on this phone can place calls."
}

@Composable
internal fun ReportsRecipientCard(
    recipient: AbsenceMessageRecipient,
    messaged: Boolean,
    onMessagedChange: (Boolean) -> Unit,
    onContact: (ReportsContactChannel) -> Unit,
    modifier: Modifier = Modifier,
    phoneDisplay: (String) -> String = { it },
) {
    val student = recipient.student.row.student
    val phone = student.phone
    val usable = hasUsablePhone(phone)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StudentAvatar(student.photoFile, recipient.student.row.enrollment.rollNumber)
                Column(Modifier.weight(1f)) {
                    Text(
                        student.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Absent ${absenceSessionLabel(recipient.student.absentSessions)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (usable) {
                        Text(phoneDisplay(phone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (usable) {
                    FilterChip(
                        selected = messaged,
                        onClick = { onMessagedChange(!messaged) },
                        label = { Text(if (messaged) "Messaged" else "Not yet") },
                        leadingIcon = if (messaged) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (messaged) {
                                "${student.name}'s parent messaged. Tap to clear."
                            } else {
                                "${student.name}'s parent not messaged yet. Tap to mark as messaged."
                            }
                        },
                    )
                } else {
                    StatusBadge("No phone", tone = BadgeTone.WARNING)
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    recipient.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp),
                )
            }
            if (usable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContactButton("SMS", Icons.Rounded.Sms, "Send SMS to ${student.name}'s parent") {
                        onContact(ReportsContactChannel.SMS)
                    }
                    ContactButton("WhatsApp", Icons.AutoMirrored.Rounded.Chat, "WhatsApp ${student.name}'s parent") {
                        onContact(ReportsContactChannel.WHATSAPP)
                    }
                    OutlinedButton(
                        onClick = { onContact(ReportsContactChannel.CALL) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Call ${student.name}'s parent" },
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Call", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Text(
                    "Add a phone number for ${student.name} on the Students tab to message this parent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ContactButton(
    label: String,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun ReportsRecipientCardPreview() {
    val base = SamplePreviewData.register()
    val rows = base.monthRows.map { row ->
        if (row.enrollment.id == 2L) row.copy(student = row.student.copy(phone = "+923001234567")) else row
    }
    val data = base.copy(
        monthRows = rows,
        marks = base.marks + listOf(
            AttendanceMark(1, 2, 6, Session.FIRST, AttendanceStatus.ABSENT),
            AttendanceMark(1, 2, 6, Session.SECOND, AttendanceStatus.ABSENT),
            AttendanceMark(1, 3, 6, Session.FIRST, AttendanceStatus.ABSENT),
        ),
    )
    val recipients = absenceMessageRecipients(data.report(ReportPeriodType.DAILY, 6), data.classGroup, data.settings)
    AttendanceTheme(dynamicColor = false) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recipients.forEachIndexed { index, recipient ->
                ReportsRecipientCard(recipient, messaged = index == 0, onMessagedChange = {}, onContact = {})
            }
        }
    }
}

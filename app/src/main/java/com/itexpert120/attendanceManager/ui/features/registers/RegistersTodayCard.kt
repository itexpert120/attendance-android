package com.itexpert120.attendanceManager.ui.features.registers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.domain.isWeekend
import com.itexpert120.attendanceManager.domain.monthName
import com.itexpert120.attendanceManager.domain.weekdayName
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.StoredPhoto
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/** The school's logo, or a school glyph on the primary colour when none was chosen. */
@Composable
internal fun RegistersSchoolMark(logoFile: String?, schoolName: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val glyph: @Composable () -> Unit = {
        Icon(
            Icons.Rounded.School,
            contentDescription = null,
            tint = colors.onPrimary,
            modifier = Modifier.padding(10.dp).size(24.dp),
        )
    }
    StoredPhoto(
        fileName = logoFile,
        contentDescription = schoolName?.let { "$it logo" },
        contentScale = ContentScale.Fit,
        maxEdgePx = 160,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (logoFile != null) colors.surfaceContainerLowest else colors.primary),
        placeholder = glyph,
    )
}

/**
 * The home screen's answer to "what do I do today?": the date, then one compact row per class. A class with
 * this month's register shows today's progress and a small Take / Continue button (the whole row opens it);
 * a class without one gets a short line and a small Create button. Nothing stretches across the card.
 */
@Composable
internal fun RegistersTodayCard(
    today: LocalDate,
    schoolName: String?,
    logoFile: String?,
    entries: List<TodayEntry>,
    busy: Boolean,
    onTakeAttendance: (registerId: Long) -> Unit,
    onCreateThisMonth: (classGroupId: Long) -> Unit,
    onOpenStudents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val weekend = isWeekend(today)
    AppCard(
        modifier = modifier,
        containerColor = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Row(
                Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RegistersSchoolMark(logoFile, schoolName)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (weekend) "Today · Weekend" else "Today",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Text(
                        "${weekdayName(today)}, ${monthName(today.month.number)} ${today.day}",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty()) {
                TodayRow(
                    title = "No classes yet",
                    detail = "Add a class to start taking attendance.",
                    onClick = onOpenStudents,
                ) {
                    FilledTonalButton(onClick = onOpenStudents, contentPadding = CompactPadding) { Text("Add class") }
                }
            }

            entries.forEach { entry ->
                val register = entry.register
                if (register != null) {
                    val action = when {
                        entry.onRoll > 0 && entry.marked >= entry.onRoll -> null
                        entry.marked > 0 -> "Continue"
                        else -> "Take"
                    }
                    TodayRow(
                        title = classLabel(entry.classGroup),
                        detail = todayProgressLabel(entry, weekend),
                        onClick = { onTakeAttendance(register.id) },
                        onClickLabel = "Open the register of ${classLabel(entry.classGroup)}",
                    ) {
                        if (action == null) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = "Done",
                                tint = colors.primary,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        } else if (!weekend) {
                            Button(onClick = { onTakeAttendance(register.id) }, contentPadding = CompactPadding) {
                                Text(action)
                            }
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                } else {
                    TodayRow(
                        title = classLabel(entry.classGroup),
                        detail = "No ${monthName(today.month.number)} register yet",
                        muted = true,
                    ) {
                        TextButton(
                            onClick = { onCreateThisMonth(entry.classGroup.id) },
                            enabled = !busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.onPrimaryContainer),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Create", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

private val CompactPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)

/** "18 of 23 marked", "Not marked yet", "All 23 marked". */
internal fun todayProgressLabel(entry: TodayEntry, weekend: Boolean): String = when {
    entry.onRoll == 0 -> "No students on the roll"
    entry.marked >= entry.onRoll -> "All ${entry.onRoll} marked"
    entry.marked > 0 -> "${entry.marked} of ${entry.onRoll} marked"
    weekend -> "${studentCountLabel(entry.onRoll)} · no school today"
    else -> "Not marked yet · ${studentCountLabel(entry.onRoll)}"
}

/** One 64dp row of the Today card: title and detail on the left, a compact action on the right. */
@Composable
private fun TodayRow(
    title: String,
    detail: String,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    muted: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val clickable = if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier
    Row(
        clickable.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (muted) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

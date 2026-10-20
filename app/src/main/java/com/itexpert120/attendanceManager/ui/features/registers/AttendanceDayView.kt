package com.itexpert120.attendanceManager.ui.features.registers

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.domain.longDateLabel
import com.itexpert120.attendanceManager.ui.components.StoredPhoto
import com.itexpert120.attendanceManager.ui.theme.AttendanceColors
import kotlinx.datetime.LocalDate
import com.itexpert120.attendanceManager.ui.theme.StatusColors

/** Status colours that follow the app's light/dark theme rather than only the system setting. */
@Composable
internal fun attendanceStatusColors(status: AttendanceStatus): StatusColors =
    AttendanceColors.of(status, dark = MaterialTheme.colorScheme.surface.luminanceBelowHalf())

private fun Color.luminanceBelowHalf(): Boolean = luminance() < 0.5f

private val RowShape = RoundedCornerShape(16.dp)
private val ButtonShape = RoundedCornerShape(14.dp)

// region Date strip

/** The horizontally scrolling strip of days; every day can be opened, locked ones simply read as such. */
@Composable
internal fun AttendanceDateStrip(
    strip: List<StripDay>,
    selectedDay: Int,
    onSelect: (Int) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(strip, key = { it.day }) { day ->
            DateChip(day, selected = day.day == selectedDay, onClick = { onSelect(day.day) })
        }
    }
}

@Composable
private fun DateChip(day: StripDay, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val container = when {
        selected -> colors.primary
        day.kind != DayKind.WORKING -> colors.surfaceContainerHigh
        else -> colors.surfaceContainerLowest
    }
    val content = when {
        selected -> colors.onPrimary
        day.kind != DayKind.WORKING -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    // Weekends and holidays are dimmed; school days outside the edit window are only slightly dimmed.
    val fade = when {
        selected -> 1f
        day.kind != DayKind.WORKING -> 0.55f
        day.lock != DayLock.OPEN -> 0.75f
        else -> 1f
    }
    val description = buildString {
        append(day.weekday).append(' ').append(day.day)
        if (day.isToday) append(", today")
        when (day.kind) {
            DayKind.WEEKEND -> append(", weekend")
            DayKind.HOLIDAY -> append(", holiday")
            DayKind.WORKING -> if (day.lock != DayLock.OPEN) append(", locked")
        }
    }
    Column(
        modifier = Modifier
            .widthIn(min = 52.dp)
            .heightIn(min = 72.dp)
            .background(container, shape)
            .then(
                if (day.isToday && !selected) Modifier.border(2.dp, colors.primary, shape) else Modifier,
            )
            .clip(shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(day.weekday, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = fade))
        Text(
            day.day.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = content.copy(alpha = fade),
        )
        // A tiny marker row keeps every chip the same height and gives a non-colour cue for the day type.
        val marker = when {
            day.kind == DayKind.HOLIDAY -> "Hol"
            day.isToday -> "Today"
            else -> " "
        }
        Text(marker, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = fade), maxLines = 1)
    }
}

// endregion

// region Day header and notice

@Composable
internal fun AttendanceDayHeader(
    info: DayInfo,
    isToday: Boolean,
    onRemoveHoliday: () -> Unit,
    onJumpToToday: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                longDateLabel(info.date),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isToday) {
                com.itexpert120.attendanceManager.ui.components.StatusBadge(
                    "Today",
                    tone = com.itexpert120.attendanceManager.ui.components.BadgeTone.SUCCESS,
                )
            }
            if (onJumpToToday != null) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onJumpToToday) { Text("Jump to today") }
            }
        }
        val explanation = dayExplanation(info)
        if (explanation != null) {
            DayNotice(info, explanation, onRemoveHoliday)
        }
    }
}

@Composable
private fun DayNotice(info: DayInfo, text: String, onRemoveHoliday: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val icon = when {
        info.kind == DayKind.HOLIDAY -> Icons.Rounded.BeachAccess
        info.kind == DayKind.WEEKEND -> Icons.Rounded.Event
        else -> Icons.Rounded.Lock
    }
    Surface(shape = RoundedCornerShape(16.dp), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (info.kind == DayKind.HOLIDAY && info.canToggleHoliday) {
                TextButton(onClick = onRemoveHoliday, modifier = Modifier.align(Alignment.End)) {
                    Text("Remove holiday")
                }
            }
        }
    }
}

// endregion

// region Student rows

/** Everything a row can ask the screen to do. One instance per selected day keeps rows from recomposing. */
internal class AttendanceRowActions(
    val onSetMark: (enrollmentId: Long, session: Session, status: AttendanceStatus?) -> Unit,
    val onSwipeAbsent: (enrollmentId: Long, name: String) -> Unit,
    val onBlocked: (message: String) -> Unit,
)

@Composable
internal fun StudentAttendanceRow(
    enrollmentId: Long,
    roll: String,
    name: String,
    photoFile: String?,
    first: AttendanceStatus?,
    second: AttendanceStatus?,
    editable: Boolean,
    blockedMessage: String,
    /** "Roll 4 · Month 12 · Total 40", or "Enrolled from ..." for a student not yet on the roll. */
    subtitle: String?,
    actions: AttendanceRowActions,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Surface(shape = RowShape, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudentAvatar(photoFile, roll)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                TimingButton(
                    session = Session.FIRST,
                    status = first,
                    editable = editable,
                    studentName = name,
                    blockedMessage = blockedMessage,
                    onCycle = { actions.onSetMark(enrollmentId, Session.FIRST, nextAttendanceStatus(first)) },
                    onPick = { actions.onSetMark(enrollmentId, Session.FIRST, it) },
                    onBlocked = actions.onBlocked,
                )
                Spacer(Modifier.width(8.dp))
                TimingButton(
                    session = Session.SECOND,
                    status = second,
                    editable = editable,
                    studentName = name,
                    blockedMessage = blockedMessage,
                    onCycle = { actions.onSetMark(enrollmentId, Session.SECOND, nextAttendanceStatus(second)) },
                    onPick = { actions.onSetMark(enrollmentId, Session.SECOND, it) },
                    onBlocked = actions.onBlocked,
                )
            }
        }
    }
    if (editable) {
        SwipeAbsentBox(
            onAbsent = { actions.onSwipeAbsent(enrollmentId, name) },
            modifier = modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Mark absent for both timings") {
                        actions.onSwipeAbsent(enrollmentId, name)
                        true
                    },
                    CustomAccessibilityAction("Mark present for both timings") {
                        actions.onSetMark(enrollmentId, Session.FIRST, AttendanceStatus.PRESENT)
                        actions.onSetMark(enrollmentId, Session.SECOND, AttendanceStatus.PRESENT)
                        true
                    },
                )
            },
            content = content,
        )
    } else {
        Box(modifier) { content() }
    }
}

@Composable
internal fun AttendanceRollBadge(roll: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            roll,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 40.dp).heightIn(min = 40.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

/** The student's photo in a circle when there is one, otherwise (and while it loads) the roll badge. */
@Composable
internal fun StudentAvatar(photoFile: String?, roll: String) {
    if (photoFile.isNullOrBlank()) {
        AttendanceRollBadge(roll)
    } else {
        StoredPhoto(
            fileName = photoFile,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(CircleShape),
            maxEdgePx = 160,
            placeholder = { AttendanceRollBadge(roll) },
        )
    }
}

/** Swipe either way to mark absent; the row always springs back and the new state arrives from the data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeAbsentBox(onAbsent: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onAbsent)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target != SwipeToDismissBoxValue.Settled) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                latest()
            }
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val swiping = state.dismissDirection != SwipeToDismissBoxValue.Settled
            val colors = MaterialTheme.colorScheme
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RowShape)
                    .background(if (swiping) colors.errorContainer else Color.Transparent)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
            ) {
                if (swiping) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.PersonOff, contentDescription = null, tint = colors.onErrorContainer)
                        Text("Absent", style = MaterialTheme.typography.labelLarge, color = colors.onErrorContainer)
                    }
                }
            }
        },
        content = { content() },
    )
}

/**
 * One timing of one student. Tap cycles none -> P -> A -> L -> none; a long press opens a compact picker so
 * any status is one step away. Locked or off-roll cells explain themselves when tapped.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimingButton(
    session: Session,
    status: AttendanceStatus?,
    editable: Boolean,
    studentName: String,
    blockedMessage: String,
    onCycle: () -> Unit,
    onPick: (AttendanceStatus?) -> Unit,
    onBlocked: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val colors = status?.let { attendanceStatusColors(it) }
    val container = colors?.container ?: scheme.surfaceContainerHighest
    val content = colors?.content ?: scheme.onSurfaceVariant
    val label = if (session == Session.FIRST) "1st" else "2nd"
    val next = nextAttendanceStatus(status)
    val description = "$studentName, ${attendanceSessionWord(session)}: ${attendanceStatusWord(status)}"

    Box {
        Column(
            modifier = Modifier
                .widthIn(min = 56.dp)
                .heightIn(min = 52.dp)
                .background(container, ButtonShape)
                .then(if (status == null) Modifier.border(1.dp, scheme.outlineVariant, ButtonShape) else Modifier)
                .clip(ButtonShape)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (editable) "Change to ${attendanceStatusWord(next)}" else "Why is this locked",
                    onLongClickLabel = if (editable) "Choose status" else null,
                    onClick = {
                        if (editable) {
                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onCycle()
                        } else {
                            onBlocked(blockedMessage)
                        }
                    },
                    onLongClick = if (editable) {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    } else {
                        null
                    },
                )
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.8f))
            Text(
                attendanceStatusLetter(status),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = content,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val options = listOf<AttendanceStatus?>(
                AttendanceStatus.PRESENT,
                AttendanceStatus.ABSENT,
                AttendanceStatus.LEAVE,
                null,
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == null) "Not marked" else attendanceStatusWord(option).replaceFirstChar { it.uppercase() }) },
                    leadingIcon = {
                        if (option == status) Icon(Icons.Rounded.Check, contentDescription = "Selected")
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        menuOpen = false
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onPick(option)
                    },
                )
            }
        }
    }
}

// endregion

// region Bottom bar

@Composable
private fun CountPill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountsLine(label: String, session: Session, counts: SessionCounts) {
    // Each mark is one timing, half a day, so the totals read in days as in the PWA's "Present totals".
    val present = formatSessionsAsDays(counts.present)
    val absentDays = formatSessionsAsDays(counts.absent)
    val leaveDays = formatSessionsAsDays(counts.leave)
    val description = "${attendanceSessionWord(session).replaceFirstChar { it.uppercase() }}: $present days present, " +
        "$absentDays days absent, $leaveDays days leave, ${counts.unmarked} students not marked"
    FlowRow(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 32.dp),
        )
        val presentColors = attendanceStatusColors(AttendanceStatus.PRESENT)
        val absentColors = attendanceStatusColors(AttendanceStatus.ABSENT)
        val leaveColors = attendanceStatusColors(AttendanceStatus.LEAVE)
        CountPill("P $present", presentColors.container, presentColors.content)
        CountPill("A $absentDays", absentColors.container, absentColors.content)
        CountPill("L $leaveDays", leaveColors.container, leaveColors.content)
        if (counts.unmarked > 0) {
            CountPill(
                "${counts.unmarked} left",
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Live counts for the day plus the bulk actions, pinned to the bottom within thumb reach. Without students on
 * the roll that day ([counts] null) only "Declare holiday" is offered, so a holiday never depends on the roster.
 */
@Composable
internal fun AttendanceBottomBar(
    counts: Map<Session, SessionCounts>?,
    canDeclareHoliday: Boolean,
    onBulk: (List<Session>, AttendanceStatus) -> Unit,
    onFillBlanks: () -> Unit,
    onClear: (List<Session>) -> Unit,
    onDeclareHoliday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            // The bar's colour runs behind the navigation bar; only its content is lifted above it.
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            counts?.get(Session.FIRST)?.let { CountsLine("1st", Session.FIRST, it) }
            counts?.get(Session.SECOND)?.let { CountsLine("2nd", Session.SECOND, it) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (counts == null) {
                    if (canDeclareHoliday) {
                        TextButton(onClick = onDeclareHoliday) {
                            Icon(Icons.Rounded.BeachAccess, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Declare holiday")
                        }
                    }
                    return@Row
                }
                Box {
                    FilledTonalIconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        val close = { menuOpen = false }
                        val bulkStatuses = listOf(
                            AttendanceStatus.PRESENT to "All present",
                            AttendanceStatus.ABSENT to "All absent",
                            AttendanceStatus.LEAVE to "All leave",
                        )
                        bulkStatuses.forEachIndexed { index, (status, label) ->
                            if (index > 0) HorizontalDivider()
                            BarMenuItem("$label: 1st timing", close) { onBulk(listOf(Session.FIRST), status) }
                            BarMenuItem("$label: 2nd timing", close) { onBulk(listOf(Session.SECOND), status) }
                        }
                        HorizontalDivider()
                        BarMenuItem("Set blank marks to present", close, onFillBlanks)
                        HorizontalDivider()
                        BarMenuItem("Clear 1st timing", close) { onClear(listOf(Session.FIRST)) }
                        BarMenuItem("Clear 2nd timing", close) { onClear(listOf(Session.SECOND)) }
                        BarMenuItem("Clear both timings", close) { onClear(Session.entries) }
                        if (canDeclareHoliday) {
                            HorizontalDivider()
                            BarMenuItem("Declare holiday", close, onDeclareHoliday)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onBulk(Session.entries, AttendanceStatus.PRESENT)
                    },
                ) {
                    Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("All present")
                }
            }
        }
    }
}

@Composable
private fun BarMenuItem(label: String, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            close()
            action()
        },
    )
}

// endregion

@Composable
internal fun AttendanceNoStudents(onManageStudents: (() -> Unit)?, modifier: Modifier = Modifier) {
    com.itexpert120.attendanceManager.ui.components.EmptyState(
        icon = Icons.Rounded.PersonOff,
        title = "No students on the roll",
        text = "Add students to this class, then mark attendance here.",
        modifier = modifier,
        action = onManageStudents?.let { open ->
            {
                FilledTonalButton(onClick = open, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Manage students")
                }
            }
        },
    )
}

/**
 * The PWA's warning when a markable day has nobody on the roll yet because every admission date is later,
 * with a shortcut to the students to correct those dates.
 */
@Composable
internal fun AttendanceNobodyOnRollBanner(date: LocalDate, onEditStudents: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "No students were enrolled on ${longDateLabel(date)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "The date is editable, but each student\u2019s admission date is later. Update the admission " +
                            "date to enable these timings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (onEditStudents != null) {
                TextButton(onClick = onEditStudents, modifier = Modifier.align(Alignment.End)) {
                    Text("Edit student dates")
                }
            }
        }
    }
}

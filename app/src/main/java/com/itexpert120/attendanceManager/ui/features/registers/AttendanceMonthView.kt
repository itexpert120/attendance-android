package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.ui.components.navigationBarBottom
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.mondayOffset
import com.itexpert120.attendanceManager.domain.firstDate
import com.itexpert120.attendanceManager.domain.monthLabel
import com.itexpert120.attendanceManager.domain.shortWeekdayName
import com.itexpert120.attendanceManager.domain.dateOf
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.ui.components.AppSheet

private val CardShape = RoundedCornerShape(16.dp)

/** Colours of the heat strip, resolved once per screen. */
internal class HeatPalette(
    val present: Color,
    val absent: Color,
    val leave: Color,
    val blank: Color,
    val noSchool: Color,
)

@Composable
internal fun rememberHeatPalette(): HeatPalette {
    val present = attendanceStatusColors(AttendanceStatus.PRESENT).content
    val absent = attendanceStatusColors(AttendanceStatus.ABSENT).content
    val leave = attendanceStatusColors(AttendanceStatus.LEAVE).content
    val blank = MaterialTheme.colorScheme.outlineVariant
    val noSchool = MaterialTheme.colorScheme.surfaceContainerHigh
    return remember(present, absent, leave, blank, noSchool) { HeatPalette(present, absent, leave, blank, noSchool) }
}

private fun HeatPalette.of(status: AttendanceStatus?): Color = when (status) {
    AttendanceStatus.PRESENT -> present
    AttendanceStatus.ABSENT -> absent
    AttendanceStatus.LEAVE -> leave
    null -> blank
}

/** The day-by-day strip: one bar per day, coloured by that day's mark. */
@Composable
internal fun HeatStrip(cells: List<HeatCell>, palette: HeatPalette, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        if (cells.isEmpty()) return@Canvas
        val gap = 1.5.dp.toPx()
        val cellWidth = (size.width - gap * (cells.size - 1)) / cells.size
        val corner = CornerRadius(1.5.dp.toPx())
        cells.forEachIndexed { index, cell ->
            val x = index * (cellWidth + gap)
            when (cell.kind) {
                HeatKind.SCHOOL ->
                    drawRoundRect(palette.of(cell.status), Offset(x, 0f), Size(cellWidth, size.height), corner)
                HeatKind.NO_SCHOOL, HeatKind.HOLIDAY ->
                    drawRoundRect(palette.noSchool, Offset(x, size.height / 2 - 2.dp.toPx()), Size(cellWidth, 4.dp.toPx()), corner)
                HeatKind.OFF_ROLL -> Unit
            }
        }
    }
}

// region Month overview (phone)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeatLegend(modifier: Modifier = Modifier) {
    val palette = rememberHeatPalette()
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendItem("Present", palette.present)
        LegendItem("Absent", palette.absent)
        LegendItem("Leave", palette.leave)
        LegendItem("Not marked", palette.blank)
        LegendItem("No school", palette.noSchool)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthSummaryCard(data: RegisterData) {
    val month = data.visiblePresentTotal
    val forward = data.visibleBroughtForwardTotal
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryFigure("This month", formatDays(month))
                SummaryFigure("Brought forward", formatDays(forward))
                SummaryFigure("Total", formatDays(month + forward))
            }
            Text(
                "Present days over ${data.workingDays} school days in ${monthLabel(data.register.month, data.register.year)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HeatLegend()
        }
    }
}

@Composable
private fun SummaryFigure(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AttendanceMonthPane(
    data: RegisterData,
    wide: Boolean,
    onOpenStudent: (Long) -> Unit,
    onOpenStudents: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (data.attendanceRows.isEmpty()) {
        AttendanceNoStudents(onOpenStudents, modifier)
        return
    }
    if (wide) {
        AttendanceMonthGrid(data, onOpenStudent, modifier)
        return
    }
    val palette = rememberHeatPalette()
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp + navigationBarBottom()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") { MonthSummaryCard(data) }
        items(data.attendanceRows, key = { it.enrollment.id }) { row ->
            MonthStudentRow(data, row, palette, onClick = { onOpenStudent(row.enrollment.id) })
        }
    }
}

@Composable
private fun MonthStudentRow(data: RegisterData, row: EnrollmentRow, palette: HeatPalette, onClick: () -> Unit) {
    val id = row.enrollment.id
    val cells = remember(data, id) { heatCells(data, row) }
    val month = data.presentSessionsOf(id)
    val forward = data.broughtForwardOf(id)
    val totals = remember(cells) { heatTotals(cells) }
    val description = "${row.student.name}, roll ${row.enrollment.rollNumber}. " +
        "${formatDays(month)} days present this month, ${formatDays(forward)} brought forward, " +
        "${formatDays(month + forward)} total. " +
        "Absent ${totals[AttendanceStatus.ABSENT] ?: 0} days, leave ${totals[AttendanceStatus.LEAVE] ?: 0} days. " +
        "Tap for day by day details."
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().clip(CardShape).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StudentAvatar(row.student.photoFile, row.enrollment.rollNumber)
                Spacer(Modifier.width(12.dp))
                Text(
                    row.student.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatDays(month + forward),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "total days",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HeatStrip(cells, palette)
            Text(
                "This month ${formatDays(month)} · Brought forward ${formatDays(forward)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion

// region Month grid (wide windows)

private val GridCellWidth = 30.dp
private val GridNameWidth = 200.dp
private val GridTotalWidth = 56.dp

/** The paper-register-like grid, offered only on tablets and landscape: names stay, the days scroll. */
@Composable
internal fun AttendanceMonthGrid(data: RegisterData, onOpenStudent: (Long) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val palette = rememberHeatPalette()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Student",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(GridNameWidth).padding(start = 12.dp),
            )
            Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                data.days.forEach { day ->
                    val date = data.register.dateOf(day)
                    Column(Modifier.width(GridCellWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            shortWeekdayName(date).take(1),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(day.toString(), style = MaterialTheme.typography.labelMedium)
                    }
                }
                listOf("Month", "B/F", "Total").forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(GridTotalWidth),
                    )
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(data.attendanceRows, key = { it.enrollment.id }) { row ->
                val id = row.enrollment.id
                val cells = remember(data, id) { heatCells(data, row) }
                val month = data.presentSessionsOf(id)
                val forward = data.broughtForwardOf(id)
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Row(
                        Modifier.clickable { onOpenStudent(id) }.heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.width(GridNameWidth).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                row.enrollment.rollNumber,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 24.dp),
                            )
                            Text(row.student.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                            cells.forEach { GridCell(it, palette) }
                            listOf(month, forward, month + forward).forEach {
                                Text(
                                    formatDays(it),
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(GridTotalWidth),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(cell: HeatCell, palette: HeatPalette) {
    Column(Modifier.width(GridCellWidth).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (cell.kind) {
            HeatKind.SCHOOL -> LetterBox(cell.status, palette, Modifier.heightIn(min = 36.dp))
            HeatKind.OFF_ROLL -> Spacer(Modifier.heightIn(min = 36.dp))
            HeatKind.NO_SCHOOL, HeatKind.HOLIDAY ->
                Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).background(palette.noSchool, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun LetterBox(status: AttendanceStatus?, palette: HeatPalette, modifier: Modifier = Modifier) {
    val colors = status?.let { attendanceStatusColors(it) }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 17.dp)
            .background(colors?.container ?: Color.Transparent, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            attendanceStatusLetter(status),
            style = MaterialTheme.typography.labelSmall,
            color = colors?.content ?: palette.blank,
        )
    }
}

// endregion

// region Student month sheet

/**
 * Day-by-day calendar of one student, with the P / A / L letter for each day. [onOpenProfile] adds a
 * "Profile" action that opens the student's (read-only) profile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttendanceStudentMonthSheet(
    data: RegisterData,
    row: EnrollmentRow,
    onDismiss: () -> Unit,
    onOpenProfile: (() -> Unit)? = null,
) {
    val cells = remember(data, row.enrollment.id) { heatCells(data, row) }
    val totals = remember(cells) { heatTotals(cells) }
    val offset = mondayOffset(data.register.firstDate)
    val weeks = remember(cells, offset) {
        val padded = List<HeatCell?>(offset) { null } + cells
        padded.chunked(7).map { week -> week + List<HeatCell?>(7 - week.size) { null } }
    }
    val palette = rememberHeatPalette()
    val id = row.enrollment.id
    AppSheet(
        title = row.student.name,
        description = "Roll ${row.enrollment.rollNumber} · ${monthLabel(data.register.month, data.register.year)}",
        onDismiss = onDismiss,
        footer = {
            if (onOpenProfile != null) TextButton(onClick = onOpenProfile) { Text("Profile") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onDismiss) { Text("Done") }
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SummaryFigure("This month", formatDays(data.presentSessionsOf(id)))
            SummaryFigure("Brought forward", formatDays(data.broughtForwardOf(id)))
            SummaryFigure("Total", formatDays(data.presentSessionsOf(id) + data.broughtForwardOf(id)))
        }
        Text(
            "Days: present ${totals[AttendanceStatus.PRESENT] ?: 0}, absent ${totals[AttendanceStatus.ABSENT] ?: 0}, " +
                "leave ${totals[AttendanceStatus.LEAVE] ?: 0}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            weeks.forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    week.forEach { cell ->
                        Box(Modifier.weight(1f)) { if (cell != null) CalendarCell(cell, palette) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarCell(cell: HeatCell, palette: HeatPalette) {
    val description = when (cell.kind) {
        HeatKind.SCHOOL ->
            "Day ${cell.day}: ${attendanceStatusWord(cell.status)}"
        HeatKind.HOLIDAY -> "Day ${cell.day}: holiday"
        HeatKind.NO_SCHOOL -> "Day ${cell.day}: weekend"
        HeatKind.OFF_ROLL -> "Day ${cell.day}: not on the roll"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (cell.kind == HeatKind.SCHOOL) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            cell.day.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = if (cell.kind == HeatKind.SCHOOL) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (cell.kind == HeatKind.SCHOOL) {
            Box(Modifier.padding(horizontal = 4.dp)) { LetterBox(cell.status, palette) }
        } else {
            Text(
                if (cell.kind == HeatKind.HOLIDAY) "Hol" else "–",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion

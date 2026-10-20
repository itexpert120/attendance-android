package com.itexpert120.attendanceManager.ui.features.registers

import androidx.compose.foundation.layout.navigationBarsPadding
import com.itexpert120.attendanceManager.ui.components.navigationBarBottom
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.export.AttendanceReportPdf
import com.itexpert120.attendanceManager.ui.components.PdfExportState
import com.itexpert120.attendanceManager.ui.components.PdfRequest
import com.itexpert120.attendanceManager.data.repo.absenceMessageRecipients

import com.itexpert120.attendanceManager.domain.AttendanceReport
import com.itexpert120.attendanceManager.domain.AttendanceReportStudent
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.dateOf
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.SegmentedChoice
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * The Reports tab of a register: a Daily / Weekly / Monthly switch, a period picker (chevrons and the
 * Material date picker), the attendance rate with present / absent / leave / blank counts, the day's
 * absentees and every student's counts. On daily reports a FAB opens the "Message parents" sheet
 * (ReportsMessageSheet.kt), and an Export button offers the report or the absentee list as a PDF.
 * Spec: the PWA's ReportsPanel.svelte.
 */

/** Daily, weekly and monthly attendance reports for one register, with messaging for daily absentees. */
@Composable
fun ReportsSection(data: RegisterData, modifier: Modifier = Modifier, pdfExport: PdfExportState? = null) {
    val container = LocalAppContainer.current
    val registerId = data.register.id
    val viewModel: ReportsViewModel = viewModel(key = "reports-$registerId") { ReportsViewModel() }
    val today by viewModel.today.collectAsState()
    val sent by viewModel.sent.collectAsState()
    var type by rememberSaveable(registerId) { mutableStateOf(ReportPeriodType.DAILY) }
    var selectedDay by rememberSaveable(registerId) { mutableIntStateOf(defaultReportDay(data.register, today)) }
    var showMessages by rememberSaveable(registerId) { mutableStateOf(false) }

    val day = selectedDay.coerceIn(data.days)
    val report = remember(data, type, day) { data.report(type, day) }
    val sentPeriod = reportSentPeriod(report)
    val sentIds = remember(sent, sentPeriod) { sent.filter { it.period == sentPeriod }.mapTo(HashSet()) { it.enrollmentId } }

    ReportsContent(
        data = data,
        report = report,
        selectedDay = day,
        today = today,
        messagedIds = sentIds,
        onTypeChange = { type = it },
        onDayChange = { selectedDay = it },
        onMessageParents = { showMessages = true },
        phoneDisplay = container.phoneNormalizer::display,
        modifier = modifier,
        // The shown report is an immutable snapshot, so the PDF matches the screen even if marks change meanwhile.
        onExportReport = pdfExport?.let { state ->
            {
                state.open(
                    PdfRequest(AttendanceReportPdf.reportTitle(report), AttendanceReportPdf.reportFileName(data, report)) {
                        AttendanceReportPdf.buildReport(data, report, container.photoStore)
                    },
                )
            }
        },
        onExportAbsentees = pdfExport?.let { state ->
            {
                state.open(
                    PdfRequest("Absentee list", AttendanceReportPdf.absenteeFileName(data, report)) {
                        AttendanceReportPdf.buildAbsentees(data, report, container.photoStore)
                    },
                )
            }
        },
    )

    if (showMessages && report.type == ReportPeriodType.DAILY) {
        val recipients = remember(report, data.classGroup, data.settings) {
            absenceMessageRecipients(report, data.classGroup, data.settings)
        }
        ReportsMessageSheet(
            periodLabel = report.period.label,
            recipients = recipients,
            messagedIds = sentIds,
            onMessagedChange = { enrollmentId, value -> viewModel.setSent(SentKey(sentPeriod, enrollmentId), value) },
            onDismiss = { showMessages = false },
            phoneDisplay = container.phoneNormalizer::display,
        )
    }
}

/** The stateless tab, kept apart from the ViewModel so previews can render it. */
@Composable
internal fun ReportsContent(
    data: RegisterData,
    report: AttendanceReport,
    selectedDay: Int,
    today: LocalDate,
    messagedIds: Set<Long>,
    onTypeChange: (ReportPeriodType) -> Unit,
    onDayChange: (Int) -> Unit,
    onMessageParents: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shows a stored (E.164) phone number the way people read it. */
    phoneDisplay: (String) -> String = { it },
    onExportReport: (() -> Unit)? = null,
    onExportAbsentees: (() -> Unit)? = null,
) {
    val daily = report.type == ReportPeriodType.DAILY
    val absentees = remember(report) { reportAbsentees(report) }
    val students = remember(report) {
        if (daily) report.students else absenteesFirst(report.students)
    }
    val notice = reportNotice(data, report, selectedDay)
    val canMessage = daily && absentees.isNotEmpty()
    // As in the PWA, the absentee list is a daily printout, offered but disabled while nobody is absent.
    val exportAbsentees = onExportAbsentees?.takeIf { daily }
    val absenteesEnabled = absentees.isNotEmpty() && !data.isHoliday(selectedDay)
    val canExport = onExportReport != null || exportAbsentees != null

    Column(modifier.fillMaxSize()) {
        SegmentedChoice(
            options = ReportPeriodType.entries,
            selected = report.type,
            optionLabel = ::reportPeriodTypeLabel,
            onSelect = onTypeChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = (if (canMessage || canExport) 96.dp else 24.dp) + navigationBarBottom()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "period") {
                    ReportsPeriodCard(
                        register = data.register,
                        report = report,
                        selectedDay = selectedDay,
                        today = today,
                        onDayChange = onDayChange,
                    )
                }
                item(key = "summary") {
                    ReportsSummaryCard(report, notice, Modifier.padding(top = 12.dp))
                }
                if (data.monthRows.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Rounded.Groups,
                            title = "No students in this register",
                            text = "Students enrolled in this class during the month appear here.",
                        )
                    }
                } else {
                    if (daily && !data.isHoliday(selectedDay)) {
                        absenteeItems(absentees, messagedIds, onMessageParents, phoneDisplay)
                    }
                    studentItems(data, report, students, selectedDay)
                    item(key = "footer") {
                        Text(
                            REPORT_TIMING_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canExport) {
                    ReportsExportButton(
                        // Beside "Message absentees" the button shrinks to its icon so both fit a phone.
                        expanded = !canMessage,
                        onExportReport = onExportReport,
                        onExportAbsentees = exportAbsentees,
                        absenteesEnabled = absenteesEnabled,
                    )
                }
                if (canMessage) {
                    ExtendedFloatingActionButton(
                        onClick = onMessageParents,
                        icon = { Icon(Icons.Rounded.Sms, contentDescription = null) },
                        text = { Text("Message absentees") },
                    )
                }
            }
        }
    }
}

/** "Export" beside the bottom action: a menu with the shown report and the absentee-only list, as PDFs. */
@Composable
private fun ReportsExportButton(
    expanded: Boolean,
    onExportReport: (() -> Unit)?,
    onExportAbsentees: (() -> Unit)?,
    absenteesEnabled: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(
            onClick = {
                when {
                    onExportReport != null && onExportAbsentees != null -> menuOpen = true
                    onExportReport != null -> onExportReport()
                    onExportAbsentees != null && absenteesEnabled -> onExportAbsentees()
                }
            },
            expanded = expanded,
            icon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = if (expanded) null else "Export PDF") },
            text = { Text("Export") },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onExportReport != null) {
                DropdownMenuItem(
                    text = { Text("Attendance report") },
                    leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onExportReport()
                    },
                )
            }
            if (onExportAbsentees != null) {
                DropdownMenuItem(
                    text = { Text(if (absenteesEnabled) "Absentee list" else "Absentee list (nobody absent)") },
                    leadingIcon = { Icon(Icons.Rounded.PersonOff, contentDescription = null) },
                    enabled = absenteesEnabled,
                    onClick = {
                        menuOpen = false
                        onExportAbsentees()
                    },
                )
            }
        }
    }
}

/** Why the counts are empty or partial: a holiday, a period without working days, or nothing marked yet. */
private fun reportNotice(data: RegisterData, report: AttendanceReport, selectedDay: Int): String? = when {
    data.monthRows.isEmpty() -> null
    report.type == ReportPeriodType.DAILY && data.isHoliday(selectedDay) ->
        data.holidayTitle(selectedDay)?.let { "Holiday: $it. No attendance is taken." }
            ?: "Weekend. No attendance is taken."
    report.possible == 0 -> "There are no working days for these students in this period."
    report.present + report.absent + report.leave == 0 -> "Nothing has been marked in this period yet."
    else -> null
}

// region Period picker

@Composable
private fun ReportsPeriodCard(
    register: Register,
    report: AttendanceReport,
    selectedDay: Int,
    today: LocalDate,
    onDayChange: (Int) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val type = report.type
    val unit = if (type == ReportPeriodType.WEEKLY) "week" else "day"
    val previous = steppedReportDay(register, type, selectedDay, forward = false)
    val next = steppedReportDay(register, type, selectedDay, forward = true)
    val todayDay = reportDayOf(register, today)
    val todayOutside = todayDay != null && todayDay !in report.period.startDay..report.period.endDay

    AppCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (type != ReportPeriodType.MONTHLY) {
                FilledTonalIconButton(onClick = { previous?.let(onDayChange) }, enabled = previous != null) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous $unit")
                }
            }
            val pickable = type != ReportPeriodType.MONTHLY
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .then(
                        if (pickable) {
                            Modifier.clickable(onClickLabel = "Pick a date", role = Role.Button) { picking = true }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pickable) {
                        Icon(
                            Icons.Rounded.CalendarMonth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        report.period.label,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                Text(
                    reportPeriodHint(type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (type != ReportPeriodType.MONTHLY) {
                FilledTonalIconButton(onClick = { next?.let(onDayChange) }, enabled = next != null) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next $unit")
                }
            }
        }
        if (type != ReportPeriodType.MONTHLY && todayOutside && todayDay != null) {
            TextButton(
                onClick = { onDayChange(todayDay) },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            ) {
                Icon(Icons.Rounded.Today, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (type == ReportPeriodType.WEEKLY) "This week" else "Today")
            }
        }
    }

    if (picking) {
        ReportsDatePickerDialog(
            register = register,
            selectedDay = selectedDay,
            weekly = type == ReportPeriodType.WEEKLY,
            onPick = {
                picking = false
                onDayChange(it)
            },
            onDismiss = { picking = false },
        )
    }
}

/** The Material date picker, limited to the register's month (the PWA's min / max on the date input). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportsDatePickerDialog(
    register: Register,
    selectedDay: Int,
    weekly: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // The picker works in UTC milliseconds at midnight, so convert through UTC to avoid off-by-one days.
    val initialMillis = register.dateOf(selectedDay).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    val selectable = remember(register) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                reportDayOf(register, utcDate(utcTimeMillis)) != null

            override fun isSelectableYear(year: Int): Boolean = year == register.year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        initialDisplayedMonthMillis = initialMillis,
        yearRange = register.year..register.year,
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val day = state.selectedDateMillis?.let { reportDayOf(register, utcDate(it)) }
                    if (day != null) onPick(day) else onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(
            state = state,
            title = {
                Text(
                    if (weekly) "Pick any day of the week" else "Pick the report date",
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

private fun utcDate(millis: Long): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date

// endregion

// region Summary

@Composable
private fun ReportsSummaryCard(report: AttendanceReport, notice: String?, modifier: Modifier = Modifier) {
    val rate = reportRate(report)
    AppCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = if (rate == null) "Attendance: nothing marked" else "Attendance ${formatRatePrecise(rate)} percent"
                },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(formatRatePrecise(rate), style = MaterialTheme.typography.displaySmall)
                if (rate != null) {
                    Text(
                        "%",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Attendance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportsCountTile(AttendanceStatus.PRESENT, "Present", report.present, Modifier.weight(1f))
                ReportsCountTile(AttendanceStatus.ABSENT, "Absent", report.absent, Modifier.weight(1f))
                ReportsCountTile(AttendanceStatus.LEAVE, "Leave", report.leave, Modifier.weight(1f))
                ReportsCountTile(null, "Blank", report.unmarked, Modifier.weight(1f))
            }
            Text(
                "Figures are in days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notice != null) {
                Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReportsCountTile(status: AttendanceStatus?, label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$label: ${formatDays(value)} days" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReportsStatusLetter(status)
        Text(formatDays(value), style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The status letter on its fixed semantic colour; a dash on a neutral surface for blank. */
@Composable
internal fun ReportsStatusLetter(status: AttendanceStatus?, modifier: Modifier = Modifier) {
    val (container, content) = if (status != null) {
        attendanceStatusColors(status).let { it.container to it.content }
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    ReportsLetterChip(status?.code ?: "–", container, content, modifier)
}

@Composable
private fun ReportsLetterChip(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(10.dp), color = container, contentColor = content, modifier = modifier) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// endregion

// region Lists

private val OuterCorner = 20.dp
private val InnerCorner = 4.dp

/** Grouped-list segment shape, matching ListGroup: big outer corners, small inner ones. */
private fun reportsSegmentShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) OuterCorner else InnerCorner,
    topEnd = if (index == 0) OuterCorner else InnerCorner,
    bottomStart = if (index == count - 1) OuterCorner else InnerCorner,
    bottomEnd = if (index == count - 1) OuterCorner else InnerCorner,
)

private fun LazyListScope.sectionHeader(key: String, title: String) {
    item(key = key) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)
                .semantics { heading() },
        )
    }
}

private fun LazyListScope.absenteeItems(
    absentees: List<AttendanceReportStudent>,
    messagedIds: Set<Long>,
    onOpen: () -> Unit,
    phoneDisplay: (String) -> String,
) {
    sectionHeader("absent-header", "Absent · ${absentees.size}")
    if (absentees.isEmpty()) {
        item(key = "absent-none") {
            AppCard {
                Text(
                    "No students are marked absent on this day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                )
            }
        }
        return
    }
    itemsIndexed(absentees, key = { _, student -> "absent-${student.row.enrollment.id}" }) { index, student ->
        val messaged = student.row.enrollment.id in messagedIds
        val phone = student.row.student.phone
        Surface(
            shape = reportsSegmentShape(index, absentees.size),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Message parents", onClick = onOpen)
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StudentAvatar(student.row.student.photoFile, student.row.enrollment.rollNumber)
                Column(Modifier.weight(1f)) {
                    Text(
                        student.row.student.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Absent for the day · " +
                            if (hasUsablePhone(phone)) phoneDisplay(phone) else "No phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (messaged) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Parent messaged",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.studentItems(
    data: RegisterData,
    report: AttendanceReport,
    students: List<AttendanceReportStudent>,
    selectedDay: Int,
) {
    val daily = report.type == ReportPeriodType.DAILY
    sectionHeader("students-header", if (daily) "Students" else "Students · most absences first")
    itemsIndexed(students, key = { _, student -> "student-${student.row.enrollment.id}" }) { index, student ->
        val id = student.row.enrollment.id
        Surface(
            shape = reportsSegmentShape(index, students.size),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AttendanceRollBadge(student.row.enrollment.rollNumber)
                Column(Modifier.weight(1f)) {
                    Text(
                        student.row.student.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        reportCountsLine(student),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (daily) {
                    val status = data.statusOf(id, selectedDay)
                    ReportsStatusLetter(
                        status,
                        Modifier.semantics { contentDescription = "Marked ${statusName(status)}" },
                    )
                } else {
                    Text(formatRate(studentRate(student)), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun statusName(status: AttendanceStatus?): String = when (status) {
    AttendanceStatus.PRESENT -> "present"
    AttendanceStatus.ABSENT -> "absent"
    AttendanceStatus.LEAVE -> "leave"
    null -> "not marked"
}

// endregion

// region Previews

private fun previewData(): RegisterData {
    val base = SamplePreviewData.register()
    val phones = mapOf(2L to "+923001234567", 3L to "+923331112222")
    val rows = base.monthRows.map { row ->
        phones[row.enrollment.id]?.let { row.copy(student = row.student.copy(phone = it)) } ?: row
    }
    val day = 6
    val extra = listOf(
        AttendanceMark(1, 1, day, Session.FIRST, AttendanceStatus.PRESENT),
        AttendanceMark(1, 2, day, Session.FIRST, AttendanceStatus.ABSENT),
        AttendanceMark(1, 3, day, Session.FIRST, AttendanceStatus.ABSENT),
        AttendanceMark(1, 5, day, Session.FIRST, AttendanceStatus.LEAVE),
    )
    return base.copy(monthRows = rows, attendanceRows = rows, marks = base.marks + extra)
}

@Composable
private fun ReportsPreview(type: ReportPeriodType) {
    val data = previewData()
    ReportsContent(
        data = data,
        report = data.report(type, 6),
        selectedDay = 6,
        today = LocalDate(2026, 8, 10),
        messagedIds = setOf(2L),
        onTypeChange = {},
        onDayChange = {},
        onMessageParents = {},
    )
}

@Preview(showBackground = true, widthDp = 380, heightDp = 1100)
@Composable
private fun ReportsDailyPreview() {
    AttendanceTheme(dynamicColor = false) { ReportsPreview(ReportPeriodType.DAILY) }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 1000, fontScale = 1.5f)
@Composable
private fun ReportsWeeklyDarkLargeFontPreview() {
    AttendanceTheme(dynamicColor = false, darkTheme = true) { ReportsPreview(ReportPeriodType.WEEKLY) }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 800)
@Composable
private fun ReportsEmptyPreview() {
    val data = SamplePreviewData.register(withStudents = false)
    AttendanceTheme(dynamicColor = false) {
        ReportsContent(
            data = data,
            report = data.report(ReportPeriodType.MONTHLY, 1),
            selectedDay = 1,
            today = LocalDate(2026, 8, 10),
            messagedIds = emptySet(),
            onTypeChange = {},
            onDayChange = {},
            onMessageParents = {},
        )
    }
}

// endregion

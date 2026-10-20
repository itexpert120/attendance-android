package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.repo.TestReport
import com.itexpert120.attendanceManager.data.repo.TestReportSection
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.export.TestPdf
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.BadgeTone
import com.itexpert120.attendanceManager.ui.components.DateField
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.ListGroup
import com.itexpert120.attendanceManager.ui.components.PdfExportSheet
import com.itexpert120.attendanceManager.ui.components.PdfRequest
import com.itexpert120.attendanceManager.ui.components.rememberPdfExportState
import com.itexpert120.attendanceManager.ui.components.SegmentedChoice
import com.itexpert120.attendanceManager.ui.components.StatusBadge
import com.itexpert120.attendanceManager.ui.theme.extraColors
import kotlinx.datetime.LocalDate

private val ReportRequestSaver: Saver<ReportRequest, Any> = listSaver(
    save = {
        listOf(
            it.mode.ordinal,
            it.periodType.ordinal,
            it.anchor.toEpochDays(),
            it.subjectId ?: -1L,
            it.classGroupId ?: -1L,
            it.studentId ?: -1L,
        )
    },
    restore = {
        ReportRequest(
            mode = ReportMode.entries[it[0] as Int],
            periodType = ReportPeriodType.entries[it[1] as Int],
            anchor = LocalDate.fromEpochDays(it[2] as Long),
            subjectId = (it[3] as Long).takeIf { id -> id > 0 },
            classGroupId = (it[4] as Long).takeIf { id -> id > 0 },
            studentId = (it[5] as Long).takeIf { id -> id > 0 },
        )
    },
)

/** Subject and Student Reports: what was tested in a day, week or month, and how a class or one student did. */
@Composable
fun TestReportsScreen(onBack: () -> Unit, onOpenTest: (testId: Long) -> Unit = {}) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        TestReportsViewModel(container.tests, container.subjects, container.classes, container.students)
    }
    val lookups by viewModel.lookups.collectAsState()
    val studentOptions by viewModel.studentOptions.collectAsState()
    val report by viewModel.report.collectAsState()
    var request by rememberSaveable(stateSaver = ReportRequestSaver) { mutableStateOf(ReportRequest(anchor = today())) }

    val classIds = lookups?.classes?.map { it.id }.orEmpty()
    val classId = reportClassId(request.mode, request.classGroupId, classIds)
    val students = studentOptions?.takeIf { it.classGroupId == classId }?.students
    val resolved = resolveReportRequest(request, classIds, students)
    val filter = if (lookups == null) null else resolved.toFilter()
    val studentClassId = if (resolved.mode == ReportMode.STUDENT) classId else null

    LaunchedEffect(lookups == null, filter, studentClassId) {
        if (lookups != null) viewModel.select(filter, studentClassId)
    }

    // Export the report on screen, only once it is the one for the current choices (not a stale one).
    val pdfExport = rememberPdfExportState()
    val shown = (report as? ReportState.Ready)?.report?.takeIf { filter != null && it.filter == filter }
    val currentLookups = lookups
    val exportPdf = if (shown == null || currentLookups == null) null else {
        {
            val subject = currentLookups.subjects.firstOrNull { it.id == shown.filter.subjectId }
            val group = currentLookups.classes.firstOrNull { it.id == shown.filter.classGroupId }
            val student = students?.firstOrNull { it.id == shown.filter.studentId }
            val period = resolved.periodLabel
            pdfExport.open(
                PdfRequest(TestPdf.reportTitle(shown), TestPdf.reportFileName(shown, period, subject, group, student)) {
                    TestPdf.buildReport(
                        shown,
                        period,
                        currentLookups.subjects,
                        currentLookups.classes,
                        student,
                        container.settings.getSettings(),
                        container.photoStore,
                    )
                },
            )
        }
    }

    TestReportsContent(
        lookups = lookups,
        request = resolved,
        students = students,
        report = report,
        onRequestChange = { request = it },
        onBack = onBack,
        onOpenTest = onOpenTest,
        onExportPdf = exportPdf,
    )
    PdfExportSheet(pdfExport)
}

@Composable
fun TestReportsContent(
    lookups: ReportLookups?,
    request: ReportRequest,
    students: List<Student>?,
    report: ReportState,
    onRequestChange: (ReportRequest) -> Unit,
    onBack: () -> Unit,
    onOpenTest: (testId: Long) -> Unit = {},
    onExportPdf: (() -> Unit)? = null,
) {
    AppScreen(
        title = "Test reports",
        subtitle = request.periodLabel,
        onBack = onBack,
        floatingActionButton = {
            if (onExportPdf != null) {
                ExtendedFloatingActionButton(
                    onClick = onExportPdf,
                    icon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null) },
                    text = { Text("Export PDF") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
    ) { padding ->
        if (lookups == null) {
            TestLoading(padding)
            return@AppScreen
        }
        val groups = lookups.classes.associateBy { it.id }
        val studentMode = request.mode == ReportMode.STUDENT

        TestPage(padding, spacing = 12.dp) {
            item(key = "controls") {
                ReportControls(lookups, request, students, onRequestChange)
            }
            when {
                studentMode && lookups.classes.isEmpty() -> item(key = "no-classes") {
                    EmptyState(
                        icon = Icons.Rounded.Groups,
                        title = "No classes yet",
                        text = "Add a class and its students to see a student's test results.",
                    )
                }
                studentMode && students != null && students.isEmpty() -> item(key = "no-students") {
                    EmptyState(
                        icon = Icons.Rounded.Person,
                        title = "No students in this class",
                        text = "Choose another class to see a student's test results.",
                    )
                }
                else -> when (report) {
                    ReportState.Loading, ReportState.Incomplete -> item(key = "loading") { TestLoading() }
                    is ReportState.Ready -> reportItems(report.report, studentMode, groups, onOpenTest)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.reportItems(
    report: TestReport,
    studentMode: Boolean,
    groups: Map<Long, ClassGroup>,
    onOpenTest: (Long) -> Unit,
) {
    item(key = "stats") { ReportStats(report) }
    item(key = "heading") {
        Text(
            if (studentMode) "Individual results" else "Subject breakdown",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        )
    }
    if (report.sections.isEmpty()) {
        item(key = "empty") {
            EmptyState(
                icon = if (studentMode) Icons.Rounded.Person else Icons.Rounded.MenuBook,
                title = "No test results in this period",
                text = if (studentMode) "Choose another period, subject or student." else "Choose another period, subject or class.",
            )
        }
        return
    }
    report.sections.forEach { section ->
        item(key = "section-${section.subject.id}") {
            ReportSectionGroup(section, report, studentMode, groups, onOpenTest)
        }
    }
}

/** Report type, period (type, a stepper and the anchor date), Subject, Class Group and, for a Student Report, the student. */
@Composable
private fun ReportControls(
    lookups: ReportLookups,
    request: ReportRequest,
    students: List<Student>?,
    onChange: (ReportRequest) -> Unit,
) {
    val studentMode = request.mode == ReportMode.STUDENT
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedChoice(
                options = ReportMode.entries,
                selected = request.mode,
                optionLabel = { it.label },
                onSelect = { onChange(request.copy(mode = it)) },
            )
            SegmentedChoice(
                options = ReportPeriodType.entries,
                selected = request.periodType,
                optionLabel = ::periodTypeLabel,
                onSelect = { onChange(request.copy(periodType = it)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(
                    onClick = { onChange(request.copy(anchor = shiftAnchor(request.periodType, request.anchor, -1))) },
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous period") }
                Text(
                    request.periodLabel,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = { onChange(request.copy(anchor = shiftAnchor(request.periodType, request.anchor, 1))) },
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next period") }
            }
            DateField(
                label = anchorFieldLabel(request.periodType),
                date = request.anchor,
                onChange = { onChange(request.copy(anchor = it)) },
            )
            ChoiceField(
                label = "Subject",
                choices = listOf(Choice<Long?>(null, "All subjects")) + lookups.subjects.map {
                    Choice<Long?>(it.id, if (it.isArchived) "${it.name} · Archived" else it.name)
                },
                value = request.subjectId,
                onChange = { onChange(request.copy(subjectId = it)) },
            )
            val classChoices = lookups.classes.map { Choice<Long?>(it.id, classLabel(it, long = true)) }
            ChoiceField(
                label = "Class",
                choices = if (studentMode) classChoices else listOf(Choice<Long?>(null, "All classes")) + classChoices,
                value = request.classGroupId,
                // A different class has different students, so the student picker starts over.
                onChange = { onChange(request.copy(classGroupId = it, studentId = null)) },
                enabled = lookups.classes.isNotEmpty(),
            )
            if (studentMode && !students.isNullOrEmpty()) {
                ChoiceField(
                    label = "Student",
                    choices = students.map { Choice<Long?>(it.id, "${it.name} · ${it.admissionNumber}") },
                    value = request.studentId,
                    onChange = { onChange(request.copy(studentId = it)) },
                )
            }
        }
    }
}

/** Tests, results, absences, outstanding results and the average percentage across the report. */
@Composable
private fun ReportStats(report: TestReport) {
    val colors = MaterialTheme.colorScheme
    val extra = MaterialTheme.extraColors
    val aggregate = report.aggregate
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportStat("Tests", "${aggregate.totalTests}", colors.surfaceContainerLowest, colors.onSurface, Modifier.weight(1f))
            ReportStat("Results", "${aggregate.numericCount}", colors.primaryContainer, colors.onPrimaryContainer, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportStat("Absent", "${aggregate.absentCount}", colors.errorContainer, colors.onErrorContainer, Modifier.weight(1f))
            ReportStat(
                "Not entered",
                "${aggregate.notEnteredCount}",
                extra.warningContainer,
                extra.onWarningContainer,
                Modifier.weight(1f),
            )
        }
        ReportStat(
            "Average",
            aggregate.averagePercentage?.let(::formatPercent) ?: "–",
            colors.tertiaryContainer,
            colors.onTertiaryContainer,
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReportStat(label: String, value: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = content,
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** One Subject's Tests as a grouped list: each Test with its counts and average, or the student's result. */
@Composable
private fun ReportSectionGroup(
    section: TestReportSection,
    report: TestReport,
    studentMode: Boolean,
    groups: Map<Long, ClassGroup>,
    onOpenTest: (Long) -> Unit,
) {
    val subject = section.subject
    ListGroup(header = "${subject.name}${if (subject.isArchived) " · Archived" else ""} · ${pluralTests(section.tests.size)}") {
        items(section.tests) { test -> ReportTestRow(test, report, studentMode, groups[test.classGroupId], onOpen = { onOpenTest(test.id) }) }
    }
}

@Composable
private fun ReportTestRow(test: TestRecord, report: TestReport, studentMode: Boolean, group: ClassGroup?, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${test.name}", onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                test.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (studentMode) {
                val result = studentResultText(report.studentEntryFor(test.id), test)
                StatusBadge(
                    result.text,
                    tone = when (result.tone) {
                        ResultTone.MARKS -> BadgeTone.SUCCESS
                        ResultTone.ABSENT -> BadgeTone.DANGER
                        ResultTone.PENDING -> BadgeTone.NEUTRAL
                    },
                )
            }
        }
        Text(
            "${shortDateLabel(test.date)} · ${classLabel(group)} · ${formatMarks(test.totalMarks)} marks",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        val detail = if (studentMode) {
            studentResultText(report.studentEntryFor(test.id), test).percent
        } else {
            val summary = report.summaryFor(test.id)
            val average = averagePercent(summary.average, test.totalMarks)?.let { "${formatPercent(it)} average" } ?: "No average"
            "${countsLine(summary.numericCount, summary.absentCount, summary.notEnteredCount)} · $average"
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

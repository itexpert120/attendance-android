package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.domain.ClassAssessment
import com.itexpert120.attendanceManager.domain.PASS_PERCENT
import com.itexpert120.attendanceManager.domain.ReportCardSubject
import com.itexpert120.attendanceManager.domain.ResultStats
import com.itexpert120.attendanceManager.domain.StudentReportCard
import com.itexpert120.attendanceManager.domain.StudentStanding
import com.itexpert120.attendanceManager.domain.TeacherPerformance
import com.itexpert120.attendanceManager.domain.TeachingStats
import com.itexpert120.attendanceManager.domain.isPass
import com.itexpert120.attendanceManager.export.TestPdf
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.BadgeTone
import com.itexpert120.attendanceManager.ui.components.DateField
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.ListGroup
import com.itexpert120.attendanceManager.ui.components.ListRow
import com.itexpert120.attendanceManager.ui.components.PdfExportSheet
import com.itexpert120.attendanceManager.ui.components.PdfRequest
import com.itexpert120.attendanceManager.ui.components.SegmentedChoice
import com.itexpert120.attendanceManager.ui.components.StatusBadge
import com.itexpert120.attendanceManager.ui.components.rememberPdfExportState
import com.itexpert120.attendanceManager.ui.theme.extraColors
import kotlinx.datetime.LocalDate

private val ReportRequestSaver: Saver<ReportRequest, Any> = listSaver(
    save = {
        listOf(
            it.mode.ordinal,
            it.periodType.ordinal,
            it.anchor.toEpochDays(),
            it.from.toEpochDays(),
            it.to.toEpochDays(),
            it.classGroupId ?: -1L,
            it.studentId ?: -1L,
        )
    },
    restore = {
        ReportRequest(
            mode = ReportMode.entries[it[0] as Int],
            periodType = TestPeriodType.entries[it[1] as Int],
            anchor = LocalDate.fromEpochDays(it[2] as Long),
            from = LocalDate.fromEpochDays(it[3] as Long),
            to = LocalDate.fromEpochDays(it[4] as Long),
            classGroupId = (it[5] as Long).takeIf { id -> id > 0 },
            studentId = (it[6] as Long).takeIf { id -> id > 0 },
        )
    },
)

/** Student report card, teacher performance and class assessment over a day, week, month or custom range. */
@Composable
fun TestReportsScreen(onBack: () -> Unit, onOpenTest: (testId: Long) -> Unit = {}) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        TestReportsViewModel(container.tests, container.subjects, container.classes, container.subjectTeachers)
    }
    val lookups by viewModel.lookups.collectAsState()
    val data by viewModel.data.collectAsState()
    var request by rememberSaveable(stateSaver = ReportRequestSaver) { mutableStateOf(ReportRequest(anchor = today())) }
    var teachersOpen by rememberSaveable { mutableStateOf(false) }

    val range = request.range
    LaunchedEffect(range) { viewModel.select(range) }
    val current = data?.takeIf { it.range == range }
    val classIds = lookups?.classes?.map { it.id }.orEmpty()
    val classId = reportClassId(request.mode, request.classGroupId, classIds)
    val students = current?.let { classStudentChoices(it.enrollments, classId) }
    val resolved = resolveReportRequest(request, classIds, students)
    val report = remember(current, resolved) { current?.let { buildShownReport(it, resolved) } }

    val pdfExport = rememberPdfExportState()
    val currentLookups = lookups
    val exportPdf = if (report == null || report == ShownReport.Incomplete || currentLookups == null) null else {
        {
            val group = currentLookups.classes.firstOrNull { it.id == resolved.classGroupId }
            val period = resolved.periodLabel
            val title = resolved.mode.title
            val fileName = when (report) {
                is ShownReport.Card -> TestPdf.reportCardFileName(report.card, group, period)
                is ShownReport.Teachers -> TestPdf.teacherFileName(group, period)
                is ShownReport.Class -> TestPdf.classFileName(group, period)
                ShownReport.Incomplete -> title
            }
            pdfExport.open(
                PdfRequest(title, fileName) {
                    val settings = container.settings.getSettings()
                    val logo = TestPdf.loadLogo(settings, container.photoStore)
                    when (report) {
                        is ShownReport.Card -> TestPdf.buildReportCard(report.card, group, period, settings, logo)
                        is ShownReport.Teachers -> TestPdf.buildTeacherReport(report.teachers, group, period, settings, logo)
                        is ShownReport.Class -> TestPdf.buildClassReport(report.assessment, group, period, settings, logo)
                        ShownReport.Incomplete -> error("No report to export")
                    }
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
        onAssignTeachers = { teachersOpen = true },
    )
    PdfExportSheet(pdfExport)

    if (teachersOpen && currentLookups != null) {
        SubjectTeachersSheet(
            classes = currentLookups.classes,
            subjects = currentLookups.subjects,
            teachers = currentLookups.teachers,
            initialClassId = resolved.classGroupId,
            onSave = viewModel::saveTeachers,
            onDismiss = { teachersOpen = false },
        )
    }
}

@Composable
fun TestReportsContent(
    lookups: ReportLookups?,
    request: ReportRequest,
    students: List<Student>?,
    report: ShownReport?,
    onRequestChange: (ReportRequest) -> Unit,
    onBack: () -> Unit,
    onOpenTest: (testId: Long) -> Unit = {},
    onExportPdf: (() -> Unit)? = null,
    onAssignTeachers: () -> Unit = {},
) {
    AppScreen(
        title = request.mode.title,
        subtitle = request.periodLabel,
        onBack = onBack,
        actions = {
            IconButton(onClick = onAssignTeachers) {
                Icon(Icons.Rounded.RecordVoiceOver, contentDescription = "Subject teachers")
            }
        },
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
        val openStudent: (StudentStanding) -> Unit = { standing ->
            onRequestChange(request.copy(mode = ReportMode.STUDENT, studentId = standing.student.id))
        }

        TestPage(padding, spacing = 12.dp) {
            item(key = "controls") {
                ReportControls(lookups, request, students, onRequestChange, onAssignTeachers)
            }
            when {
                request.mode != ReportMode.TEACHER && lookups.classes.isEmpty() -> item(key = "no-classes") {
                    EmptyState(
                        icon = Icons.Rounded.Groups,
                        title = "No classes yet",
                        text = "Add a class and its students to see test reports.",
                    )
                }
                request.mode == ReportMode.STUDENT && students != null && students.isEmpty() -> item(key = "no-students") {
                    EmptyState(
                        icon = Icons.Rounded.Person,
                        title = "No students in this class",
                        text = "Choose another class to see a student's report card.",
                    )
                }
                else -> when (report) {
                    null, ShownReport.Incomplete -> item(key = "loading") { TestLoading() }
                    is ShownReport.Card -> reportCardItems(report.card, onOpenTest)
                    is ShownReport.Teachers -> teacherItems(report.teachers, groups, onAssignTeachers)
                    is ShownReport.Class -> classItems(report.assessment, openStudent)
                }
            }
        }
    }
}

/** Report type, period (type, a stepper and the dates), class and, for a report card, the student. */
@Composable
private fun ReportControls(
    lookups: ReportLookups,
    request: ReportRequest,
    students: List<Student>?,
    onChange: (ReportRequest) -> Unit,
    onAssignTeachers: () -> Unit,
) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedChoice(
                options = ReportMode.entries,
                selected = request.mode,
                optionLabel = { it.label },
                onSelect = { onChange(request.copy(mode = it)) },
            )
            SegmentedChoice(
                options = TestPeriodType.entries,
                selected = request.periodType,
                optionLabel = { it.label },
                onSelect = { onChange(request.withPeriodType(it)) },
            )
            val custom = request.periodType == TestPeriodType.CUSTOM
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { onChange(request.shifted(-1)) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = if (custom) "Previous range" else "Previous period")
                }
                Text(
                    request.periodLabel,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(onClick = { onChange(request.shifted(1)) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = if (custom) "Next range" else "Next period")
                }
            }
            if (custom) {
                DateField(label = "From", date = request.from, onChange = { onChange(request.withFrom(it)) }, maxDate = request.to)
                DateField(label = "To", date = request.to, onChange = { onChange(request.withTo(it)) }, minDate = request.from)
            } else {
                DateField(
                    label = anchorFieldLabel(request.periodType),
                    date = request.anchor,
                    onChange = { onChange(request.copy(anchor = it)) },
                )
            }
            val classChoices = lookups.classes.map { Choice<Long?>(it.id, classLabel(it, long = true)) }
            ChoiceField(
                label = "Class",
                choices = if (request.mode == ReportMode.TEACHER) listOf(Choice<Long?>(null, "All classes")) + classChoices else classChoices,
                value = request.classGroupId,
                // A different class has different students, so the student picker starts over.
                onChange = { onChange(request.copy(classGroupId = it, studentId = null)) },
                enabled = lookups.classes.isNotEmpty(),
            )
            if (request.mode == ReportMode.STUDENT && !students.isNullOrEmpty()) {
                ChoiceField(
                    label = "Student",
                    choices = students.map { Choice<Long?>(it.id, "${it.name} · B-Form ${it.admissionNumber}") },
                    value = request.studentId,
                    onChange = { onChange(request.copy(studentId = it)) },
                )
            }
            if (request.mode == ReportMode.TEACHER) {
                FilledTonalButton(onClick = onAssignTeachers, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                    Text("Assign teachers")
                }
            }
        }
    }
}

// region Student report card

private fun LazyListScope.reportCardItems(card: StudentReportCard, onOpenTest: (Long) -> Unit) {
    item(key = "card-stats") {
        val colors = MaterialTheme.colorScheme
        val extra = MaterialTheme.extraColors
        StatGrid(
            listOf(
                Stat("Overall", percentOrDash(card.percent), colors.tertiaryContainer, colors.onTertiaryContainer),
                Stat("Marks", marksOutOf(card.obtained, card.possible), colors.primaryContainer, colors.onPrimaryContainer),
                Stat("Tests", "${card.tests}", colors.surfaceContainerLowest, colors.onSurface),
                Stat("Absences", "${card.absent}", colors.errorContainer, colors.onErrorContainer),
                Stat(
                    "Class rank",
                    card.rank?.let { "${ordinal(it)} of ${card.rankedCount}" } ?: "–",
                    extra.warningContainer,
                    extra.onWarningContainer,
                ),
            ),
        )
    }
    if (card.subjects.isEmpty()) {
        item(key = "card-empty") {
            EmptyState(
                icon = Icons.Rounded.Person,
                title = "No test results in this period",
                text = "Choose another period, class or student.",
            )
        }
        return
    }
    item(key = "card-subjects") {
        var expanded by rememberSaveable { mutableStateOf(ArrayList<Long>()) }
        ListGroup(header = "Subjects", footer = "Tap a subject to see its tests. Pass mark ${PASS_PERCENT.toInt()}%.") {
            items(card.subjects) { subject ->
                ReportCardSubjectRow(
                    subject = subject,
                    expanded = subject.subjectId in expanded,
                    onToggle = {
                        expanded = ArrayList(expanded).apply { if (!remove(subject.subjectId)) add(subject.subjectId) }
                    },
                    onOpenTest = onOpenTest,
                )
            }
        }
    }
}

@Composable
private fun ReportCardSubjectRow(subject: ReportCardSubject, expanded: Boolean, onToggle: () -> Unit, onOpenTest: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = subject.subject?.let { if (it.isArchived) "${it.name} · Archived" else it.name } ?: "Subject"
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "expand")
    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Hide tests" else "Show tests", onClick = onToggle)
                .heightIn(min = 72.dp)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${testCountsLine(subject.tests.size, subject.absent, subject.notEntered)} · ${marksOutOf(subject.obtained, subject.possible)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            PercentBadge(subject.percent)
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                subject.tests.forEach { line ->
                    val result = studentResultText(line.entry, line.test)
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(onClickLabel = "Open ${line.test.name}") { onOpenTest(line.test.id) }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 6.dp)
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(line.test.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(shortDateLabel(line.test.date), result.percent).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
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
            }
        }
    }
}

// endregion

// region Teacher performance

private fun LazyListScope.teacherItems(teachers: List<TeacherPerformance>, groups: Map<Long, ClassGroup>, onAssignTeachers: () -> Unit) {
    if (teachers.isEmpty()) {
        item(key = "teachers-empty") {
            EmptyState(
                icon = Icons.Rounded.RecordVoiceOver,
                title = "No tests in this period",
                text = "Choose another period or class, or assign teachers to subjects.",
            )
        }
        return
    }
    teachers.forEach { teacher ->
        item(key = "teacher-${teacher.teacherName?.lowercase() ?: "none"}") {
            val name = teacher.teacherName ?: "No teacher assigned"
            ListGroup(
                header = "$name · ${pluralTests(teacher.overall.tests)}",
                footer = if (teacher.teacherName == null) "Assign teachers to group these subjects by teacher." else null,
            ) {
                item {
                    ListRow(
                        label = "Overall",
                        detail = statsLine(teacher.overall),
                        trailing = { PercentBadge(teacher.overall.averagePercent) },
                        onClick = if (teacher.teacherName == null) onAssignTeachers else null,
                    )
                }
                items(teacher.rows) { row -> TeachingRow(row, groups[row.classGroupId] ?: row.group) }
            }
        }
    }
}

@Composable
private fun TeachingRow(row: TeachingStats, group: ClassGroup?) {
    val colors = MaterialTheme.colorScheme
    val stats = row.stats
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${row.subject?.name ?: "Subject"} · ${classLabel(group)}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(statsLine(stats), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (stats.results > 0) {
                Text(
                    "Highest ${percentOrDash(stats.highestPercent)} · Lowest ${percentOrDash(stats.lowestPercent)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        PercentBadge(stats.averagePercent)
    }
}

/** "3 tests · Pass 85.0% · 40 entered · 2 not entered" */
private fun statsLine(stats: ResultStats): String = buildList {
    add(pluralTests(stats.tests))
    stats.passRate?.let { add("Pass ${formatPercent(it)}") }
    if (stats.tests > 0) add("${stats.entered} entered")
    if (stats.notEntered > 0) add("${stats.notEntered} not entered")
}.joinToString(" · ")

// endregion

// region Class assessment

private fun LazyListScope.classItems(assessment: ClassAssessment, onOpenStudent: (StudentStanding) -> Unit) {
    item(key = "class-stats") {
        val colors = MaterialTheme.colorScheme
        val extra = MaterialTheme.extraColors
        val overall = assessment.overall
        StatGrid(
            listOf(
                Stat("Class average", percentOrDash(overall.averagePercent), colors.tertiaryContainer, colors.onTertiaryContainer),
                Stat("Pass rate", percentOrDash(overall.passRate), colors.primaryContainer, colors.onPrimaryContainer),
                Stat("Tests", "${overall.tests}", colors.surfaceContainerLowest, colors.onSurface),
                Stat("Need attention", "${assessment.needsAttention.size}", extra.warningContainer, extra.onWarningContainer),
            ),
        )
    }
    if (assessment.subjects.isEmpty()) {
        item(key = "class-empty") {
            EmptyState(
                icon = Icons.Rounded.Assessment,
                title = "No tests in this period",
                text = "Choose another period or class.",
            )
        }
        return
    }
    item(key = "class-subjects") {
        ListGroup(header = "Subjects") {
            items(assessment.subjects) { subject ->
                ListRow(
                    label = subject.subject?.name ?: "Subject",
                    detail = statsLine(subject.stats),
                    trailing = { PercentBadge(subject.stats.averagePercent) },
                )
            }
        }
    }
    if (assessment.topStudents.isNotEmpty()) {
        item(key = "class-top") {
            ListGroup(header = "Top students") {
                items(assessment.topStudents) { StandingRow(it, onOpenStudent) }
            }
        }
    }
    if (assessment.needsAttention.isNotEmpty()) {
        item(key = "class-attention") {
            ListGroup(header = "Needs attention", footer = "Overall below the ${PASS_PERCENT.toInt()}% pass mark.") {
                items(assessment.needsAttention) { StandingRow(it, onOpenStudent) }
            }
        }
    }
    item(key = "class-ranking") {
        ListGroup(header = "Ranking", footer = "Tap a student to open their report card.") {
            items(assessment.standings) { StandingRow(it, onOpenStudent) }
        }
    }
}

@Composable
private fun StandingRow(standing: StudentStanding, onOpen: (StudentStanding) -> Unit) {
    ListRow(
        label = standing.student.name,
        detail = "Roll ${standing.rollNumber} · ${testCountsLine(standing.tests, standing.absent, standing.notEntered)}",
        leading = {
            Text(
                standing.rank?.let { "#$it" } ?: "–",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        },
        trailing = { PercentBadge(standing.percent) },
        onClick = { onOpen(standing) },
    )
}

// endregion

/** A percentage badge: success at or above the pass mark, danger below, neutral without marks. */
@Composable
private fun PercentBadge(percent: Double?) {
    StatusBadge(
        percentOrDash(percent),
        tone = when {
            percent == null -> BadgeTone.NEUTRAL
            isPass(percent) -> BadgeTone.SUCCESS
            else -> BadgeTone.DANGER
        },
    )
}

private data class Stat(val label: String, val value: String, val container: Color, val content: Color)

/** Stat tiles two per row; an odd last tile spans the row. */
@Composable
private fun StatGrid(stats: List<Stat>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { ReportStat(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ReportStat(stat: Stat, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = stat.container,
        contentColor = stat.content,
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stat.value, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stat.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

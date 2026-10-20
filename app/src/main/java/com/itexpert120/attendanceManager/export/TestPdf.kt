package com.itexpert120.attendanceManager.export

import android.graphics.Bitmap
import com.itexpert120.attendanceManager.data.repo.TestReport
import com.itexpert120.attendanceManager.data.repo.TestRosterRow
import com.itexpert120.attendanceManager.domain.ClassAssessment
import com.itexpert120.attendanceManager.domain.PASS_PERCENT
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.ResultStats
import com.itexpert120.attendanceManager.domain.StudentReportCard
import com.itexpert120.attendanceManager.domain.StudentStanding
import com.itexpert120.attendanceManager.domain.TeacherPerformance
import com.itexpert120.attendanceManager.domain.isPass
import com.itexpert120.attendanceManager.domain.dateLabel
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.domain.marksPercentage
import com.itexpert120.attendanceManager.domain.shortMonthName
import com.itexpert120.attendanceManager.domain.testSummary
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.ui.features.tests.averagePercent
import com.itexpert120.attendanceManager.ui.features.tests.formatMarkValue
import com.itexpert120.attendanceManager.ui.features.tests.marksOutOf
import com.itexpert120.attendanceManager.ui.features.tests.ordinal
import com.itexpert120.attendanceManager.ui.features.tests.progressLabel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import java.util.Locale

/**
 * The Tests printouts, A4 portrait: one Test's result sheet (the PWA's `PrintTestReport.svelte`) and the
 * Subject / Student Report over a period (`PrintPeriodicTestReport.svelte`). The periodic report takes the
 * [TestReport] the reports screen is showing, so the PDF always matches the screen.
 */
object TestPdf {
    const val TEST_LABEL = "Test Report"
    const val SUBJECT_LABEL = "Subject Report"
    const val STUDENT_LABEL = "Student Report"

    // region One Test

    fun testTitle(test: TestRecord): String = "Test report · ${test.name}"

    fun testFileName(test: TestRecord, group: ClassGroup?): String =
        "Test ${test.name} ${classPart(group)} ${test.date}"

    suspend fun buildTest(
        test: TestRecord,
        subject: Subject?,
        group: ClassGroup?,
        rows: List<TestRosterRow>,
        settings: SchoolSettings?,
        photoStore: PhotoStore?,
    ): PdfDoc {
        val logo = photoStore?.let { RegisterPdf.loadBitmap(it, settings?.logoFile, 256) }
        return buildTest(test, subject, group, rows, settings, logo)
    }

    /** [rows] is the Test Roster with results, in roll order (as `TestRepository.observeRoster` gives it). */
    fun buildTest(
        test: TestRecord,
        subject: Subject?,
        group: ClassGroup?,
        rows: List<TestRosterRow>,
        settings: SchoolSettings?,
        logo: Bitmap?,
    ): PdfDoc {
        val school = schoolName(settings)
        val summary = testSummary(rows.map { it.entry })
        val doc = PdfDoc(PageOrientation.PORTRAIT, footerLeft = school, footerRight = TEST_LABEL)
        doc.header(
            schoolName = school,
            eyebrow = "Test report · ${progressLabel(summary.progress)}",
            title = test.name,
            subtitle = "${subject?.name ?: "Subject"} · ${classLine(group)}",
            logo = logo,
        )
        doc.keyValues(
            listOf(
                "Date" to dateLabel(test.date),
                "Total marks" to formatMarks(test.totalMarks),
                "Class" to (group?.let { "${it.className} · ${it.section}" } ?: "—"),
                "Subject" to (subject?.name ?: "—"),
            ),
        )
        doc.stats(
            listOf(
                "Results" to summary.numericCount.toString(),
                "Absent" to summary.absentCount.toString(),
                "Not entered" to summary.notEnteredCount.toString(),
                "Average" to (summary.average?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"),
                "Highest" to (summary.highest?.let(::formatMarkValue) ?: "—"),
                "Lowest" to (summary.lowest?.let(::formatMarkValue) ?: "—"),
            ),
        )
        averagePercent(summary.average, test.totalMarks)?.let {
            doc.text("Class average ${percent(it)} of ${formatMarks(test.totalMarks)} marks.", PdfTextStyle(8f, color = PdfColors.Muted))
        }

        doc.table(
            PdfTable(
                columns = listOf(
                    PdfColumn("B-Form", width = 70f),
                    PdfColumn("Roll", width = 44f),
                    PdfColumn("Student name", weight = 1f),
                    PdfColumn("Marks / status", width = 92f, align = PdfAlign.CENTER),
                    PdfColumn("Percentage", width = 78f, align = PdfAlign.CENTER),
                ),
                rows = rows.map { row ->
                    PdfRow(
                        listOf(
                            PdfCell(row.student.admissionNumber),
                            PdfCell(row.enrollment.rollNumber, bold = true),
                            PdfCell(row.student.name, bold = true),
                            resultCell(row.result, row.marks),
                            PdfCell(
                                if (row.result == TestResultStatus.MARKS) percent(marksPercentage(row.marks ?: 0, test.totalMarks)) else "—",
                            ),
                        ),
                    )
                },
                fontSize = 9f,
            ),
        )
        if (rows.isEmpty()) {
            doc.text("No students are on this test's roster.", PdfTextStyle(9f, color = PdfColors.Muted))
        }

        doc.signatures(listOf("Class incharge signature" to inchargeName(settings)))
        return doc
    }

    // endregion

    // region Subject / Student Report

    fun reportTitle(report: TestReport): String = if (report.filter.isStudentReport) "Student report" else "Subject report"

    fun reportFileName(report: TestReport, periodLabel: String, subject: Subject?, group: ClassGroup?, student: Student?): String =
        if (report.filter.isStudentReport) {
            "Student report ${student?.name ?: "Student"} ${classPart(group)} $periodLabel"
        } else {
            "Subject report ${subject?.name ?: "All subjects"} ${group?.let(::classPart) ?: "All classes"} $periodLabel"
        }

    suspend fun buildReport(
        report: TestReport,
        periodLabel: String,
        subjects: List<Subject>,
        classes: List<ClassGroup>,
        student: Student?,
        settings: SchoolSettings?,
        photoStore: PhotoStore?,
    ): PdfDoc {
        val logo = photoStore?.let { RegisterPdf.loadBitmap(it, settings?.logoFile, 256) }
        return buildReport(report, periodLabel, subjects, classes, student, settings, logo)
    }

    /**
     * [subjects] and [classes] resolve the names of the report's Tests (archived Subjects included); [student]
     * is the reported student of a Student Report and ignored for a Subject Report.
     */
    fun buildReport(
        report: TestReport,
        periodLabel: String,
        subjects: List<Subject>,
        classes: List<ClassGroup>,
        student: Student?,
        settings: SchoolSettings?,
        logo: Bitmap?,
    ): PdfDoc {
        val studentMode = report.filter.isStudentReport
        val school = schoolName(settings)
        val subjectById = subjects.associateBy { it.id }
        val classById = classes.associateBy { it.id }
        val subject = report.filter.subjectId?.let(subjectById::get)
        val group = report.filter.classGroupId?.let(classById::get)
        val doc = PdfDoc(PageOrientation.PORTRAIT, footerLeft = school, footerRight = if (studentMode) STUDENT_LABEL else SUBJECT_LABEL)

        doc.header(
            schoolName = school,
            eyebrow = if (studentMode) "Student report" else "Subject report",
            title = if (studentMode) student?.name ?: "Student report" else subject?.name ?: "All subjects",
            subtitle = if (studentMode) {
                listOfNotNull(student?.admissionNumber?.takeIf { it.isNotBlank() }?.let { "B-Form $it" }, classLine(group, comma = true)).joinToString(" · ")
            } else {
                group?.let { classLine(it, comma = true) } ?: "All classes"
            },
            logo = logo,
        )
        doc.text(
            periodLabel + if (studentMode) " · ${subject?.name ?: "All subjects"}" else "",
            PdfTextStyle(10f, bold = true, color = PdfColors.Muted),
            spacingAfter = 8f,
        )
        val aggregate = report.aggregate
        doc.stats(
            listOf(
                "Tests" to aggregate.totalTests.toString(),
                "Results" to aggregate.numericCount.toString(),
                "Absent" to aggregate.absentCount.toString(),
                "Not entered" to aggregate.notEnteredCount.toString(),
                "Average" to (aggregate.averagePercentage?.let(::percent) ?: "—"),
            ),
        )

        val lead = listOf(
            PdfColumn("Date", width = 66f),
            PdfColumn("Subject", width = 88f),
            PdfColumn("Class", width = 52f),
            PdfColumn("Test", weight = 1f),
        )
        val columns = lead + if (studentMode) {
            listOf(
                PdfColumn("Marks / status", width = 78f, align = PdfAlign.CENTER),
                PdfColumn("Total", width = 44f, align = PdfAlign.CENTER),
                PdfColumn("Percentage", width = 66f, align = PdfAlign.CENTER),
            )
        } else {
            listOf(
                PdfColumn("Entered", width = 52f, align = PdfAlign.CENTER),
                PdfColumn("Absent", width = 50f, align = PdfAlign.CENTER),
                PdfColumn("Average", width = 66f, align = PdfAlign.CENTER),
            )
        }
        val rows = report.tests.map { test ->
            val testGroup = classById[test.classGroupId]
            val cells = mutableListOf(
                PdfCell(shortDate(test.date)),
                PdfCell(subjectById[test.subjectId]?.name ?: "Subject", bold = true),
                PdfCell(testGroup?.let { "${it.className} · ${it.section}" } ?: "Class"),
                PdfCell(test.name, bold = true),
            )
            if (studentMode) {
                val entry = report.studentEntryFor(test.id)
                cells += resultCell(entry?.result ?: TestResultStatus.NOT_ENTERED, entry?.marks)
                cells += PdfCell(formatMarks(test.totalMarks))
                cells += PdfCell(
                    if (entry?.result == TestResultStatus.MARKS) percent(marksPercentage(entry.marks ?: 0, test.totalMarks)) else "—",
                )
            } else {
                val summary = report.summaryFor(test.id)
                cells += PdfCell(summary.numericCount.toString())
                cells += PdfCell(summary.absentCount.toString(), fill = if (summary.absentCount > 0) PdfColors.AbsentFill else null)
                cells += PdfCell(averagePercent(summary.average, test.totalMarks)?.let(::percent) ?: "—", bold = true)
            }
            PdfRow(cells)
        }
        doc.table(PdfTable(columns, rows, fontSize = 8.5f))
        if (report.tests.isEmpty()) {
            doc.text(
                if (studentMode) "No test results for this student in this period." else "No tests in this period.",
                PdfTextStyle(9f, color = PdfColors.Muted),
            )
        }

        doc.signatures(listOf("Class incharge signature" to inchargeName(settings)))
        return doc
    }

    // endregion

    // region Assessment reports (report card, teacher performance, class assessment)

    const val REPORT_CARD_LABEL = "Student Report Card"
    const val TEACHER_LABEL = "Teacher Performance"
    const val CLASS_LABEL = "Class Assessment"

    fun reportCardFileName(card: StudentReportCard, group: ClassGroup?, periodLabel: String): String =
        "Report card ${card.student?.name ?: "Student"} ${classPart(group)} $periodLabel"

    fun teacherFileName(group: ClassGroup?, periodLabel: String): String =
        "Teacher performance ${group?.let(::classPart) ?: "All classes"} $periodLabel"

    fun classFileName(group: ClassGroup?, periodLabel: String): String = "Class assessment ${classPart(group)} $periodLabel"

    suspend fun loadLogo(settings: SchoolSettings?, photoStore: PhotoStore?): Bitmap? =
        photoStore?.let { RegisterPdf.loadBitmap(it, settings?.logoFile, 256) }

    /** One student across every Subject: a row per Subject, then each Test, with totals and class rank. */
    fun buildReportCard(card: StudentReportCard, group: ClassGroup?, periodLabel: String, settings: SchoolSettings?, logo: Bitmap?): PdfDoc {
        val school = schoolName(settings)
        val doc = PdfDoc(PageOrientation.PORTRAIT, footerLeft = school, footerRight = REPORT_CARD_LABEL)
        val student = card.student
        doc.header(
            schoolName = school,
            eyebrow = "Student report card",
            title = student?.name ?: "Student",
            subtitle = listOfNotNull(
                student?.admissionNumber?.takeIf { it.isNotBlank() }?.let { "B-Form $it" },
                classLine(group, comma = true),
            ).joinToString(" · "),
            logo = logo,
        )
        periodLine(doc, periodLabel)
        doc.stats(
            listOf(
                "Overall" to (card.percent?.let(::percent) ?: "—"),
                "Marks" to marksOutOf(card.obtained, card.possible),
                "Tests" to card.tests.toString(),
                "Absences" to card.absent.toString(),
                "Class rank" to (card.rank?.let { "${ordinal(it)} of ${card.rankedCount}" } ?: "—"),
            ),
        )
        doc.sectionTitle("Subjects")
        doc.table(
            PdfTable(
                columns = listOf(
                    PdfColumn("Subject", weight = 1f),
                    PdfColumn("Tests", width = 44f, align = PdfAlign.CENTER),
                    PdfColumn("Absent", width = 48f, align = PdfAlign.CENTER),
                    PdfColumn("Marks", width = 96f, align = PdfAlign.CENTER),
                    PdfColumn("Percentage", width = 70f, align = PdfAlign.CENTER),
                ),
                rows = card.subjects.map { subject ->
                    PdfRow(
                        listOf(
                            PdfCell(subject.subject?.name ?: "Subject", bold = true),
                            PdfCell(subject.tests.size.toString()),
                            PdfCell(subject.absent.toString(), fill = if (subject.absent > 0) PdfColors.AbsentFill else null),
                            PdfCell(marksOutOf(subject.obtained, subject.possible)),
                            percentCell(subject.percent),
                        ),
                    )
                },
                totals = PdfRow(
                    listOf(
                        PdfCell("Overall", bold = true),
                        PdfCell(card.tests.toString(), bold = true),
                        PdfCell(card.absent.toString(), bold = true),
                        PdfCell(marksOutOf(card.obtained, card.possible), bold = true),
                        percentCell(card.percent),
                    ),
                ),
                fontSize = 9f,
            ),
        )
        if (card.subjects.isEmpty()) {
            doc.text("No test results for this student in this period.", PdfTextStyle(9f, color = PdfColors.Muted))
        } else {
            doc.sectionTitle("Tests")
            doc.table(
                PdfTable(
                    columns = listOf(
                        PdfColumn("Date", width = 66f),
                        PdfColumn("Subject", width = 96f),
                        PdfColumn("Test", weight = 1f),
                        PdfColumn("Marks / status", width = 78f, align = PdfAlign.CENTER),
                        PdfColumn("Total", width = 44f, align = PdfAlign.CENTER),
                        PdfColumn("Percentage", width = 66f, align = PdfAlign.CENTER),
                    ),
                    rows = card.subjects.flatMap { subject ->
                        subject.tests.map { line ->
                            PdfRow(
                                listOf(
                                    PdfCell(shortDate(line.test.date)),
                                    PdfCell(subject.subject?.name ?: "Subject"),
                                    PdfCell(line.test.name, bold = true),
                                    resultCell(line.entry.result, line.entry.marks),
                                    PdfCell(formatMarks(line.test.totalMarks)),
                                    PdfCell(line.percent?.let(::percent) ?: "—"),
                                ),
                            )
                        }
                    },
                    fontSize = 8.5f,
                ),
            )
        }
        doc.text("Pass mark ${PASS_PERCENT.toInt()}%.", PdfTextStyle(8f, color = PdfColors.Muted))
        doc.signatures(listOf("Class incharge signature" to inchargeName(settings), "Parent signature" to null))
        return doc
    }

    /** Per teacher, each Subject + class they teach with the period's averages and pass rates. */
    fun buildTeacherReport(
        teachers: List<TeacherPerformance>,
        group: ClassGroup?,
        periodLabel: String,
        settings: SchoolSettings?,
        logo: Bitmap?,
    ): PdfDoc {
        val school = schoolName(settings)
        val doc = PdfDoc(PageOrientation.PORTRAIT, footerLeft = school, footerRight = TEACHER_LABEL)
        doc.header(
            schoolName = school,
            eyebrow = "Teacher performance",
            title = group?.let { classLine(it, comma = true) } ?: "All classes",
            logo = logo,
        )
        periodLine(doc, "$periodLabel · pass mark ${PASS_PERCENT.toInt()}%")
        if (teachers.isEmpty()) {
            doc.text("No tests or teacher assignments in this period.", PdfTextStyle(9f, color = PdfColors.Muted))
        }
        teachers.forEach { teacher ->
            doc.sectionTitle(teacher.teacherName ?: "No teacher assigned")
            doc.table(
                PdfTable(
                    columns = listOf(
                        PdfColumn("Subject", weight = 1f),
                        PdfColumn("Class", width = 52f),
                        PdfColumn("Tests", width = 36f, align = PdfAlign.CENTER),
                        PdfColumn("Average", width = 56f, align = PdfAlign.CENTER),
                        PdfColumn("Pass rate", width = 56f, align = PdfAlign.CENTER),
                        PdfColumn("Highest", width = 54f, align = PdfAlign.CENTER),
                        PdfColumn("Lowest", width = 54f, align = PdfAlign.CENTER),
                        PdfColumn("Entered", width = 48f, align = PdfAlign.CENTER),
                        PdfColumn("Not entered", width = 52f, align = PdfAlign.CENTER),
                    ),
                    rows = teacher.rows.map { row ->
                        statsRow(listOf(PdfCell(row.subject?.name ?: "Subject", bold = true), PdfCell(classPart(row.group))), row.stats)
                    },
                    totals = statsRow(listOf(PdfCell("Overall", bold = true), PdfCell("")), teacher.overall),
                    fontSize = 8.5f,
                ),
            )
        }
        doc.signatures(listOf("Principal signature" to null))
        return doc
    }

    /** One class across every Subject: averages and pass rates, top students, students needing attention, ranking. */
    fun buildClassReport(assessment: ClassAssessment, group: ClassGroup?, periodLabel: String, settings: SchoolSettings?, logo: Bitmap?): PdfDoc {
        val school = schoolName(settings)
        val doc = PdfDoc(PageOrientation.PORTRAIT, footerLeft = school, footerRight = CLASS_LABEL)
        doc.header(schoolName = school, eyebrow = "Class assessment", title = classLine(group, comma = true), logo = logo)
        periodLine(doc, "$periodLabel · pass mark ${PASS_PERCENT.toInt()}%")
        val overall = assessment.overall
        doc.stats(
            listOf(
                "Class average" to (overall.averagePercent?.let(::percent) ?: "—"),
                "Pass rate" to (overall.passRate?.let(::percent) ?: "—"),
                "Tests" to overall.tests.toString(),
                "Students" to assessment.standings.size.toString(),
                "Need attention" to assessment.needsAttention.size.toString(),
            ),
        )
        doc.sectionTitle("Subjects")
        doc.table(
            PdfTable(
                columns = listOf(
                    PdfColumn("Subject", weight = 1f),
                    PdfColumn("Tests", width = 40f, align = PdfAlign.CENTER),
                    PdfColumn("Average", width = 60f, align = PdfAlign.CENTER),
                    PdfColumn("Pass rate", width = 60f, align = PdfAlign.CENTER),
                    PdfColumn("Highest", width = 58f, align = PdfAlign.CENTER),
                    PdfColumn("Lowest", width = 58f, align = PdfAlign.CENTER),
                    PdfColumn("Not entered", width = 58f, align = PdfAlign.CENTER),
                ),
                rows = assessment.subjects.map { subject ->
                    val s = subject.stats
                    PdfRow(
                        listOf(
                            PdfCell(subject.subject?.name ?: "Subject", bold = true),
                            PdfCell(s.tests.toString()),
                            percentCell(s.averagePercent),
                            PdfCell(s.passRate?.let(::percent) ?: "—"),
                            PdfCell(s.highestPercent?.let(::percent) ?: "—"),
                            PdfCell(s.lowestPercent?.let(::percent) ?: "—"),
                            PdfCell(s.notEntered.toString()),
                        ),
                    )
                },
                fontSize = 9f,
            ),
        )
        if (assessment.subjects.isEmpty()) {
            doc.text("No tests for this class in this period.", PdfTextStyle(9f, color = PdfColors.Muted))
        }
        if (assessment.topStudents.isNotEmpty()) {
            doc.sectionTitle("Top students")
            doc.table(standingsTable(assessment.topStudents))
        }
        if (assessment.needsAttention.isNotEmpty()) {
            doc.sectionTitle("Students needing attention (below ${PASS_PERCENT.toInt()}%)")
            doc.table(standingsTable(assessment.needsAttention))
        }
        if (assessment.standings.isNotEmpty()) {
            doc.sectionTitle("Class ranking")
            doc.table(standingsTable(assessment.standings))
        }
        doc.signatures(listOf("Class incharge signature" to inchargeName(settings)))
        return doc
    }

    private fun periodLine(doc: PdfDoc, text: String) =
        doc.text(text, PdfTextStyle(10f, bold = true, color = PdfColors.Muted), spacingAfter = 8f)

    private fun statsRow(lead: List<PdfCell>, s: ResultStats): PdfRow = PdfRow(
        lead + listOf(
            PdfCell(s.tests.toString()),
            percentCell(s.averagePercent),
            PdfCell(s.passRate?.let(::percent) ?: "—"),
            PdfCell(s.highestPercent?.let(::percent) ?: "—"),
            PdfCell(s.lowestPercent?.let(::percent) ?: "—"),
            PdfCell(s.entered.toString()),
            PdfCell(s.notEntered.toString(), color = if (s.notEntered > 0) null else PdfColors.Muted),
        ),
    )

    private fun standingsTable(standings: List<StudentStanding>) = PdfTable(
        columns = listOf(
            PdfColumn("Rank", width = 36f, align = PdfAlign.CENTER),
            PdfColumn("Roll", width = 40f),
            PdfColumn("Student name", weight = 1f),
            PdfColumn("B-Form", width = 92f),
            PdfColumn("Tests", width = 36f, align = PdfAlign.CENTER),
            PdfColumn("Absent", width = 44f, align = PdfAlign.CENTER),
            PdfColumn("Marks", width = 84f, align = PdfAlign.CENTER),
            PdfColumn("Percentage", width = 62f, align = PdfAlign.CENTER),
        ),
        rows = standings.map { s ->
            PdfRow(
                listOf(
                    PdfCell(s.rank?.toString() ?: "—", bold = true),
                    PdfCell(s.rollNumber),
                    PdfCell(s.student.name, bold = true),
                    PdfCell(s.student.admissionNumber),
                    PdfCell(s.tests.toString()),
                    PdfCell(s.absent.toString(), fill = if (s.absent > 0) PdfColors.AbsentFill else null),
                    PdfCell(marksOutOf(s.obtained, s.possible)),
                    percentCell(s.percent),
                ),
            )
        },
        fontSize = 8.5f,
    )

    /** A percentage, bold, tinted when below the pass mark. */
    private fun percentCell(value: Double?): PdfCell = when {
        value == null -> PdfCell("—", color = PdfColors.Muted)
        isPass(value) -> PdfCell(percent(value), bold = true)
        else -> PdfCell(percent(value), bold = true, fill = PdfColors.AbsentFill)
    }

    // endregion

    /** The marks ("32.5"), or "Absent" / "Not entered" on a tinted cell. */
    private fun resultCell(result: TestResultStatus, marks: Int?): PdfCell = when (result) {
        TestResultStatus.MARKS -> PdfCell(formatMarks(marks ?: 0), bold = true)
        TestResultStatus.ABSENT -> PdfCell("Absent", bold = true, fill = PdfColors.AbsentFill)
        TestResultStatus.NOT_ENTERED -> PdfCell("Not entered", color = PdfColors.Muted)
    }

    /** "81.25%", two decimals like the PWA's printouts. */
    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.2f%%", value)

    /** "3 Aug 2026" */
    private fun shortDate(date: LocalDate): String = "${date.day} ${shortMonthName(date.month.number)} ${date.year}"

    private fun schoolName(settings: SchoolSettings?): String = settings?.schoolName?.takeIf { it.isNotBlank() } ?: "School"

    private fun inchargeName(settings: SchoolSettings?): String =
        settings?.classInchargeName?.takeIf { it.isNotBlank() } ?: "Name and signature"

    private fun classLine(group: ClassGroup?, comma: Boolean = false): String = when {
        group == null -> "Class"
        comma -> "Class ${group.className}, Section ${group.section}"
        else -> "Class ${group.className} · Section ${group.section}"
    }

    private fun classPart(group: ClassGroup?): String = group?.let { "${it.className}-${it.section}" } ?: "Class"
}

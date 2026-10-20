package com.itexpert120.attendanceManager.ui.features.tests

import com.itexpert120.attendanceManager.domain.AssessmentData
import com.itexpert120.attendanceManager.domain.ClassAssessment
import com.itexpert120.attendanceManager.domain.DateRange
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.dateRangeLabel
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.domain.marksPercentage
import com.itexpert120.attendanceManager.domain.StudentReportCard
import com.itexpert120.attendanceManager.domain.TeacherPerformance
import com.itexpert120.attendanceManager.domain.classAssessment
import com.itexpert120.attendanceManager.domain.studentReportCard
import com.itexpert120.attendanceManager.domain.teacherPerformance
import com.itexpert120.attendanceManager.domain.subjectReportPeriod
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** The three test reports. */
enum class ReportMode(val label: String, val title: String) {
    STUDENT("Student", "Student report card"),
    TEACHER("Teachers", "Teacher performance"),
    CLASS("Class", "Class assessment"),
}

/** Daily, weekly and monthly periods around an anchor date, or a custom From–To range. */
enum class TestPeriodType(val label: String) {
    DAILY("Day"),
    WEEKLY("Week"),
    MONTHLY("Month"),
    CUSTOM("Custom"),
}

private fun TestPeriodType.calendarType(): ReportPeriodType? = when (this) {
    TestPeriodType.DAILY -> ReportPeriodType.DAILY
    TestPeriodType.WEEKLY -> ReportPeriodType.WEEKLY
    TestPeriodType.MONTHLY -> ReportPeriodType.MONTHLY
    TestPeriodType.CUSTOM -> null
}

/** The calendar month containing [date]. */
fun monthRange(date: LocalDate): DateRange = subjectReportPeriod(ReportPeriodType.MONTHLY, date).range!!

/**
 * Everything the reports screen lets the user choose. Ids of 0 or less never occur, so null means "all" / none.
 * [from] and [to] are the custom range (always From <= To); they default to the anchor's month.
 */
data class ReportRequest(
    val mode: ReportMode = ReportMode.CLASS,
    val periodType: TestPeriodType = TestPeriodType.MONTHLY,
    val anchor: LocalDate,
    val from: LocalDate = monthRange(anchor).start,
    val to: LocalDate = monthRange(anchor).end,
    val classGroupId: Long? = null,
    val studentId: Long? = null,
) {
    /** The inclusive dates the report covers. */
    val range: DateRange
        get() = when (val type = periodType.calendarType()) {
            null -> DateRange(minOf(from, to), maxOf(from, to))
            else -> subjectReportPeriod(type, anchor).range!!
        }

    val periodLabel: String
        get() = when (val type = periodType.calendarType()) {
            null -> dateRangeLabel(from, to)
            else -> subjectReportPeriod(type, anchor).label
        }

    /** Switching to a custom range starts from the period shown so far; leaving one anchors on its start. */
    fun withPeriodType(type: TestPeriodType): ReportRequest = when {
        type == periodType -> this
        type == TestPeriodType.CUSTOM -> range.let { copy(periodType = type, from = it.start, to = it.end) }
        periodType == TestPeriodType.CUSTOM -> copy(periodType = type, anchor = from)
        else -> copy(periodType = type)
    }

    /** A new From date; To moves along if it would fall before it. */
    fun withFrom(date: LocalDate): ReportRequest = copy(from = date, to = maxOf(date, to))

    /** A new To date; From moves along if it would fall after it. */
    fun withTo(date: LocalDate): ReportRequest = copy(to = date, from = minOf(date, from))

    /** The previous ([steps] < 0) or next period; a custom range moves by its own length. */
    fun shifted(steps: Int): ReportRequest = when (periodType) {
        TestPeriodType.CUSTOM -> {
            val days = (to.toEpochDays() - from.toEpochDays() + 1).toInt() * steps
            copy(from = from.plus(DatePeriod(days = days)), to = to.plus(DatePeriod(days = days)))
        }
        else -> copy(anchor = shiftAnchor(periodType, anchor, steps))
    }

    /** Whether the request has what its report needs (a class, and a student for a report card). */
    val isComplete: Boolean
        get() = when (mode) {
            ReportMode.STUDENT -> classGroupId != null && studentId != null
            ReportMode.CLASS -> classGroupId != null
            ReportMode.TEACHER -> true
        }
}

/** Moves the anchor one day, week or month, so the period stepper can go to the previous or next period. */
fun shiftAnchor(type: TestPeriodType, anchor: LocalDate, steps: Int): LocalDate = when (type) {
    TestPeriodType.DAILY -> anchor.plus(DatePeriod(days = steps))
    TestPeriodType.WEEKLY -> anchor.plus(DatePeriod(days = 7 * steps))
    TestPeriodType.MONTHLY, TestPeriodType.CUSTOM -> anchor.plus(DatePeriod(months = steps))
}

fun anchorFieldLabel(type: TestPeriodType): String = when (type) {
    TestPeriodType.DAILY -> "Report date"
    TestPeriodType.WEEKLY -> "A date in the week"
    TestPeriodType.MONTHLY, TestPeriodType.CUSTOM -> "A date in the month"
}

/** Students sorted by name for the report card picker; a student with two enrollments appears once. */
fun studentChoices(students: List<Student>): List<Student> =
    students.distinctBy { it.id }.sortedWith(compareBy({ it.name.lowercase() }, { it.admissionNumber }))

/** The students ever enrolled in [classGroupId], for the report card picker. */
fun classStudentChoices(enrollments: List<EnrollmentRow>, classGroupId: Long?): List<Student> =
    if (classGroupId == null) emptyList() else studentChoices(enrollments.filter { it.enrollment.classGroupId == classGroupId }.map { it.student })

/** Keeps the chosen student if still offered, otherwise the first one (or null when the class has none). */
fun resolveStudentId(current: Long?, choices: List<Student>): Long? =
    if (choices.any { it.id == current }) current else choices.firstOrNull()?.id

/**
 * The Class Group a report uses: Teacher performance keeps the choice (null = all classes) while that class
 * exists; the report card and class assessment need one, so they fall back to the first class.
 */
fun reportClassId(mode: ReportMode, current: Long?, classIds: List<Long>): Long? {
    val kept = current?.takeIf { it in classIds }
    return if (mode == ReportMode.TEACHER) kept else kept ?: classIds.firstOrNull()
}

/**
 * [request] with its defaults resolved against what exists: the class via [reportClassId] and, for a report card,
 * the student via [resolveStudentId]. [students] are the choices of the resolved class, or null while they are
 * loading (the student stays unresolved, so no report is built for a stale student).
 */
fun resolveReportRequest(request: ReportRequest, classIds: List<Long>, students: List<Student>?): ReportRequest {
    val classId = reportClassId(request.mode, request.classGroupId, classIds)
    val studentId = when {
        request.mode != ReportMode.STUDENT -> request.studentId
        students == null -> null
        else -> resolveStudentId(request.studentId, students)
    }
    return request.copy(classGroupId = classId, studentId = studentId)
}

/** A rank as "1st", "2nd", "3rd", "11th", "22nd". */
fun ordinal(rank: Int): String {
    val suffix = when {
        rank % 100 in 11..13 -> "th"
        rank % 10 == 1 -> "st"
        rank % 10 == 2 -> "nd"
        rank % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$rank$suffix"
}

/** "45 / 60", or "–" with nothing to add up. Values are hundredths. */
fun marksOutOf(obtained: Int, possible: Int): String =
    if (possible <= 0) "–" else "${formatMarks(obtained)} / ${formatMarks(possible)}"

/** "75.0%", or "–" without marks. */
fun percentOrDash(percent: Double?): String = percent?.let(::formatPercent) ?: "–"

/** "3 tests · 1 absent · 2 not entered", leaving out zero counts after the tests. */
fun testCountsLine(tests: Int, absent: Int, notEntered: Int): String = buildList {
    add(pluralTests(tests))
    if (absent > 0) add("$absent absent")
    if (notEntered > 0) add("$notEntered not entered")
}.joinToString(" · ")

enum class ResultTone { MARKS, ABSENT, PENDING }

/** One student's result in one Test: "32.5 / 40", "Absent" or "Not entered", plus the percentage for marks. */
data class StudentResultText(val text: String, val percent: String?, val tone: ResultTone)

fun studentResultText(entry: TestRosterEntry?, test: TestRecord): StudentResultText = when (entry?.result) {
    TestResultStatus.MARKS -> {
        val marks = entry.marks ?: 0
        StudentResultText(
            "${formatMarks(marks)} / ${formatMarks(test.totalMarks)}",
            formatPercent(marksPercentage(marks, test.totalMarks)),
            ResultTone.MARKS,
        )
    }
    TestResultStatus.ABSENT -> StudentResultText("Absent", null, ResultTone.ABSENT)
    else -> StudentResultText("Not entered", null, ResultTone.PENDING)
}

/** "5 results · 1 absent · 2 not entered" for a Test line in a Subject Report. */
fun countsLine(numeric: Int, absent: Int, notEntered: Int): String =
    "$numeric result${if (numeric == 1) "" else "s"} · $absent absent · $notEntered not entered"

/** The report the screen shows for a request, derived from the period's [AssessmentData]. */
sealed interface ShownReport {
    /** The request lacks its class or student (no classes, or a class without students). */
    data object Incomplete : ShownReport

    data class Card(val card: StudentReportCard) : ShownReport

    data class Teachers(val classGroupId: Long?, val teachers: List<TeacherPerformance>) : ShownReport

    data class Class(val assessment: ClassAssessment) : ShownReport
}

/** [request] must be resolved (see [resolveReportRequest]) and [data] must be for its range. */
fun buildShownReport(data: AssessmentData, request: ReportRequest): ShownReport {
    val classId = request.classGroupId
    val studentId = request.studentId
    return when (request.mode) {
        ReportMode.STUDENT ->
            if (classId == null || studentId == null) ShownReport.Incomplete else ShownReport.Card(studentReportCard(data, classId, studentId))
        ReportMode.TEACHER -> ShownReport.Teachers(classId, teacherPerformance(data, classId))
        ReportMode.CLASS -> if (classId == null) ShownReport.Incomplete else ShownReport.Class(classAssessment(data, classId))
    }
}

package com.itexpert120.attendanceManager.ui.features.tests

import com.itexpert120.attendanceManager.data.repo.TestReportFilter
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.domain.marksPercentage
import com.itexpert120.attendanceManager.domain.subjectReportPeriod
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

enum class ReportMode(val label: String) {
    SUBJECT("By subject"),
    STUDENT("By student"),
}

/** Everything the reports screen lets the user choose. Ids of 0 or less never occur, so null means "all" / none. */
data class ReportRequest(
    val mode: ReportMode = ReportMode.SUBJECT,
    val periodType: ReportPeriodType = ReportPeriodType.MONTHLY,
    val anchor: LocalDate,
    val subjectId: Long? = null,
    val classGroupId: Long? = null,
    val studentId: Long? = null,
) {
    /** The repository filter, or null while a Student Report still lacks its class or student. */
    fun toFilter(): TestReportFilter? {
        val range = subjectReportPeriod(periodType, anchor).range ?: return null
        return when (mode) {
            ReportMode.SUBJECT -> TestReportFilter(range, subjectId = subjectId, classGroupId = classGroupId)
            ReportMode.STUDENT -> {
                if (classGroupId == null || studentId == null) {
                    null
                } else {
                    TestReportFilter(range, subjectId = subjectId, classGroupId = classGroupId, studentId = studentId)
                }
            }
        }
    }

    val periodLabel: String get() = subjectReportPeriod(periodType, anchor).label
}

/** Moves the anchor one day, week or month, so the period stepper can go to the previous or next period. */
fun shiftAnchor(type: ReportPeriodType, anchor: LocalDate, steps: Int): LocalDate = when (type) {
    ReportPeriodType.DAILY -> anchor.plus(DatePeriod(days = steps))
    ReportPeriodType.WEEKLY -> anchor.plus(DatePeriod(days = 7 * steps))
    ReportPeriodType.MONTHLY -> anchor.plus(DatePeriod(months = steps))
}

fun periodTypeLabel(type: ReportPeriodType): String = when (type) {
    ReportPeriodType.DAILY -> "Daily"
    ReportPeriodType.WEEKLY -> "Weekly"
    ReportPeriodType.MONTHLY -> "Monthly"
}

fun anchorFieldLabel(type: ReportPeriodType): String = when (type) {
    ReportPeriodType.DAILY -> "Report date"
    ReportPeriodType.WEEKLY -> "A date in the week"
    ReportPeriodType.MONTHLY -> "A date in the month"
}

/** Students sorted by name for the Student Report picker; a student with two enrollments appears once. */
fun studentChoices(students: List<Student>): List<Student> =
    students.distinctBy { it.id }.sortedWith(compareBy({ it.name.lowercase() }, { it.admissionNumber }))

/** Keeps the chosen student if still offered, otherwise the first one (or null when the class has none). */
fun resolveStudentId(current: Long?, choices: List<Student>): Long? =
    if (choices.any { it.id == current }) current else choices.firstOrNull()?.id

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

/**
 * The Class Group a report uses: a Subject Report keeps the choice (null = all classes) while that class
 * exists; a Student Report always needs one, so it falls back to the first class.
 */
fun reportClassId(mode: ReportMode, current: Long?, classIds: List<Long>): Long? {
    val kept = current?.takeIf { it in classIds }
    return if (mode == ReportMode.STUDENT) kept ?: classIds.firstOrNull() else kept
}

/**
 * [request] with its defaults resolved against what exists: the class via [reportClassId] and, for a Student
 * Report, the student via [resolveStudentId]. [students] are the choices of the resolved class, or null while
 * they are loading (the student stays unresolved, so no report is built for a stale student).
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

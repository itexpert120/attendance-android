package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/*
 * Marks are integer hundredths ("Marks ... recorded to at most two decimal places"), so 32.5 is 3250.
 * Statistics are reported as Double mark values because they are only ever displayed.
 */

private val MARKS_PATTERN = Regex("""^(\d+(\.\d{1,2})?|\.\d{1,2})$""")

/** Parses typed marks into hundredths; null when the text isn't a number with at most two decimals. */
fun parseMarks(input: String): Int? {
    val text = input.trim()
    if (!MARKS_PATTERN.matches(text)) return null
    val whole = text.substringBefore('.').ifEmpty { "0" }.toLongOrNull() ?: return null
    val fraction = text.substringAfter('.', "").padEnd(2, '0').toInt()
    val hundredths = whole * 100 + fraction
    return if (hundredths > Int.MAX_VALUE) null else hundredths.toInt()
}

/** Hundredths as the shortest exact decimal: 3250 becomes "32.5", 500 becomes "5" and 507 becomes "5.07". */
fun formatMarks(hundredths: Int): String {
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
}

fun marksPercentage(marks: Int, totalMarks: Int): Double =
    if (totalMarks > 0) marks.toDouble() / totalMarks * 100 else 0.0

enum class TestProgress { NOT_STARTED, IN_PROGRESS, COMPLETE }

/** Whether the entry has any recorded result; Not Entered is the only state that counts as outstanding. */
val TestRosterEntry.isEntered: Boolean get() = result != TestResultStatus.NOT_ENTERED

data class TestSummary(
    val progress: TestProgress,
    val numericCount: Int,
    val absentCount: Int,
    val notEnteredCount: Int,
    val average: Double?,
    val highest: Double?,
    val lowest: Double?,
)

/** Progress and score statistics for one Test, given exactly that Test's roster entries. */
fun testSummary(entries: List<TestRosterEntry>): TestSummary {
    val marks = entries.filter { it.result == TestResultStatus.MARKS }.map { it.marks ?: 0 }
    val entered = entries.count { it.isEntered }
    val notEntered = entries.size - entered
    return TestSummary(
        progress = when {
            entered == 0 -> TestProgress.NOT_STARTED
            notEntered == 0 -> TestProgress.COMPLETE
            else -> TestProgress.IN_PROGRESS
        },
        numericCount = marks.size,
        absentCount = entries.count { it.result == TestResultStatus.ABSENT },
        notEnteredCount = notEntered,
        average = marks.takeIf { it.isNotEmpty() }?.let { it.sum().toDouble() / it.size / 100 },
        highest = marks.maxOrNull()?.let { it / 100.0 },
        lowest = marks.minOrNull()?.let { it / 100.0 },
    )
}

data class TestCounts(val total: Int, val bySubject: List<SubjectTestCount>)

data class SubjectTestCount(val subjectId: Long, val count: Int)

/** Total Tests plus a count per active Subject; Subjects with no Tests are included with zero. */
fun testCountsBySubject(subjects: List<Subject>, tests: List<TestRecord>): TestCounts = TestCounts(
    total = tests.size,
    bySubject = subjects
        .filterNot { it.isArchived }
        .map { subject -> SubjectTestCount(subject.id, tests.count { it.subjectId == subject.id }) },
)

data class TestAggregate(
    val totalTests: Int,
    val numericCount: Int,
    val absentCount: Int,
    val notEnteredCount: Int,
    /** Mean of each numeric result as a percentage of its own Test's Total Marks. */
    val averagePercentage: Double?,
)

/** Combines several Tests, normalising marks to percentages so different Total Marks are comparable. */
fun testReportAggregate(tests: List<TestRecord>, entries: List<TestRosterEntry>): TestAggregate {
    val totalByTest = tests.associate { it.id to it.totalMarks }
    val relevant = entries.filter { it.testId in totalByTest }
    val percentages = relevant
        .filter { it.result == TestResultStatus.MARKS }
        .map { marksPercentage(it.marks ?: 0, totalByTest.getValue(it.testId)) }
    return TestAggregate(
        totalTests = tests.size,
        numericCount = percentages.size,
        absentCount = relevant.count { it.result == TestResultStatus.ABSENT },
        notEnteredCount = relevant.count { !it.isEntered },
        averagePercentage = percentages.takeIf { it.isNotEmpty() }?.average(),
    )
}

/** Inclusive date range a Subject or Student Report covers. */
data class DateRange(val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = date in start..end
}

data class SubjectReportPeriod(val range: DateRange?, val label: String)

/** The calendar day, Monday-to-Sunday week, or calendar month containing [anchor]. */
fun subjectReportPeriod(type: ReportPeriodType, anchor: LocalDate?): SubjectReportPeriod {
    if (anchor == null) return SubjectReportPeriod(null, "Choose a report date")
    return when (type) {
        ReportPeriodType.DAILY -> SubjectReportPeriod(DateRange(anchor, anchor), gbLongDate(anchor))
        ReportPeriodType.MONTHLY -> SubjectReportPeriod(
            DateRange(
                LocalDate(anchor.year, anchor.month.number, 1),
                LocalDate(anchor.year, anchor.month.number, daysInMonth(anchor.year, anchor.month.number)),
            ),
            monthLabel(anchor.month.number, anchor.year),
        )
        ReportPeriodType.WEEKLY -> {
            val start = LocalDate.fromEpochDays(anchor.toEpochDays() - mondayOffset(anchor))
            val end = LocalDate.fromEpochDays(start.toEpochDays() + 6)
            SubjectReportPeriod(
                DateRange(start, end),
                "${start.day} ${shortMonthName(start.month.number)} – ${end.day} ${shortMonthName(end.month.number)} ${end.year}",
            )
        }
    }
}

/**
 * A custom report range: "1 Sep – 15 Oct 2026", "20 Dec 2025 – 5 Jan 2026" across years, or "1 Sep 2026" for a
 * single day. [start] and [end] may come in either order.
 */
fun dateRangeLabel(start: LocalDate, end: LocalDate): String {
    val from = minOf(start, end)
    val to = maxOf(start, end)
    fun dayMonth(date: LocalDate) = "${date.day} ${shortMonthName(date.month.number)}"
    return when {
        from == to -> "${dayMonth(from)} ${from.year}"
        from.year == to.year -> "${dayMonth(from)} – ${dayMonth(to)} ${to.year}"
        else -> "${dayMonth(from)} ${from.year} – ${dayMonth(to)} ${to.year}"
    }
}

/** "Wednesday, 2 September 2026" */
private fun gbLongDate(date: LocalDate) =
    "${weekdayName(date)}, ${date.day} ${monthName(date.month.number)} ${date.year}"

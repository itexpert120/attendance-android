package com.itexpert120.attendanceManager.ui.features.tests

import com.itexpert120.attendanceManager.data.repo.TestInput
import com.itexpert120.attendanceManager.data.repo.TestWithEntries
import com.itexpert120.attendanceManager.domain.DateRange
import com.itexpert120.attendanceManager.domain.TestProgress
import com.itexpert120.attendanceManager.domain.academicYearStartYear
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.domain.parseMarks
import com.itexpert120.attendanceManager.domain.shortMonthName
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Clock

/*
 * Plain, JVM-testable logic behind the Tests tab, the Test form and the shared formatting helpers of the
 * Test screens. Nothing here touches Android or Compose.
 */

/** `TestsFilter.yearChoice`: the academic year containing today (the default). */
const val YEAR_CURRENT = 0

/** `TestsFilter.yearChoice`: every Test regardless of date. */
const val YEAR_ALL = -1

/** `TestsFilter.yearChoice`: use [TestsFilter.from] / [TestsFilter.to] instead of an academic year. */
const val YEAR_CUSTOM = -2

/** The filters of the Tests list. Any other `yearChoice` value is the start year of an academic year. */
data class TestsFilter(
    val query: String = "",
    val classGroupId: Long? = null,
    val subjectId: Long? = null,
    val progress: TestProgress? = null,
    val yearChoice: Int = YEAR_CURRENT,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    /** Filters that are not the defaults, for the badge on the Filters chip (subject has its own chips). */
    val activeCount: Int
        get() = listOf(
            classGroupId != null,
            progress != null,
            yearChoice != YEAR_CURRENT,
        ).count { it }

    val isDefault: Boolean get() = this == TestsFilter()
}

fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** First to last day of the academic year that starts in [startMonth] of [startYear]. */
fun academicYearRange(startYear: Int, startMonth: Int): DateRange {
    val start = LocalDate(startYear, startMonth, 1)
    val nextStart = LocalDate(startYear + 1, startMonth, 1)
    return DateRange(start, LocalDate.fromEpochDays(nextStart.toEpochDays() - 1))
}

/** "2026–27" */
fun academicYearLabel(startYear: Int): String =
    "$startYear–${((startYear + 1) % 100).toString().padStart(2, '0')}"

/** The inclusive bounds a filter selects; either end can be open. */
fun filterBounds(filter: TestsFilter, today: LocalDate, academicYearStartMonth: Int): Pair<LocalDate?, LocalDate?> =
    when (filter.yearChoice) {
        YEAR_ALL -> null to null
        YEAR_CUSTOM -> filter.from to filter.to
        YEAR_CURRENT -> academicYearRange(academicYearStartYear(today, academicYearStartMonth), academicYearStartMonth)
            .let { it.start to it.end }
        else -> academicYearRange(filter.yearChoice, academicYearStartMonth).let { it.start to it.end }
    }

/** Years offered by the filter sheet: three either side of the current one, plus any year that holds a Test. */
fun academicYearChoices(today: LocalDate, academicYearStartMonth: Int, testDates: List<LocalDate>): List<Int> {
    val current = academicYearStartYear(today, academicYearStartMonth)
    val years = ((current - 3)..(current + 3)).toMutableSet()
    testDates.mapTo(years) { academicYearStartYear(it, academicYearStartMonth) }
    return years.sortedDescending()
}

/**
 * The Tests that pass [filter]. With [ignoreSubject] the Subject filter is skipped, which gives the per-Subject
 * counts shown on the Subject chips.
 */
fun filterTests(
    tests: List<TestWithEntries>,
    filter: TestsFilter,
    today: LocalDate,
    academicYearStartMonth: Int,
    subjectNames: Map<Long, String> = emptyMap(),
    ignoreSubject: Boolean = false,
): List<TestWithEntries> {
    val (start, end) = filterBounds(filter, today, academicYearStartMonth)
    val query = filter.query.trim().lowercase()
    return tests.filter { item ->
        val test = item.test
        (start == null || test.date >= start) &&
            (end == null || test.date <= end) &&
            (filter.classGroupId == null || test.classGroupId == filter.classGroupId) &&
            (ignoreSubject || filter.subjectId == null || test.subjectId == filter.subjectId) &&
            (filter.progress == null || item.summary.progress == filter.progress) &&
            (
                query.isEmpty() ||
                    test.name.lowercase().contains(query) ||
                    subjectNames[test.subjectId]?.lowercase()?.contains(query) == true
                )
    }
}

/** "7 · A", or "Class 7 · Section A" when [long]. */
fun classLabel(group: ClassGroup?, long: Boolean = false): String = when {
    group == null -> "Class"
    long -> "Class ${group.className} · Section ${group.section}"
    else -> "${group.className} · ${group.section}"
}

/** "Aug 3, 2026" */
fun shortDateLabel(date: LocalDate): String = "${shortMonthName(date.month.number)} ${date.day}, ${date.year}"

fun progressLabel(progress: TestProgress): String = when (progress) {
    TestProgress.NOT_STARTED -> "Not started"
    TestProgress.IN_PROGRESS -> "In progress"
    TestProgress.COMPLETE -> "Complete"
}

fun pluralTests(count: Int): String = if (count == 1) "1 test" else "$count tests"

/**
 * The period a filter shows, for the list header: the academic year ("2026–27"), "All time", or the custom
 * range ("Aug 3, 2026 – Sep 1, 2026", "From Aug 3, 2026", "Until Sep 1, 2026").
 */
fun testsRangeLabel(filter: TestsFilter, today: LocalDate, academicYearStartMonth: Int): String =
    when (filter.yearChoice) {
        YEAR_ALL -> "All time"
        YEAR_CUSTOM -> {
            val from = filter.from
            val to = filter.to
            when {
                from != null && to != null -> "${shortDateLabel(from)} – ${shortDateLabel(to)}"
                from != null -> "From ${shortDateLabel(from)}"
                to != null -> "Until ${shortDateLabel(to)}"
                else -> "All time"
            }
        }
        YEAR_CURRENT -> academicYearLabel(academicYearStartYear(today, academicYearStartMonth))
        else -> academicYearLabel(filter.yearChoice)
    }

/** The Tests list header, as the web app: "3 Tests · 2026–27". */
fun testsListHeader(count: Int, rangeLabel: String): String =
    "$count Test${if (count == 1) "" else "s"} · $rangeLabel"

/** A Test average given in marks (as `TestSummary.average` is) as a percentage of Total Marks in hundredths. */
fun averagePercent(averageMarks: Double?, totalMarks: Int): Double? =
    if (averageMarks == null || totalMarks <= 0) null else averageMarks * 10_000.0 / totalMarks

/** "81.3%" */
fun formatPercent(percent: Double): String = String.format(Locale.ROOT, "%.1f%%", percent)

/** Marks given in mark units (statistics) as the shortest exact decimal, e.g. 32.5 or 40. */
fun formatMarkValue(marks: Double): String = formatMarks((marks * 100).roundToInt())

/** One row of the Tests list, ready to draw. */
data class TestListItem(
    val id: Long,
    val title: String,
    val meta: String,
    val totalMarksLabel: String,
    val progress: TestProgress,
    val entered: Int,
    val total: Int,
    val averageLabel: String?,
)

fun buildTestListItem(item: TestWithEntries, subjectName: String?, group: ClassGroup?): TestListItem {
    val test = item.test
    val summary = item.summary
    return TestListItem(
        id = test.id,
        title = test.name,
        meta = "${subjectName ?: "Subject"} · ${classLabel(group)} · ${shortDateLabel(test.date)}",
        totalMarksLabel = "${formatMarks(test.totalMarks)} marks",
        progress = summary.progress,
        // As the web app: "entered" counts students with marks; Absent is neither entered nor pending here.
        entered = summary.numericCount,
        total = summary.numericCount + summary.absentCount + summary.notEnteredCount,
        averageLabel = averagePercent(summary.average, test.totalMarks)?.let { "Avg ${formatPercent(it)}" },
    )
}

// ---- Test form ---------------------------------------------------------------------------------------

data class TestFormFields(
    val name: String = "",
    val subjectId: Long? = null,
    val classGroupId: Long? = null,
    val date: LocalDate? = null,
    val totalMarksText: String = "",
)

data class TestFormErrors(
    val name: String? = null,
    val subject: String? = null,
    val classGroup: String? = null,
    val date: String? = null,
    val totalMarks: String? = null,
) {
    val any: Boolean get() = listOf(name, subject, classGroup, date, totalMarks).any { it != null }
}

/** [input] is non-null exactly when [errors] has none. */
data class TestFormValidation(val input: TestInput?, val errors: TestFormErrors)

/**
 * Validates the Test form before it is sent to the repository, so each field shows its own message. The
 * repository still re-validates (duplicates, locks) and its message is shown on the form. [highestRecordedMarks]
 * is the floor for Total Marks when editing a Test that has results (hundredths, 0 for none).
 */
fun validateTestForm(fields: TestFormFields, highestRecordedMarks: Int = 0): TestFormValidation {
    val total = parseMarks(fields.totalMarksText)
    val errors = TestFormErrors(
        name = if (fields.name.isBlank()) "Enter a test name." else null,
        subject = if (fields.subjectId == null) "Select a subject." else null,
        classGroup = if (fields.classGroupId == null) "Select a class." else null,
        date = if (fields.date == null) "Choose the test date." else null,
        totalMarks = when {
            total == null || total <= 0 -> "Enter Total Marks above zero, with up to two decimals."
            total < highestRecordedMarks ->
                "Total Marks cannot be below recorded Marks (${formatMarks(highestRecordedMarks)})."
            else -> null
        },
    )
    if (errors.any) return TestFormValidation(null, errors)
    return TestFormValidation(
        TestInput(
            name = fields.name.trim(),
            subjectId = fields.subjectId,
            classGroupId = fields.classGroupId,
            date = fields.date,
            totalMarks = total ?: 0,
        ),
        errors,
    )
}

/** The Subjects a Test form can pick: the active ones, plus [currentSubjectId] even if it is archived. */
fun selectableSubjects(subjects: List<Subject>, currentSubjectId: Long? = null): List<Subject> =
    subjects.filter { !it.isArchived || it.id == currentSubjectId }

/** Counts of [tests] per Subject id, for the Subjects manager. */
fun testCountBySubject(tests: List<TestWithEntries>): Map<Long, Int> =
    tests.groupingBy { it.test.subjectId }.eachCount()

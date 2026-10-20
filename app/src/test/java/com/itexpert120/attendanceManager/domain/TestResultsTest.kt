package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus.ABSENT
import com.itexpert120.attendanceManager.model.TestResultStatus.MARKS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestResultsTest {
    private fun test(id: Long, totalMarks: Int, subjectId: Long = 1) = TestRecord(
        id = id,
        name = "Test $id",
        normalizedName = "test $id",
        subjectId = subjectId,
        classGroupId = 1,
        date = date("2026-08-15"),
        totalMarks = totalMarks,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    private fun subject(id: Long, archived: Boolean = false) = Subject(
        id = id,
        name = "Subject $id",
        normalizedName = "subject $id",
        archivedAt = if (archived) EPOCH else null,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    @Test
    fun `distinguishes numeric Marks, Absent, and Not Entered in one summary`() {
        val summary = testSummary(
            listOf(
                rosterEntry(1, 1, MARKS, marks = 3250),
                rosterEntry(1, 2, MARKS, marks = 0),
                rosterEntry(1, 3, ABSENT),
                rosterEntry(1, 4),
            ),
        )
        assertEquals(
            TestSummary(
                progress = TestProgress.IN_PROGRESS,
                numericCount = 2,
                absentCount = 1,
                notEnteredCount = 1,
                average = 16.25,
                highest = 32.5,
                lowest = 0.0,
            ),
            summary,
        )
    }

    @Test
    fun `derives Test Progress from how many results are recorded`() {
        assertEquals(TestProgress.NOT_STARTED, testSummary(listOf(rosterEntry(1, 1), rosterEntry(1, 2))).progress)
        assertEquals(TestProgress.COMPLETE, testSummary(listOf(rosterEntry(1, 1, ABSENT), rosterEntry(1, 2, MARKS, 100))).progress)
        assertEquals(TestProgress.NOT_STARTED, testSummary(emptyList()).progress)
    }

    @Test
    fun `an empty or all-absent summary has no average`() {
        val summary = testSummary(listOf(rosterEntry(1, 1, ABSENT)))
        assertNull(summary.average)
        assertNull(summary.highest)
        assertNull(summary.lowest)
    }

    @Test
    fun `calculates a Test Result percentage from Marks and Total Marks`() {
        assertEquals(87.5, marksPercentage(1750, 2000), 0.0)
        assertEquals(0.0, marksPercentage(100, 0), 0.0)
    }

    @Test
    fun `counts filtered Tests overall and includes zero-count active Subjects`() {
        val counts = testCountsBySubject(
            subjects = listOf(subject(1), subject(2), subject(3, archived = true)),
            tests = listOf(test(1, 4000, subjectId = 1), test(2, 4000, subjectId = 1), test(3, 4000, subjectId = 3)),
        )
        assertEquals(
            TestCounts(total = 3, bySubject = listOf(SubjectTestCount(1, 2), SubjectTestCount(2, 0))),
            counts,
        )
    }

    @Test
    fun `aggregates normalized Test Results across different Total Marks`() {
        val aggregate = testReportAggregate(
            tests = listOf(test(1, 4000), test(2, 2000)),
            entries = listOf(
                rosterEntry(1, 1, MARKS, 2000),
                rosterEntry(1, 2, ABSENT),
                rosterEntry(2, 1, MARKS, 2000),
                rosterEntry(2, 2),
                rosterEntry(99, 1, MARKS, 500), // belongs to a Test outside the report
            ),
        )
        assertEquals(
            TestAggregate(totalTests = 2, numericCount = 2, absentCount = 1, notEnteredCount = 1, averagePercentage = 75.0),
            aggregate,
        )
    }

    @Test
    fun `uses calendar day, Monday-to-Sunday week, and calendar month report periods`() {
        val daily = subjectReportPeriod(ReportPeriodType.DAILY, date("2026-09-02"))
        assertEquals(DateRange(date("2026-09-02"), date("2026-09-02")), daily.range)
        assertEquals("Wednesday, 2 September 2026", daily.label)

        val weekly = subjectReportPeriod(ReportPeriodType.WEEKLY, date("2026-09-02"))
        assertEquals(DateRange(date("2026-08-31"), date("2026-09-06")), weekly.range)
        assertEquals("31 Aug – 6 Sep 2026", weekly.label)

        val monthly = subjectReportPeriod(ReportPeriodType.MONTHLY, date("2026-09-02"))
        assertEquals(DateRange(date("2026-09-01"), date("2026-09-30")), monthly.range)
        assertEquals("September 2026", monthly.label)
    }

    @Test
    fun `returns an empty report period when the anchor date is cleared`() {
        val period = subjectReportPeriod(ReportPeriodType.DAILY, null)
        assertNull(period.range)
        assertEquals("Choose a report date", period.label)
    }

    @Test
    fun `parses marks to hundredths and rejects more than two decimals`() {
        assertEquals(3250, parseMarks("32.5"))
        assertEquals(507, parseMarks("5.07"))
        assertEquals(1012, parseMarks(" 10.12 "))
        assertEquals(0, parseMarks("0"))
        assertEquals(50, parseMarks(".5"))
        assertEquals(4000, parseMarks("40"))
        assertNull(parseMarks("5.075"))
        assertNull(parseMarks("-1"))
        assertNull(parseMarks("abc"))
        assertNull(parseMarks(""))
        assertNull(parseMarks("1e3"))
        assertNull(parseMarks("99999999999"))
    }

    @Test
    fun `formats marks as the shortest exact decimal`() {
        assertEquals("32.5", formatMarks(3250))
        assertEquals("5.07", formatMarks(507))
        assertEquals("40", formatMarks(4000))
        assertEquals("0", formatMarks(0))
        assertEquals("0.05", formatMarks(5))
    }
}

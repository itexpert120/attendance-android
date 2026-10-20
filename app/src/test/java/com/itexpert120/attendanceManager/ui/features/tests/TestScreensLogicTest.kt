package com.itexpert120.attendanceManager.ui.features.tests

import com.itexpert120.attendanceManager.data.repo.TestResultInput
import com.itexpert120.attendanceManager.data.repo.TestWithEntries
import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.date
import com.itexpert120.attendanceManager.domain.rosterEntry
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestResultStatus.ABSENT
import com.itexpert120.attendanceManager.model.TestResultStatus.MARKS
import com.itexpert120.attendanceManager.model.TestResultStatus.NOT_ENTERED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestScreensLogicTest {
    private fun student(id: Long, name: String) = Student(id = id, admissionNumber = "A$id", name = name, createdAt = EPOCH)

    // ---- Rapid entry -----------------------------------------------------------------------------

    @Test
    fun sanitizeKeepsOneDecimalMarkAndTwoDecimals() {
        assertEquals("32.55", sanitizeMarksInput("32,555"))
        assertEquals("12.53", sanitizeMarksInput("1a2.5.3"))
        assertEquals("", sanitizeMarksInput("-x"))
    }

    @Test
    fun blankClearsZeroSavesAndAboveTotalIsInvalid() {
        assertEquals(FieldOutcome.Clear, evaluateMarksField("  ", 4000))
        assertEquals(FieldOutcome.Save(0), evaluateMarksField("0", 4000))
        assertEquals(FieldOutcome.Save(3250), evaluateMarksField("32.5", 4000))
        assertTrue(evaluateMarksField("40.01", 4000) is FieldOutcome.Invalid)
    }

    @Test
    fun writesOnlyWhenTheStoredResultDiffers() {
        val marked = rosterEntry(1, 1, MARKS, 3250)
        assertNull(writeFor(FieldOutcome.Save(3250), marked))
        assertEquals(Write(TestResultInput.Marks(3000)), writeFor(FieldOutcome.Save(3000), marked))
        assertEquals(Write(null), writeFor(FieldOutcome.Clear, marked))
        // A blank, disabled field of an Absent student must not wipe the absence.
        assertNull(writeFor(FieldOutcome.Clear, rosterEntry(1, 2, ABSENT)))
        assertNull(writeFor(FieldOutcome.Invalid("x"), marked))
    }

    @Test
    fun absentToggleSetsAndClears() {
        assertEquals(Write(TestResultInput.Absent), toggleAbsent(rosterEntry(1, 1)))
        assertEquals(Write(null), toggleAbsent(rosterEntry(1, 1, ABSENT)))
    }

    @Test
    fun nextSkipsAbsentStudentsAndStopsAtTheEnd() {
        val statuses = listOf(MARKS, ABSENT, NOT_ENTERED, ABSENT)
        assertEquals(2, nextEntryIndex(statuses, 0))
        assertNull(nextEntryIndex(statuses, 2))
    }

    @Test
    fun nextPendingWrapsAround() {
        val statuses: List<TestResultStatus> = listOf(NOT_ENTERED, MARKS, NOT_ENTERED)
        assertEquals(0, nextPendingIndex(statuses))
        assertEquals(2, nextPendingIndex(statuses, 0))
        assertEquals(0, nextPendingIndex(statuses, 2))
        assertNull(nextPendingIndex(listOf(MARKS, ABSENT)))
    }

    // ---- Reports ---------------------------------------------------------------------------------

    @Test
    fun subjectReportKeepsAllClassesButStudentReportNeedsOne() {
        assertNull(reportClassId(ReportMode.SUBJECT, null, listOf(1, 2)))
        assertEquals(2L, reportClassId(ReportMode.SUBJECT, 2, listOf(1, 2)))
        assertNull(reportClassId(ReportMode.SUBJECT, 9, listOf(1, 2)))
        assertEquals(1L, reportClassId(ReportMode.STUDENT, null, listOf(1, 2)))
        assertEquals(1L, reportClassId(ReportMode.STUDENT, 9, listOf(1, 2)))
        assertNull(reportClassId(ReportMode.STUDENT, null, emptyList()))
    }

    @Test
    fun studentReportWaitsForStudentsThenPicksTheFirst() {
        val request = ReportRequest(mode = ReportMode.STUDENT, anchor = date("2026-09-15"), studentId = 7)
        val loading = resolveReportRequest(request, listOf(1), null)
        assertEquals(1L, loading.classGroupId)
        assertNull(loading.studentId)
        assertNull(loading.toFilter())

        val students = studentChoices(listOf(student(5, "Zara"), student(3, "Ali"), student(3, "Ali")))
        assertEquals(listOf(3L, 5L), students.map { it.id })
        val ready = resolveReportRequest(request, listOf(1), students)
        assertEquals(3L, ready.studentId)
        val filter = ready.toFilter()
        assertEquals(3L, filter?.studentId)
        assertEquals(date("2026-09-01"), filter?.range?.start)
        assertEquals(date("2026-09-30"), filter?.range?.end)
    }

    @Test
    fun periodStepperMovesByThePeriodType() {
        val anchor = date("2026-01-31")
        assertEquals(date("2026-02-01"), shiftAnchor(ReportPeriodType.DAILY, anchor, 1))
        assertEquals(date("2026-01-24"), shiftAnchor(ReportPeriodType.WEEKLY, anchor, -1))
        assertEquals(date("2026-02-28"), shiftAnchor(ReportPeriodType.MONTHLY, anchor, 1))
    }
    // ---- Tests list ------------------------------------------------------------------------------

    @Test
    fun listCountsOnlyStudentsWithMarksAsEntered() {
        val test = TestRecord(1, "Midterm", "midterm", 1, 1, date("2026-09-20"), 4000, EPOCH, EPOCH)
        val entries = listOf(rosterEntry(1, 1, MARKS, 3000), rosterEntry(1, 2, ABSENT), rosterEntry(1, 3))
        val item = buildTestListItem(TestWithEntries(test, entries), "Maths", null)
        assertEquals(1, item.entered)
        assertEquals(3, item.total)
    }

    @Test
    fun listHeaderNamesTheSelectedPeriod() {
        val today = date("2026-10-07")
        assertEquals("2026–27", testsRangeLabel(TestsFilter(), today, 4))
        assertEquals("2025–26", testsRangeLabel(TestsFilter(yearChoice = 2025), today, 4))
        assertEquals("All time", testsRangeLabel(TestsFilter(yearChoice = YEAR_ALL), today, 4))
        assertEquals(
            "Aug 3, 2026 – Sep 1, 2026",
            testsRangeLabel(TestsFilter(yearChoice = YEAR_CUSTOM, from = date("2026-08-03"), to = date("2026-09-01")), today, 4),
        )
        assertEquals("From Aug 3, 2026", testsRangeLabel(TestsFilter(yearChoice = YEAR_CUSTOM, from = date("2026-08-03")), today, 4))
        assertEquals("1 Test · 2026–27", testsListHeader(1, "2026–27"))
        assertEquals("3 Tests · All time", testsListHeader(3, "All time"))
    }

    @Test
    fun compactRowShowsResultThenAdmissionNumber() {
        assertEquals("75.0% · A-7", entryRowDetail(rosterEntry(1, 1, MARKS, 3000), 4000, "A-7"))
        assertEquals("Absent · A-7", entryRowDetail(rosterEntry(1, 1, ABSENT), 4000, "A-7"))
        assertEquals("Not entered · A-7", entryRowDetail(rosterEntry(1, 1), 4000, "A-7"))
    }
}

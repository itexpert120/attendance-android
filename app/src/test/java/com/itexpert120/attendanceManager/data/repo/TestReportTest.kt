package com.itexpert120.attendanceManager.data.repo

import com.itexpert120.attendanceManager.domain.DateRange
import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.date
import com.itexpert120.attendanceManager.domain.rosterEntry
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus.ABSENT
import com.itexpert120.attendanceManager.model.TestResultStatus.MARKS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestReportTest {
    private fun subject(id: Long, name: String, archived: Boolean = false) = Subject(
        id = id,
        name = name,
        normalizedName = name.lowercase(),
        archivedAt = if (archived) EPOCH else null,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    private fun test(id: Long, subjectId: Long, classGroupId: Long, on: String, totalMarks: Int = 1000) = TestRecord(
        id = id,
        name = "Test $id",
        normalizedName = "test $id",
        subjectId = subjectId,
        classGroupId = classGroupId,
        date = date(on),
        totalMarks = totalMarks,
        createdAt = EPOCH,
        updatedAt = EPOCH,
    )

    private val maths = subject(1, "Maths")
    private val art = subject(2, "Art", archived = true)
    private val science = subject(3, "Science")
    private val subjects = listOf(art, science, maths)
    private val august = DateRange(date("2026-08-01"), date("2026-08-31"))

    @Test
    fun `a Subject Report includes Tests in the period for the chosen Subject and class`() {
        val tests = listOf(
            test(1, maths.id, 1, "2026-08-10"),
            test(2, maths.id, 2, "2026-08-11"),
            test(3, science.id, 1, "2026-08-12"),
            test(4, maths.id, 1, "2026-09-01"),
        )

        val everything = buildTestReport(TestReportFilter(august), tests, emptyList(), subjects)
        assertEquals(listOf(1L, 2L, 3L), everything.tests.map { it.id })
        assertEquals(3, everything.aggregate.totalTests)

        val mathsInClassOne = buildTestReport(
            TestReportFilter(august, subjectId = maths.id, classGroupId = 1),
            tests, emptyList(), subjects,
        )
        assertEquals(listOf(1L), mathsInClassOne.tests.map { it.id })
    }

    @Test
    fun `tests are grouped under their Subjects in display order with archived Subjects last`() {
        val tests = listOf(
            test(1, art.id, 1, "2026-08-10"),
            test(2, maths.id, 1, "2026-08-10"),
            test(3, science.id, 1, "2026-08-10"),
            test(4, maths.id, 1, "2026-08-11"),
        )

        val report = buildTestReport(TestReportFilter(august), tests, emptyList(), subjects)

        assertEquals(listOf("Maths", "Science", "Art"), report.sections.map { it.subject.name })
        assertEquals(listOf(2L, 4L), report.sections[0].tests.map { it.id })
    }

    @Test
    fun `the aggregate normalises each result to a percentage of its own Test`() {
        val tests = listOf(test(1, maths.id, 1, "2026-08-10", 5000), test(2, maths.id, 1, "2026-08-11", 1000))
        val entries = listOf(
            rosterEntry(1, 1, MARKS, 2500), // 50%
            rosterEntry(1, 2, ABSENT),
            rosterEntry(2, 1, MARKS, 900), // 90%
            rosterEntry(2, 2),
            rosterEntry(99, 1, MARKS, 1), // belongs to a Test outside the report
        )

        val report = buildTestReport(TestReportFilter(august), tests, entries, subjects)

        assertEquals(2, report.aggregate.numericCount)
        assertEquals(1, report.aggregate.absentCount)
        assertEquals(1, report.aggregate.notEnteredCount)
        assertEquals(70.0, report.aggregate.averagePercentage!!, 0.0001)
        assertEquals(4, report.entries.size)
        assertEquals(1, report.summaryFor(1).numericCount)
    }

    @Test
    fun `a Student Report keeps only Tests the student sat and only their own entries`() {
        val tests = listOf(
            test(1, maths.id, 1, "2026-08-10"),
            test(2, maths.id, 1, "2026-08-11"),
            test(3, maths.id, 2, "2026-08-12"),
        )
        val entries = listOf(
            rosterEntry(1, 10, MARKS, 800),
            rosterEntry(1, 11, MARKS, 200),
            rosterEntry(2, 11, ABSENT), // enrollment 10 was not on this Test's roster
            rosterEntry(3, 10, MARKS, 500), // a different class group
        )

        val report = buildTestReport(
            TestReportFilter(august, classGroupId = 1, studentId = 5),
            tests, entries, subjects,
            studentEnrollmentIds = setOf(10L),
        )

        assertEquals(listOf(1L), report.tests.map { it.id })
        assertEquals(listOf(10L), report.entries.map { it.enrollmentId })
        assertEquals(800, report.studentEntryFor(1)!!.marks)
        assertNull(report.studentEntryFor(2))
        assertEquals(80.0, report.aggregate.averagePercentage!!, 0.0001)
    }

    @Test
    fun `a Student Report without any matching enrollment is empty`() {
        val tests = listOf(test(1, maths.id, 1, "2026-08-10"))
        val entries = listOf(rosterEntry(1, 10, MARKS, 800))

        val report = buildTestReport(TestReportFilter(august, classGroupId = 1, studentId = 5), tests, entries, subjects)

        assertEquals(emptyList<TestRecord>(), report.tests)
        assertNull(report.aggregate.averagePercentage)
    }

    @Test
    fun `Subjects sort active first then by natural name order`() {
        val list = listOf(
            subject(1, "Maths 10"),
            subject(2, "art", archived = true),
            subject(3, "Maths 2"),
            subject(4, "Biology"),
        )

        assertEquals(listOf("Biology", "Maths 2", "Maths 10", "art"), list.sortedForDisplay().map { it.name })
    }
}

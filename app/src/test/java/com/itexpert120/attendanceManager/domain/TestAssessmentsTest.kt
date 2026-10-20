package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.data.repo.normalizeTeacherName
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.SubjectTeacher
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus.ABSENT
import com.itexpert120.attendanceManager.model.TestResultStatus.MARKS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TestAssessmentsTest {
    private fun subject(id: Long, name: String, archived: Boolean = false) =
        Subject(id = id, name = name, normalizedName = name.lowercase(), archivedAt = if (archived) EPOCH else null, createdAt = EPOCH, updatedAt = EPOCH)

    private fun test(id: Long, subjectId: Long, classGroupId: Long, on: String = "2026-09-10", totalMarks: Int = 10_000) = TestRecord(
        id = id, name = "Test $id", normalizedName = "test $id", subjectId = subjectId, classGroupId = classGroupId,
        date = date(on), totalMarks = totalMarks, createdAt = EPOCH, updatedAt = EPOCH,
    )

    private val maths = subject(1, "Maths")
    private val english = subject(2, "English")
    private val art = subject(3, "Art", archived = true)
    private val seven = ClassGroup(id = 1, className = "7", section = "A", createdAt = EPOCH)
    private val eight = ClassGroup(id = 2, className = "8", section = "A", createdAt = EPOCH)

    // Class 7: students 1 (Ali), 2 (Bina), 3 (Chand); class 8: student 4.
    private val enrollments = listOf(
        row(enrollment(1, "2026-04-01"), "Ali"),
        row(enrollment(2, "2026-04-01"), "Bina"),
        row(enrollment(3, "2026-04-01"), "Chand"),
        row(enrollment(4, "2026-04-01", classGroupId = 2), "Dua"),
    )

    // Maths test 1 out of 100, English test 2 out of 50, Maths test 3 out of 100; class 8: test 4.
    private val tests = listOf(
        test(1, maths.id, 1),
        test(2, english.id, 1, totalMarks = 5_000),
        test(3, maths.id, 1, on = "2026-09-20"),
        test(4, maths.id, 2),
    )
    private val entries = listOf(
        rosterEntry(1, 1, MARKS, 9_000), rosterEntry(1, 2, MARKS, 3_000), rosterEntry(1, 3, ABSENT),
        rosterEntry(2, 1, MARKS, 4_000), rosterEntry(2, 2, MARKS, 1_500), rosterEntry(2, 3),
        rosterEntry(3, 1, ABSENT), rosterEntry(3, 2, MARKS, 6_000), rosterEntry(3, 3, MARKS, 9_000),
        rosterEntry(4, 4, MARKS, 5_000),
    )

    private fun data(teachers: List<SubjectTeacher> = emptyList()) = AssessmentData(
        range = DateRange(date("2026-09-01"), date("2026-09-30")),
        tests = tests,
        entries = entries,
        enrollments = enrollments,
        subjects = listOf(art, maths, english),
        classes = listOf(eight, seven),
        teachers = teachers,
    )

    @Test
    fun `result stats average per-result percentages and count passes at forty percent`() {
        val stats = resultStats(tests.filter { it.classGroupId == 1L }, entries)
        // Percentages: 90, 30, 80, 30, 60, 90.
        assertEquals(6, stats.results)
        assertEquals(2, stats.absent)
        assertEquals(1, stats.notEntered)
        assertEquals(8, stats.entered)
        assertEquals(63.333, stats.averagePercent!!, 0.001)
        assertEquals(4 * 100.0 / 6, stats.passRate!!, 0.001)
        assertEquals(90.0, stats.highestPercent!!, 0.0)
        assertEquals(30.0, stats.lowestPercent!!, 0.0)
        assertEquals(true, isPass(40.0))
        assertEquals(false, isPass(39.99))
        assertNull(resultStats(emptyList(), emptyList()).averagePercent)
    }

    @Test
    fun `report card adds up obtained over possible per subject and ranks the student`() {
        val card = studentReportCard(data(), classGroupId = 1, studentId = 1)
        assertEquals("Ali", card.student?.name)
        // Subjects in display order: English before Maths.
        assertEquals(listOf(english.id, maths.id), card.subjects.map { it.subjectId })
        val mathsLine = card.subjects.single { it.subjectId == maths.id }
        assertEquals(2, mathsLine.tests.size)
        assertEquals(1, mathsLine.absent)
        assertEquals(9_000, mathsLine.obtained)
        assertEquals(10_000, mathsLine.possible)
        assertEquals(90.0, mathsLine.percent!!, 0.0)
        // Overall: (90 + 40) / (100 + 50).
        assertEquals(13_000, card.obtained)
        assertEquals(15_000, card.possible)
        assertEquals(86.667, card.percent!!, 0.001)
        assertEquals(3, card.tests)
        assertEquals(1, card.absent)
        // Ali 86.7%, Chand 90/100 = 90%, Bina 105/250 = 42%.
        assertEquals(2, card.rank)
        assertEquals(3, card.rankedCount)
    }

    @Test
    fun `standings rank by overall percentage with ties sharing a rank`() {
        val standings = classStandings(data(), 1)
        assertEquals(listOf("Chand", "Ali", "Bina"), standings.map { it.student.name })
        assertEquals(listOf(1, 2, 3), standings.map { it.rank })

        val exact = data().copy(
            tests = listOf(test(1, maths.id, 1)),
            entries = listOf(rosterEntry(1, 1, MARKS, 5_000), rosterEntry(1, 2, MARKS, 5_000), rosterEntry(1, 3, MARKS, 2_000)),
        )
        assertEquals(listOf(1, 1, 3), classStandings(exact, 1).map { it.rank })
    }

    @Test
    fun `class assessment lists subjects, top students and those below the pass mark`() {
        val assessment = classAssessment(data(), 1)
        assertEquals(listOf(english.id, maths.id), assessment.subjects.map { it.subjectId })
        val englishStats = assessment.subjects.first().stats
        assertEquals(1, englishStats.tests)
        assertEquals(55.0, englishStats.averagePercent!!, 0.001) // 80% and 30%
        assertEquals(50.0, englishStats.passRate!!, 0.001)
        assertEquals(3, assessment.overall.tests)
        assertEquals(3, assessment.topStudents.size)
        assertEquals(emptyList<String>(), assessment.needsAttention.map { it.student.name })

        val weak = data().copy(entries = entries.map { if (it.enrollmentId == 2L && it.result == MARKS) it.copy(marks = 500) else it })
        assertEquals(listOf("Bina"), classAssessment(weak, 1).needsAttention.map { it.student.name })
    }

    @Test
    fun `teacher performance groups by assigned teacher and keeps unassigned subjects last`() {
        val teachers = listOf(
            SubjectTeacher(1, maths.id, "Ms Khan"),
            SubjectTeacher(2, maths.id, "ms khan "),
            SubjectTeacher(2, english.id, "Mr Ali"),
            SubjectTeacher(1, art.id, "Mr Archive"),
        )
        val report = teacherPerformance(data(teachers))
        assertEquals(listOf("Mr Ali", "Ms Khan", null), report.map { it.teacherName })

        val ali = report[0]
        // Assigned but untested: shown with zero tests.
        assertEquals(0, ali.overall.tests)
        assertEquals(listOf(2L to english.id), ali.rows.map { it.classGroupId to it.subjectId })

        val khan = report[1]
        // Class 7 before class 8.
        assertEquals(listOf(1L, 2L), khan.rows.map { it.classGroupId })
        assertEquals(2, khan.rows[0].stats.tests)
        assertEquals(3, khan.overall.tests)

        val unassigned = report[2]
        assertEquals(listOf(1L to english.id), unassigned.rows.map { it.classGroupId to it.subjectId })

        val classEight = teacherPerformance(data(teachers), classGroupId = 2)
        // Names match case-insensitively; a group shows the spelling of its first assignment.
        assertEquals(listOf("mr ali", "ms khan"), classEight.map { it.teacherName?.lowercase() })
    }

    @Test
    fun `custom range labels`() {
        assertEquals("1 Sep – 15 Oct 2026", dateRangeLabel(date("2026-09-01"), date("2026-10-15")))
        assertEquals("20 Dec 2025 – 5 Jan 2026", dateRangeLabel(date("2026-01-05"), date("2025-12-20")))
        assertEquals("3 Sep 2026", dateRangeLabel(date("2026-09-03"), date("2026-09-03")))
    }

    @Test
    fun `teacher names are trimmed and limited in length`() {
        assertEquals("Ms Khan", normalizeTeacherName("  Ms   Khan "))
        assertEquals("", normalizeTeacherName("   "))
        assertThrows(ValidationException::class.java) { normalizeTeacherName("x".repeat(61)) }
    }
}

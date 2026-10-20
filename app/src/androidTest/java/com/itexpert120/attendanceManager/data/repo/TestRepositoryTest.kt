package com.itexpert120.attendanceManager.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.data.db.addClass
import com.itexpert120.attendanceManager.data.db.addStudent
import com.itexpert120.attendanceManager.data.db.enroll
import com.itexpert120.attendanceManager.domain.DateRange
import com.itexpert120.attendanceManager.domain.TestProgress
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.domain.parseMarks
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.support.MutableClock
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
class TestRepositoryTest {
    private lateinit var db: AttendanceDatabase
    private val clock = MutableClock()
    private lateinit var subjects: SubjectRepository
    private lateinit var repo: TestRepository

    private val testDate = LocalDate.parse("2026-08-14")

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        subjects = SubjectRepository(db, clock)
        repo = TestRepository(db, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun assertRejected(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected a ValidationException")
        } catch (e: ValidationException) {
            assertEquals(message, e.message)
        }
    }

    /** A class with one enrolled student (roll "1"); returns the class id and that enrollment's id. */
    private suspend fun classWithStudent(section: String = "A"): Pair<Long, Long> {
        val classId = db.addClass("7", section)
        val enrollmentId = db.enroll(db.addStudent("A-$section-1"), classId, roll = "1")
        return classId to enrollmentId
    }

    private fun input(
        subjectId: Long,
        classGroupId: Long,
        name: String = "Midterm",
        date: LocalDate = testDate,
        totalMarks: Int = 4000,
    ) = TestInput(name, subjectId, classGroupId, date, totalMarks)

    private suspend fun entry(testId: Long, enrollmentId: Long) = db.tests().getRosterEntry(testId, enrollmentId)!!

    @Test
    fun createTestSnapshotsOnlyEnrollmentsActiveOnTheTestDate() = runTest {
        val (classId, active) = classWithStudent()
        val struckOffBefore = db.enroll(db.addStudent("S-1"), classId, roll = "2", struckOffOn = "2026-08-13")
        val struckOffOnTheDay = db.enroll(db.addStudent("S-2"), classId, roll = "3", struckOffOn = "2026-08-14")
        val admittedAfter = db.enroll(db.addStudent("S-3"), classId, roll = "4", admittedOn = "2026-08-15")
        val admittedOnTheDay = db.enroll(db.addStudent("S-4"), classId, roll = "5", admittedOn = "2026-08-14")
        val otherClass = db.addClass("7", "B")
        db.enroll(db.addStudent("S-5"), otherClass, roll = "1")
        val maths = subjects.saveSubject("Mathematics")

        val test = repo.createTest(input(maths.id, classId, name = "  Midterm "))

        assertEquals("Midterm", test.name)
        assertEquals("midterm", test.normalizedName)
        assertEquals(4000, test.totalMarks)
        assertEquals(test, repo.getTest(test.id))
        val roster = db.tests().getRoster(test.id)
        assertEquals(setOf(active, struckOffOnTheDay, admittedOnTheDay), roster.map { it.enrollmentId }.toSet())
        assertTrue(struckOffBefore !in roster.map { it.enrollmentId } && admittedAfter !in roster.map { it.enrollmentId })
        assertTrue(roster.all { it.result == TestResultStatus.NOT_ENTERED && it.marks == null })
        assertTrue(roster.all { it.createdAt == clock.now() })
    }

    @Test
    fun createTestRejectsInvalidInput() = runTest {
        val (classId, _) = classWithStudent()
        val emptyClass = db.addClass("8", "A")
        val maths = subjects.saveSubject("Mathematics")
        val archived = subjects.saveSubject("Art").also { subjects.archiveSubject(it.id) }
        val required = "Test name, Subject, Class Group, and date are required."

        assertRejected(required) { repo.createTest(input(maths.id, classId, name = "  ")) }
        assertRejected(required) { repo.createTest(TestInput("T", null, classId, testDate, 4000)) }
        assertRejected(required) { repo.createTest(TestInput("T", maths.id, null, testDate, 4000)) }
        assertRejected(required) { repo.createTest(TestInput("T", maths.id, classId, null, 4000)) }
        val totalMessage = "Total Marks must be greater than zero, with up to two decimals."
        assertRejected(totalMessage) { repo.createTest(input(maths.id, classId, totalMarks = 0)) }
        assertRejected(totalMessage) { repo.createTest(input(maths.id, classId, totalMarks = -100)) }
        assertRejected("Select an active Subject.") { repo.createTest(input(archived.id, classId)) }
        assertRejected("Select an active Subject.") { repo.createTest(input(9999, classId)) }
        assertRejected("Select a Class Group.") { repo.createTest(input(maths.id, 9999)) }
        assertRejected("No students are enrolled in this Class Group on the Test date.") {
            repo.createTest(input(maths.id, emptyClass))
        }
        assertRejected("No students are enrolled in this Class Group on the Test date.") {
            repo.createTest(input(maths.id, classId, date = LocalDate.parse("2025-01-01")))
        }
        assertTrue(repo.observeTests().first().isEmpty())

        repo.createTest(input(maths.id, classId))
        assertRejected("That Test already exists for this Class Group and date.") {
            repo.createTest(input(maths.id, classId, name = "MIDTERM"))
        }
        // A different date, name or Subject is a different Test.
        repo.createTest(input(maths.id, classId, name = "Retake"))
        assertEquals(2, repo.observeTests().first().size)
    }

    @Test
    fun aFailedCreateLeavesNothingBehind() = runTest {
        val emptyClass = db.addClass("8", "A")
        val maths = subjects.saveSubject("Mathematics")

        assertRejected("No students are enrolled in this Class Group on the Test date.") {
            repo.createTest(input(maths.id, emptyClass))
        }

        assertTrue(db.tests().observeAll().first().isEmpty())
        assertTrue(db.tests().observeAllRosterEntries().first().isEmpty())
    }

    @Test
    fun aTestResultMovesBetweenMarksAbsentAndNotEntered() = runTest {
        val (classId, enrollmentId) = classWithStudent()
        val maths = subjects.saveSubject("Mathematics")
        val test = repo.createTest(input(maths.id, classId))

        repo.setTestResult(test.id, enrollmentId, TestResultInput.Marks(0))
        entry(test.id, enrollmentId).let {
            assertEquals(TestResultStatus.MARKS, it.result)
            assertEquals(0, it.marks)
            assertEquals(clock.now(), it.resultUpdatedAt)
        }

        clock.now += 1.hours
        repo.setTestResult(test.id, enrollmentId, TestResultInput.Absent)
        entry(test.id, enrollmentId).let {
            assertEquals(TestResultStatus.ABSENT, it.result)
            assertNull(it.marks)
            assertEquals(clock.now(), it.resultUpdatedAt)
        }

        repo.setTestResult(test.id, enrollmentId, null)
        entry(test.id, enrollmentId).let {
            assertEquals(TestResultStatus.NOT_ENTERED, it.result)
            assertNull(it.marks)
            assertNull(it.resultUpdatedAt)
        }
    }

    @Test
    fun marksMustLieWithinZeroAndTheTotalAndMayHaveTwoDecimals() = runTest {
        val (classId, enrollmentId) = classWithStudent()
        val maths = subjects.saveSubject("Mathematics")
        val test = repo.createTest(input(maths.id, classId, totalMarks = parseMarks("10.12")!!))

        assertEquals(1012, test.totalMarks)
        repo.setTestResult(test.id, enrollmentId, TestResultInput.Marks(parseMarks("5.07")!!))
        assertEquals(507, entry(test.id, enrollmentId).marks)

        val message = "Marks must be between 0 and 10.12, with up to two decimals."
        assertRejected(message) { repo.setTestResult(test.id, enrollmentId, TestResultInput.Marks(1013)) }
        assertRejected(message) { repo.setTestResult(test.id, enrollmentId, TestResultInput.Marks(-1)) }
        // The rejected entry did not disturb the stored mark; the maximum itself is allowed.
        assertEquals(507, entry(test.id, enrollmentId).marks)
        repo.setTestResult(test.id, enrollmentId, TestResultInput.Marks(1012))
        assertEquals(1012, entry(test.id, enrollmentId).marks)
    }

    @Test
    fun setTestResultRejectsUnknownTestsAndStudentsOffTheRoster() = runTest {
        val (classId, enrollmentId) = classWithStudent()
        val outsider = db.enroll(db.addStudent("S-9"), classId, roll = "9", admittedOn = "2026-08-20")
        val maths = subjects.saveSubject("Mathematics")
        val test = repo.createTest(input(maths.id, classId))

        assertRejected("Test not found.") { repo.setTestResult(9999, enrollmentId, TestResultInput.Absent) }
        assertRejected("Student is not on this Test Roster.") {
            repo.setTestResult(test.id, outsider, TestResultInput.Absent)
        }
    }

    @Test
    fun refreshTestRosterPicksUpNewEnrollmentsOnlyBeforeResultEntryBegins() = runTest {
        val (classId, first) = classWithStudent()
        val maths = subjects.saveSubject("Mathematics")
        val test = repo.createTest(input(maths.id, classId))
        val second = db.enroll(db.addStudent("S-2"), classId, roll = "2")

        repo.refreshTestRoster(test.id)
        assertEquals(setOf(first, second), db.tests().getRoster(test.id).map { it.enrollmentId }.toSet())

        repo.setTestResult(test.id, first, TestResultInput.Marks(3000))
        assertRejected("Refresh the Test Roster before entering results.") { repo.refreshTestRoster(test.id) }
        assertRejected("Test not found.") { repo.refreshTestRoster(9999) }
        assertEquals(3000, entry(test.id, first).marks)
    }

    @Test
    fun updateTestLocksClassAndDateOnceResultsExistAndProtectsRecordedMarks() = runTest {
        val (classId, enrollmentId) = classWithStudent()
        val maths = subjects.saveSubject("Mathematics")
        val test = repo.createTest(input(maths.id, classId))
        repo.setTestResult(test.id, enrollmentId, TestResultInput.Marks(3000))

        assertRejected("Class Group and date are locked after result entry begins.") {
            repo.updateTest(test.id, input(maths.id, classId, date = LocalDate.parse("2099-01-01")))
        }
        assertRejected("Class Group and date are locked after result entry begins.") {
            repo.updateTest(test.id, input(maths.id, db.addClass("7", "B")))
        }
        assertRejected("Total Marks cannot be below recorded Marks (30).") {
            repo.updateTest(test.id, input(maths.id, classId, totalMarks = 2000))
        }
        assertRejected("Total Marks must be greater than zero, with up to two decimals.") {
            repo.updateTest(test.id, input(maths.id, classId, totalMarks = 0))
        }

        clock.now += 1.hours
        val renamed = repo.updateTest(test.id, input(maths.id, classId, name = "Term Midterm", totalMarks = 3000))
        assertEquals("Term Midterm", renamed.name)
        assertEquals("term midterm", renamed.normalizedName)
        assertEquals(3000, renamed.totalMarks)
        assertEquals(test.createdAt, renamed.createdAt)
        assertEquals(clock.now(), renamed.updatedAt)
        assertEquals(renamed, repo.getTest(test.id))
        assertEquals(3000, entry(test.id, enrollmentId).marks)
    }

    @Test
    fun updateTestRebuildsTheRosterWhenClassOrDateChangesBeforeResults() = runTest {
        val (classId, first) = classWithStudent()
        val otherClass = db.addClass("7", "B")
        val otherEnrollment = db.enroll(db.addStudent("B-1"), otherClass, roll = "1")
        val lateJoiner = db.enroll(db.addStudent("B-2"), otherClass, roll = "2", admittedOn = "2026-08-20")
        val maths = subjects.saveSubject("Mathematics")
        val test = repo.createTest(input(maths.id, classId))
        assertEquals(listOf(first), db.tests().getRoster(test.id).map { it.enrollmentId })

        repo.updateTest(test.id, input(maths.id, otherClass))
        assertEquals(listOf(otherEnrollment), db.tests().getRoster(test.id).map { it.enrollmentId })

        repo.updateTest(test.id, input(maths.id, otherClass, date = LocalDate.parse("2026-08-25")))
        assertEquals(
            setOf(otherEnrollment, lateJoiner),
            db.tests().getRoster(test.id).map { it.enrollmentId }.toSet(),
        )
    }

    @Test
    fun updateTestThatCannotBuildARosterChangesNothing() = runTest {
        val (classId, first) = classWithStudent()
        val emptyClass = db.addClass("8", "A")
        val maths = subjects.saveSubject("Mathematics")
        val test = repo.createTest(input(maths.id, classId))

        assertRejected("No students are enrolled in this Class Group on the Test date.") {
            repo.updateTest(test.id, input(maths.id, emptyClass, name = "Changed"))
        }

        assertEquals(test, repo.getTest(test.id))
        assertEquals(listOf(first), db.tests().getRoster(test.id).map { it.enrollmentId })
    }

    @Test
    fun updateTestValidatesSubjectDuplicatesAndExistence() = runTest {
        val (classId, _) = classWithStudent()
        val maths = subjects.saveSubject("Mathematics")
        val science = subjects.saveSubject("Science")
        val art = subjects.saveSubject("Art")
        val test = repo.createTest(input(maths.id, classId, name = "Quiz"))
        repo.createTest(input(science.id, classId, name = "Quiz"))
        subjects.archiveSubject(maths.id)
        subjects.archiveSubject(art.id)

        // A Test may keep its own archived Subject, but cannot move to another archived one.
        assertEquals("Quiz 2", repo.updateTest(test.id, input(maths.id, classId, name = "Quiz 2")).name)
        assertRejected("Select an active Subject.") { repo.updateTest(test.id, input(art.id, classId, name = "Quiz 2")) }
        assertRejected("Select a Class Group.") { repo.updateTest(test.id, input(maths.id, 9999, name = "Quiz 2")) }
        assertRejected("Test not found.") { repo.updateTest(9999, input(maths.id, classId)) }
        assertRejected("Test name, Subject, Class Group, and date are required.") {
            repo.updateTest(test.id, input(maths.id, classId, name = " "))
        }
        assertRejected("That Test already exists for this Class Group and date.") {
            repo.updateTest(test.id, input(science.id, classId, name = "quiz"))
        }
    }

    @Test
    fun rosterMembersCanBeAddedAndRemovedUntilTheyHaveAResult() = runTest {
        val (classId, first) = classWithStudent()
        val maths = subjects.saveSubject("Mathematics")
        val test = repo.createTest(input(maths.id, classId))
        val late = db.enroll(db.addStudent("S-2"), classId, roll = "2", admittedOn = "2026-09-01")
        val otherClass = db.addClass("7", "B")
        val foreign = db.enroll(db.addStudent("B-1"), otherClass, roll = "1")

        repo.addTestRosterMember(test.id, late)
        repo.addTestRosterMember(test.id, late) // already there: a no-op
        assertEquals(setOf(first, late), db.tests().getRoster(test.id).map { it.enrollmentId }.toSet())
        assertRejected("Select a Student from this Class Group.") { repo.addTestRosterMember(test.id, foreign) }
        assertRejected("Test or Enrollment not found.") { repo.addTestRosterMember(test.id, 9999) }
        assertRejected("Test or Enrollment not found.") { repo.addTestRosterMember(9999, late) }

        repo.setTestResult(test.id, late, TestResultInput.Absent)
        assertRejected("Clear this Student’s Test Result before removing them.") {
            repo.removeTestRosterMember(test.id, late)
        }
        assertEquals(TestResultStatus.ABSENT, entry(test.id, late).result)

        repo.setTestResult(test.id, late, null)
        repo.removeTestRosterMember(test.id, late)
        repo.removeTestRosterMember(test.id, late) // already gone: a no-op
        assertEquals(listOf(first), db.tests().getRoster(test.id).map { it.enrollmentId })
    }

    @Test
    fun deleteTestRemovesItsRosterAndResultsButNotOtherTests() = runTest {
        val (classId, enrollmentId) = classWithStudent()
        val maths = subjects.saveSubject("Mathematics")
        val doomed = repo.createTest(input(maths.id, classId, name = "Doomed"))
        val kept = repo.createTest(input(maths.id, classId, name = "Kept"))
        repo.setTestResult(doomed.id, enrollmentId, TestResultInput.Marks(1000))

        repo.deleteTest(doomed.id)
        repo.deleteTest(doomed.id) // already gone: a no-op

        assertNull(repo.getTest(doomed.id))
        assertTrue(db.tests().getRoster(doomed.id).isEmpty())
        assertEquals(listOf(kept), repo.observeTests().first())
        assertEquals(1, db.tests().getRoster(kept.id).size)
    }

    @Test
    fun observeRosterJoinsStudentsAndResultsSortedByNaturalRollNumber() = runTest {
        val classId = db.addClass("7", "A")
        val roll10 = db.enroll(db.addStudent("S-10", "Tenth"), classId, roll = "10")
        val roll2 = db.enroll(db.addStudent("S-2", "Second"), classId, roll = "2")
        val roll1 = db.enroll(db.addStudent("S-1", "First"), classId, roll = "1")
        val maths = subjects.saveSubject("Mathematics")
        val test = repo.createTest(input(maths.id, classId))
        repo.setTestResult(test.id, roll2, TestResultInput.Marks(1550))
        repo.setTestResult(test.id, roll10, TestResultInput.Absent)

        val rows = repo.observeRoster(test.id).first()

        assertEquals(listOf("1", "2", "10"), rows.map { it.enrollment.rollNumber })
        assertEquals(listOf("First", "Second", "Tenth"), rows.map { it.student.name })
        assertEquals(
            listOf(TestResultStatus.NOT_ENTERED, TestResultStatus.MARKS, TestResultStatus.ABSENT),
            rows.map { it.result },
        )
        assertEquals(listOf(null, 1550, null), rows.map { it.marks })
        assertEquals(listOf(roll1, roll2, roll10), rows.map { it.enrollment.id })
    }

    @Test
    fun summariesAndTestsListReflectResultProgress() = runTest {
        val classId = db.addClass("7", "A")
        val e1 = db.enroll(db.addStudent("S-1"), classId, roll = "1")
        val e2 = db.enroll(db.addStudent("S-2"), classId, roll = "2")
        val e3 = db.enroll(db.addStudent("S-3"), classId, roll = "3")
        val maths = subjects.saveSubject("Mathematics")
        val older = repo.createTest(input(maths.id, classId, name = "Older", date = LocalDate.parse("2026-08-10")))
        val newer = repo.createTest(input(maths.id, classId, name = "Newer"))

        assertEquals(TestProgress.NOT_STARTED, repo.observeSummary(newer.id).first().progress)

        repo.setTestResult(newer.id, e1, TestResultInput.Marks(3000))
        repo.setTestResult(newer.id, e2, TestResultInput.Marks(2000))
        repo.setTestResult(newer.id, e3, TestResultInput.Absent)

        val summary = repo.observeSummary(newer.id).first()
        assertEquals(TestProgress.COMPLETE, summary.progress)
        assertEquals(2, summary.numericCount)
        assertEquals(1, summary.absentCount)
        assertEquals(25.0, summary.average!!, 0.0001)
        assertEquals(30.0, summary.highest!!, 0.0001)

        val list = repo.observeTestsWithEntries().first()
        assertEquals(listOf(newer.id, older.id), list.map { it.test.id })
        assertEquals(TestProgress.COMPLETE, list[0].summary.progress)
        assertEquals(TestProgress.NOT_STARTED, list[1].summary.progress)
    }

    @Test
    fun subjectReportCoversTestsInThePeriodForOneOrAllClassesAndSubjects() = runTest {
        val (classA, a1) = classWithStudent("A")
        val (classB, b1) = classWithStudent("B")
        val maths = subjects.saveSubject("Mathematics")
        val science = subjects.saveSubject("Science")
        val mathsA = repo.createTest(input(maths.id, classA, name = "M-A", totalMarks = 5000))
        val mathsB = repo.createTest(input(maths.id, classB, name = "M-B", totalMarks = 1000))
        val scienceA = repo.createTest(input(science.id, classA, name = "S-A"))
        repo.createTest(input(maths.id, classA, name = "Outside", date = LocalDate.parse("2026-07-30")))
        repo.setTestResult(mathsA.id, a1, TestResultInput.Marks(2500)) // 50%
        repo.setTestResult(mathsB.id, b1, TestResultInput.Marks(900)) // 90%
        repo.setTestResult(scienceA.id, a1, TestResultInput.Absent)
        val august = DateRange(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"))

        val all = repo.observeReport(TestReportFilter(august)).first()
        assertEquals(setOf(mathsA.id, mathsB.id, scienceA.id), all.tests.map { it.id }.toSet())
        assertEquals(3, all.aggregate.totalTests)
        assertEquals(2, all.aggregate.numericCount)
        assertEquals(1, all.aggregate.absentCount)
        assertEquals(0, all.aggregate.notEnteredCount)
        assertEquals(70.0, all.aggregate.averagePercentage!!, 0.0001)
        assertEquals(listOf("Mathematics", "Science"), all.sections.map { it.subject.name })

        val mathsOnly = repo.observeReport(TestReportFilter(august, subjectId = maths.id)).first()
        assertEquals(setOf(mathsA.id, mathsB.id), mathsOnly.tests.map { it.id }.toSet())

        val classBOnly = repo.observeReport(TestReportFilter(august, classGroupId = classB)).first()
        assertEquals(listOf(mathsB.id), classBOnly.tests.map { it.id })
        assertEquals(90.0, classBOnly.aggregate.averagePercentage!!, 0.0001)
    }

    @Test
    fun studentReportListsOnlyTestsTheStudentSatWithTheirOwnResults() = runTest {
        val classId = db.addClass("7", "A")
        val ayeshaStudent = db.addStudent("S-1", "Ayesha")
        val ayesha = db.enroll(ayeshaStudent, classId, roll = "1")
        val bilal = db.enroll(db.addStudent("S-2", "Bilal"), classId, roll = "2")
        val joinedLater = db.enroll(db.addStudent("S-3", "Chand"), classId, roll = "3", admittedOn = "2026-08-12")
        val maths = subjects.saveSubject("Mathematics")
        val science = subjects.saveSubject("Science")
        val early = repo.createTest(input(maths.id, classId, name = "Early", date = LocalDate.parse("2026-08-10")))
        val late = repo.createTest(input(science.id, classId, name = "Late", date = LocalDate.parse("2026-08-14")))
        repo.setTestResult(early.id, ayesha, TestResultInput.Marks(3000))
        repo.setTestResult(early.id, bilal, TestResultInput.Marks(1000))
        repo.setTestResult(late.id, ayesha, TestResultInput.Absent)
        val august = DateRange(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"))

        val report = repo.observeReport(
            TestReportFilter(august, classGroupId = classId, studentId = ayeshaStudent),
        ).first()

        assertEquals(setOf(early.id, late.id), report.tests.map { it.id }.toSet())
        assertEquals(setOf(ayesha), report.entries.map { it.enrollmentId }.toSet())
        assertEquals(TestResultStatus.MARKS, report.studentEntryFor(early.id)!!.result)
        assertEquals(TestResultStatus.ABSENT, report.studentEntryFor(late.id)!!.result)
        assertEquals(1, report.aggregate.numericCount)
        assertEquals(75.0, report.aggregate.averagePercentage!!, 0.0001)

        // A student admitted after the first Test did not sit it.
        val joinerStudent = db.enrollments().getById(joinedLater)!!.studentId
        val joiner = repo.observeReport(
            TestReportFilter(august, classGroupId = classId, studentId = joinerStudent),
        ).first()
        assertEquals(listOf(late.id), joiner.tests.map { it.id })
        assertEquals(TestResultStatus.NOT_ENTERED, joiner.studentEntryFor(late.id)!!.result)
    }
}

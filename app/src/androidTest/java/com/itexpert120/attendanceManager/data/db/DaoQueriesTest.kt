package com.itexpert120.attendanceManager.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class DaoQueriesTest {
    private lateinit var db: AttendanceDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- EnrollmentDao --------------------------------------------------------------------------------------

    @Test
    fun observeRowsForClassEmbedsTheStudent() = runTest {
        val cls = db.addClass()
        val other = db.addClass("6", "A")
        val s1 = db.students().insert(
            Student(
                admissionNumber = "A-1",
                name = "Ayesha",
                phone = "+923001234567",
                dateOfBirth = d("2015-03-02"),
                photoFile = "photo-1.jpg",
                createdAt = T0,
            ),
        )
        val s2 = db.addStudent("A-2", "Bilal")
        val s3 = db.addStudent("A-3", "Chand")
        db.enroll(s1, cls, "1")
        db.enroll(s2, cls, "2")
        db.enroll(s3, other, "1")

        val rows = db.enrollments().observeRowsForClass(cls).first().sortedBy { it.enrollment.rollNumber }
        assertEquals(listOf("1", "2"), rows.map { it.enrollment.rollNumber })
        assertEquals(listOf("Ayesha", "Bilal"), rows.map { it.student.name })
        assertEquals(s1, rows[0].enrollment.studentId)
        assertEquals(s1, rows[0].student.id)
        val ayesha = rows[0].student
        assertEquals("+923001234567", ayesha.phone)
        assertEquals(d("2015-03-02"), ayesha.dateOfBirth)
        assertEquals("photo-1.jpg", ayesha.photoFile)
        assertEquals(T0, ayesha.createdAt)

        assertEquals(2, db.enrollments().getRowsForClass(cls).size)
        assertEquals(3, db.enrollments().observeAllRows().first().size)
        assertEquals(1, db.enrollments().observeRowsForClass(other).first().size)
        assertTrue(db.enrollments().observeRowsForClass(9999).first().isEmpty())
    }

    @Test
    fun rollNumberConflictsAreCaseInsensitive() = runTest {
        val cls = db.addClass()
        db.enroll(db.addStudent("1"), cls, "A1")
        val dao = db.enrollments()
        assertEquals(1, dao.countRollNumberConflicts(cls, "A1", 0, d("2026-08-01")))
        assertEquals(1, dao.countRollNumberConflicts(cls, "a1", 0, d("2026-08-01")))
        assertEquals(0, dao.countRollNumberConflicts(cls, "A2", 0, d("2026-08-01")))
    }

    @Test
    fun rollNumberConflictsAreScopedToTheClass() = runTest {
        val cls = db.addClass()
        val other = db.addClass("6", "A")
        db.enroll(db.addStudent("1"), cls, "7")
        assertEquals(0, db.enrollments().countRollNumberConflicts(other, "7", 0, d("2026-08-01")))
        assertEquals(1, db.enrollments().countRollNumberConflicts(cls, "7", 0, d("2026-08-01")))
    }

    @Test
    fun rollNumberConflictsExcludeTheGivenEnrollment() = runTest {
        val cls = db.addClass()
        val mine = db.enroll(db.addStudent("1"), cls, "7")
        val dao = db.enrollments()
        assertEquals(0, dao.countRollNumberConflicts(cls, "7", mine, d("2026-08-01")))
        val twin = db.enroll(db.addStudent("2"), cls, "7")
        assertEquals(1, dao.countRollNumberConflicts(cls, "7", mine, d("2026-08-01")))
        assertEquals(1, dao.countRollNumberConflicts(cls, "7", twin, d("2026-08-01")))
        assertEquals(2, dao.countRollNumberConflicts(cls, "7", 0, d("2026-08-01")))
    }

    @Test
    fun rollNumberConflictsIgnoreStudentsStruckOffBeforeAdmission() = runTest {
        val cls = db.addClass()
        db.enroll(db.addStudent("1"), cls, "7", admittedOn = "2026-04-01", struckOffOn = "2026-06-30")
        val dao = db.enrollments()
        // New student arrives after the old one left: the roll number is free.
        assertEquals(0, dao.countRollNumberConflicts(cls, "7", 0, d("2026-07-01")))
        // struckOffOn is inclusive, so on that very day the roll number is still taken.
        assertEquals(1, dao.countRollNumberConflicts(cls, "7", 0, d("2026-06-30")))
        // And before it, certainly.
        assertEquals(1, dao.countRollNumberConflicts(cls, "7", 0, d("2026-05-01")))
    }

    // ---- TestDao --------------------------------------------------------------------------------------------

    @Test
    fun countDuplicatesMatchesClassSubjectDateAndNormalizedName() = runTest {
        val cls = db.addClass()
        val other = db.addClass("6", "A")
        val maths = db.addSubject("Maths")
        val english = db.addSubject("English")
        val test = db.addTest(cls, maths, "Unit 1", "2026-08-10")
        val dao = db.tests()
        assertEquals(1, dao.countDuplicates(cls, maths, d("2026-08-10"), "unit 1", 0))
        assertEquals(0, dao.countDuplicates(cls, maths, d("2026-08-10"), "unit 1", test)) // excludes itself
        assertEquals(0, dao.countDuplicates(other, maths, d("2026-08-10"), "unit 1", 0))
        assertEquals(0, dao.countDuplicates(cls, english, d("2026-08-10"), "unit 1", 0))
        assertEquals(0, dao.countDuplicates(cls, maths, d("2026-08-11"), "unit 1", 0))
        assertEquals(0, dao.countDuplicates(cls, maths, d("2026-08-10"), "unit 2", 0))
    }

    private data class RosterFixture(val cls: Long, val test: Long, val enrollments: List<Long>)

    private suspend fun rosterFixture(students: Int = 4): RosterFixture {
        val cls = db.addClass()
        val enrollments = (1..students).map { i -> db.enroll(db.addStudent("S$i", "Student $i"), cls, "$i") }
        val test = db.addTest(cls, db.addSubject())
        return RosterFixture(cls, test, enrollments)
    }

    @Test
    fun observeRosterRowsReturnsOnlyRosterMembersWithTheirStudent() = runTest {
        val f = rosterFixture(4)
        db.tests().insertRoster(
            listOf(f.enrollments[0], f.enrollments[2]).map { TestRosterEntry(f.test, it, createdAt = T0) },
        )
        val rows = db.tests().observeRosterRows(f.test).first().sortedBy { it.enrollment.rollNumber }
        assertEquals(listOf("1", "3"), rows.map { it.enrollment.rollNumber })
        assertEquals(listOf("Student 1", "Student 3"), rows.map { it.student.name })
    }

    @Test
    fun countEnteredResultsCountsMarksAndAbsentButNotNotEntered() = runTest {
        val f = rosterFixture(4)
        db.tests().insertRoster(
            listOf(
                TestRosterEntry(f.test, f.enrollments[0], TestResultStatus.MARKS, 3000, T0, T0),
                TestRosterEntry(f.test, f.enrollments[1], TestResultStatus.ABSENT, null, T0, T0),
                TestRosterEntry(f.test, f.enrollments[2], TestResultStatus.NOT_ENTERED, null, null, T0),
                TestRosterEntry(f.test, f.enrollments[3], TestResultStatus.MARKS, 0, T0, T0),
            ),
        )
        assertEquals(3, db.tests().countEnteredResults(f.test))
        assertEquals(0, db.tests().countEnteredResults(9999))
    }

    @Test
    fun highestMarksIgnoresAbsentAndNotEntered() = runTest {
        val f = rosterFixture(4)
        assertNull(db.tests().highestMarks(f.test)) // empty roster
        db.tests().insertRoster(
            listOf(
                TestRosterEntry(f.test, f.enrollments[0], TestResultStatus.ABSENT, null, T0, T0),
                TestRosterEntry(f.test, f.enrollments[1], TestResultStatus.NOT_ENTERED, null, null, T0),
            ),
        )
        assertNull(db.tests().highestMarks(f.test)) // nobody has marks
        db.tests().upsertRosterEntry(TestRosterEntry(f.test, f.enrollments[2], TestResultStatus.MARKS, 3575, T0, T0))
        db.tests().upsertRosterEntry(TestRosterEntry(f.test, f.enrollments[3], TestResultStatus.MARKS, 1200, T0, T0))
        assertEquals(3575, db.tests().highestMarks(f.test))
    }

    @Test
    fun highestMarksCanBeZero() = runTest {
        val f = rosterFixture(1)
        db.tests().insertRoster(
            listOf(TestRosterEntry(f.test, f.enrollments[0], TestResultStatus.MARKS, 0, T0, T0)),
        )
        assertEquals(0, db.tests().highestMarks(f.test))
    }

    @Test
    fun rosterEntryRoundTripsResultAndTimestamps() = runTest {
        val f = rosterFixture(1)
        val entry = TestRosterEntry(f.test, f.enrollments[0], TestResultStatus.MARKS, 3999, Instant.fromEpochMilliseconds(1_786_000_123_456), T0)
        db.tests().insertRoster(listOf(entry))
        assertEquals(entry, db.tests().getRosterEntry(f.test, f.enrollments[0]))
        assertEquals(listOf(entry), db.tests().observeRoster(f.test).first())
        db.tests().deleteRosterEntry(f.test, f.enrollments[0])
        assertNull(db.tests().getRosterEntry(f.test, f.enrollments[0]))
        db.tests().insertRoster(listOf(entry))
        db.tests().deleteRoster(f.test)
        assertTrue(db.tests().getRoster(f.test).isEmpty())
    }

    // ---- AttendanceDao --------------------------------------------------------------------------------------

    private data class RegisterFixture(val register: Long, val enrollments: List<Long>)

    private suspend fun registerFixture(students: Int = 3, month: Int = 8): RegisterFixture {
        val cls = db.addClass("7", "B$month")
        val enrollments = (1..students).map { i -> db.enroll(db.addStudent("R$month-$i"), cls, "$i") }
        return RegisterFixture(db.addRegister(cls, 2026, month), enrollments)
    }

    private suspend fun mark(register: Long, enrollment: Long, day: Int, session: Session, status: AttendanceStatus) =
        db.attendance().upsert(AttendanceMark(register, enrollment, day, session, status))

    @Test
    fun presentSessionCountsCountsOnlyPresentPerEnrollmentAcrossRegisters() = runTest {
        val cls = db.addClass()
        val e1 = db.enroll(db.addStudent("1"), cls, "1")
        val e2 = db.enroll(db.addStudent("2"), cls, "2")
        val e3 = db.enroll(db.addStudent("3"), cls, "3")
        val aug = db.addRegister(cls, 2026, 8)
        val sep = db.addRegister(cls, 2026, 9)
        val oct = db.addRegister(cls, 2026, 10)

        mark(aug, e1, 3, Session.FIRST, AttendanceStatus.PRESENT)
        mark(aug, e1, 3, Session.SECOND, AttendanceStatus.PRESENT)
        mark(aug, e1, 4, Session.FIRST, AttendanceStatus.ABSENT)
        mark(sep, e1, 1, Session.FIRST, AttendanceStatus.PRESENT)
        mark(oct, e1, 1, Session.FIRST, AttendanceStatus.PRESENT) // not asked for
        mark(aug, e2, 3, Session.FIRST, AttendanceStatus.ABSENT)
        mark(aug, e2, 4, Session.FIRST, AttendanceStatus.LEAVE)
        mark(sep, e3, 2, Session.SECOND, AttendanceStatus.PRESENT)

        val counts = db.attendance().presentSessionCounts(listOf(aug, sep)).associate { it.enrollmentId to it.sessions }
        assertEquals(mapOf(e1 to 3, e3 to 1), counts) // e2 has no present session so is absent from the result

        val onlyAug = db.attendance().presentSessionCounts(listOf(aug)).associate { it.enrollmentId to it.sessions }
        assertEquals(mapOf(e1 to 2), onlyAug)
        assertTrue(db.attendance().presentSessionCounts(emptyList()).isEmpty())
    }

    @Test
    fun deleteRemovesOnlyTheExactCell() = runTest {
        val f = registerFixture(2)
        val (e1, e2) = f.enrollments
        mark(f.register, e1, 3, Session.FIRST, AttendanceStatus.PRESENT)
        mark(f.register, e1, 3, Session.SECOND, AttendanceStatus.PRESENT)
        mark(f.register, e1, 4, Session.FIRST, AttendanceStatus.PRESENT)
        mark(f.register, e2, 3, Session.FIRST, AttendanceStatus.PRESENT)

        db.attendance().delete(f.register, e1, 3, Session.FIRST)

        val remaining = db.attendance().getForRegister(f.register).map { Triple(it.enrollmentId, it.day, it.session) }.toSet()
        assertEquals(
            setOf(Triple(e1, 3, Session.SECOND), Triple(e1, 4, Session.FIRST), Triple(e2, 3, Session.FIRST)),
            remaining,
        )
    }

    @Test
    fun deleteSessionRemovesThatSessionForTheGivenEnrollmentsOnly() = runTest {
        val f = registerFixture(3)
        val (e1, e2, e3) = f.enrollments
        for (e in f.enrollments) {
            mark(f.register, e, 3, Session.FIRST, AttendanceStatus.PRESENT)
            mark(f.register, e, 3, Session.SECOND, AttendanceStatus.ABSENT)
            mark(f.register, e, 4, Session.FIRST, AttendanceStatus.PRESENT)
        }

        db.attendance().deleteSession(f.register, 3, Session.FIRST, listOf(e1, e2))

        val remaining = db.attendance().getForRegister(f.register).map { Triple(it.enrollmentId, it.day, it.session) }.toSet()
        val expected = buildSet {
            for (e in f.enrollments) {
                add(Triple(e, 3, Session.SECOND))
                add(Triple(e, 4, Session.FIRST))
            }
            add(Triple(e3, 3, Session.FIRST)) // not in the id list, so kept
        }
        assertEquals(expected, remaining)
    }

    @Test
    fun deleteDayRemovesBothSessionsForEveryoneButOnlyInThatRegister() = runTest {
        val f = registerFixture(2, month = 8)
        val g = registerFixture(1, month = 9)
        for (e in f.enrollments) {
            mark(f.register, e, 3, Session.FIRST, AttendanceStatus.PRESENT)
            mark(f.register, e, 3, Session.SECOND, AttendanceStatus.PRESENT)
            mark(f.register, e, 4, Session.FIRST, AttendanceStatus.PRESENT)
        }
        mark(g.register, g.enrollments[0], 3, Session.FIRST, AttendanceStatus.PRESENT)

        db.attendance().deleteDay(f.register, 3)

        assertEquals(setOf(4), db.attendance().getForRegister(f.register).map { it.day }.toSet())
        assertEquals(2, db.attendance().getForRegister(f.register).size)
        assertEquals(1, db.attendance().getForRegister(g.register).size)
        assertEquals(3, db.attendance().getForRegisters(listOf(f.register, g.register)).size)
        assertEquals(2, db.attendance().observeForRegister(f.register).first().size)
    }

    // ---- Converters -----------------------------------------------------------------------------------------

    @Test
    fun convertersRoundTrip() {
        val c = Converters()
        val date = d("2026-02-28")
        assertEquals("2026-02-28", c.localDateToText(date))
        assertEquals(date, c.textToLocalDate(c.localDateToText(date)))
        assertNull(c.localDateToText(null))
        assertNull(c.textToLocalDate(null))

        val instant = Instant.fromEpochMilliseconds(1_786_000_123_456)
        assertEquals(1_786_000_123_456, c.instantToMillis(instant))
        assertEquals(instant, c.millisToInstant(c.instantToMillis(instant)))
        assertNull(c.instantToMillis(null))
        assertNull(c.millisToInstant(null))
    }

    @Test
    fun datesInstantsAndEnumsRoundTripThroughTheDatabase() = runTest {
        val cls = db.addClass()
        val student = db.students().insert(
            Student(
                admissionNumber = "X",
                name = "X",
                dateOfBirth = d("2014-12-31"),
                createdAt = Instant.fromEpochMilliseconds(1_786_000_123_456),
            ),
        )
        val e = db.enrollments().insert(
            Enrollment(
                studentId = student,
                classGroupId = cls,
                rollNumber = "1",
                admittedOn = d("2026-04-01"),
                struckOffOn = d("2026-09-30"),
            ),
        )
        val register = db.addRegister(cls)
        mark(register, e, 31, Session.SECOND, AttendanceStatus.LEAVE)
        db.fees().upsertEntries(
            listOf(com.itexpert120.attendanceManager.model.FeeEntry(register, e, InstallmentNumber.THIRD)),
        )

        val s = db.students().getById(student)!!
        assertEquals(d("2014-12-31"), s.dateOfBirth)
        assertEquals(Instant.fromEpochMilliseconds(1_786_000_123_456), s.createdAt)
        val en = db.enrollments().getById(e)!!
        assertEquals(d("2026-04-01"), en.admittedOn)
        assertEquals(d("2026-09-30"), en.struckOffOn)
        val m = db.attendance().getForRegister(register).single()
        assertEquals(Session.SECOND, m.session)
        assertEquals(AttendanceStatus.LEAVE, m.status)
        assertEquals(InstallmentNumber.THIRD, db.fees().getEntries(register).single().installment)

        val nullable = db.students().insert(Student(admissionNumber = "Y", name = "Y", createdAt = T0))
        assertNull(db.students().getById(nullable)!!.dateOfBirth)
        assertNull(db.students().getById(nullable)!!.photoFile)
    }
}

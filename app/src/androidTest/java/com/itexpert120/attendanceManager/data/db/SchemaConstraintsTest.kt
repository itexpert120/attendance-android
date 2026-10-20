package com.itexpert120.attendanceManager.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.Holiday
import com.itexpert120.attendanceManager.model.HomeworkItem
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.StudentRemark
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.itexpert120.attendanceManager.support.inMemoryDatabase

@RunWith(AndroidJUnit4::class)
class SchemaConstraintsTest {
    private lateinit var db: AttendanceDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- unique constraints ---------------------------------------------------------------------------------

    @Test
    fun admissionNumberIsUniqueCaseInsensitively() = runTest {
        db.addStudent("AB-100")
        assertViolation("same admission number") { db.addStudent("AB-100") }
        assertViolation("different case") { db.addStudent("ab-100") }
        db.addStudent("AB-101") // a different one is fine
        assertEquals(2, db.students().observeAll().first().size)
        assertNotNull(db.students().findByAdmissionNumber("Ab-100"))
    }

    @Test
    fun classNameAndSectionAreUniqueCaseInsensitively() = runTest {
        db.addClass("Nursery", "A")
        assertViolation("same class") { db.addClass("Nursery", "A") }
        assertViolation("different case") { db.addClass("nursery", "a") }
        db.addClass("Nursery", "B") // same name, different section
        db.addClass("Prep", "A") // different name, same section
        assertEquals(3, db.classGroups().getAll().size)
        assertNotNull(db.classGroups().find("NURSERY", "b"))
    }

    @Test
    fun registerIsUniquePerClassYearMonth() = runTest {
        val c1 = db.addClass("5", "A")
        val c2 = db.addClass("5", "B")
        db.addRegister(c1, 2026, 8)
        assertViolation("duplicate register") { db.addRegister(c1, 2026, 8) }
        db.addRegister(c1, 2026, 9)
        db.addRegister(c1, 2027, 8)
        db.addRegister(c2, 2026, 8)
        assertEquals(4, db.registers().getAll().size)
    }

    @Test
    fun testIsUniquePerClassSubjectDateAndNormalizedName() = runTest {
        val c1 = db.addClass("5", "A")
        val c2 = db.addClass("5", "B")
        val maths = db.addSubject("Maths")
        val english = db.addSubject("English")
        db.addTest(c1, maths, "Unit 1", "2026-08-10")
        assertViolation("duplicate test") { db.addTest(c1, maths, "Unit 1", "2026-08-10") }
        db.addTest(c2, maths, "Unit 1", "2026-08-10")
        db.addTest(c1, english, "Unit 1", "2026-08-10")
        db.addTest(c1, maths, "Unit 1", "2026-08-11")
        db.addTest(c1, maths, "Unit 2", "2026-08-10")
        assertEquals(5, db.tests().observeAll().first().size)
    }

    @Test
    fun subjectNormalizedNameIsUnique() = runTest {
        db.addSubject("Maths")
        assertViolation("duplicate subject") {
            db.subjects().insert(Subject(name = "MATHS", normalizedName = "maths", createdAt = T0, updatedAt = T0))
        }
        db.addSubject("English")
        assertEquals(2, db.subjects().getAll().size)
        assertNotNull(db.subjects().findByNormalizedName("maths"))
    }

    @Test
    fun homeworkReportIsUniquePerClassAndDate() = runTest {
        val c1 = db.addClass("5", "A")
        val c2 = db.addClass("5", "B")
        db.addHomeworkReport(c1, "2026-08-10")
        assertViolation("duplicate report") { db.addHomeworkReport(c1, "2026-08-10") }
        db.addHomeworkReport(c1, "2026-08-11")
        db.addHomeworkReport(c2, "2026-08-10")
        assertNotNull(db.homework().findReport(c1, d("2026-08-10")))
    }

    @Test
    fun homeworkItemIsUniquePerReportAndSubject() = runTest {
        val c1 = db.addClass()
        val report = db.addHomeworkReport(c1)
        val maths = db.addSubject("Maths")
        val english = db.addSubject("English")
        db.homework().insertItems(listOf(HomeworkItem(reportId = report, subjectId = maths, details = "Ex 1", position = 0)))
        assertViolation("duplicate item") {
            db.homework().insertItems(listOf(HomeworkItem(reportId = report, subjectId = maths, details = "Ex 2", position = 1)))
        }
        db.homework().insertItems(listOf(HomeworkItem(reportId = report, subjectId = english, details = "Essay", position = 1)))
        assertEquals(listOf("Ex 1", "Essay"), db.homework().getItems(report).map { it.details })
    }

    // ---- foreign keys ---------------------------------------------------------------------------------------

    @Test
    fun foreignKeysAreEnforced() = runTest {
        assertViolation("enrollment without student/class") { db.enroll(studentId = 999, classGroupId = 999) }
        assertViolation("register without class") { db.addRegister(999) }
        assertViolation("test without subject/class") { db.addTest(999, 999) }
    }

    @Test
    fun deletingClassGroupCascadesToEverythingBelow() = runTest {
        val cls = db.addClass()
        val other = db.addClass("6", "A")
        val student = db.addStudent("1")
        val enrollment = db.enroll(student, cls)
        val otherStudent = db.addStudent("2")
        val otherEnrollment = db.enroll(otherStudent, other)
        val register = db.addRegister(cls)
        val otherRegister = db.addRegister(other)
        val subject = db.addSubject()
        val test = db.addTest(cls, subject)
        val otherTest = db.addTest(other, subject)
        val report = db.addHomeworkReport(cls)
        val otherReport = db.addHomeworkReport(other)

        db.attendance().upsert(AttendanceMark(register, enrollment, 3, Session.FIRST, AttendanceStatus.PRESENT))
        db.attendance().upsert(AttendanceMark(otherRegister, otherEnrollment, 3, Session.FIRST, AttendanceStatus.PRESENT))
        db.holidays().upsert(Holiday(register, 5, "Eid"))
        db.fees().upsertEntries(listOf(FeeEntry(register, enrollment, InstallmentNumber.FIRST, FeeAmounts(ftf = 500))))
        db.fees().upsertMeta(InstallmentMeta(register, InstallmentNumber.FIRST, 500, "Mr Ali"))
        db.fees().upsertRemark(StudentRemark(register, enrollment, "late"))
        db.tests().insertRoster(listOf(TestRosterEntry(test, enrollment, createdAt = T0)))
        db.tests().insertRoster(listOf(TestRosterEntry(otherTest, otherEnrollment, createdAt = T0)))
        db.homework().insertItems(listOf(HomeworkItem(reportId = report, subjectId = subject, details = "x", position = 0)))
        db.homework().insertItems(listOf(HomeworkItem(reportId = otherReport, subjectId = subject, details = "y", position = 0)))

        db.exec("DELETE FROM class_groups WHERE id = $cls")

        assertNull(db.classGroups().getById(cls))
        assertNull(db.enrollments().getById(enrollment))
        assertNull(db.registers().getById(register))
        assertTrue(db.attendance().getForRegister(register).isEmpty())
        assertTrue(db.holidays().getForRegister(register).isEmpty())
        assertTrue(db.fees().getEntries(register).isEmpty())
        assertTrue(db.fees().observeMeta(register).first().isEmpty())
        assertTrue(db.fees().observeRemarks(register).first().isEmpty())
        assertNull(db.tests().getById(test))
        assertTrue(db.tests().getRoster(test).isEmpty())
        assertNull(db.homework().getReport(report))
        assertTrue(db.homework().getItems(report).isEmpty())

        // The other class and the student row itself are untouched.
        assertNotNull(db.students().getById(student))
        assertNotNull(db.enrollments().getById(otherEnrollment))
        assertNotNull(db.registers().getById(otherRegister))
        assertEquals(1, db.attendance().getForRegister(otherRegister).size)
        assertNotNull(db.tests().getById(otherTest))
        assertEquals(1, db.tests().getRoster(otherTest).size)
        assertNotNull(db.homework().getReport(otherReport))
        assertEquals(1, db.homework().getItems(otherReport).size)
    }

    @Test
    fun deletingTestRemovesItsRosterEntriesOnly() = runTest {
        val cls = db.addClass()
        val enrollment = db.enroll(db.addStudent("1"), cls)
        val subject = db.addSubject()
        val test = db.addTest(cls, subject, "T1")
        val keep = db.addTest(cls, subject, "T2")
        db.tests().insertRoster(listOf(TestRosterEntry(test, enrollment, createdAt = T0)))
        db.tests().insertRoster(listOf(TestRosterEntry(keep, enrollment, createdAt = T0)))

        db.tests().delete(db.tests().getById(test)!!)

        assertNull(db.tests().getById(test))
        assertTrue(db.tests().getRoster(test).isEmpty())
        assertEquals(1, db.tests().getRoster(keep).size)
        assertNotNull(db.enrollments().getById(enrollment))
    }

    @Test
    fun deletingRegisterRemovesMarksHolidaysFeesMetaAndRemarks() = runTest {
        val cls = db.addClass()
        val enrollment = db.enroll(db.addStudent("1"), cls)
        val register = db.addRegister(cls, 2026, 8)
        val keepRegister = db.addRegister(cls, 2026, 9)
        db.attendance().upsert(AttendanceMark(register, enrollment, 3, Session.FIRST, AttendanceStatus.ABSENT))
        db.attendance().upsert(AttendanceMark(keepRegister, enrollment, 3, Session.FIRST, AttendanceStatus.ABSENT))
        db.holidays().upsert(Holiday(register, 5, "Eid"))
        db.fees().upsertEntries(listOf(FeeEntry(register, enrollment, InstallmentNumber.SECOND, FeeAmounts(ff = 100))))
        db.fees().upsertMeta(InstallmentMeta(register, InstallmentNumber.SECOND, 100, "Mr Ali"))
        db.fees().upsertRemark(StudentRemark(register, enrollment, "note"))

        db.exec("DELETE FROM registers WHERE id = $register")

        assertTrue(db.attendance().getForRegister(register).isEmpty())
        assertTrue(db.holidays().getForRegister(register).isEmpty())
        assertTrue(db.fees().getEntries(register).isEmpty())
        assertTrue(db.fees().observeMeta(register).first().isEmpty())
        assertTrue(db.fees().observeRemarks(register).first().isEmpty())
        assertEquals(1, db.attendance().getForRegister(keepRegister).size)
        assertNotNull(db.enrollments().getById(enrollment))
    }

    @Test
    fun deletingStudentCascadesToEnrollmentAndItsRows() = runTest {
        val cls = db.addClass()
        val student = db.addStudent("1")
        val enrollment = db.enroll(student, cls)
        val register = db.addRegister(cls)
        val test = db.addTest(cls, db.addSubject())
        db.attendance().upsert(AttendanceMark(register, enrollment, 1, Session.FIRST, AttendanceStatus.LEAVE))
        db.tests().insertRoster(listOf(TestRosterEntry(test, enrollment, createdAt = T0)))
        db.fees().upsertRemark(StudentRemark(register, enrollment, "x"))

        db.exec("DELETE FROM students WHERE id = $student")

        assertNull(db.enrollments().getById(enrollment))
        assertTrue(db.attendance().getForRegister(register).isEmpty())
        assertTrue(db.tests().getRoster(test).isEmpty())
        assertTrue(db.fees().observeRemarks(register).first().isEmpty())
        assertNotNull(db.registers().getById(register))
    }

    @Test
    fun deletingSubjectUsedByTestIsRestricted() = runTest {
        val cls = db.addClass()
        val subject = db.addSubject()
        db.addTest(cls, subject)
        assertViolation("subject used by test") { db.exec("DELETE FROM subjects WHERE id = $subject") }
        assertNotNull(db.subjects().getById(subject))
    }

    @Test
    fun deletingSubjectUsedByHomeworkItemIsRestricted() = runTest {
        val cls = db.addClass()
        val report = db.addHomeworkReport(cls)
        val subject = db.addSubject()
        db.homework().insertItems(listOf(HomeworkItem(reportId = report, subjectId = subject, details = "x", position = 0)))
        assertViolation("subject used by homework") { db.exec("DELETE FROM subjects WHERE id = $subject") }
        assertNotNull(db.subjects().getById(subject))
    }

    @Test
    fun unusedSubjectCanBeDeleted() = runTest {
        val subject = db.addSubject()
        db.exec("DELETE FROM subjects WHERE id = $subject")
        assertNull(db.subjects().getById(subject))
    }

    // ---- composite-key upserts ------------------------------------------------------------------------------

    @Test
    fun attendanceMarkUpsertReplacesTheSameCell() = runTest {
        val cls = db.addClass()
        val enrollment = db.enroll(db.addStudent("1"), cls)
        val register = db.addRegister(cls)
        val dao = db.attendance()
        dao.upsert(AttendanceMark(register, enrollment, 3, Session.FIRST, AttendanceStatus.PRESENT))
        dao.upsert(AttendanceMark(register, enrollment, 3, Session.FIRST, AttendanceStatus.ABSENT))
        assertEquals(listOf(AttendanceStatus.ABSENT), dao.getForRegister(register).map { it.status })

        dao.upsert(AttendanceMark(register, enrollment, 3, Session.SECOND, AttendanceStatus.LEAVE))
        dao.upsertAll(
            listOf(
                AttendanceMark(register, enrollment, 4, Session.FIRST, AttendanceStatus.PRESENT),
                AttendanceMark(register, enrollment, 3, Session.FIRST, AttendanceStatus.PRESENT),
            ),
        )
        val marks = dao.getForRegister(register)
        assertEquals(3, marks.size)
        assertEquals(
            AttendanceStatus.PRESENT,
            marks.single { it.day == 3 && it.session == Session.FIRST }.status,
        )
        assertEquals(AttendanceStatus.LEAVE, marks.single { it.day == 3 && it.session == Session.SECOND }.status)
    }

    @Test
    fun feeEntryUpsertReplacesTheSameInstallment() = runTest {
        val cls = db.addClass()
        val enrollment = db.enroll(db.addStudent("1"), cls)
        val register = db.addRegister(cls)
        val dao = db.fees()
        dao.upsertEntries(listOf(FeeEntry(register, enrollment, InstallmentNumber.FIRST, FeeAmounts(ftf = 100, slc = 5))))
        dao.upsertEntries(
            listOf(
                FeeEntry(register, enrollment, InstallmentNumber.FIRST, FeeAmounts(ftf = 250, dcf = 7)),
                FeeEntry(register, enrollment, InstallmentNumber.SECOND, FeeAmounts(arrears = 9)),
            ),
        )
        val entries = dao.getEntries(register)
        assertEquals(2, entries.size)
        val first = entries.single { it.installment == InstallmentNumber.FIRST }
        assertEquals(FeeAmounts(ftf = 250, dcf = 7), first.amounts)
        assertEquals(257L, first.amounts.total)
        assertEquals(9L, entries.single { it.installment == InstallmentNumber.SECOND }.amounts.arrears)
    }

    @Test
    fun feeAmountsRoundTripAllSixColumns() = runTest {
        val cls = db.addClass()
        val enrollment = db.enroll(db.addStudent("1"), cls)
        val register = db.addRegister(cls)
        val amounts = FeeAmounts(ftf = 1, ff = 2, arrears = 3, lateCertificate = 4, slc = 5, dcf = 6)
        db.fees().upsertEntries(listOf(FeeEntry(register, enrollment, InstallmentNumber.THIRD, amounts)))
        assertEquals(amounts, db.fees().getEntries(register).single().amounts)
    }

    @Test
    fun holidayUpsertReplacesTitleAndDeleteRemoves() = runTest {
        val register = db.addRegister(db.addClass())
        db.holidays().upsert(Holiday(register, 14, "Independence Day"))
        db.holidays().upsert(Holiday(register, 14, "Azadi"))
        db.holidays().upsert(Holiday(register, 15, "Other"))
        assertEquals(setOf("Azadi", "Other"), db.holidays().getForRegister(register).map { it.title }.toSet())
        db.holidays().delete(register, 14)
        assertEquals(listOf(15), db.holidays().getForRegister(register).map { it.day })
    }

    @Test
    fun remarkUpsertReplacesAndDeleteRemoves() = runTest {
        val cls = db.addClass()
        val enrollment = db.enroll(db.addStudent("1"), cls)
        val register = db.addRegister(cls)
        db.fees().upsertRemark(StudentRemark(register, enrollment, "first"))
        db.fees().upsertRemark(StudentRemark(register, enrollment, "second"))
        assertEquals(listOf("second"), db.fees().observeRemarks(register).first().map { it.text })
        db.fees().deleteRemark(register, enrollment)
        assertTrue(db.fees().observeRemarks(register).first().isEmpty())
    }

    @Test
    fun installmentMetaUpsertReplaces() = runTest {
        val register = db.addRegister(db.addClass())
        db.fees().upsertMeta(InstallmentMeta(register, InstallmentNumber.FIRST, 500, "A"))
        db.fees().upsertMeta(InstallmentMeta(register, InstallmentNumber.FIRST, 700, "B"))
        db.fees().upsertMeta(InstallmentMeta(register, InstallmentNumber.SECOND, 900, "C"))
        val meta = db.fees().observeMeta(register).first()
        assertEquals(2, meta.size)
        val first = meta.single { it.installment == InstallmentNumber.FIRST }
        assertEquals(700L, first.rate)
        assertEquals("B", first.receiverName)
    }

    @Test
    fun rosterEntryUpsertReplacesResult() = runTest {
        val cls = db.addClass()
        val enrollment = db.enroll(db.addStudent("1"), cls)
        val test = db.addTest(cls, db.addSubject())
        db.tests().insertRoster(listOf(TestRosterEntry(test, enrollment, createdAt = T0)))
        db.tests().upsertRosterEntry(
            TestRosterEntry(test, enrollment, TestResultStatus.MARKS, 3550, T0, T0),
        )
        db.tests().upsertRosterEntry(
            TestRosterEntry(test, enrollment, TestResultStatus.MARKS, 3600, T0, T0),
        )
        val roster = db.tests().getRoster(test)
        assertEquals(1, roster.size)
        assertEquals(3600, roster.single().marks)
        assertEquals(TestResultStatus.MARKS, roster.single().result)
        assertViolation("duplicate roster insert") {
            db.tests().insertRoster(listOf(TestRosterEntry(test, enrollment, createdAt = T0)))
        }
    }

    @Test
    fun settingsUpsertKeepsASingleRow() = runTest {
        val settings = com.itexpert120.attendanceManager.model.SchoolSettings(
            schoolName = "A", academicYearStartMonth = 4, updatedAt = T0,
        )
        db.settings().upsert(settings)
        db.settings().upsert(settings.copy(schoolName = "B"))
        assertEquals("B", db.settings().get()?.schoolName)
        assertEquals("B", db.settings().observe().first()?.schoolName)
    }
}

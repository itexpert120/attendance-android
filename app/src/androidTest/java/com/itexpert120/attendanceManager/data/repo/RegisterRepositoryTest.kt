package com.itexpert120.attendanceManager.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.AttendanceStatus.ABSENT
import com.itexpert120.attendanceManager.model.AttendanceStatus.LEAVE
import com.itexpert120.attendanceManager.model.AttendanceStatus.PRESENT
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.InstallmentNumber.FIRST
import com.itexpert120.attendanceManager.model.InstallmentNumber.SECOND
import com.itexpert120.attendanceManager.model.InstallmentNumber.THIRD
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.Session.FIRST as MORNING
import com.itexpert120.attendanceManager.model.Session.SECOND as AFTERNOON
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.support.MutableClock
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

/**
 * August 2026, with "today" fixed at Wednesday 12 August. The editable days are therefore 5 to 12
 * (today and the seven days before), and day 11 is the ordinary Tuesday used by most tests.
 */
@RunWith(AndroidJUnit4::class)
class RegisterRepositoryTest {
    private lateinit var db: AttendanceDatabase
    private val clock = MutableClock(Instant.parse("2026-08-12T09:00:00Z"))
    private lateinit var repo: RegisterRepository

    private var classId = 0L
    private var registerId = 0L
    private var enrollmentId = 0L

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        repo = RegisterRepository(db, clock, TimeZone.UTC)
        db.settings().upsert(SchoolSettings(schoolName = "Test School", academicYearStartMonth = 4, updatedAt = clock.now))
        classId = addClass("5", "A")
        enrollmentId = enrol(classId, "A-001", "1", "2026-08-01")
        registerId = repo.createRegister(classId, 2026, 8).id
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun addClass(name: String, section: String): Long =
        db.classGroups().insert(ClassGroup(className = name, section = section, createdAt = clock.now))

    private suspend fun enrol(
        classGroupId: Long,
        admission: String,
        roll: String,
        admittedOn: String,
        struckOffOn: String? = null,
    ): Long {
        val studentId = db.students().insert(Student(admissionNumber = admission, name = "Student $admission", createdAt = clock.now))
        return db.enrollments().insert(
            Enrollment(
                studentId = studentId,
                classGroupId = classGroupId,
                rollNumber = roll,
                admittedOn = LocalDate.parse(admittedOn),
                struckOffOn = struckOffOn?.let(LocalDate::parse),
            ),
        )
    }

    private suspend fun marks() = db.attendance().getForRegister(registerId)

    private suspend fun snapshot() = withTimeout(10_000) { repo.observeRegister(registerId).first()!! }

    // region createRegister

    @Test
    fun createRegisterReturnsTheExistingRegisterForTheSameMonth() = runBlocking {
        val again = repo.createRegister(classId, 2026, 8)
        assertEquals(registerId, again.id)
        assertEquals(1, db.registers().getAll().size)

        val september = repo.createRegister(classId, 2026, 9)
        assertTrue(september.id != registerId)
        assertEquals(listOf(9, 8), repo.observeRegisters().first().map { it.month })
    }

    @Test
    fun createRegisterRejectsAnInvalidMonthOrUnknownClass() = runBlocking {
        try {
            repo.createRegister(classId, 2026, 13)
            fail("expected a ValidationException")
        } catch (_: ValidationException) {
        }
        try {
            repo.createRegister(9999, 2026, 9)
            fail("expected a ValidationException")
        } catch (_: ValidationException) {
        }
        assertEquals(1, db.registers().getAll().size)
    }

    // endregion

    // region attendance (ported from the PWA's hot-path tests)

    @Test
    fun setsAndClearsASingleMark() = runBlocking {
        repo.setMark(registerId, enrollmentId, 11, MORNING, PRESENT)

        assertEquals(listOf(AttendanceMark(registerId, enrollmentId, 11, MORNING, PRESENT)), marks())

        repo.setMark(registerId, enrollmentId, 11, MORNING, ABSENT)
        assertEquals(listOf(ABSENT), marks().map { it.status })

        repo.setMark(registerId, enrollmentId, 11, MORNING, null)
        assertTrue(marks().isEmpty())
    }

    @Test
    fun preservesHiddenStruckOffAttendanceDuringBulkMarkingAndClearing() = runBlocking {
        repo.setMark(registerId, enrollmentId, 11, MORNING, PRESENT)
        val struckOff = db.enrollments().getById(enrollmentId)!!
        db.enrollments().update(struckOff.copy(struckOffOn = LocalDate.parse("2026-08-11")))

        val hidden = snapshot()
        assertTrue(hidden.attendanceRows.isEmpty())
        assertEquals(1, hidden.monthRows.size)

        repo.bulkSetMarks(registerId, 11, MORNING, ABSENT)
        repo.bulkSetMarks(registerId, 11, MORNING, null)

        assertEquals(listOf(PRESENT), marks().map { it.status })

        db.enrollments().update(struckOff.copy(struckOffOn = null))
        assertEquals(1, snapshot().attendanceRows.size)
    }

    @Test
    fun bulkMarkingAffectsOnlyVisibleStudentsActiveThatDay() = runBlocking {
        val second = enrol(classId, "A-002", "2", "2026-08-01")
        val joinsLater = enrol(classId, "A-003", "3", "2026-08-12")
        val otherClass = addClass("5", "B")
        val outsider = enrol(otherClass, "B-001", "1", "2026-08-01")

        repo.bulkSetMarks(registerId, 11, AFTERNOON, LEAVE)

        val written = marks()
        assertEquals(setOf(enrollmentId, second), written.map { it.enrollmentId }.toSet())
        assertTrue(written.all { it.status == LEAVE && it.session == AFTERNOON && it.day == 11 })
        assertFalse(written.any { it.enrollmentId == joinsLater || it.enrollmentId == outsider })

        repo.bulkSetMarks(registerId, 11, MORNING, PRESENT)
        repo.bulkSetMarks(registerId, 11, AFTERNOON, null)
        assertEquals(setOf(MORNING), marks().map { it.session }.toSet())
        assertEquals(2, marks().size)
    }

    @Test
    fun clearsMarksWhenASchoolHolidayIsCreated() = runBlocking {
        repo.setMark(registerId, enrollmentId, 11, MORNING, PRESENT)
        repo.setMark(registerId, enrollmentId, 11, AFTERNOON, ABSENT)
        repo.setMark(registerId, enrollmentId, 10, MORNING, PRESENT)

        repo.toggleHoliday(registerId, 11)

        val holidays = db.holidays().getForRegister(registerId)
        assertEquals(1, holidays.size)
        assertEquals("School holiday", holidays[0].title)
        assertEquals(listOf(10), marks().map { it.day })
    }

    @Test
    fun storesTheReasonSuppliedForAHoliday() = runBlocking {
        repo.toggleHoliday(registerId, 11, "  Independence Day ")
        assertEquals("Independence Day", db.holidays().getForRegister(registerId).single().title)

        repo.toggleHoliday(registerId, 10, "   ")
        assertEquals("School holiday", db.holidays().getForRegister(registerId).first { it.day == 10 }.title)
    }

    @Test
    fun togglingAnExistingHolidayRemovesItWithoutRestoringMarks() = runBlocking {
        repo.setMark(registerId, enrollmentId, 11, MORNING, PRESENT)
        repo.toggleHoliday(registerId, 11, "Strike")
        repo.toggleHoliday(registerId, 11)

        assertTrue(db.holidays().getForRegister(registerId).isEmpty())
        assertTrue(marks().isEmpty())

        repo.setMark(registerId, enrollmentId, 11, MORNING, PRESENT)
        assertEquals(1, marks().size)
    }

    @Test
    fun ignoresAttendanceEditsOutsideTheSevenDayWindow() = runBlocking {
        // 4 August is eight days before "today", 13 August is tomorrow.
        repo.setMark(registerId, enrollmentId, 4, MORNING, PRESENT)
        repo.setMark(registerId, enrollmentId, 13, MORNING, PRESENT)
        repo.bulkSetMarks(registerId, 4, MORNING, PRESENT)
        repo.toggleHoliday(registerId, 4, "Too old")
        assertTrue(marks().isEmpty())
        assertTrue(db.holidays().getForRegister(registerId).isEmpty())

        // 5 August is exactly seven days ago and still editable.
        repo.setMark(registerId, enrollmentId, 5, MORNING, PRESENT)
        assertEquals(1, marks().size)
    }

    @Test
    fun ignoresEditsOldEnoughToBeInAPreviousRegister() = runBlocking {
        val july = repo.createRegister(classId, 2026, 7).id
        repo.setMark(july, enrollmentId, 31, MORNING, PRESENT)
        assertTrue(db.attendance().getForRegister(july).isEmpty())
    }

    @Test
    fun ignoresMarksOnWeekendsDeclaredHolidaysAndDaysBeforeAdmission() = runBlocking {
        val late = enrol(classId, "A-002", "2", "2026-08-12")
        // Saturday 8 August.
        repo.setMark(registerId, enrollmentId, 8, MORNING, PRESENT)
        repo.bulkSetMarks(registerId, 8, MORNING, PRESENT)
        assertTrue(marks().isEmpty())

        // Admitted on the 12th: not on the roll on the 11th.
        repo.setMark(registerId, late, 11, MORNING, PRESENT)
        assertTrue(marks().isEmpty())
        repo.setMark(registerId, late, 12, MORNING, PRESENT)
        assertEquals(1, marks().size)

        repo.toggleHoliday(registerId, 10, "Closed")
        repo.setMark(registerId, enrollmentId, 10, MORNING, PRESENT)
        repo.bulkSetMarks(registerId, 10, MORNING, PRESENT)
        assertEquals(listOf(12), marks().map { it.day })
    }

    @Test
    fun ignoresStrangersAndMissingRegisters() = runBlocking {
        val otherClass = addClass("5", "B")
        val outsider = enrol(otherClass, "B-001", "1", "2026-08-01")
        repo.setMark(registerId, outsider, 11, MORNING, PRESENT)
        repo.setMark(registerId, 424242, 11, MORNING, PRESENT)
        repo.setMark(555, enrollmentId, 11, MORNING, PRESENT)
        repo.toggleHoliday(555, 11)
        assertTrue(marks().isEmpty())
    }

    @Test
    fun editWindowFollowsTheClock() = runBlocking {
        clock.now = Instant.parse("2026-08-20T09:00:00Z")
        repo.setMark(registerId, enrollmentId, 11, MORNING, PRESENT)
        assertTrue(marks().isEmpty())
        repo.setMark(registerId, enrollmentId, 13, MORNING, PRESENT)
        assertEquals(1, marks().size)
    }

    // endregion

    // region fees, installment meta, remarks

    @Test
    fun savesFeeEntriesAndReplacesPreviousAmountsPerInstallment() = runBlocking {
        repo.saveFeeEntries(
            registerId,
            enrollmentId,
            listOf(
                InstallmentFees(FIRST, FeeAmounts(ftf = 50_000, ff = 1_000)),
                InstallmentFees(SECOND, FeeAmounts(arrears = 2_550)),
            ),
        )
        repo.saveFeeEntries(registerId, enrollmentId, listOf(InstallmentFees(FIRST, FeeAmounts(ftf = 60_000))))

        val entries = db.fees().getEntries(registerId).associateBy { it.installment }
        assertEquals(FeeAmounts(ftf = 60_000), entries.getValue(FIRST).amounts)
        assertEquals(FeeAmounts(arrears = 2_550), entries.getValue(SECOND).amounts)
        assertNull(entries[THIRD])
    }

    @Test
    fun savesInstallmentMetaWithTrimmedReceiver() = runBlocking {
        repo.saveInstallmentMeta(registerId, FIRST, 120_000, "  Mr Ali ")
        repo.saveInstallmentMeta(registerId, FIRST, 150_000, "Mr Ali")
        repo.saveInstallmentMeta(registerId, THIRD, 0, "")

        val meta = snapshot().installmentMeta
        assertEquals(150_000L, meta.getValue(FIRST).rate)
        assertEquals("Mr Ali", meta.getValue(FIRST).receiverName)
        assertEquals("", meta.getValue(THIRD).receiverName)
        assertNull(meta[SECOND])
        assertEquals(150_000L, snapshot().totalInstallmentRate)
    }

    @Test
    fun savesTrimmedRemarksAndDeletesBlankOnes() = runBlocking {
        repo.saveRemark(registerId, enrollmentId, "  Sick on 10th ")
        assertEquals("Sick on 10th", snapshot().remarkOf(enrollmentId))

        repo.saveRemark(registerId, enrollmentId, "Left early")
        assertEquals("Left early", snapshot().remarkOf(enrollmentId))

        repo.saveRemark(registerId, enrollmentId, "   ")
        assertEquals("", snapshot().remarkOf(enrollmentId))
        assertTrue(snapshot().remarks.isEmpty())
    }

    // endregion

    // region observeRegister

    @Test
    fun observeRegisterEmitsNullForAMissingRegister() = runBlocking {
        assertNull(withTimeout(10_000) { repo.observeRegister(9999).first() })
    }

    @Test
    fun observeRegisterReflectsMutations() = runBlocking {
        repo.setMark(registerId, enrollmentId, 11, MORNING, PRESENT)
        repo.setMark(registerId, enrollmentId, 11, AFTERNOON, ABSENT)
        repo.toggleHoliday(registerId, 10, "Closed")

        val data = snapshot()
        assertEquals(registerId, data.register.id)
        assertEquals("5", data.classGroup.className)
        assertEquals("Test School", data.settings?.schoolName)
        assertEquals(PRESENT, data.statusOf(enrollmentId, 11, MORNING))
        assertEquals(ABSENT, data.statusOf(enrollmentId, 11, AFTERNOON))
        assertNull(data.statusOf(enrollmentId, 12, MORNING))
        assertEquals(mapOf(10 to "Closed"), data.holidayTitles)
        assertTrue(data.isHoliday(10))
        assertTrue(data.isHoliday(8)) // Saturday
        assertFalse(data.isHoliday(11))
        assertEquals(1, data.presentSessionsOf(enrollmentId))
        assertEquals(1, data.monthPresentSessions)
        assertEquals(1, data.presentOn(11, MORNING))
        assertEquals(0, data.presentOn(11, AFTERNOON))
        assertTrue(data.isDayEditable(11, LocalDate.parse("2026-08-12")))
        assertFalse(data.isDayEditable(4, LocalDate.parse("2026-08-12")))

        // 21 weekdays in August 2026, minus the declared holiday on a Monday-Friday.
        assertEquals((21 - 1) * 2, data.workingTimings)
        assertEquals(1.0 / ((21 - 1) * 2), data.averageAttendance, 1e-9)

        // A later write shows up on the same flow subscription.
        val flow = repo.observeRegister(registerId)
        repo.setMark(registerId, enrollmentId, 12, MORNING, LEAVE)
        val updated = withTimeout(10_000) { flow.first { it?.statusOf(enrollmentId, 12, MORNING) == LEAVE }!! }
        assertEquals(LEAVE, updated.statusOf(enrollmentId, 12, MORNING))
    }

    @Test
    fun observeRegisterSeparatesAttendanceRowsFromMonthRowsInRollOrder() = runBlocking {
        enrol(classId, "A-010", "10", "2026-08-01")
        enrol(classId, "A-002", "2", "2026-08-01")
        val struckOff = enrol(classId, "A-003", "3", "2026-08-01", struckOffOn = "2026-08-05")
        enrol(classId, "A-004", "4", "2026-07-01", struckOffOn = "2026-07-20") // gone before the month
        enrol(classId, "A-005", "5", "2026-09-01") // joins after the month
        val otherClass = addClass("6", "A")
        enrol(otherClass, "C-001", "1", "2026-04-01")

        val data = snapshot()
        assertEquals(listOf("1", "2", "3", "10"), data.monthRows.map { it.enrollment.rollNumber })
        assertEquals(listOf("1", "2", "10"), data.attendanceRows.map { it.enrollment.rollNumber })
        assertTrue(data.monthRows.any { it.enrollment.id == struckOff })

        // Marks of the hidden student still count towards the month total and the fee list.
        db.attendance().upsert(AttendanceMark(registerId, struckOff, 3, MORNING, PRESENT))
        db.attendance().upsert(AttendanceMark(registerId, enrollmentId, 3, MORNING, PRESENT))
        val withMarks = snapshot()
        assertEquals(2, withMarks.monthPresentSessions)
        assertEquals(1, withMarks.visiblePresentTotal)
        assertEquals(1, withMarks.presentOn(3, MORNING))

        // All four admitted on 1 August; A-003 is struck off on the 5th.
        assertEquals(4, data.movement.beginning)
        assertEquals(3, data.movement.end)
        assertEquals(4, data.movement.admitted)
        assertEquals(1, data.movement.struckOff)
    }

    @Test
    fun observeRegisterComputesBroughtForwardFromEarlierRegistersInTheAcademicYear() = runBlocking {
        val june = repo.createRegister(classId, 2026, 6).id
        val july = repo.createRegister(classId, 2026, 7).id
        val march = repo.createRegister(classId, 2026, 3).id // previous academic year (April start)
        val september = repo.createRegister(classId, 2026, 9).id // later, so not "earlier"
        val otherClass = addClass("5", "B")
        val outsider = enrol(otherClass, "B-001", "1", "2026-04-01")
        val otherJuly = repo.createRegister(otherClass, 2026, 7).id

        suspend fun put(register: Long, enrollment: Long, day: Int, session: Session, status: AttendanceStatus) =
            db.attendance().upsert(AttendanceMark(register, enrollment, day, session, status))
        put(june, enrollmentId, 1, MORNING, PRESENT)
        put(june, enrollmentId, 1, AFTERNOON, PRESENT)
        put(june, enrollmentId, 2, MORNING, ABSENT)
        put(july, enrollmentId, 1, MORNING, PRESENT)
        put(march, enrollmentId, 2, MORNING, PRESENT)
        put(september, enrollmentId, 1, MORNING, PRESENT)
        put(otherJuly, outsider, 1, MORNING, PRESENT)

        val data = snapshot()
        assertEquals(3, data.broughtForwardOf(enrollmentId))
        assertEquals(0, data.broughtForwardOf(outsider))
        assertEquals(3, data.broughtForwardTotal)
        assertEquals(3, data.visibleBroughtForwardTotal)

        // Live: a new mark in an earlier register updates the open register's snapshot.
        val flow = repo.observeRegister(registerId)
        put(july, enrollmentId, 2, MORNING, PRESENT)
        val updated = withTimeout(10_000) { flow.first { it?.broughtForwardOf(enrollmentId) == 4 }!! }
        assertEquals(4, updated.broughtForwardOf(enrollmentId))
    }

    @Test
    fun observeRegisterHasNoBroughtForwardWithoutSettings() = runBlocking {
        val bare = inMemoryDatabase() // no school settings saved
        try {
            val bareRepo = RegisterRepository(bare, clock, TimeZone.UTC)
            val group = bare.classGroups().insert(ClassGroup(className = "5", section = "A", createdAt = clock.now))
            val student = bare.students().insert(Student(admissionNumber = "A-001", name = "Ali", createdAt = clock.now))
            val enrollment = bare.enrollments().insert(
                Enrollment(studentId = student, classGroupId = group, rollNumber = "1", admittedOn = LocalDate.parse("2026-04-01")),
            )
            val june = bareRepo.createRegister(group, 2026, 6).id
            val august = bareRepo.createRegister(group, 2026, 8).id
            bare.attendance().upsert(AttendanceMark(june, enrollment, 1, MORNING, PRESENT))

            val data = withTimeout(10_000) { bareRepo.observeRegister(august).first()!! }
            assertNull(data.settings)
            assertTrue(data.broughtForwardByEnrollment.isEmpty())
        } finally {
            bare.close()
        }
    }

    @Test
    fun observeRegisterDerivesFeeTotals() = runBlocking {
        val second = enrol(classId, "A-002", "2", "2026-08-01")
        val struckOff = enrol(classId, "A-003", "3", "2026-08-01", struckOffOn = "2026-08-05")
        repo.saveFeeEntries(
            registerId,
            enrollmentId,
            listOf(InstallmentFees(FIRST, FeeAmounts(ftf = 1_000, ff = 100)), InstallmentFees(SECOND, FeeAmounts(ftf = 500))),
        )
        repo.saveFeeEntries(registerId, second, listOf(InstallmentFees(FIRST, FeeAmounts(ftf = 2_000))))
        repo.saveFeeEntries(registerId, struckOff, listOf(InstallmentFees(THIRD, FeeAmounts(dcf = 700))))
        repo.saveInstallmentMeta(registerId, FIRST, 3_000, "Ali")
        repo.saveInstallmentMeta(registerId, SECOND, 4_000, "Sara")

        val data = snapshot()
        assertEquals(FeeAmounts(ftf = 1_500, ff = 100), data.feesOf(enrollmentId))
        assertEquals(FeeAmounts(ftf = 2_000), data.feesOf(second))
        assertEquals(FeeAmounts.EMPTY, data.feesOf(99_999))
        assertEquals(mapOf(FIRST to FeeAmounts(ftf = 1_000, ff = 100), SECOND to FeeAmounts(ftf = 500), THIRD to FeeAmounts.EMPTY), data.feeEntriesOf(enrollmentId))
        assertEquals(FeeAmounts(ftf = 3_000, ff = 100), data.installmentTotals.getValue(FIRST))
        assertEquals(FeeAmounts(dcf = 700), data.installmentTotals.getValue(THIRD))
        assertEquals(mapOf(FIRST to 2, SECOND to 1, THIRD to 1), data.installmentStudentCounts)
        assertEquals(4, data.totalInstallmentStudents)
        assertEquals(7_000L, data.totalInstallmentRate)
        assertEquals(FeeAmounts(ftf = 3_500, ff = 100, dcf = 700), data.installmentGrandTotals)
        // The Fees tab total covers every student on the roll in the month, struck off included.
        assertEquals(4_300L, data.feeMonthTotals.total)
    }

    @Test
    fun observeRegisterBuildsAttendanceReportsAndAbsenceMessages() = runBlocking {
        val second = enrol(classId, "A-002", "2", "2026-08-01")
        repo.setMark(registerId, enrollmentId, 11, MORNING, PRESENT)
        repo.setMark(registerId, enrollmentId, 11, AFTERNOON, PRESENT)
        repo.setMark(registerId, second, 11, MORNING, ABSENT)
        repo.setMark(registerId, second, 11, AFTERNOON, ABSENT)
        db.settings().upsert(
            SchoolSettings(
                schoolName = "Test School",
                academicYearStartMonth = 4,
                classInchargeName = "Ms Khan",
                updatedAt = clock.now,
            ),
        )

        val data = snapshot()
        val report = data.report(ReportPeriodType.DAILY, 11)
        assertEquals(2, report.present)
        assertEquals(2, report.absent)
        assertEquals("Tuesday, August 11, 2026", report.period.label)

        val recipients = absenceMessageRecipients(report, data.classGroup, data.settings)
        assertEquals(1, recipients.size)
        assertEquals(second, recipients[0].student.row.enrollment.id)
        assertEquals(
            "Dear Parent/Guardian, Student A-002 was marked absent for both timings on Tuesday, August 11, 2026 " +
                "at Test School. Please contact Ms Khan, the class incharge, if this is unexpected.",
            recipients[0].message,
        )
    }

    @Test
    fun observeRegisterListItemsPairRegistersWithTheirClassNewestFirst() = runBlocking {
        val other = addClass("6", "B")
        repo.createRegister(other, 2026, 9)

        val items = repo.observeRegisterListItems().first()
        assertEquals(listOf(9 to "6", 8 to "5"), items.map { it.register.month to it.classGroup?.className })
    }

    // endregion
}

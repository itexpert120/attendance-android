package com.itexpert120.attendanceManager.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.data.repo.InstallmentFees
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.data.repo.RegisterRepository
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.support.FakePhotoStore
import com.itexpert120.attendanceManager.support.MutableClock
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

/** Renders the register and report PDFs from a seeded August 2026 register ("today" is 12 August). */
@RunWith(AndroidJUnit4::class)
class RegisterPdfTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = MutableClock(Instant.parse("2026-08-12T09:00:00Z"))
    private lateinit var db: AttendanceDatabase
    private lateinit var repo: RegisterRepository
    private var registerId = 0L

    @Before
    fun setUp() = runBlocking {
        db = inMemoryDatabase()
        repo = RegisterRepository(db, clock, TimeZone.UTC)
        db.settings().upsert(
            SchoolSettings(schoolName = "Test School", academicYearStartMonth = 4, classInchargeName = "Ms. Ayesha", updatedAt = clock.now),
        )
        val classId = db.classGroups().insert(ClassGroup(className = "5", section = "A", createdAt = clock.now))
        registerId = repo.createRegister(classId, 2026, 8).id
        val enrollments = (1..3).map { n ->
            val studentId = db.students().insert(Student(admissionNumber = "A-00$n", name = "Student $n s/o Parent $n", createdAt = clock.now))
            db.enrollments().insert(
                Enrollment(studentId = studentId, classGroupId = classId, rollNumber = "$n", admittedOn = LocalDate.parse("2026-08-01")),
            )
        }
        for (day in listOf(10, 11)) {
            repo.setMark(registerId, enrollments[0], day, AttendanceStatus.PRESENT)
            repo.setMark(registerId, enrollments[1], day, if (day == 10) AttendanceStatus.ABSENT else AttendanceStatus.LEAVE)
        }
        repo.saveFeeEntries(registerId, enrollments[0], listOf(InstallmentFees(InstallmentNumber.FIRST, FeeAmounts(ftf = 50_00, ff = 10_50))))
        repo.saveInstallmentMeta(registerId, InstallmentNumber.FIRST, 50_00, "Mr. Khan")
        repo.saveRemark(registerId, enrollments[1], "Absent with a note from home")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun snapshot(): RegisterData = withTimeout(10_000) { repo.observeRegister(registerId).first()!! }

    @Test
    fun registerPdfHasPagesAndBytes() = runBlocking {
        val doc = RegisterPdf.build(snapshot(), FakePhotoStore())
        val pages = doc.pageCount
        val file = PdfExports.write(context, "register-test", doc)
        assertTrue("expected at least one page, got $pages", pages >= 1)
        assertTrue("the PDF file is empty", file.length() > 0)
        file.delete()
        Unit
    }

    @Test
    fun reportAndAbsenteePdfsHavePagesAndBytes() = runBlocking {
        val data = snapshot()
        val report = data.report(ReportPeriodType.DAILY, 11)
        listOf(
            AttendanceReportPdf.buildReport(data, report, FakePhotoStore()),
            AttendanceReportPdf.buildAbsentees(data, report, FakePhotoStore()),
            AttendanceReportPdf.buildReport(data, data.report(ReportPeriodType.MONTHLY, 11), FakePhotoStore()),
        ).forEachIndexed { index, doc ->
            val pages = doc.pageCount
            val file = PdfExports.write(context, "report-test-$index", doc)
            assertTrue("expected at least one page, got $pages", pages >= 1)
            assertTrue("the PDF file is empty", file.length() > 0)
            file.delete()
        }
    }
}

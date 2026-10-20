package com.itexpert120.attendanceManager.export

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.data.db.addClass
import com.itexpert120.attendanceManager.data.db.addStudent
import com.itexpert120.attendanceManager.data.db.enroll
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.DiaryPhotoInput
import com.itexpert120.attendanceManager.data.repo.HomeworkItemInput
import com.itexpert120.attendanceManager.data.repo.HomeworkReportInput
import com.itexpert120.attendanceManager.data.repo.HomeworkRepository
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.data.repo.TestInput
import com.itexpert120.attendanceManager.data.repo.TestReportFilter
import com.itexpert120.attendanceManager.data.repo.TestRepository
import com.itexpert120.attendanceManager.data.repo.TestResultInput
import com.itexpert120.attendanceManager.domain.DateRange
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.support.FakePhotoStore
import com.itexpert120.attendanceManager.support.MutableClock
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Renders the Test result sheet, the Subject / Student Reports and the Daily Homework Report from seeded data. */
@RunWith(AndroidJUnit4::class)
class TestHomeworkPdfTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = MutableClock()
    private val photos = FakePhotoStore()
    private lateinit var db: AttendanceDatabase
    private lateinit var tests: TestRepository
    private lateinit var subjects: SubjectRepository
    private lateinit var classes: ClassRepository
    private lateinit var settings: SettingsRepository
    private lateinit var homework: HomeworkRepository
    private var classId = 0L
    private var testId = 0L
    private var mathsId = 0L
    private val enrollments = mutableListOf<Long>()
    private val studentIds = mutableListOf<Long>()
    private val testDate = LocalDate.parse("2026-08-14")

    @Before
    fun setUp(): Unit = runBlocking {
        db = inMemoryDatabase()
        tests = TestRepository(db, clock)
        subjects = SubjectRepository(db, clock)
        classes = ClassRepository(db, clock)
        settings = SettingsRepository(db, photos, clock)
        homework = HomeworkRepository(db, photos, clock)
        db.settings().upsert(
            SchoolSettings(schoolName = "Test School", academicYearStartMonth = 4, classInchargeName = "Ms. Ayesha", updatedAt = clock.now),
        )
        classId = db.addClass("7", "A")
        (1..4).forEach { n ->
            val studentId = db.addStudent("A-00$n", "Student $n s/o Parent $n")
            studentIds += studentId
            enrollments += db.enroll(studentId, classId, roll = "$n")
        }
        mathsId = subjects.saveSubject("Mathematics").id
        val test = tests.createTest(TestInput("Midterm", mathsId, classId, testDate, totalMarks = 4000))
        testId = test.id
        tests.setTestResult(testId, enrollments[0], TestResultInput.Marks(3250))
        tests.setTestResult(testId, enrollments[1], TestResultInput.Marks(1800))
        tests.setTestResult(testId, enrollments[2], TestResultInput.Absent)
        tests.createTest(TestInput("Quiz 1", mathsId, classId, LocalDate.parse("2026-08-20"), totalMarks = 1000))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun assertRenders(name: String, doc: PdfDoc) {
        val pages = doc.pageCount
        val file = PdfExports.write(context, name, doc)
        assertTrue("expected at least one page, got $pages", pages >= 1)
        assertTrue("the PDF file is empty", file.length() > 0)
        file.delete()
    }

    @Test
    fun testResultSheetHasPages() = runBlocking {
        val test = tests.getTest(testId)!!
        val rows = withTimeout(10_000) { tests.observeRoster(testId).first() }
        val doc = TestPdf.buildTest(
            test,
            subjects.get(mathsId),
            classes.getClass(classId),
            rows,
            settings.getSettings(),
            photos,
        )
        assertRenders("test-sheet", doc)
    }

    @Test
    fun subjectAndStudentReportsHavePages() = runBlocking {
        val range = DateRange(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"))
        val subjectList = subjects.observe().first()
        val classList = classes.observeClasses().first()
        val subjectReport = withTimeout(10_000) { tests.observeReport(TestReportFilter(range)).first() }
        assertRenders(
            "subject-report",
            TestPdf.buildReport(subjectReport, "August 2026", subjectList, classList, null, settings.getSettings(), photos),
        )

        val student = db.students().getById(studentIds[0])!!
        val studentReport = withTimeout(10_000) {
            tests.observeReport(TestReportFilter(range, classGroupId = classId, studentId = student.id)).first()
        }
        assertRenders(
            "student-report",
            TestPdf.buildReport(studentReport, "August 2026", subjectList, classList, student, settings.getSettings(), photos),
        )

        // An empty period still renders a page.
        val empty = withTimeout(10_000) {
            tests.observeReport(TestReportFilter(DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31")))).first()
        }
        assertRenders("empty-report", TestPdf.buildReport(empty, "January 2026", subjectList, classList, null, null, logo = null))
        Unit
    }

    @Test
    fun typedHomeworkReportHasPages() = runBlocking {
        val science = subjects.saveSubject("Science").id
        val report = homework.createReport(
            HomeworkReportInput(
                classGroupId = classId,
                date = LocalDate.parse("2026-08-14"),
                inchargeName = "Ms. Ayesha",
                parentNote = "Please check the diary and sign.",
                items = listOf(
                    HomeworkItemInput(mathsId, "Exercise 4.2, questions 1–6.\nLearn the table of 7."),
                    HomeworkItemInput(science, "Read chapter 3."),
                ),
            ),
        )
        assertRenders("homework-typed", HomeworkPdf.load(report.id, homework, classes, subjects, settings, photos))
    }

    @Test
    fun diaryPhotoHomeworkReportHasPages() = runBlocking {
        val bitmap = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF88AACC.toInt()) }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val report = homework.createReport(
            HomeworkReportInput(
                classGroupId = classId,
                date = LocalDate.parse("2026-08-15"),
                inchargeName = "Ms. Ayesha",
                parentNote = "Please check the diary and sign.",
                photo = DiaryPhotoInput.New(bytes, "image/png"),
            ),
        )
        assertRenders("homework-photo", HomeworkPdf.load(report.id, homework, classes, subjects, settings, photos))
    }
}

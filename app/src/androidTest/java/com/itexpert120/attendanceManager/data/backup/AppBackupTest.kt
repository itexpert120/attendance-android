package com.itexpert120.attendanceManager.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.data.repo.DiaryPhotoInput
import com.itexpert120.attendanceManager.data.repo.HomeworkItemInput
import com.itexpert120.attendanceManager.data.repo.HomeworkReportInput
import com.itexpert120.attendanceManager.data.repo.HomeworkRepository
import com.itexpert120.attendanceManager.data.repo.InstallmentFees
import com.itexpert120.attendanceManager.data.repo.PhotoChange
import com.itexpert120.attendanceManager.data.repo.RegisterRepository
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.data.repo.StudentInput
import com.itexpert120.attendanceManager.data.repo.StudentRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.data.repo.TestInput
import com.itexpert120.attendanceManager.data.repo.TestRepository
import com.itexpert120.attendanceManager.data.repo.TestResultInput
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.support.FakePhoneNormalizer
import com.itexpert120.attendanceManager.support.FakePhotoStore
import com.itexpert120.attendanceManager.support.MutableClock
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AppBackupTest {
    private val clock = MutableClock()

    /** The phone the backup is made on. */
    private lateinit var source: AttendanceDatabase
    private lateinit var sourcePhotos: FakePhotoStore

    /** The phone it is restored on, which already has other data. */
    private lateinit var target: AttendanceDatabase
    private lateinit var targetPhotos: FakePhotoStore

    @Before
    fun setUp() = runBlocking {
        source = inMemoryDatabase()
        sourcePhotos = FakePhotoStore()
        target = inMemoryDatabase()
        targetPhotos = FakePhotoStore()
        seedSource()
        seedTarget()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun roundTripRestoresEveryTableAndPhoto() = runTest {
        val zip = export()
        val expected = dump(source, sourcePhotos)

        val summary = AppBackup(target, targetPhotos, clock).inspect(ByteArrayInputStream(zip))
        assertEquals("Green Valley", summary.schoolName)
        assertEquals(2, summary.counts[BackupTables.STUDENTS])
        assertEquals(3, summary.photoCount)

        val result = AppBackup(target, targetPhotos, clock).restore(ByteArrayInputStream(zip))
        assertEquals("Green Valley", result.schoolName)
        assertEquals(3, result.photoCount)
        assertEquals(0, result.missingPhotos)

        assertEquals(expected, dump(target, targetPhotos))
        // The replaced data's photos are gone; only the backup's remain.
        assertEquals(setOf("logo", "ayesha", "diary"), targetPhotos.files.values.map { it.decodeToString() }.toSet())
        assertEquals(3, targetPhotos.files.size)

        // Every table really had rows, so the comparison above covered it.
        for ((table, count) in result.counts) assertTrue("$table is empty", count > 0)
    }

    @Test
    fun rejectsBrokenFilesAndChangesNothing() = runTest {
        val good = export()
        val before = dump(target, targetPhotos)
        val filesBefore = targetPhotos.files.keys.toSet()
        val backup = AppBackup(target, targetPhotos, clock)

        suspend fun assertRejected(bytes: ByteArray, message: String? = null) {
            try {
                backup.restore(ByteArrayInputStream(bytes))
                fail("Expected the file to be rejected")
            } catch (e: ValidationException) {
                if (message != null) assertEquals(message, e.message)
            }
            assertEquals(before, dump(target, targetPhotos))
            assertEquals(filesBefore, targetPhotos.files.keys.toSet())
        }

        assertRejected("this is not a zip file at all".encodeToByteArray(), AppBackup.NOT_A_BACKUP)
        assertRejected(good.copyOf(good.size / 2))
        // Cut exactly where the end marker starts: every entry before it is intact, yet photos could be missing.
        assertRejected(good.copyOf(localHeaderOffset(good, "complete")))
        assertRejected(zipOf("notes.txt" to "hello"), AppBackup.NOT_A_BACKUP)
        assertRejected("""{"formatName":"dexie","data":{}}""".encodeToByteArray(), AppBackup.LOOKS_LIKE_WEB_BACKUP)
        assertRejected(
            zipOf("manifest.json" to """{"format":"${AppBackup.FORMAT_NAME}","version":99,"createdAt":0}"""),
            AppBackup.NEWER_VERSION,
        )
        assertRejected(
            zipOf(
                "manifest.json" to """{"format":"${AppBackup.FORMAT_NAME}","version":1,"createdAt":0}""",
                "data.json" to """{"enrollments":[{"id":1,"studentId":7,"classGroupId":3,"rollNumber":"1","admittedOn":"2026-08-01"}]}""",
                "complete" to "0",
            ),
        )
    }

    private suspend fun export(): ByteArray {
        val out = ByteArrayOutputStream()
        AppBackup(source, sourcePhotos, clock).export(out)
        return out.toByteArray()
    }

    /** Where the local header of the entry [name] starts (local headers precede the central directory). */
    private fun localHeaderOffset(zip: ByteArray, name: String): Int {
        val needle = name.encodeToByteArray()
        val at = (0..zip.size - needle.size).first { i -> needle.indices.all { zip[i + it] == needle[it] } }
        return at - LOCAL_HEADER_SIZE
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Every row of every table, sorted, with photo file names replaced by the photo's content. */
    private suspend fun dump(db: AttendanceDatabase, photos: FakePhotoStore): List<Any?> {
        fun content(name: String?): String? = name?.let { photos.files[it]?.decodeToString() ?: "missing $it" }
        fun <T> List<T>.rows(): List<String> = map { it.toString() }.sorted()
        return listOf(
            db.settings().get()?.let { it.copy(logoFile = content(it.logoFile)) },
            db.classGroups().getAll().rows(),
            db.students().getAll().map { it.copy(photoFile = content(it.photoFile)) }.rows(),
            db.enrollments().getAll().rows(),
            db.registers().getAll().rows(),
            db.attendance().getAll().rows(),
            db.holidays().getAll().rows(),
            db.fees().getAllEntries().rows(),
            db.fees().getAllMeta().rows(),
            db.fees().getAllRemarks().rows(),
            db.subjects().getAll().rows(),
            db.tests().getAll().rows(),
            db.tests().getAllRosterEntries().rows(),
            db.homework().getAllReports().map { it.copy(photoFile = content(it.photoFile)) }.rows(),
            db.homework().getAllItems().rows(),
        )
    }

    private suspend fun seedSource() {
        val db = source
        val photos = sourcePhotos
        val classId = SettingsRepository(db, photos, clock).completeSetup(
            schoolName = "Green Valley",
            className = "7",
            section = "A",
            academicYearStartMonth = 4,
            classInchargeName = "Ms Khan",
            logo = PhotoChange.Replace("logo".encodeToByteArray(), "image/png"),
        ).id

        val students = StudentRepository(db, photos, FakePhoneNormalizer(), clock)
        val ayesha = students.saveStudent(
            StudentInput(
                classGroupId = classId,
                admissionNumber = "A-001",
                rollNumber = "1",
                name = "Ayesha",
                phone = "03001234567",
                admittedOn = LocalDate(2026, 8, 1),
                dateOfBirth = LocalDate(2014, 5, 12),
                photo = PhotoChange.Replace("ayesha".encodeToByteArray(), "image/jpeg"),
            ),
        ).enrollmentId
        val bilal = students.saveStudent(
            StudentInput(classId, "A-002", "2", "Bilal", "", LocalDate(2026, 8, 1)),
        ).enrollmentId

        val registers = RegisterRepository(db, clock, TimeZone.UTC)
        val registerId = registers.createRegister(classId, 2026, 8).id
        registers.setMark(registerId, ayesha, 14, Session.FIRST, AttendanceStatus.PRESENT)
        registers.setMark(registerId, bilal, 14, Session.SECOND, AttendanceStatus.LEAVE)
        registers.toggleHoliday(registerId, 13, "Founders' Day")
        registers.saveFeeEntries(registerId, ayesha, listOf(InstallmentFees(InstallmentNumber.FIRST, FeeAmounts(ftf = 10000, ff = 500))))
        registers.saveInstallmentMeta(registerId, InstallmentNumber.FIRST, 50000, "Mr Khan")
        registers.saveRemark(registerId, bilal, "Unwell this week")

        val subjects = SubjectRepository(db, clock)
        val maths = subjects.saveSubject("Maths").id
        subjects.archiveSubject(subjects.saveSubject("Art").id)

        val tests = TestRepository(db, clock)
        val testId = tests.createTest(TestInput("Unit 1", maths, classId, LocalDate(2026, 8, 14), 4000)).id
        tests.setTestResult(testId, ayesha, TestResultInput.Marks(3250))
        tests.setTestResult(testId, bilal, TestResultInput.Absent)

        val homework = HomeworkRepository(db, photos, clock)
        homework.createReport(
            HomeworkReportInput(classId, LocalDate(2026, 8, 14), "Ms Khan", "Please sign.", listOf(HomeworkItemInput(maths, "Exercise 4"))),
        )
        homework.createReport(
            HomeworkReportInput(
                classId, LocalDate(2026, 8, 15), "Ms Khan", "Please sign.",
                photo = DiaryPhotoInput.New("diary".encodeToByteArray(), "image/jpeg"),
            ),
        )
    }

    private suspend fun seedTarget() {
        val classId = SettingsRepository(target, targetPhotos, clock).completeSetup(
            schoolName = "Old School",
            className = "1",
            section = "B",
            academicYearStartMonth = 1,
            logo = PhotoChange.Replace("old logo".encodeToByteArray(), "image/png"),
        ).id
        StudentRepository(target, targetPhotos, FakePhoneNormalizer(), clock).saveStudent(
            StudentInput(
                classGroupId = classId,
                admissionNumber = "OLD-1",
                rollNumber = "1",
                name = "Old Student",
                phone = "",
                admittedOn = LocalDate(2026, 1, 10),
                photo = PhotoChange.Replace("old photo".encodeToByteArray(), "image/jpeg"),
            ),
        )
    }

    private companion object {
        /** Fixed part of a zip local file header; the file name follows it. */
        const val LOCAL_HEADER_SIZE = 30
    }
}

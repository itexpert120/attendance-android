package com.itexpert120.attendanceManager.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.support.FakePhotoStore
import com.itexpert120.attendanceManager.support.MutableClock
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class PwaBackupImporterTest {
    private lateinit var db: AttendanceDatabase
    private lateinit var photos: FakePhotoStore
    private lateinit var importer: PwaBackupImporter

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        photos = FakePhotoStore()
        importer = PwaBackupImporter(db, photos, MutableClock())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val backup = """
        {"formatName":"dexie","formatVersion":1,"data":{"databaseName":"students-attendance-register","databaseVersion":30,
        "tables":[],"data":[
          {"tableName":"settings","inbound":true,"rows":[{"id":"school","schoolName":"Green Valley","academicYearStartMonth":4,
            "currencyLabel":"Rs.","classInchargeName":"","headmasterName":"Ms Hina","logoDataUrl":"data:image/png;base64,bG9nbw==",
            "updatedAt":"2026-08-29T00:00:00.000Z"}]},
          {"tableName":"classGroups","inbound":true,"rows":[{"id":"c1","className":"7","section":"A","createdAt":"2026-08-01T00:00:00.000Z"}]},
          {"tableName":"students","inbound":true,"rows":[
            {"id":"s1","admissionNumber":"A-001","name":"Ayesha","phone":"","dateOfBirth":"2014-05-12","photoDataUrl":"data:image/jpeg;base64,dGVzdA==","createdAt":"2026-08-01T00:00:00.000Z"},
            {"id":"s2","admissionNumber":"a-001","name":"Bilal","phone":"","createdAt":"2026-08-01T00:00:00.000Z"}]},
          {"tableName":"enrollments","inbound":true,"rows":[
            {"id":"e1","studentId":"s1","classGroupId":"c1","rollNumber":"1","admittedOn":"2026-08-01"},
            {"id":"e2","studentId":"s2","classGroupId":"c1","rollNumber":"2","admittedOn":"2026-08-01"},
            {"id":"e3","studentId":"ghost","classGroupId":"c1","rollNumber":"3","admittedOn":"2026-08-01"}]},
          {"tableName":"registers","inbound":true,"rows":[{"id":"r1","classGroupId":"c1","year":2026,"month":8,"createdAt":"2026-08-01T00:00:00.000Z"}]},
          {"tableName":"attendance","inbound":true,"rows":[
            {"id":"r1:e1:3:1","registerId":"r1","enrollmentId":"e1","day":3,"session":1,"status":"P"},
            {"id":"r1:e2:3:2","registerId":"r1","enrollmentId":"e2","day":3,"session":2,"status":"A"},
            {"id":"r1:e3:3:1","registerId":"r1","enrollmentId":"e3","day":3,"session":1,"status":"P"}]},
          {"tableName":"holidays","inbound":true,"rows":[{"id":"r1:15","registerId":"r1","day":15,"title":"Holiday"}]},
          {"tableName":"feeEntries","inbound":true,"rows":[{"id":"r1:e1:1","registerId":"r1","enrollmentId":"e1","installment":1,"ftf":10000,"ff":500,"arrears":0,"lateCertificate":0,"slc":0,"dcf":0}]},
          {"tableName":"installmentMeta","inbound":true,"rows":[{"id":"r1:1","registerId":"r1","installment":1,"rate":50000,"receiverName":"Mr Khan"}]},
          {"tableName":"remarks","inbound":true,"rows":[{"id":"r1:e1","registerId":"r1","enrollmentId":"e1","text":"Remark"}]},
          {"tableName":"subjects","inbound":true,"rows":[{"id":"sub1","name":"Mathematics","normalizedName":"mathematics","createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-08-01T00:00:00.000Z"}]},
          {"tableName":"tests","inbound":true,"rows":[{"id":"t1","name":"Midterm","normalizedName":"midterm","subjectId":"sub1","classGroupId":"c1","date":"2026-08-15","totalMarks":40,"createdAt":"2026-08-15T00:00:00.000Z","updatedAt":"2026-08-15T00:00:00.000Z"}]},
          {"tableName":"testRoster","inbound":true,"rows":[
            {"id":"t1:e1","testId":"t1","enrollmentId":"e1","createdAt":"2026-08-15T00:00:00.000Z"},
            {"id":"t1:e2","testId":"t1","enrollmentId":"e2","createdAt":"2026-08-15T00:00:00.000Z"}]},
          {"tableName":"testResults","inbound":true,"rows":[
            {"id":"t1:e1","testId":"t1","enrollmentId":"e1","status":"marks","marks":32.5,"updatedAt":"2026-08-16T00:00:00.000Z"},
            {"id":"t1:e2","testId":"t1","enrollmentId":"e2","status":"absent","updatedAt":"2026-08-16T00:00:00.000Z"}]},
          {"tableName":"homeworkReports","inbound":true,"rows":[
            {"id":"h1","classGroupId":"c1","date":"2026-08-15","inchargeName":"Ms Hina","parentNote":"Sign.","items":[{"subjectId":"sub1","details":"Ex 4","order":0}],"createdAt":"2026-08-15T00:00:00.000Z","updatedAt":"2026-08-15T00:00:00.000Z"},
            {"id":"h2","classGroupId":"c1","date":"2026-08-16","inchargeName":"Ms Hina","parentNote":"Sign.","items":[],"photoDataUrl":"data:image/jpeg;base64,/9j/4AAQ","createdAt":"2026-08-16T00:00:00.000Z","updatedAt":"2026-08-16T00:00:00.000Z"}]}
        ]}}
    """.trimIndent()

    @Test
    fun importsAFullBackupIntoTheDatabase() = runTest {
        val summary = importer.inspect(backup.byteInputStream())
        assertEquals("Green Valley", summary.schoolName)
        assertEquals(3, summary.counts["attendance"])

        val result = importer.import(backup.byteInputStream())

        assertEquals("Green Valley", result.schoolName)
        assertEquals(2, result.counts["students"])
        assertEquals(2, result.counts["attendance"])
        assertTrue(result.warnings.toString(), result.warnings.isNotEmpty())

        val settings = db.settings().get()!!
        assertEquals("Ms Hina", settings.classInchargeName)
        assertNotNull(settings.logoFile)
        assertEquals("logo", photos.read(settings.logoFile!!)!!.decodeToString())

        val students = db.students().observeAll().first().sortedBy { it.id }
        assertEquals(listOf("A-001", "a-001 (2)"), students.map { it.admissionNumber })
        assertEquals(LocalDate(2014, 5, 12), students[0].dateOfBirth)
        assertEquals("test", photos.read(students[0].photoFile!!)!!.decodeToString())

        val classGroup = db.classGroups().getAll().single()
        val register = db.registers().find(classGroup.id, 2026, 8)!!
        assertEquals(2, db.attendance().getForRegister(register.id).size)
        assertEquals(1, db.holidays().getForRegister(register.id).size)
        assertEquals(1, db.fees().getEntries(register.id).size)

        val test = db.tests().observeAll().first().single()
        assertEquals(4000, test.totalMarks)
        val roster = db.tests().getRoster(test.id).sortedBy { it.enrollmentId }
        assertEquals(TestResultStatus.MARKS, roster[0].result)
        assertEquals(3250, roster[0].marks)
        assertEquals(TestResultStatus.ABSENT, roster[1].result)

        val reports = db.homework().observeReports().first()
        assertEquals(2, reports.size)
        val typed = reports.first { it.date == LocalDate(2026, 8, 15) }
        assertEquals(listOf("Ex 4"), db.homework().getItems(typed.id).map { it.details })
        assertNotNull(reports.first { it.date == LocalDate(2026, 8, 16) }.photoFile)
    }

    @Test
    fun replacesExistingDataAndDeletesItsPhotos() = runTest {
        val oldPhoto = photos.save(byteArrayOf(1), "image/png")
        val group = db.classGroups().insert(ClassGroup(className = "9", section = "Z", createdAt = Instant.fromEpochMilliseconds(0)))
        db.students().insert(
            Student(admissionNumber = "OLD-1", name = "Old", photoFile = oldPhoto, createdAt = Instant.fromEpochMilliseconds(0)),
        )

        importer.import(backup.byteInputStream())

        assertNull(db.classGroups().find("9", "Z"))
        assertNull(db.students().findByAdmissionNumber("OLD-1"))
        assertNull(photos.read(oldPhoto))
        assertEquals(1, db.classGroups().getAll().size)
        assertTrue(group > 0)
    }

    @Test
    fun aFailedImportLeavesTheDatabaseAndPhotoStoreUntouched() = runTest {
        val keptPhoto = photos.save(byteArrayOf(1), "image/png")
        db.students().insert(
            Student(admissionNumber = "KEEP-1", name = "Keep", photoFile = keptPhoto, createdAt = Instant.fromEpochMilliseconds(0)),
        )
        val filesBefore = photos.files.keys.toSet()

        // The third photo save (the diary page) fails after two files were already written: they must be cleaned up.
        val failing = object : PhotoStore by photos {
            private var saves = 0
            override suspend fun save(bytes: ByteArray, mimeType: String): String {
                if (++saves == 3) error("disk full")
                return photos.save(bytes, mimeType)
            }
        }
        try {
            PwaBackupImporter(db, failing, MutableClock()).import(backup.byteInputStream())
            fail("expected the import to fail")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }

        assertNotNull(db.students().findByAdmissionNumber("KEEP-1"))
        assertEquals(filesBefore, photos.files.keys.toSet())
    }

    @Test
    fun rejectsFilesThatAreNotBackups() = runTest {
        try {
            importer.import("""{"hello":"world"}""".byteInputStream())
            fail("expected a ValidationException")
        } catch (e: ValidationException) {
            assertEquals("This file is not an attendance register backup.", e.message)
        }
        assertTrue(db.classGroups().getAll().isEmpty())
    }
}

package com.itexpert120.attendanceManager.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.support.FakePhoneNormalizer
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

@RunWith(AndroidJUnit4::class)
class StudentRepositoryTest {
    private lateinit var db: AttendanceDatabase
    private val photos = FakePhotoStore()
    private val clock = MutableClock()
    private lateinit var repo: StudentRepository
    private lateinit var classA: ClassGroup
    private lateinit var classB: ClassGroup

    @Before
    fun setUp() = runTest {
        db = inMemoryDatabase()
        repo = StudentRepository(db, photos, FakePhoneNormalizer(), clock)
        val classes = ClassRepository(db, clock)
        classA = classes.addClass("7", "A")
        classB = classes.addClass("7", "B")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun date(text: String) = LocalDate.parse(text)

    private fun input(
        admissionNumber: String = "101",
        rollNumber: String = "1",
        classGroupId: Long = classA.id,
        name: String = "Ayesha Khan",
        phone: String = "",
        admittedOn: String = "2026-04-01",
        struckOffOn: String? = null,
        studentId: Long? = null,
        enrollmentId: Long? = null,
        photo: PhotoChange = PhotoChange.Keep,
    ) = StudentInput(
        classGroupId = classGroupId,
        admissionNumber = admissionNumber,
        rollNumber = rollNumber,
        name = name,
        phone = phone,
        admittedOn = date(admittedOn),
        studentId = studentId,
        enrollmentId = enrollmentId,
        struckOffOn = struckOffOn?.let(::date),
        photo = photo,
    )

    private suspend fun assertRejected(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected a ValidationException")
        } catch (e: ValidationException) {
            assertEquals(message, e.message)
        }
    }

    @Test
    fun savingANewStudentCreatesStudentAndEnrollment() = runTest {
        val saved = repo.saveStudent(
            input(name = "  Ayesha Khan ", admissionNumber = " 101 ", rollNumber = " 7 ", phone = " 03001234567 ")
                .copy(dateOfBirth = date("2015-06-30")),
        )

        val student = db.students().getById(saved.studentId)!!
        assertEquals("Ayesha Khan", student.name)
        assertEquals("101", student.admissionNumber)
        assertEquals("03001234567", student.phone)
        assertEquals(date("2015-06-30"), student.dateOfBirth)
        assertEquals(clock.now(), student.createdAt)
        val enrollment = db.enrollments().getById(saved.enrollmentId)!!
        assertEquals(saved.studentId, enrollment.studentId)
        assertEquals(classA.id, enrollment.classGroupId)
        assertEquals("7", enrollment.rollNumber)
        assertEquals(date("2026-04-01"), enrollment.admittedOn)
        assertNull(enrollment.struckOffOn)
    }

    @Test
    fun phoneIsNormalisedThroughThePhoneNormalizer() = runTest {
        assertRejected("Enter a valid phone number, for example 0300 1234567 or +92 300 1234567.") {
            repo.saveStudent(input(phone = "12"))
        }
        assertTrue(db.students().getAll().isEmpty())
    }

    @Test
    fun admissionNumberMustBeUniqueIgnoringCase() = runTest {
        repo.saveStudent(input(admissionNumber = "A-101", rollNumber = "1"))

        assertRejected("Admission number must be unique.") {
            repo.saveStudent(input(admissionNumber = "a-101", rollNumber = "2"))
        }
        assertEquals(1, db.students().getAll().size)
        assertEquals(1, db.enrollments().getForClass(classA.id).size)
    }

    @Test
    fun editingAStudentMayKeepTheirOwnAdmissionNumber() = runTest {
        val saved = repo.saveStudent(input(admissionNumber = "101", name = "Ayesha"))

        repo.saveStudent(
            input(admissionNumber = "101", name = "Ayesha Khan", studentId = saved.studentId, enrollmentId = saved.enrollmentId),
        )

        assertEquals("Ayesha Khan", db.students().getById(saved.studentId)!!.name)
        assertEquals(1, db.students().getAll().size)
        assertEquals(1, db.enrollments().getForClass(classA.id).size)
    }

    @Test
    fun updatingKeepsCreatedAtAndMovesTheEnrollmentFields() = runTest {
        val saved = repo.saveStudent(input())
        clock.now = kotlin.time.Instant.parse("2026-09-01T00:00:00Z")

        repo.saveStudent(
            input(rollNumber = "9", admittedOn = "2026-04-02", struckOffOn = "2026-08-01", studentId = saved.studentId, enrollmentId = saved.enrollmentId),
        )

        assertEquals(kotlin.time.Instant.parse("2026-08-15T09:00:00Z"), db.students().getById(saved.studentId)!!.createdAt)
        val enrollment = db.enrollments().getById(saved.enrollmentId)!!
        assertEquals("9", enrollment.rollNumber)
        assertEquals(date("2026-04-02"), enrollment.admittedOn)
        assertEquals(date("2026-08-01"), enrollment.struckOffOn)
    }

    @Test
    fun rollNumberCannotBeSharedWithAnActiveEnrollmentInTheSameClass() = runTest {
        repo.saveStudent(input(admissionNumber = "1", rollNumber = "5"))

        assertRejected("Roll number is already active in this class.") {
            repo.saveStudent(input(admissionNumber = "2", rollNumber = "5"))
        }
    }

    @Test
    fun rollNumberConflictIgnoresCase() = runTest {
        repo.saveStudent(input(admissionNumber = "1", rollNumber = "A1"))

        assertRejected("Roll number is already active in this class.") {
            repo.saveStudent(input(admissionNumber = "2", rollNumber = "a1"))
        }
    }

    @Test
    fun sameRollNumberInAnotherClassIsAllowed() = runTest {
        repo.saveStudent(input(admissionNumber = "1", rollNumber = "5"))

        repo.saveStudent(input(admissionNumber = "2", rollNumber = "5", classGroupId = classB.id))

        assertEquals(1, db.enrollments().getForClass(classB.id).size)
    }

    @Test
    fun rollNumberCanBeReusedAfterTheEarlierStudentWasStruckOffBeforeAdmission() = runTest {
        repo.saveStudent(input(admissionNumber = "1", rollNumber = "5", struckOffOn = "2026-06-30"))

        repo.saveStudent(input(admissionNumber = "2", rollNumber = "5", admittedOn = "2026-07-01"))

        assertEquals(2, db.enrollments().getForClass(classA.id).size)
    }

    @Test
    fun rollNumberStaysTakenOnTheStruckOffDayItself() = runTest {
        repo.saveStudent(input(admissionNumber = "1", rollNumber = "5", struckOffOn = "2026-06-30"))

        assertRejected("Roll number is already active in this class.") {
            repo.saveStudent(input(admissionNumber = "2", rollNumber = "5", admittedOn = "2026-06-30"))
        }
    }

    @Test
    fun editingAnEnrollmentDoesNotConflictWithItself() = runTest {
        val saved = repo.saveStudent(input(rollNumber = "5"))

        repo.saveStudent(input(rollNumber = "5", name = "Renamed", studentId = saved.studentId, enrollmentId = saved.enrollmentId))

        assertEquals("Renamed", db.students().getById(saved.studentId)!!.name)
    }

    @Test
    fun struckOffDateCannotBeBeforeAdmissionDate() = runTest {
        assertRejected("Struck-off date cannot be before admission date.") {
            repo.saveStudent(input(admittedOn = "2026-04-10", struckOffOn = "2026-04-09"))
        }
        assertTrue(db.students().getAll().isEmpty())
        assertTrue(db.enrollments().getForClass(classA.id).isEmpty())
    }

    @Test
    fun struckOffOnTheAdmissionDateIsAllowed() = runTest {
        val saved = repo.saveStudent(input(admittedOn = "2026-04-10", struckOffOn = "2026-04-10"))

        assertEquals(date("2026-04-10"), db.enrollments().getById(saved.enrollmentId)!!.struckOffOn)
    }

    @Test
    fun clearingTheStruckOffDateRestoresTheStudent() = runTest {
        val saved = repo.saveStudent(input(struckOffOn = "2026-05-01"))

        repo.saveStudent(input(struckOffOn = null, studentId = saved.studentId, enrollmentId = saved.enrollmentId))

        assertNull(db.enrollments().getById(saved.enrollmentId)!!.struckOffOn)
    }

    @Test
    fun existingStudentCanBeEnrolledInAnotherClass() = runTest {
        val saved = repo.saveStudent(input())

        val second = repo.saveStudent(input(classGroupId = classB.id, studentId = saved.studentId))

        assertEquals(saved.studentId, second.studentId)
        assertTrue(second.enrollmentId != saved.enrollmentId)
        assertEquals(1, db.students().getAll().size)
        assertEquals(1, db.enrollments().getForClass(classB.id).size)
    }

    @Test
    fun newPhotoIsStoredAgainstTheStudent() = runTest {
        val saved = repo.saveStudent(input(photo = PhotoChange.Replace(byteArrayOf(1, 2), "image/jpeg")))

        val file = db.students().getById(saved.studentId)!!.photoFile
        assertNotNull(file)
        assertTrue(photos.files.getValue(file!!).contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun replacingThePhotoDeletesTheOldFile() = runTest {
        val saved = repo.saveStudent(input(photo = PhotoChange.Replace(byteArrayOf(1), "image/jpeg")))
        val oldFile = db.students().getById(saved.studentId)!!.photoFile!!

        repo.saveStudent(
            input(studentId = saved.studentId, enrollmentId = saved.enrollmentId, photo = PhotoChange.Replace(byteArrayOf(2), "image/jpeg")),
        )

        val newFile = db.students().getById(saved.studentId)!!.photoFile!!
        assertTrue(newFile != oldFile)
        assertEquals(setOf(newFile), photos.files.keys)
    }

    @Test
    fun removingThePhotoDeletesTheFile() = runTest {
        val saved = repo.saveStudent(input(photo = PhotoChange.Replace(byteArrayOf(1), "image/jpeg")))

        repo.saveStudent(input(studentId = saved.studentId, enrollmentId = saved.enrollmentId, photo = PhotoChange.Remove))

        assertNull(db.students().getById(saved.studentId)!!.photoFile)
        assertTrue(photos.files.isEmpty())
    }

    @Test
    fun keepingThePhotoLeavesItAlone() = runTest {
        val saved = repo.saveStudent(input(photo = PhotoChange.Replace(byteArrayOf(1), "image/jpeg")))
        val file = db.students().getById(saved.studentId)!!.photoFile

        repo.saveStudent(input(name = "Renamed", studentId = saved.studentId, enrollmentId = saved.enrollmentId))

        assertEquals(file, db.students().getById(saved.studentId)!!.photoFile)
        assertEquals(setOf(file), photos.files.keys)
    }

    @Test
    fun failedSaveKeepsTheOldPhotoAndDiscardsTheNewOne() = runTest {
        val first = repo.saveStudent(input(admissionNumber = "1", rollNumber = "1", photo = PhotoChange.Replace(byteArrayOf(1), "image/jpeg")))
        val keptFile = db.students().getById(first.studentId)!!.photoFile
        repo.saveStudent(input(admissionNumber = "2", rollNumber = "2"))

        assertRejected("Admission number must be unique.") {
            repo.saveStudent(
                input(
                    admissionNumber = "2",
                    rollNumber = "1",
                    studentId = first.studentId,
                    enrollmentId = first.enrollmentId,
                    photo = PhotoChange.Replace(byteArrayOf(9), "image/jpeg"),
                ),
            )
        }

        assertEquals(keptFile, db.students().getById(first.studentId)!!.photoFile)
        assertEquals(setOf(keptFile), photos.files.keys)
        assertEquals("1", db.students().getById(first.studentId)!!.admissionNumber)
    }

    @Test
    fun enrollmentRowsAreSortedByRollNumberNaturally() = runTest {
        repo.saveStudent(input(admissionNumber = "1", rollNumber = "10"))
        repo.saveStudent(input(admissionNumber = "2", rollNumber = "2"))
        repo.saveStudent(input(admissionNumber = "3", rollNumber = "1"))
        repo.saveStudent(input(admissionNumber = "4", rollNumber = "7", classGroupId = classB.id))

        val rows = repo.observeEnrollmentRows(classA.id).first()

        assertEquals(listOf("1", "2", "10"), rows.map { it.enrollment.rollNumber })
        assertEquals(listOf("3", "2", "1"), rows.map { it.student.admissionNumber })
    }

    @Test
    fun observeStudentsListsEveryStudent() = runTest {
        repo.saveStudent(input(admissionNumber = "1", rollNumber = "1"))
        repo.saveStudent(input(admissionNumber = "2", rollNumber = "1", classGroupId = classB.id))

        assertEquals(setOf("1", "2"), repo.observeStudents().first().map { it.admissionNumber }.toSet())
    }

    @Test
    fun requiredFieldsAreChecked() = runTest {
        assertRejected("Student name is required.") { repo.saveStudent(input(name = " ")) }
        assertRejected("Admission number is required.") { repo.saveStudent(input(admissionNumber = "")) }
        assertRejected("Roll number is required.") { repo.saveStudent(input(rollNumber = " ")) }
    }

    @Test
    fun migratePhoneNumbersRewritesChangedNumbersAndSkipsInvalidOnes() = runTest {
        val converter = object : com.itexpert120.attendanceManager.domain.PhoneNormalizer {
            override fun normalize(input: String): String = when (input) {
                "" -> ""
                "0300" -> "+92300"
                "+92300" -> "+92300"
                else -> throw ValidationException("bad")
            }

            override fun display(value: String) = value
        }
        val migrating = StudentRepository(db, photos, converter, clock)
        val now = clock.now()
        db.students().insert(Student(admissionNumber = "1", name = "A", phone = "0300", createdAt = now))
        db.students().insert(Student(admissionNumber = "2", name = "B", phone = "+92300", createdAt = now))
        db.students().insert(Student(admissionNumber = "3", name = "C", phone = "junk", createdAt = now))
        db.students().insert(Student(admissionNumber = "4", name = "D", phone = "", createdAt = now))

        val changed = migrating.migratePhoneNumbers()

        assertEquals(1, changed)
        assertEquals(
            mapOf("1" to "+92300", "2" to "+92300", "3" to "junk", "4" to ""),
            db.students().getAll().associate { it.admissionNumber to it.phone },
        )
    }
}

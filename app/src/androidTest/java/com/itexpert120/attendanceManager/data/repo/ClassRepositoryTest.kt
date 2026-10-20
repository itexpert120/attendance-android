package com.itexpert120.attendanceManager.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.Student
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

@RunWith(AndroidJUnit4::class)
class ClassRepositoryTest {
    private lateinit var db: AttendanceDatabase
    private val clock = MutableClock()
    private lateinit var repo: ClassRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = ClassRepository(db, clock)
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

    private suspend fun enrol(
        classGroupId: Long,
        admissionNumber: String,
        rollNumber: String,
        admittedOn: String,
        struckOffOn: String? = null,
    ): Long {
        val studentId = db.students().insert(
            Student(admissionNumber = admissionNumber, name = "Student $admissionNumber", createdAt = clock.now()),
        )
        return db.enrollments().insert(
            Enrollment(
                studentId = studentId,
                classGroupId = classGroupId,
                rollNumber = rollNumber,
                admittedOn = LocalDate.parse(admittedOn),
                struckOffOn = struckOffOn?.let(LocalDate::parse),
            ),
        )
    }

    @Test
    fun addClassTrimsAndStoresTheClass() = runTest {
        val group = repo.addClass(" 8 ", " B ")

        assertEquals("8", group.className)
        assertEquals("B", group.section)
        assertEquals(clock.now(), group.createdAt)
        assertEquals(group, repo.getClass(group.id))
    }

    @Test
    fun duplicateClassAndSectionIsRejectedIgnoringCase() = runTest {
        repo.addClass("7", "A")

        assertRejected("That class and section already exists.") { repo.addClass("7", "A") }
        assertRejected("That class and section already exists.") { repo.addClass(" 7 ", "a") }
        assertEquals(1, db.classGroups().getAll().size)
    }

    @Test
    fun sameClassWithAnotherSectionIsAllowed() = runTest {
        repo.addClass("7", "A")
        repo.addClass("7", "B")

        assertEquals(2, db.classGroups().getAll().size)
    }

    @Test
    fun classesAreObservedInNaturalOrder() = runTest {
        repo.addClass("10", "A")
        repo.addClass("7", "B")
        repo.addClass("7", "A")
        repo.addClass("2", "A")

        val names = repo.observeClasses().first().map { it.className + it.section }

        assertEquals(listOf("2A", "7A", "7B", "10A"), names)
    }

    @Test
    fun duplicateClassCopiesOnlyEnrollmentsActiveOnTheAdmissionDate() = runTest {
        val source = repo.addClass("7", "A")
        val active = enrol(source.id, "1", "1", "2025-04-01")
        val stillActiveOnStruckOffDay = enrol(source.id, "2", "2", "2025-04-01", struckOffOn = "2026-04-01")
        val struckOffEarlier = enrol(source.id, "3", "3", "2025-04-01", struckOffOn = "2026-03-31")
        val admittedLater = enrol(source.id, "4", "4", "2026-04-02")

        val copy = repo.duplicateClass(source.id, "8", "A", LocalDate.parse("2026-04-01"))

        val copied = db.enrollments().getForClass(copy.id)
        assertEquals(listOf("1", "2"), copied.map { it.rollNumber }.sorted())
        val sourceById = db.enrollments().getForClass(source.id).associateBy { it.id }
        for (enrollment in copied) {
            assertEquals(LocalDate.parse("2026-04-01"), enrollment.admittedOn)
            assertNull(enrollment.struckOffOn)
        }
        assertEquals(
            setOf(sourceById.getValue(active).studentId, sourceById.getValue(stillActiveOnStruckOffDay).studentId),
            copied.map { it.studentId }.toSet(),
        )
        assertTrue(copied.none { it.studentId == sourceById.getValue(struckOffEarlier).studentId })
        assertTrue(copied.none { it.studentId == sourceById.getValue(admittedLater).studentId })
        // The source class keeps its own enrollments untouched.
        assertEquals(4, sourceById.size)
        assertEquals(LocalDate.parse("2026-04-01"), sourceById.getValue(stillActiveOnStruckOffDay).struckOffOn)
    }

    @Test
    fun duplicatedStudentsAreTheSameStudentRows() = runTest {
        val source = repo.addClass("7", "A")
        enrol(source.id, "1", "5", "2025-04-01")

        val copy = repo.duplicateClass(source.id, "8", "A", LocalDate.parse("2026-04-01"))

        assertEquals(1, db.students().getAll().size)
        val row = db.enrollments().getRowsForClass(copy.id).single()
        assertEquals("Student 1", row.student.name)
        assertEquals("5", row.enrollment.rollNumber)
    }

    @Test
    fun duplicateClassRejectsAnExistingClassAndSection() = runTest {
        val source = repo.addClass("7", "A")
        repo.addClass("8", "A")

        assertRejected("That class and section already exists.") {
            repo.duplicateClass(source.id, "8", "a", LocalDate.parse("2026-04-01"))
        }
        assertEquals(2, db.classGroups().getAll().size)
    }

    @Test
    fun duplicateClassRequiresClassAndSection() = runTest {
        val source = repo.addClass("7", "A")

        assertRejected("Class and section are required.") {
            repo.duplicateClass(source.id, " ", "A", LocalDate.parse("2026-04-01"))
        }
        assertRejected("Class and section are required.") {
            repo.duplicateClass(source.id, "8", "", LocalDate.parse("2026-04-01"))
        }
    }

    @Test
    fun duplicateClassRequiresAnExistingSource() = runTest {
        assertRejected("Select a class to duplicate.") {
            repo.duplicateClass(999, "8", "A", LocalDate.parse("2026-04-01"))
        }
        assertTrue(db.classGroups().getAll().isEmpty())
    }

    @Test
    fun renameClassKeepsTheIdAndRejectsDuplicates() = runTest {
        val a = repo.addClass("7", "A")
        repo.addClass("7", "B")

        val renamed = repo.renameClass(a.id, " 8 ", " a ")
        assertEquals(a.id, renamed.id)
        assertEquals("8", renamed.className)
        assertEquals("a", renamed.section)
        // Same class, only the case changes: not a clash with itself.
        assertEquals("A", repo.renameClass(a.id, "8", "A").section)

        assertRejected("That class and section already exists.") { repo.renameClass(a.id, "7", "b") }
        assertRejected("Class and section are required.") { repo.renameClass(a.id, "", "A") }
        assertRejected("That class no longer exists.") { repo.renameClass(999, "9", "A") }
    }

    @Test
    fun deleteClassRemovesItsRollButKeepsStudents() = runTest {
        val a = repo.addClass("7", "A")
        val b = repo.addClass("7", "B")
        enrol(a.id, "1", "1", "2026-04-01")
        enrol(b.id, "2", "1", "2026-04-01")

        val usage = repo.classUsage(a.id)
        assertEquals(1, usage.enrollments)
        assertEquals(0, usage.registers)

        repo.deleteClass(a.id)

        assertNull(repo.getClass(a.id))
        assertTrue(db.enrollments().getForClass(a.id).isEmpty())
        assertEquals(1, db.enrollments().getForClass(b.id).size)
        assertEquals(2, db.students().getAll().size)
    }

    @Test
    fun deleteClassKeepsTheLastClass() = runTest {
        val only = repo.addClass("7", "A")
        assertRejected("The school needs at least one class.") { repo.deleteClass(only.id) }
        assertRejected("That class no longer exists.") { repo.deleteClass(999) }
    }
}

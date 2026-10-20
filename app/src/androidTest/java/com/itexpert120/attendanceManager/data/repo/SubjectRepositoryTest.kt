package com.itexpert120.attendanceManager.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.support.MutableClock
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
class SubjectRepositoryTest {
    private lateinit var db: AttendanceDatabase
    private val clock = MutableClock()
    private lateinit var repo: SubjectRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = SubjectRepository(db, clock)
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

    @Test
    fun saveSubjectTrimsTheNameAndStoresItNormalised() = runTest {
        val subject = repo.saveSubject("  Mathematics ")

        assertEquals("Mathematics", subject.name)
        assertEquals("mathematics", subject.normalizedName)
        assertEquals(clock.now(), subject.createdAt)
        assertEquals(subject, repo.get(subject.id))
        assertFalse(subject.isArchived)
    }

    @Test
    fun saveSubjectRejectsBlankAndDuplicateNames() = runTest {
        repo.saveSubject("Mathematics")

        assertRejected("Subject name is required.") { repo.saveSubject("   ") }
        assertRejected("That Subject already exists.") { repo.saveSubject("mathematics ") }
        assertEquals(1, repo.observe().first().size)
    }

    @Test
    fun saveSubjectReactivatesAnArchivedSubjectWithTheSameName() = runTest {
        val original = repo.saveSubject("Maths")
        repo.archiveSubject(original.id)
        clock.now += 1.hours

        val again = repo.saveSubject("MATHS")

        assertEquals(original.id, again.id)
        assertEquals("MATHS", again.name)
        assertNull(again.archivedAt)
        assertEquals(original.createdAt, again.createdAt)
        assertEquals(clock.now(), again.updatedAt)
        assertEquals(1, repo.observe().first().size)
    }

    @Test
    fun renameSubjectUpdatesNameAndNormalisedName() = runTest {
        val subject = repo.saveSubject("Maths")
        clock.now += 1.hours

        val renamed = repo.renameSubject(subject.id, " Mathematics ")

        assertEquals("Mathematics", renamed.name)
        assertEquals("mathematics", renamed.normalizedName)
        assertEquals(clock.now(), renamed.updatedAt)
        assertEquals(renamed, repo.get(subject.id))
    }

    @Test
    fun renameSubjectKeepsItsOwnNameButRejectsAnotherSubjectsName() = runTest {
        val maths = repo.saveSubject("Maths")
        val science = repo.saveSubject("Science")
        repo.archiveSubject(science.id)

        // Changing only the capitalisation of its own name is fine.
        assertEquals("MATHS", repo.renameSubject(maths.id, "MATHS").name)
        // An archived Subject still owns its name.
        assertRejected("That Subject already exists.") { repo.renameSubject(maths.id, "science") }
        assertRejected("Subject name is required.") { repo.renameSubject(maths.id, " ") }
        assertRejected("Subject not found.") { repo.renameSubject(9999, "Ghost") }
    }

    @Test
    fun archiveAndRestoreToggleTheSubjectWithoutDeletingIt() = runTest {
        val subject = repo.saveSubject("Maths")

        repo.archiveSubject(subject.id)
        val archived = repo.get(subject.id)!!
        assertTrue(archived.isArchived)
        assertEquals(clock.now(), archived.archivedAt)

        // Archiving again, or restoring an unknown Subject, is harmless.
        repo.archiveSubject(subject.id)
        repo.archiveSubject(9999)
        repo.restoreSubject(9999)

        repo.restoreSubject(subject.id)
        assertNull(repo.get(subject.id)!!.archivedAt)
    }

    @Test
    fun observeListsActiveSubjectsFirstThenNaturalOrderByName() = runTest {
        val maths10 = repo.saveSubject("Maths 10")
        repo.saveSubject("Maths 2")
        val art = repo.saveSubject("art")
        repo.saveSubject("Zoology")
        repo.archiveSubject(art.id)
        repo.archiveSubject(maths10.id)

        assertEquals(listOf("Maths 2", "Zoology", "art", "Maths 10"), repo.observe().first().map { it.name })
    }
}

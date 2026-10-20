package com.itexpert120.attendanceManager.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.data.db.addClass
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.support.FakePhotoStore
import com.itexpert120.attendanceManager.support.MutableClock
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
class HomeworkRepositoryTest {
    private lateinit var db: AttendanceDatabase
    private val clock = MutableClock()
    private val photos = FakePhotoStore()
    private lateinit var subjects: SubjectRepository
    private lateinit var repo: HomeworkRepository

    private val day = LocalDate.parse("2026-09-01")

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        subjects = SubjectRepository(db, clock)
        repo = HomeworkRepository(db, photos, clock)
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

    private fun report(
        classGroupId: Long?,
        items: List<HomeworkItemInput> = emptyList(),
        photo: DiaryPhotoInput? = null,
        date: LocalDate? = day,
        incharge: String = "Ms Fatima",
        note: String = "Please sign after checking the work.",
    ) = HomeworkReportInput(classGroupId, date, incharge, note, items, photo)

    private fun item(subjectId: Long?, details: String = "Complete exercise 4.") = HomeworkItemInput(subjectId, details)

    private fun diary(vararg bytes: Byte) = DiaryPhotoInput.New(bytes, "image/jpeg")

    @Test
    fun createReportStoresTrimmedFieldsAndOrderedItems() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")
        val science = subjects.saveSubject("Science")

        val created = repo.createReport(
            report(
                classId,
                items = listOf(item(science.id, "  Read chapter 2. "), item(maths.id)),
                incharge = " Ms Fatima ",
                note = " Please sign. ",
            ),
        )

        assertEquals(classId, created.report.classGroupId)
        assertEquals(day, created.report.date)
        assertEquals("Ms Fatima", created.report.inchargeName)
        assertEquals("Please sign.", created.report.parentNote)
        assertNull(created.report.photoFile)
        assertEquals(clock.now(), created.report.createdAt)
        assertEquals(listOf(science.id, maths.id), created.items.map { it.subjectId })
        assertEquals(listOf("Read chapter 2.", "Complete exercise 4."), created.items.map { it.details })
        assertEquals(listOf(0, 1), created.items.map { it.position })
        assertEquals(created, repo.getReport(created.id))
        assertEquals(listOf(created), repo.observeReports().first())
        assertEquals(created, repo.observeReport(created.id).first())
    }

    @Test
    fun onlyOneReportPerClassGroupAndDateAndItCanBeUpdated() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")
        val created = repo.createReport(report(classId, items = listOf(item(maths.id))))

        assertRejected("A Daily Homework Report already exists for this Class Group and date.") {
            repo.createReport(report(classId, items = listOf(item(maths.id, "Read chapter 2."))))
        }
        // A different date or class is fine.
        repo.createReport(report(classId, items = listOf(item(maths.id)), date = LocalDate.parse("2026-09-02")))
        repo.createReport(report(db.addClass("7", "B"), items = listOf(item(maths.id))))

        clock.now += 1.hours
        val updated = repo.updateReport(
            created.id,
            report(
                classId,
                items = listOf(item(maths.id, "Complete exercise 4 and 5.")),
                note = "Bring the completed notebook tomorrow.",
            ),
        )
        assertEquals("Bring the completed notebook tomorrow.", updated.report.parentNote)
        assertEquals(listOf("Complete exercise 4 and 5."), updated.items.map { it.details })
        assertEquals(created.report.createdAt, updated.report.createdAt)
        assertEquals(clock.now(), updated.report.updatedAt)
        assertEquals(3, repo.observeReports().first().size)

        // Moving a report onto another report's class and date is rejected; staying put is not.
        assertRejected("A Daily Homework Report already exists for this Class Group and date.") {
            repo.updateReport(created.id, report(classId, items = listOf(item(maths.id)), date = LocalDate.parse("2026-09-02")))
        }
        assertRejected("Daily Homework Report not found.") {
            repo.updateReport(9999, report(classId, items = listOf(item(maths.id))))
        }
    }

    @Test
    fun observeReportsListsNewestDateFirstWithTheirItems() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")
        val older = repo.createReport(report(classId, items = listOf(item(maths.id)), date = LocalDate.parse("2026-08-30")))
        val newer = repo.createReport(report(classId, items = listOf(item(maths.id), item(subjects.saveSubject("Art").id))))

        val all = repo.observeReports().first()

        assertEquals(listOf(newer.id, older.id), all.map { it.id })
        assertEquals(2, all[0].items.size)
        assertEquals(1, all[1].items.size)
    }

    @Test
    fun requiresClassGroupDateInchargeAndParentNote() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")
        val items = listOf(item(maths.id))
        val required = "Class Group, date, incharge, and Parent Note are required."

        assertRejected(required) { repo.createReport(report(null, items)) }
        assertRejected(required) { repo.createReport(report(classId, items, date = null)) }
        assertRejected(required) { repo.createReport(report(classId, items, incharge = "  ")) }
        assertRejected(required) { repo.createReport(report(classId, items, note = " ")) }
        assertRejected("Select a Class Group.") { repo.createReport(report(9999, items)) }
        assertTrue(repo.observeReports().first().isEmpty())
    }

    @Test
    fun parseDateAcceptsOnlyRealCalendarDates() {
        assertEquals(LocalDate.parse("2026-09-01"), HomeworkRepository.parseDate("2026-09-01"))
        assertEquals(LocalDate.parse("2028-02-29"), HomeworkRepository.parseDate("2028-02-29"))
        assertNull(HomeworkRepository.parseDate("2026-02-31"))
        assertNull(HomeworkRepository.parseDate("2027-02-29"))
        assertNull(HomeworkRepository.parseDate("2026-13-01"))
        assertNull(HomeworkRepository.parseDate("2026-9-1"))
        assertNull(HomeworkRepository.parseDate(""))
    }

    @Test
    fun requiresCompleteNonDuplicatedHomeworkItems() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")
        val science = subjects.saveSubject("Science")

        assertRejected("Every Homework Item needs a Subject and assignment details.") {
            repo.createReport(report(classId, listOf(item(null))))
        }
        assertRejected("Every Homework Item needs a Subject and assignment details.") {
            repo.createReport(report(classId, listOf(item(maths.id, "  "))))
        }
        assertRejected("Each Subject can appear only once in a Daily Homework Report.") {
            repo.createReport(report(classId, listOf(item(maths.id, "Exercise 4."), item(maths.id, "Exercise 5."))))
        }
        assertRejected("Select an active Subject for every Homework Item.") {
            repo.createReport(report(classId, listOf(item(maths.id), item(9999))))
        }
        subjects.archiveSubject(science.id)
        assertRejected("Select an active Subject for every Homework Item.") {
            repo.createReport(report(classId, listOf(item(science.id))))
        }
        assertTrue(repo.observeReports().first().isEmpty())
    }

    @Test
    fun anArchivedSubjectIsAllowedOnlyWhereTheEditedReportAlreadyUsedIt() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")
        val science = subjects.saveSubject("Science")
        val created = repo.createReport(report(classId, listOf(item(maths.id), item(science.id))))
        val other = repo.createReport(report(classId, listOf(item(maths.id)), date = LocalDate.parse("2026-09-02")))
        subjects.archiveSubject(science.id)

        // The report that already lists the archived Subject can keep it...
        val updated = repo.updateReport(created.id, report(classId, listOf(item(maths.id, "New"), item(science.id, "Kept"))))
        assertEquals(listOf("New", "Kept"), updated.items.map { it.details })
        // ...but another report cannot adopt it.
        assertRejected("Select an active Subject for every Homework Item.") {
            repo.updateReport(other.id, report(classId, listOf(item(science.id)), date = LocalDate.parse("2026-09-02")))
        }
    }

    @Test
    fun aDiaryPhotoCanReplaceTypedItemsButNotAccompanyThem() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")

        assertRejected("Use either a diary photo or typed Homework Items, not both.") {
            repo.createReport(report(classId, listOf(item(maths.id)), diary(1, 2)))
        }
        assertRejected("Add at least one Homework Item or attach a diary photo.") {
            repo.createReport(report(classId))
        }
        assertRejected("Add at least one Homework Item or attach a diary photo.") {
            repo.createReport(report(classId, photo = DiaryPhotoInput.New(ByteArray(0), "image/jpeg")))
        }
        assertRejected("Add at least one Homework Item or attach a diary photo.") {
            repo.createReport(report(classId, photo = DiaryPhotoInput.KeepExisting))
        }
        assertTrue(photos.files.isEmpty())

        val created = repo.createReport(report(classId, photo = diary(1, 2, 3)))

        assertTrue(created.items.isEmpty())
        val file = created.report.photoFile!!
        assertArrayEquals(byteArrayOf(1, 2, 3), photos.read(file))
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.readDiaryPhoto(created.report))
        assertEquals(setOf(file), photos.files.keys)
    }

    @Test
    fun updatingToTypedItemsClearsTheDiaryPhotoAndItsFile() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")
        val created = repo.createReport(report(classId, photo = diary(1, 2, 3)))

        val updated = repo.updateReport(created.id, report(classId, listOf(item(maths.id))))

        assertNull(updated.report.photoFile)
        assertEquals(listOf(maths.id), updated.items.map { it.subjectId })
        assertTrue(photos.files.isEmpty())
        assertNull(repo.getReport(created.id)!!.report.photoFile)
    }

    @Test
    fun updatingToANewPhotoReplacesAndDeletesTheOldFileAndKeepExistingKeepsIt() = runTest {
        val classId = db.addClass()
        val created = repo.createReport(report(classId, photo = diary(1)))
        val firstFile = created.report.photoFile!!

        val kept = repo.updateReport(created.id, report(classId, photo = DiaryPhotoInput.KeepExisting, note = "New note"))
        assertEquals(firstFile, kept.report.photoFile)
        assertEquals("New note", kept.report.parentNote)
        assertEquals(setOf(firstFile), photos.files.keys)

        val replaced = repo.updateReport(created.id, report(classId, photo = diary(9, 9)))
        val secondFile = replaced.report.photoFile!!
        assertNotNull(secondFile)
        assertTrue(secondFile != firstFile)
        assertEquals(setOf(secondFile), photos.files.keys)
        assertArrayEquals(byteArrayOf(9, 9), photos.read(secondFile))
    }

    @Test
    fun aRejectedUpdateKeepsTheOldPhotoAndDoesNotLeakTheNewOne() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")
        repo.createReport(report(classId, listOf(item(maths.id)), date = LocalDate.parse("2026-09-02")))
        val created = repo.createReport(report(classId, photo = diary(1)))
        val originalFile = created.report.photoFile!!

        // Fails inside the transaction (the date is taken), after the new photo was already stored.
        assertRejected("A Daily Homework Report already exists for this Class Group and date.") {
            repo.updateReport(created.id, report(classId, photo = diary(7), date = LocalDate.parse("2026-09-02")))
        }

        assertEquals(setOf(originalFile), photos.files.keys)
        assertEquals(created, repo.getReport(created.id))
    }

    @Test
    fun aRejectedCreateDoesNotLeakItsPhoto() = runTest {
        val classId = db.addClass()
        repo.createReport(report(classId, photo = diary(1)))
        val before = photos.files.keys.toSet()

        assertRejected("A Daily Homework Report already exists for this Class Group and date.") {
            repo.createReport(report(classId, photo = diary(2)))
        }

        assertEquals(before, photos.files.keys)
    }

    @Test
    fun deleteReportRemovesItsItemsAndPhoto() = runTest {
        val classId = db.addClass()
        val maths = subjects.saveSubject("Mathematics")
        val typed = repo.createReport(report(classId, listOf(item(maths.id))))
        val pictured = repo.createReport(report(classId, photo = diary(5), date = LocalDate.parse("2026-09-02")))

        repo.deleteReport(pictured.id)
        repo.deleteReport(pictured.id) // already gone: a no-op
        assertTrue(photos.files.isEmpty())
        assertNull(repo.getReport(pictured.id))

        repo.deleteReport(typed.id)
        assertTrue(repo.observeReports().first().isEmpty())
        assertTrue(db.homework().getItems(typed.id).isEmpty())
        // The Subject outlives the report.
        assertNotNull(subjects.get(maths.id))
    }
}

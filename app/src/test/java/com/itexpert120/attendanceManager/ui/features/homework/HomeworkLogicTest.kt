package com.itexpert120.attendanceManager.ui.features.homework

import com.itexpert120.attendanceManager.data.repo.DiaryPhotoInput
import com.itexpert120.attendanceManager.data.repo.HomeworkReportWithItems
import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.date
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.HomeworkItem
import com.itexpert120.attendanceManager.model.HomeworkReport
import com.itexpert120.attendanceManager.model.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeworkLogicTest {
    private val sevenA = ClassGroup(1, "7", "A", EPOCH)
    private val eightB = ClassGroup(2, "8", "B", EPOCH)
    private val maths = Subject(1, "Mathematics", "mathematics", null, EPOCH, EPOCH)
    private val urdu = Subject(2, "Urdu", "urdu", null, EPOCH, EPOCH)
    private val art = Subject(3, "Art", "art", archivedAt = EPOCH, createdAt = EPOCH, updatedAt = EPOCH)
    private val today = date("2026-10-07")

    private fun report(id: Long, classId: Long, on: String, subjects: List<Long> = listOf(1), photo: String? = null) =
        HomeworkReportWithItems(
            HomeworkReport(id, classId, date(on), "Ms. Sana", DEFAULT_PARENT_NOTE, photo, EPOCH, EPOCH),
            subjects.mapIndexed { index, subject -> HomeworkItem(id * 10 + index, id, subject, "Work", index) },
        )

    @Test
    fun `groups reports by date newest first, classes in natural order within a date`() {
        val groups = buildHomeworkGroups(
            reports = listOf(report(1, 2, "2026-10-07"), report(2, 1, "2026-10-06"), report(3, 1, "2026-10-07")),
            classes = listOf(sevenA, eightB),
            subjects = listOf(maths),
            classFilter = null,
            today = today,
        )
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.label })
        assertEquals(listOf(3L, 1L), groups[0].reports.map { it.id })
        assertEquals(listOf(2L), groups[1].reports.map { it.id })
    }

    @Test
    fun `class filter keeps only that class`() {
        val groups = buildHomeworkGroups(
            listOf(report(1, 2, "2026-10-07"), report(2, 1, "2026-10-06")),
            listOf(sevenA, eightB),
            listOf(maths),
            classFilter = 1,
            today = today,
        )
        assertEquals(listOf(2L), groups.flatMap { it.reports }.map { it.id })
    }

    @Test
    fun `date headers drop the year only within the current year`() {
        assertEquals("Monday, October 5", homeworkDateHeader(date("2026-10-05"), today))
        assertEquals("Friday, December 5, 2025", homeworkDateHeader(date("2025-12-05"), today))
    }

    @Test
    fun `card preview lists subjects or says it is a diary photo`() {
        val typed = homeworkCardItem(report(1, 1, "2026-10-07", listOf(1, 2)), sevenA, mapOf(1L to "Mathematics", 2L to "Urdu"))
        assertEquals("Mathematics · Urdu", typed.preview)
        assertEquals("Class 7 · A", typed.classLabel)
        assertEquals(0, typed.photoCount)

        val photo = homeworkCardItem(report(2, 1, "2026-10-07", emptyList(), photo = "a.jpg"), sevenA, emptyMap())
        assertEquals("Photographed diary", photo.preview)
        assertEquals(1, photo.photoCount)
    }

    @Test
    fun `effective class filter ignores a deleted class or a single class`() {
        assertEquals(2L, effectiveClassFilter(2, listOf(sevenA, eightB)))
        assertNull(effectiveClassFilter(9, listOf(sevenA, eightB)))
        assertNull(effectiveClassFilter(1, listOf(sevenA)))
    }

    @Test
    fun `a new draft starts with the first class, today, the incharge and the first subject`() {
        val draft = newHomeworkDraft(listOf(sevenA, eightB), listOf(maths, urdu), "Ms. Sana", today)
        assertEquals(1L, draft.classGroupId)
        assertEquals(today, draft.date)
        assertEquals("Ms. Sana", draft.inchargeName)
        assertEquals(DEFAULT_PARENT_NOTE, draft.parentNote)
        assertEquals(listOf(1L), draft.items.map { it.subjectId })
    }

    @Test
    fun `adding an item uses the first unused active subject`() {
        val draft = newHomeworkDraft(listOf(sevenA), listOf(maths, urdu), "", today)
        assertEquals(2L, draft.firstUnusedSubjectId(listOf(maths, urdu)))
        val full = draft.withItemAdded(2)
        assertNull(full.firstUnusedSubjectId(listOf(maths, urdu)))
        assertEquals(2, full.items.map { it.key }.distinct().size)
    }

    @Test
    fun `remove and undo puts the item back in place`() {
        val draft = newHomeworkDraft(listOf(sevenA), listOf(maths), "", today).withItemAdded(2).withItemAdded(3)
        val middle = draft.items[1]
        val removed = draft.withoutItem(middle.key)
        assertEquals(2, removed.items.size)
        assertEquals(draft, removed.withItemRestored(middle, 1))
        // Restoring twice does not duplicate.
        assertEquals(draft.items.size, draft.withItemRestored(middle, 1).items.size)
    }

    @Test
    fun `moving an item swaps it with its neighbour and ignores moves off the ends`() {
        val draft = newHomeworkDraft(listOf(sevenA), listOf(maths), "", today).withItemAdded(2)
        val first = draft.items[0].key
        assertEquals(listOf(2L, 1L), draft.withItemMoved(first, 1).items.map { it.subjectId })
        assertEquals(draft, draft.withItemMoved(first, -1))
    }

    @Test
    fun `input sends only the selected mode`() {
        val draft = newHomeworkDraft(listOf(sevenA), listOf(maths), "Ms. Sana", today)
        val typed = draft.toInput(DiaryPhotoInput.KeepExisting)
        assertEquals(1, typed.items.size)
        assertNull(typed.photo)

        val photo = draft.copy(mode = HomeworkEntryMode.PHOTO).toInput(DiaryPhotoInput.KeepExisting)
        assertTrue(photo.items.isEmpty())
        assertSame(DiaryPhotoInput.KeepExisting, photo.photo)
    }

    @Test
    fun `subject choices hide archived subjects unless the item uses one, and mark used ones`() {
        val draft = newHomeworkDraft(listOf(sevenA), listOf(maths), "", today).withItemAdded(3)
        val second = draft.items[1]
        val choices = subjectChoicesFor(second, draft, listOf(maths, urdu, art))
        assertEquals(listOf(1L, 2L, 3L), choices.map { it.subject.id })
        assertTrue(choices.first { it.subject.id == 1L }.usedElsewhere)

        val first = draft.items[0]
        assertEquals(listOf(1L, 2L), subjectChoicesFor(first, draft, listOf(maths, urdu, art)).map { it.subject.id })
    }

    @Test
    fun `field errors mark missing values and duplicate subjects`() {
        val draft = newHomeworkDraft(listOf(sevenA), listOf(maths), " ", today)
            .withItemAdded(1)
            .withItemAdded(null)
            .copy(parentNote = "")
        val errors = homeworkFieldErrors(draft)
        assertTrue(errors.inchargeMissing)
        assertTrue(errors.noteMissing)
        assertEquals(3, errors.itemDetails.size)
        assertEquals(3, errors.itemSubject.size)
    }

    @Test
    fun `repository messages land next to their field`() {
        val draft = newHomeworkDraft(listOf(sevenA), listOf(maths), "", today)
        val required = "Class Group, date, incharge, and Parent Note are required."
        assertEquals(HomeworkErrorTarget.INCHARGE, homeworkErrorTarget(required, draft))
        assertEquals(HomeworkErrorTarget.NOTE, homeworkErrorTarget(required, draft.copy(inchargeName = "A", parentNote = "")))
        assertEquals(
            HomeworkErrorTarget.DATE,
            homeworkErrorTarget("A Daily Homework Report already exists for this Class Group and date.", draft),
        )
        val empty = "Add at least one Homework Item or attach a diary photo."
        assertEquals(HomeworkErrorTarget.ITEMS, homeworkErrorTarget(empty, draft))
        assertEquals(HomeworkErrorTarget.PHOTO, homeworkErrorTarget(empty, draft.copy(mode = HomeworkEntryMode.PHOTO)))
        assertEquals(HomeworkErrorTarget.GENERAL, homeworkErrorTarget("Daily Homework Report not found.", draft))
    }
    @Test
    fun `date filter keeps only reports of that day`() {
        val reports = listOf(report(1, 2, "2026-10-07"), report(2, 1, "2026-10-06"), report(3, 1, "2026-10-07"))
        val groups = buildHomeworkGroups(reports, listOf(sevenA, eightB), listOf(maths), null, today, dateFilter = date("2026-10-07"))
        assertEquals(listOf(3L, 1L), groups.flatMap { it.reports }.map { it.id })
        assertEquals(listOf(1L, 3L), reportsOnDate(reports, date("2026-10-07")).map { it.id })
        assertEquals(3, reportsOnDate(reports, null).size)
    }

    @Test
    fun `subjects manager counts reports per subject and blocks subjects on the draft`() {
        val counts = homeworkReportCountBySubject(
            listOf(report(1, 1, "2026-10-07", listOf(1, 2)), report(2, 1, "2026-10-06", listOf(1)), report(3, 1, "2026-10-05", emptyList(), "p.jpg")),
        )
        assertEquals(mapOf(1L to 2, 2L to 1), counts)
        assertEquals("Active · 2 homework reports", homeworkSubjectUsage(maths, 2))
        assertEquals("Archived · 1 homework report", homeworkSubjectUsage(art, 1))
        val draft = newHomeworkDraft(listOf(sevenA), listOf(maths, urdu), "Ms. Sana", today)
        assertTrue(draft.usesSubject(maths.id))
        assertTrue(!draft.usesSubject(urdu.id))
    }
}

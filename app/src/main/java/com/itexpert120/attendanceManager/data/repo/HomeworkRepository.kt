package com.itexpert120.attendanceManager.data.repo

import androidx.room3.withWriteTransaction
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.HomeworkItem
import com.itexpert120.attendanceManager.model.HomeworkReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** One typed line of a Daily Homework Report. */
data class HomeworkItemInput(val subjectId: Long?, val details: String)

/** The diary photo of a Daily Homework Report being saved. A report with no photo passes null instead. */
sealed interface DiaryPhotoInput {
    /** Keep the photo the report already has (only meaningful when updating a report that has one). */
    data object KeepExisting : DiaryPhotoInput

    /** Store this image, replacing any existing photo. */
    class New(val bytes: ByteArray, val mimeType: String) : DiaryPhotoInput
}

/** Everything the Daily Homework Report form submits; [date] is null while it is not a real calendar date. */
data class HomeworkReportInput(
    val classGroupId: Long?,
    val date: LocalDate?,
    val inchargeName: String,
    val parentNote: String,
    /** Typed items in display order; must be empty when [photo] is given. */
    val items: List<HomeworkItemInput> = emptyList(),
    val photo: DiaryPhotoInput? = null,
)

/** A saved report with its typed items ordered by position (empty for a diary-photo report). */
data class HomeworkReportWithItems(val report: HomeworkReport, val items: List<HomeworkItem>) {
    val id: Long get() = report.id
}

/**
 * Daily Homework Reports. A report is a snapshot (ADR 0003): it stores the incharge name used that day and its
 * items reference Subjects, which are archived rather than deleted so old reports keep rendering.
 */
class HomeworkRepository(
    private val db: AttendanceDatabase,
    private val photoStore: PhotoStore,
    private val clock: Clock = Clock.System,
) {
    /** All reports, newest date first, each with its items. */
    fun observeReports(): Flow<List<HomeworkReportWithItems>> =
        combine(db.homework().observeReports(), db.homework().observeAllItems()) { reports, items ->
            val byReport = items.groupBy { it.reportId }
            reports.map { HomeworkReportWithItems(it, byReport[it.id].orEmpty().sortedBy(HomeworkItem::position)) }
        }

    fun observeReport(reportId: Long): Flow<HomeworkReportWithItems?> =
        combine(db.homework().observeReport(reportId), db.homework().observeItems(reportId)) { report, items ->
            report?.let { HomeworkReportWithItems(it, items) }
        }

    suspend fun getReport(reportId: Long): HomeworkReportWithItems? {
        val report = db.homework().getReport(reportId) ?: return null
        return HomeworkReportWithItems(report, db.homework().getItems(reportId))
    }

    /** The photo bytes of a diary-photo report, or null when it has none. */
    suspend fun readDiaryPhoto(report: HomeworkReport): ByteArray? = report.photoFile?.let { photoStore.read(it) }

    suspend fun createReport(input: HomeworkReportInput): HomeworkReportWithItems {
        val checked = validateShape(input)
        var savedPhoto: String? = null
        try {
            savedPhoto = checked.newPhoto?.let { photoStore.save(it.bytes, it.mimeType) }
            val result = db.withWriteTransaction {
                validateAgainstDatabase(checked, current = null)
                requireNoClash(checked, excludingReportId = 0)
                requirePhotoOrItems(checked, savedPhoto)
                val now = clock.now()
                val draft = HomeworkReport(
                    classGroupId = checked.classGroupId,
                    date = checked.date,
                    inchargeName = checked.inchargeName,
                    parentNote = checked.parentNote,
                    photoFile = savedPhoto,
                    createdAt = now,
                    updatedAt = now,
                )
                val report = draft.copy(id = db.homework().insertReport(draft))
                val items = checked.items.mapIndexed { index, item ->
                    HomeworkItem(reportId = report.id, subjectId = item.subjectId, details = item.details, position = index)
                }
                if (items.isNotEmpty()) db.homework().insertItems(items)
                HomeworkReportWithItems(report, db.homework().getItems(report.id))
            }
            savedPhoto = null
            return result
        } finally {
            // Failed or cancelled: the photo never made it into a row, so don't leak the file.
            savedPhoto?.let { deleteQuietly(it) }
        }
    }

    suspend fun updateReport(reportId: Long, input: HomeworkReportInput): HomeworkReportWithItems {
        if (db.homework().getReport(reportId) == null) throw ValidationException("Daily Homework Report not found.")
        val checked = validateShape(input)
        var savedPhoto: String? = null
        try {
            savedPhoto = checked.newPhoto?.let { photoStore.save(it.bytes, it.mimeType) }
            var orphan: String? = null
            val result = db.withWriteTransaction {
                val current = db.homework().getReport(reportId) ?: throw ValidationException("Daily Homework Report not found.")
                val currentItems = db.homework().getItems(reportId)
                validateAgainstDatabase(checked, current = currentItems)
                requireNoClash(checked, excludingReportId = reportId)
                val photoFile = when {
                    checked.newPhoto != null -> savedPhoto
                    checked.keepPhoto -> current.photoFile
                    else -> null
                }
                requirePhotoOrItems(checked, photoFile)
                orphan = current.photoFile?.takeIf { it != photoFile }
                val updated = current.copy(
                    classGroupId = checked.classGroupId,
                    date = checked.date,
                    inchargeName = checked.inchargeName,
                    parentNote = checked.parentNote,
                    photoFile = photoFile,
                    updatedAt = clock.now(),
                )
                db.homework().updateReport(updated)
                db.homework().deleteItems(reportId)
                val items = checked.items.mapIndexed { index, item ->
                    HomeworkItem(reportId = reportId, subjectId = item.subjectId, details = item.details, position = index)
                }
                if (items.isNotEmpty()) db.homework().insertItems(items)
                HomeworkReportWithItems(updated, db.homework().getItems(reportId))
            }
            savedPhoto = null
            // Only after the commit: a failed transaction must leave the old photo intact.
            orphan?.let { deleteQuietly(it) }
            return result
        } finally {
            savedPhoto?.let { deleteQuietly(it) }
        }
    }

    suspend fun deleteReport(reportId: Long) {
        val photo = db.withWriteTransaction {
            val report = db.homework().getReport(reportId)
            if (report != null) db.homework().deleteReport(report)
            report?.photoFile
        }
        photo?.let { deleteQuietly(it) }
    }

    // ---- Validation -------------------------------------------------------------------------------

    private class CheckedInput(
        val classGroupId: Long,
        val date: LocalDate,
        val inchargeName: String,
        val parentNote: String,
        val items: List<CheckedItem>,
        /** A photo to store. */
        val newPhoto: DiaryPhotoInput.New?,
        /** True when the report keeps its current photo (only possible when it has one). */
        val keepPhoto: Boolean,
    )

    private class CheckedItem(val subjectId: Long, val details: String)

    /** Checks everything that doesn't need the database, in the PWA's order. */
    private fun validateShape(input: HomeworkReportInput): CheckedInput {
        val incharge = input.inchargeName.trim()
        val note = input.parentNote.trim()
        val classGroupId = input.classGroupId
        val date = input.date
        if (classGroupId == null || date == null || incharge.isEmpty() || note.isEmpty()) {
            throw ValidationException("Class Group, date, incharge, and Parent Note are required.")
        }
        val photo = input.photo
        val newPhoto = (photo as? DiaryPhotoInput.New)?.takeIf { it.bytes.isNotEmpty() }
        val keepPhoto = photo is DiaryPhotoInput.KeepExisting
        val hasPhoto = newPhoto != null || keepPhoto
        if (hasPhoto && input.items.isNotEmpty()) {
            throw ValidationException("Use either a diary photo or typed Homework Items, not both.")
        }
        if (!hasPhoto && input.items.isEmpty()) {
            throw ValidationException("Add at least one Homework Item or attach a diary photo.")
        }
        if (hasPhoto) return CheckedInput(classGroupId, date, incharge, note, emptyList(), newPhoto, keepPhoto)

        if (input.items.any { it.subjectId == null || it.details.isBlank() }) {
            throw ValidationException("Every Homework Item needs a Subject and assignment details.")
        }
        val items = input.items.map { CheckedItem(it.subjectId!!, it.details.trim()) }
        if (items.map { it.subjectId }.toSet().size != items.size) {
            throw ValidationException("Each Subject can appear only once in a Daily Homework Report.")
        }
        return CheckedInput(classGroupId, date, incharge, note, items, newPhoto = null, keepPhoto = false)
    }

    /** Checks the Class Group and the Subjects (archived ones only if the report being edited already used them). */
    private suspend fun validateAgainstDatabase(checked: CheckedInput, current: List<HomeworkItem>?) {
        if (db.classGroups().getById(checked.classGroupId) == null) throw ValidationException("Select a Class Group.")
        val alreadyUsed = current?.mapTo(HashSet()) { it.subjectId }.orEmpty()
        val unusable = checked.items.any { item ->
            val subject = db.subjects().getById(item.subjectId)
            subject == null || (subject.isArchived && item.subjectId !in alreadyUsed)
        }
        if (unusable) throw ValidationException("Select an active Subject for every Homework Item.")
    }

    /** KeepExisting on a report that has no photo would otherwise save an empty report. */
    private fun requirePhotoOrItems(checked: CheckedInput, photoFile: String?) {
        if (checked.items.isEmpty() && photoFile == null) {
            throw ValidationException("Add at least one Homework Item or attach a diary photo.")
        }
    }

    private suspend fun requireNoClash(checked: CheckedInput, excludingReportId: Long) {
        val clash = db.homework().findReport(checked.classGroupId, checked.date)
        if (clash != null && clash.id != excludingReportId) {
            throw ValidationException("A Daily Homework Report already exists for this Class Group and date.")
        }
    }

    private suspend fun deleteQuietly(fileName: String) {
        try {
            photoStore.delete(fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // An undeletable file is garbage, not a reason to fail a save that already succeeded.
        }
    }

    companion object {
        /**
         * Parses `yyyy-MM-dd` strictly; null for anything that isn't a real calendar date (the PWA rejected
         * "2026-02-31" rather than rolling it over to March).
         */
        fun parseDate(text: String): LocalDate? {
            if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(text)) return null
            return try {
                LocalDate.parse(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

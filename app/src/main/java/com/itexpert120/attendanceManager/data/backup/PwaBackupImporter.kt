package com.itexpert120.attendanceManager.data.backup

import androidx.room3.executeSQL
import androidx.room3.withWriteTransaction
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.PhoneNormalizer
import com.itexpert120.attendanceManager.domain.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.time.Clock

/**
 * Restores a backup made by the PWA (a `dexie-export-import` JSON file) into the native database.
 *
 * Like the PWA's restore (`clearTablesBeforeImport`) it REPLACES everything. [import] does that in one
 * write transaction, so a failure leaves the database untouched; photos are written to the [PhotoStore]
 * before the transaction (to keep the write lock short), and are deleted again if anything fails. After a
 * successful import the photos of the replaced data are deleted too.
 *
 * Use [inspect] first to show the user what the file contains. Both functions read their stream to the end
 * but do not close it.
 */
class PwaBackupImporter(
    private val db: AttendanceDatabase,
    private val photoStore: PhotoStore,
    private val clock: Clock = Clock.System,
    /** When given, student phone numbers are normalised like the app does; invalid ones are kept as written. */
    private val phoneNormalizer: PhoneNormalizer? = null,
) {
    private val mapper = PwaBackupMapper(clock, phoneNormalizer)

    /**
     * Reads the backup without touching the database or the photo store. Throws `ValidationException`
     * ("This file is not an attendance register backup.") for anything that is not a Dexie export of this app.
     */
    suspend fun inspect(input: InputStream): BackupSummary = withContext(Dispatchers.IO) {
        val parsed = PwaBackupParser.parse(input)
        val mapped = mapper.map(parsed) { _, _ -> "" } // dry run: decodes photos to validate them, stores nothing
        BackupSummary(
            schoolName = mapped.settings?.schoolName,
            databaseVersion = parsed.databaseVersion,
            counts = parsed.rowCounts,
            warnings = mapped.warnings,
        )
    }

    /** Replaces all data with the backup's. Throws `ValidationException` for a file that is not a backup. */
    suspend fun import(input: InputStream): ImportResult {
        val savedPhotos = mutableListOf<String>()
        try {
            val mapped = withContext(Dispatchers.IO) { parseAndMap(input, savedPhotos) }

            var replacedPhotos = emptyList<String>()
            db.withWriteTransaction {
                replacedPhotos = existingPhotoFiles()
                clearAllRows()
                insertAll(mapped)
            }

            // Committed: the old data's photos are unreachable now. Failing to delete one is not worth failing for.
            for (name in replacedPhotos) {
                if (name !in savedPhotos) runCatching { photoStore.delete(name) }
            }
            return ImportResult(mapped.settings?.schoolName, mapped.counts, mapped.warnings)
        } catch (t: Throwable) {
            // Includes cancellation: the database rolled back, so the photos written for it are garbage.
            withContext(NonCancellable) {
                for (name in savedPhotos) runCatching { photoStore.delete(name) }
            }
            throw t
        }
    }

    /** Its own function so the parsed tree (the bulk of the memory) is unreachable once mapping is done. */
    private suspend fun parseAndMap(input: InputStream, savedPhotos: MutableList<String>): MappedBackup {
        val parsed = PwaBackupParser.parse(input)
        return mapper.map(parsed) { bytes, mime ->
            photoStore.save(bytes, mime).also { savedPhotos += it }
        }
    }

    private suspend fun androidx.room3.PooledConnection.existingPhotoFiles(): List<String> {
        val names = mutableListOf<String>()
        for ((table, column) in PHOTO_COLUMNS) {
            usePrepared("SELECT $column FROM $table WHERE $column IS NOT NULL") { statement ->
                while (statement.step()) names += statement.getText(0)
            }
        }
        return names
    }

    /** Child tables first, so foreign keys (and RESTRICT on subjects) never block a delete. */
    private suspend fun androidx.room3.PooledConnection.clearAllRows() {
        for (table in TABLES_CHILDREN_FIRST) executeSQL("DELETE FROM $table")
    }

    private suspend fun insertAll(mapped: MappedBackup) {
        mapped.settings?.let { db.settings().upsert(it) }
        mapped.classGroups.forEach { db.classGroups().insert(it) }
        mapped.students.forEach { db.students().insert(it) }
        mapped.subjects.forEach { db.subjects().insert(it) }
        mapped.enrollments.chunked(CHUNK).forEach { db.enrollments().insertAll(it) }
        mapped.registers.forEach { db.registers().insert(it) }
        mapped.attendance.chunked(CHUNK).forEach { db.attendance().upsertAll(it) }
        mapped.holidays.forEach { db.holidays().upsert(it) }
        mapped.feeEntries.chunked(CHUNK).forEach { db.fees().upsertEntries(it) }
        mapped.installmentMeta.forEach { db.fees().upsertMeta(it) }
        mapped.remarks.forEach { db.fees().upsertRemark(it) }
        mapped.tests.forEach { db.tests().insert(it) }
        mapped.rosterEntries.chunked(CHUNK).forEach { db.tests().insertRoster(it) }
        mapped.homeworkReports.forEach { db.homework().insertReport(it) }
        mapped.homeworkItems.chunked(CHUNK).forEach { db.homework().insertItems(it) }
    }

    private companion object {
        const val CHUNK = 500

        val TABLES_CHILDREN_FIRST = listOf(
            "homework_items", "homework_reports", "test_roster_entries", "tests", "attendance_marks", "holidays",
            "fee_entries", "installment_meta", "student_remarks", "registers", "enrollments", "students",
            "class_groups", "subjects", "school_settings",
        )

        /** table to column holding a photo file name. */
        val PHOTO_COLUMNS = listOf(
            "students" to "photoFile",
            "homework_reports" to "photoFile",
            "school_settings" to "logoFile",
        )
    }
}

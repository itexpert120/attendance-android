@file:OptIn(ExperimentalSerializationApi::class)

package com.itexpert120.attendanceManager.data.backup

import androidx.room3.executeSQL
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteException
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.domain.mergeTimings
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.Holiday
import com.itexpert120.attendanceManager.model.HomeworkItem
import com.itexpert120.attendanceManager.model.HomeworkReport
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.StudentRemark
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.SubjectTeacher
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.intOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.time.Clock
import kotlin.time.Instant

/** What a native backup holds; shown before a restore and after a backup. */
data class AppBackupSummary(
    val createdAt: Instant,
    val schoolName: String?,
    /** Rows per table, keyed by [BackupTables] names. */
    val counts: Map<String, Int>,
    val photoCount: Int,
)

data class AppRestoreResult(
    val schoolName: String?,
    /** Rows written per table, keyed by [BackupTables] names. */
    val counts: Map<String, Int>,
    val photoCount: Int,
    /** Photos the data referred to that were not in the file; those records were restored without a photo. */
    val missingPhotos: Int,
)

/**
 * The app's own backup: ONE zip with every table (as JSON, see `AppBackupFormat.kt`) and every photo file.
 *
 * [restore] REPLACES all data. It reads and checks the whole file before touching anything, writes the
 * backup's photos under new names, then swaps the rows in a single write transaction; the replaced data's
 * photos are deleted only after that commits. Any failure (including cancellation) leaves the database as
 * it was and removes the photos written for the attempt.
 *
 * Problems with the file are reported as [ValidationException]s whose message can be shown as-is. None of
 * the functions close the stream they are given.
 */
class AppBackup(
    private val db: AttendanceDatabase,
    private val photoStore: PhotoStore,
    private val clock: Clock = Clock.System,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Writes a backup of everything to [output]. */
    suspend fun export(output: OutputStream): AppBackupSummary = withContext(io) {
        val data = db.withReadTransaction { snapshot() }
        val photoNames = data.photoFiles().sorted()
        val createdAt = clock.now()
        val manifest = BackupManifest(
            format = FORMAT_NAME,
            version = FORMAT_VERSION,
            createdAt = createdAt.toEpochMilliseconds(),
            schoolName = data.settings?.schoolName,
            counts = data.counts(),
            photoCount = photoNames.size,
        )

        val buffered = BufferedOutputStream(output, BUFFER)
        val zip = ZipOutputStream(buffered)
        fun begin(name: String) = zip.putNextEntry(ZipEntry(name).apply { time = createdAt.toEpochMilliseconds() })

        begin(MANIFEST_ENTRY)
        json.encodeToStream(BackupManifest.serializer(), manifest, zip)
        zip.closeEntry()
        begin(DATA_ENTRY)
        json.encodeToStream(BackupData.serializer(), data, zip)
        zip.closeEntry()

        // Photos are already compressed; deflating them again only costs time.
        zip.setLevel(Deflater.NO_COMPRESSION)
        var written = 0
        for (name in photoNames) {
            // A missing file is skipped; restore then clears that reference.
            val bytes = photoStore.read(name) ?: continue
            begin(PHOTOS_PREFIX + name)
            zip.write(bytes)
            zip.closeEntry()
            written++
        }
        // Marks the end, so a file cut short at an entry boundary is still recognised as incomplete.
        begin(COMPLETE_ENTRY)
        zip.write(written.toString().encodeToByteArray())
        zip.closeEntry()
        zip.finish() // not close(): the caller owns [output]
        buffered.flush()
        AppBackupSummary(createdAt, manifest.schoolName, manifest.counts, written)
    }

    /** Reads only the backup's header, to show what a restore would bring back. */
    suspend fun inspect(input: InputStream): AppBackupSummary = withContext(io) {
        readingZip {
            val zip = openZip(input)
            val manifest = readManifest(zip)
            AppBackupSummary(
                createdAt = Instant.fromEpochMilliseconds(manifest.createdAt),
                schoolName = manifest.schoolName,
                counts = manifest.counts,
                photoCount = manifest.photoCount,
            )
        }
    }

    /** Replaces all data on this phone with the backup in [input]. */
    suspend fun restore(input: InputStream): AppRestoreResult {
        val saved = LinkedHashMap<String, String>() // name in the backup -> name written here
        try {
            val rows = withContext(io) {
                readingZip {
                    val zip = openZip(input)
                    readManifest(zip)
                    val rows = readData(zip)
                    readPhotos(zip, rows.photoFiles(), saved)
                    rows
                }
            }
            val restored = rows.withPhotos(saved)

            var replacedPhotos = emptySet<String>()
            try {
                db.withWriteTransaction<Unit> {
                    replacedPhotos = currentPhotoFiles()
                    for (table in TABLES_CHILDREN_FIRST) executeSQL("DELETE FROM $table")
                    insertAll(restored)
                }
            } catch (e: SQLiteException) {
                throw ValidationException("This backup's data is inconsistent (${e.message}), so it was not restored. Nothing was changed.")
            }

            // Committed: the replaced data's photos are unreachable now. A failed delete is not worth failing for.
            withContext(NonCancellable) {
                val kept = saved.values.toSet()
                for (name in replacedPhotos) if (name !in kept) runCatching { photoStore.delete(name) }
            }
            return AppRestoreResult(
                schoolName = restored.settings?.schoolName,
                counts = restored.counts(),
                photoCount = saved.size,
                missingPhotos = rows.photoFiles().count { it !in saved },
            )
        } catch (t: Throwable) {
            // Includes cancellation: nothing was committed, so the photos written for this attempt are garbage.
            withContext(NonCancellable) {
                for (name in saved.values) runCatching { photoStore.delete(name) }
            }
            throw t
        }
    }

    // ---- Export ----------------------------------------------------------------------------------

    private suspend fun snapshot() = BackupData(
        settings = db.settings().get()?.toDto(),
        classGroups = db.classGroups().getAll().map { it.toDto() },
        students = db.students().getAll().map { it.toDto() },
        enrollments = db.enrollments().getAll().map { it.toDto() },
        registers = db.registers().getAll().map { it.toDto() },
        attendance = db.attendance().getAll().map { it.toDto() },
        holidays = db.holidays().getAll().map { it.toDto() },
        feeEntries = db.fees().getAllEntries().map { it.toDto() },
        installmentMeta = db.fees().getAllMeta().map { it.toDto() },
        remarks = db.fees().getAllRemarks().map { it.toDto() },
        subjects = db.subjects().getAll().map { it.toDto() },
        subjectTeachers = db.subjectTeachers().getAll().map { it.toDto() },
        tests = db.tests().getAll().map { it.toDto() },
        testRoster = db.tests().getAllRosterEntries().map { it.toDto() },
        homeworkReports = db.homework().getAllReports().map { it.toDto() },
        homeworkItems = db.homework().getAllItems().map { it.toDto() },
    )

    // ---- Reading ---------------------------------------------------------------------------------

    /** Turns the ways a damaged or foreign file fails into one clear message. */
    private inline fun <T> readingZip(block: () -> T): T = try {
        block()
    } catch (_: ZipException) {
        throw ValidationException(DAMAGED)
    } catch (_: EOFException) {
        throw ValidationException(DAMAGED)
    }

    private fun openZip(input: InputStream): ZipInputStream {
        val buffered = BufferedInputStream(input, BUFFER)
        buffered.mark(64)
        val head = ByteArray(64)
        val read = buffered.read(head).coerceAtLeast(0)
        buffered.reset()
        // An old web-app backup is JSON; point the user at the right action instead of a generic error.
        val firstChar = head.take(read).map { it.toInt().toChar() }.firstOrNull { !it.isWhitespace() && it != '﻿' && it.code < 0x80 }
        if (firstChar == '{') throw ValidationException(LOOKS_LIKE_WEB_BACKUP)
        return ZipInputStream(buffered)
    }

    private fun readManifest(zip: ZipInputStream): BackupManifest {
        val entry = zip.nextEntry ?: throw ValidationException(NOT_A_BACKUP)
        if (entry.name != MANIFEST_ENTRY) throw ValidationException(NOT_A_BACKUP)
        val text = zip.readLimited(MANIFEST_LIMIT).decodeToString()
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw ValidationException(NOT_A_BACKUP)
        // Check format and version before decoding the rest, whose shape a newer version may have changed.
        if ((obj["format"] as? JsonPrimitive)?.content != FORMAT_NAME) throw ValidationException(NOT_A_BACKUP)
        val version = (obj["version"] as? JsonPrimitive)?.intOrNull ?: throw ValidationException(DAMAGED)
        if (version > FORMAT_VERSION) throw ValidationException(NEWER_VERSION)
        if (version < 1) throw ValidationException(DAMAGED)
        return try {
            json.decodeFromJsonElement<BackupManifest>(obj)
        } catch (_: SerializationException) {
            throw ValidationException(DAMAGED)
        } catch (_: IllegalArgumentException) {
            throw ValidationException(DAMAGED)
        }
    }

    private fun readData(zip: ZipInputStream): RestoreRows {
        val entry = zip.nextEntry ?: throw ValidationException(DAMAGED)
        if (entry.name != DATA_ENTRY) throw ValidationException(DAMAGED)
        val data = try {
            json.decodeFromStream(BackupData.serializer(), LimitedInputStream(zip, DATA_LIMIT))
        } catch (_: SerializationException) {
            throw ValidationException(DAMAGED)
        } catch (_: IllegalArgumentException) {
            throw ValidationException(DAMAGED)
        }
        val rows = try {
            data.toRows()
        } catch (_: IllegalArgumentException) {
            throw ValidationException(DAMAGED)
        }
        rows.check()
        return rows
    }

    /**
     * Stores every referenced photo under a new name, recording it in [saved]; other entries are ignored.
     * Fails unless the end marker is reached, so a truncated file is never restored with photos missing.
     */
    private suspend fun readPhotos(zip: ZipInputStream, wanted: Set<String>, saved: MutableMap<String, String>) {
        while (true) {
            val entry = zip.nextEntry ?: throw ValidationException(DAMAGED)
            if (entry.name == COMPLETE_ENTRY) return
            val name = entry.name.removePrefix(PHOTOS_PREFIX)
            if (entry.isDirectory || !entry.name.startsWith(PHOTOS_PREFIX) || name !in wanted || name in saved) continue
            val bytes = zip.readLimited(PHOTO_LIMIT)
            saved[name] = photoStore.save(bytes, mimeTypeFor(name))
        }
    }

    // ---- Restore ---------------------------------------------------------------------------------

    private suspend fun currentPhotoFiles(): Set<String> = buildSet {
        db.settings().get()?.logoFile?.let(::add)
        db.students().getAll().forEach { s -> s.photoFile?.let(::add) }
        db.homework().getAllReports().forEach { r -> r.photoFile?.let(::add) }
    }

    /** Parents before children, so every foreign key already has its row. */
    private suspend fun insertAll(rows: RestoreRows) {
        rows.settings?.let { db.settings().upsert(it) }
        db.classGroups().insertAll(rows.classGroups)
        db.students().insertAll(rows.students)
        db.subjects().insertAll(rows.subjects)
        db.subjectTeachers().insertAll(rows.subjectTeachers)
        db.enrollments().insertAll(rows.enrollments)
        db.registers().insertAll(rows.registers)
        db.attendance().upsertAll(rows.attendance)
        db.holidays().insertAll(rows.holidays)
        db.fees().insertAllEntries(rows.feeEntries)
        db.fees().insertAllMeta(rows.installmentMeta)
        db.fees().insertAllRemarks(rows.remarks)
        db.tests().insertAll(rows.tests)
        db.tests().insertRoster(rows.testRoster)
        db.homework().insertAllReports(rows.homeworkReports)
        db.homework().insertItems(rows.homeworkItems)
    }

    companion object {
        const val FORMAT_NAME = "attendance-manager-backup"
        /** Written into new backups; every version from 1 up to this one can be restored. */
        const val FORMAT_VERSION = 2
        const val MIME_TYPE = "application/zip"

        const val NOT_A_BACKUP = "This file is not an Attendance backup. Pick a .zip file made with \"Back up now\"."
        const val LOOKS_LIKE_WEB_BACKUP =
            "This looks like a backup from the web app. Use \"Import from web app backup\" to bring it in."
        const val NEWER_VERSION =
            "This backup was made by a newer version of the app. Update the app on this phone, then try again."
        const val DAMAGED = "This backup file is damaged or incomplete, so it was not restored. Nothing was changed."

        /** "attendance-backup-2026-10-07.zip". */
        fun suggestedFileName(date: LocalDate): String = "attendance-backup-$date.zip"

        private const val MANIFEST_ENTRY = "manifest.json"
        private const val DATA_ENTRY = "data.json"
        private const val PHOTOS_PREFIX = "photos/"
        private const val COMPLETE_ENTRY = "complete"
        private const val BUFFER = 64 * 1024
        private const val MANIFEST_LIMIT = 1L shl 20
        private const val DATA_LIMIT = 512L shl 20
        private const val PHOTO_LIMIT = 32L shl 20

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        private val TABLES_CHILDREN_FIRST = listOf(
            "homework_items", "homework_reports", "test_roster_entries", "tests", "attendance_marks", "holidays",
            "fee_entries", "installment_meta", "student_remarks", "registers", "enrollments", "students",
            "subject_teachers", "class_groups", "subjects", "school_settings",
        )

        private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

        private fun InputStream.readLimited(limit: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            var total = 0L
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw ValidationException(DAMAGED)
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/** Stops a decoder from reading more than [limit] bytes (a zip bomb, or a file that is not ours); never closes [input]. */
private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) count(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }

    override fun close() = Unit

    private fun count(n: Long) {
        count += n
        if (count > limit) throw ValidationException(AppBackup.DAMAGED)
    }
}

/** The backup's rows as entities, photo names still those of the backup. */
private class RestoreRows(
    val settings: SchoolSettings?,
    val classGroups: List<ClassGroup>,
    val students: List<Student>,
    val enrollments: List<Enrollment>,
    val registers: List<Register>,
    val attendance: List<AttendanceMark>,
    val holidays: List<Holiday>,
    val feeEntries: List<FeeEntry>,
    val installmentMeta: List<InstallmentMeta>,
    val remarks: List<StudentRemark>,
    val subjects: List<Subject>,
    val subjectTeachers: List<SubjectTeacher>,
    val tests: List<TestRecord>,
    val testRoster: List<TestRosterEntry>,
    val homeworkReports: List<HomeworkReport>,
    val homeworkItems: List<HomeworkItem>,
) {
    fun photoFiles(): Set<String> = buildSet {
        settings?.logoFile?.let(::add)
        students.forEach { s -> s.photoFile?.let(::add) }
        homeworkReports.forEach { r -> r.photoFile?.let(::add) }
    }

    /** Points the photo columns at the files written on this phone; a photo missing from the file is dropped. */
    fun withPhotos(saved: Map<String, String>): RestoreRows {
        fun map(name: String?) = name?.let(saved::get)
        return RestoreRows(
            settings = settings?.copy(logoFile = map(settings.logoFile)),
            classGroups = classGroups,
            students = students.map { it.copy(photoFile = map(it.photoFile)) },
            enrollments = enrollments,
            registers = registers,
            attendance = attendance,
            holidays = holidays,
            feeEntries = feeEntries,
            installmentMeta = installmentMeta,
            remarks = remarks,
            subjects = subjects,
            subjectTeachers = subjectTeachers,
            tests = tests,
            testRoster = testRoster,
            homeworkReports = homeworkReports.map { it.copy(photoFile = map(it.photoFile)) },
            homeworkItems = homeworkItems,
        )
    }

    fun counts(): Map<String, Int> = linkedMapOf(
        BackupTables.SETTINGS to if (settings == null) 0 else 1,
        BackupTables.CLASS_GROUPS to classGroups.size,
        BackupTables.STUDENTS to students.size,
        BackupTables.ENROLLMENTS to enrollments.size,
        BackupTables.REGISTERS to registers.size,
        BackupTables.ATTENDANCE to attendance.size,
        BackupTables.HOLIDAYS to holidays.size,
        BackupTables.FEE_ENTRIES to feeEntries.size,
        BackupTables.INSTALLMENT_META to installmentMeta.size,
        BackupTables.REMARKS to remarks.size,
        BackupTables.SUBJECTS to subjects.size,
        BackupTables.SUBJECT_TEACHERS to subjectTeachers.size,
        BackupTables.TESTS to tests.size,
        BackupTables.TEST_ROSTER to testRoster.size,
        BackupTables.HOMEWORK_REPORTS to homeworkReports.size,
        BackupTables.HOMEWORK_ITEMS to homeworkItems.size,
    )

    /**
     * Keys and references the database would otherwise reject halfway through the transaction. Unique
     * business keys (B-Form numbers, class names ...) are left to the database's own constraints. Attendance
     * has already been merged to one mark per student and day, so its key leaves out the session.
     */
    fun check() {
        fun fail(detail: String): Nothing =
            throw ValidationException("This backup file is damaged ($detail), so it was not restored. Nothing was changed.")

        fun <T, K> List<T>.uniqueKeys(table: String, key: (T) -> K): Set<K> {
            val keys = HashSet<K>(size * 2)
            for (row in this) if (!keys.add(key(row))) fail("$table has a duplicate row")
            return keys
        }

        val classIds = classGroups.uniqueKeys("classes") { it.id }
        val studentIds = students.uniqueKeys("students") { it.id }
        val subjectIds = subjects.uniqueKeys("subjects") { it.id }
        val enrollmentIds = enrollments.uniqueKeys("enrollments") { it.id }
        val registerIds = registers.uniqueKeys("registers") { it.id }
        val testIds = tests.uniqueKeys("tests") { it.id }
        val reportIds = homeworkReports.uniqueKeys("homework") { it.id }
        homeworkItems.uniqueKeys("homework items") { it.id }
        attendance.uniqueKeys("attendance") { Triple(it.registerId, it.enrollmentId, it.day) }
        subjectTeachers.uniqueKeys("subject teachers") { it.classGroupId to it.subjectId }
        holidays.uniqueKeys("holidays") { it.registerId to it.day }
        feeEntries.uniqueKeys("fees") { Triple(it.registerId, it.enrollmentId, it.installment) }
        installmentMeta.uniqueKeys("installments") { it.registerId to it.installment }
        remarks.uniqueKeys("remarks") { it.registerId to it.enrollmentId }
        testRoster.uniqueKeys("test rosters") { it.testId to it.enrollmentId }

        fun refs(table: String, ok: Boolean) {
            if (!ok) fail("$table refers to a record that is not in the file")
        }
        enrollments.forEach { refs("enrollments", it.studentId in studentIds && it.classGroupId in classIds) }
        registers.forEach { refs("registers", it.classGroupId in classIds) }
        attendance.forEach { refs("attendance", it.registerId in registerIds && it.enrollmentId in enrollmentIds) }
        holidays.forEach { refs("holidays", it.registerId in registerIds) }
        feeEntries.forEach { refs("fees", it.registerId in registerIds && it.enrollmentId in enrollmentIds) }
        installmentMeta.forEach { refs("installments", it.registerId in registerIds) }
        remarks.forEach { refs("remarks", it.registerId in registerIds && it.enrollmentId in enrollmentIds) }
        tests.forEach { refs("tests", it.subjectId in subjectIds && it.classGroupId in classIds) }
        testRoster.forEach { refs("test rosters", it.testId in testIds && it.enrollmentId in enrollmentIds) }
        homeworkReports.forEach { refs("homework", it.classGroupId in classIds) }
        homeworkItems.forEach { refs("homework items", it.reportId in reportIds && it.subjectId in subjectIds) }
        subjectTeachers.forEach { refs("subject teachers", it.classGroupId in classIds && it.subjectId in subjectIds) }

        if (attendance.any { it.day !in 1..31 } || holidays.any { it.day !in 1..31 }) fail("a day is out of range")
    }
}

private fun BackupData.toRows(): RestoreRows {
    val keepName: (String?) -> String? = { it }
    return RestoreRows(
        settings = settings?.toEntity(keepName),
        classGroups = classGroups.map { it.toEntity() },
        students = students.map { it.toEntity(keepName) },
        enrollments = enrollments.map { it.toEntity() },
        registers = registers.map { it.toEntity() },
        // Older backups may hold two timings per day; only one mark per day is ever stored now.
        attendance = mergeTimings(attendance.map { it.toEntity() }),
        holidays = holidays.map { it.toEntity() },
        feeEntries = feeEntries.map { it.toEntity() },
        installmentMeta = installmentMeta.map { it.toEntity() },
        remarks = remarks.map { it.toEntity() },
        subjects = subjects.map { it.toEntity() },
        subjectTeachers = subjectTeachers.map { it.toEntity() },
        tests = tests.map { it.toEntity() },
        testRoster = testRoster.map { it.toEntity() },
        homeworkReports = homeworkReports.map { it.toEntity(keepName) },
        homeworkItems = homeworkItems.map { it.toEntity() },
    )
}

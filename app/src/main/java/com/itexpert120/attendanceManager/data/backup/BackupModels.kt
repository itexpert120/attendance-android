package com.itexpert120.attendanceManager.data.backup

import java.math.BigDecimal

/*
 * Intermediate, schema-faithful view of a PWA (Dexie) backup: every field is what the file said, as
 * strings and exact decimals, with nothing validated or converted yet. `PwaBackupParser` produces it,
 * `PwaBackupMapper` turns it into the native entities. Ids are the PWA's UUID strings.
 */

/** The PWA's table names, in the order a confirmation dialog should list them. */
object PwaTables {
    const val SETTINGS = "settings"
    const val CLASS_GROUPS = "classGroups"
    const val STUDENTS = "students"
    const val ENROLLMENTS = "enrollments"
    const val REGISTERS = "registers"
    const val ATTENDANCE = "attendance"
    const val HOLIDAYS = "holidays"
    const val FEE_ENTRIES = "feeEntries"
    const val INSTALLMENT_META = "installmentMeta"
    const val REMARKS = "remarks"
    const val SUBJECTS = "subjects"
    const val TESTS = "tests"
    const val TEST_ROSTER = "testRoster"
    const val TEST_RESULTS = "testResults"
    const val HOMEWORK_REPORTS = "homeworkReports"

    val ALL: List<String> = listOf(
        SETTINGS, CLASS_GROUPS, STUDENTS, ENROLLMENTS, REGISTERS, ATTENDANCE, HOLIDAYS, FEE_ENTRIES,
        INSTALLMENT_META, REMARKS, SUBJECTS, TESTS, TEST_ROSTER, TEST_RESULTS, HOMEWORK_REPORTS,
    )
}

data class PwaSettings(
    val id: String?,
    val schoolName: String?,
    val academicYearStartMonth: BigDecimal?,
    val currencyLabel: String?,
    val classInchargeName: String?,
    val headmasterName: String?,
    val logoDataUrl: String?,
    val absenceMessageTemplate: String?,
    val updatedAt: String?,
)

data class PwaClassGroup(val id: String, val className: String?, val section: String?, val createdAt: String?)

data class PwaStudent(
    val id: String,
    val admissionNumber: String?,
    val name: String?,
    val phone: String?,
    val dateOfBirth: String?,
    val photoDataUrl: String?,
    val createdAt: String?,
)

data class PwaSubject(
    val id: String,
    val name: String?,
    val normalizedName: String?,
    val archivedAt: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class PwaEnrollment(
    val id: String,
    val studentId: String?,
    val classGroupId: String?,
    val rollNumber: String?,
    val admittedOn: String?,
    val struckOffOn: String?,
)

data class PwaTest(
    val id: String,
    val name: String?,
    val normalizedName: String?,
    val subjectId: String?,
    val classGroupId: String?,
    val date: String?,
    val totalMarks: BigDecimal?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class PwaRosterEntry(val id: String?, val testId: String?, val enrollmentId: String?, val createdAt: String?)

data class PwaTestResult(
    val id: String?,
    val testId: String?,
    val enrollmentId: String?,
    /** "marks" or "absent". */
    val status: String?,
    val marks: BigDecimal?,
    val updatedAt: String?,
)

data class PwaHomeworkItem(val subjectId: String?, val details: String?, val order: BigDecimal?)

data class PwaHomeworkReport(
    val id: String,
    val classGroupId: String?,
    val date: String?,
    val inchargeName: String?,
    val parentNote: String?,
    val items: List<PwaHomeworkItem>,
    val photoDataUrl: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class PwaRegister(
    val id: String,
    val classGroupId: String?,
    val year: BigDecimal?,
    val month: BigDecimal?,
    val createdAt: String?,
)

data class PwaAttendanceMark(
    val id: String?,
    val registerId: String?,
    val enrollmentId: String?,
    val day: BigDecimal?,
    val session: BigDecimal?,
    /** "P", "A" or "L". */
    val status: String?,
)

data class PwaHoliday(val id: String?, val registerId: String?, val day: BigDecimal?, val title: String?)

data class PwaFeeEntry(
    val id: String?,
    val registerId: String?,
    val enrollmentId: String?,
    val installment: BigDecimal?,
    val ftf: BigDecimal?,
    val ff: BigDecimal?,
    val arrears: BigDecimal?,
    val lateCertificate: BigDecimal?,
    val slc: BigDecimal?,
    val dcf: BigDecimal?,
)

data class PwaInstallmentMeta(
    val id: String?,
    val registerId: String?,
    val installment: BigDecimal?,
    val rate: BigDecimal?,
    val receiverName: String?,
)

data class PwaRemark(val id: String?, val registerId: String?, val enrollmentId: String?, val text: String?)

/** Everything in a backup file. Tables that are missing from older backups are simply empty. */
class ParsedBackup(
    val databaseName: String?,
    val databaseVersion: Int?,
    /** Names of the known tables that appear in the file, even when they have no rows. */
    val presentTables: Set<String>,
    /** Rows found per known table (all [PwaTables.ALL] keys, 0 when the table is missing). */
    val rowCounts: Map<String, Int>,
    val settings: List<PwaSettings>,
    val classGroups: List<PwaClassGroup>,
    val students: List<PwaStudent>,
    val enrollments: List<PwaEnrollment>,
    val registers: List<PwaRegister>,
    val attendance: List<PwaAttendanceMark>,
    val holidays: List<PwaHoliday>,
    val feeEntries: List<PwaFeeEntry>,
    val installmentMeta: List<PwaInstallmentMeta>,
    val remarks: List<PwaRemark>,
    val subjects: List<PwaSubject>,
    val tests: List<PwaTest>,
    val testRoster: List<PwaRosterEntry>,
    val testResults: List<PwaTestResult>,
    val homeworkReports: List<PwaHomeworkReport>,
    /** Problems found while reading, e.g. rows that were not JSON objects. */
    val warnings: List<String>,
)

/** What a confirmation dialog shows before the user agrees to replace their data (like Dexie's `peakImportFile`). */
data class BackupSummary(
    val schoolName: String?,
    val databaseVersion: Int?,
    /** Rows per PWA table found in the file, keyed by [PwaTables] names; 0 for tables an older backup lacks. */
    val counts: Map<String, Int>,
    /** Rows that would be skipped or repaired, in human-readable form. */
    val warnings: List<String>,
)

data class ImportResult(
    val schoolName: String?,
    /**
     * Rows actually written, keyed by [PwaTables] names. `testRoster` counts roster entries,
     * `testResults` the entries that carry a Result, and `homeworkItems` is an extra key for the
     * items that were split out of the reports.
     */
    val counts: Map<String, Int>,
    val warnings: List<String>,
)

/** Collects human-readable problems, merging repeats ("attendance: ... (37 rows)") and capping the total. */
class WarningLog(private val maxEntries: Int = 100) {
    private class Entry(val text: String, var count: Int = 0, val examples: MutableList<String> = mutableListOf())

    private val entries = LinkedHashMap<String, Entry>()

    /** A one-off message. */
    fun note(message: String) {
        entries.getOrPut(message) { Entry(message) }
    }

    /** A per-row problem; rows with the same [table] and [reason] are merged into one line with a count. */
    fun add(table: String, reason: String, example: String? = null) {
        val entry = entries.getOrPut("$table\u0000$reason") { Entry("$table: $reason") }
        entry.count++
        if (example != null && entry.examples.size < 3 && example !in entry.examples) entry.examples += example
    }

    fun toList(): List<String> {
        val lines = entries.values.map { entry ->
            when {
                entry.count == 0 -> entry.text
                else -> buildString {
                    append(entry.text)
                    append(" (").append(entry.count).append(if (entry.count == 1) " row" else " rows")
                    if (entry.examples.isNotEmpty()) append("; e.g. ").append(entry.examples.joinToString(", "))
                    append(')')
                }
            }
        }
        return if (lines.size <= maxEntries) {
            lines
        } else {
            lines.take(maxEntries) + "...and ${lines.size - maxEntries} more kinds of problems."
        }
    }
}

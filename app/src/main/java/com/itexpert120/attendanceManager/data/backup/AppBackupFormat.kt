package com.itexpert120.attendanceManager.data.backup

import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.Holiday
import com.itexpert120.attendanceManager.model.HomeworkItem
import com.itexpert120.attendanceManager.model.HomeworkReport
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.StudentRemark
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.SubjectTeacher
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/*
 * The on-disk shape of a native backup (format version 2). It is deliberately decoupled from the Room
 * entities: dates are ISO text, timestamps epoch milliseconds, enums their stable codes, so a schema change
 * only needs a new mapping here (and, when the meaning changes, a new format version), never a change to
 * old files. Ids are kept as they are, so a restore reproduces the database exactly.
 *
 * Zip layout, in this order (the reader relies on it to validate before it writes anything):
 *   manifest.json   [BackupManifest]  small; read alone by `AppBackup.inspect`
 *   data.json       [BackupData]      every table
 *   photos/<name>   one entry per photo file referenced by the data (missing files are left out)
 *   complete        end marker holding the number of photos; a file without it is incomplete
 *
 * Versions:
 *   1  the first native format; attendance could hold two timings (session 1 and 2) per day.
 *   2  adds [BackupData.subjectTeachers]. Attendance is written with session 1 only. Version 1 files are still
 *      read: the missing table is empty and two-timing attendance is merged into one mark per day on restore.
 */

@Serializable
internal class BackupManifest(
    val format: String,
    val version: Int,
    /** Epoch milliseconds. */
    val createdAt: Long,
    val schoolName: String? = null,
    /** Rows per table, keyed by the names in [BackupTables]. */
    val counts: Map<String, Int> = emptyMap(),
    val photoCount: Int = 0,
)

/** Table names used in [BackupManifest.counts] and the restore result. */
object BackupTables {
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
    const val SUBJECT_TEACHERS = "subjectTeachers"
    const val TESTS = "tests"
    const val TEST_ROSTER = "testRoster"
    const val HOMEWORK_REPORTS = "homeworkReports"
    const val HOMEWORK_ITEMS = "homeworkItems"
}

@Serializable
internal class BackupData(
    val settings: SettingsDto? = null,
    val classGroups: List<ClassGroupDto> = emptyList(),
    val students: List<StudentDto> = emptyList(),
    val enrollments: List<EnrollmentDto> = emptyList(),
    val registers: List<RegisterDto> = emptyList(),
    val attendance: List<AttendanceDto> = emptyList(),
    val holidays: List<HolidayDto> = emptyList(),
    val feeEntries: List<FeeEntryDto> = emptyList(),
    val installmentMeta: List<InstallmentMetaDto> = emptyList(),
    val remarks: List<RemarkDto> = emptyList(),
    val subjects: List<SubjectDto> = emptyList(),
    /** Added in format version 2; older files have none. */
    val subjectTeachers: List<SubjectTeacherDto> = emptyList(),
    val tests: List<TestDto> = emptyList(),
    val testRoster: List<RosterEntryDto> = emptyList(),
    val homeworkReports: List<HomeworkReportDto> = emptyList(),
    val homeworkItems: List<HomeworkItemDto> = emptyList(),
) {
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

    /** Every photo file name the data refers to. */
    fun photoFiles(): Set<String> = buildSet {
        settings?.logoFile?.let(::add)
        students.forEach { s -> s.photoFile?.let(::add) }
        homeworkReports.forEach { r -> r.photoFile?.let(::add) }
    }
}

@Serializable
internal class SettingsDto(
    val schoolName: String,
    val academicYearStartMonth: Int,
    val currencyLabel: String,
    val classInchargeName: String,
    val logoFile: String? = null,
    val absenceMessageTemplate: String? = null,
    val updatedAt: Long,
)

@Serializable
internal class ClassGroupDto(val id: Long, val className: String, val section: String, val createdAt: Long)

@Serializable
internal class StudentDto(
    val id: Long,
    val admissionNumber: String,
    val name: String,
    val phone: String,
    val dateOfBirth: String? = null,
    val photoFile: String? = null,
    val createdAt: Long,
)

@Serializable
internal class EnrollmentDto(
    val id: Long,
    val studentId: Long,
    val classGroupId: Long,
    val rollNumber: String,
    val admittedOn: String,
    val struckOffOn: String? = null,
)

@Serializable
internal class RegisterDto(val id: Long, val classGroupId: Long, val year: Int, val month: Int, val createdAt: Long)

/**
 * [session] is always 1 since attendance is taken once a day (format version 1 files may also hold 2; those
 * are merged into one mark per day on restore), [status] the register code "P", "A" or "L".
 */
@Serializable
internal class AttendanceDto(
    val registerId: Long,
    val enrollmentId: Long,
    val day: Int,
    val session: Int,
    val status: String,
)

@Serializable
internal class HolidayDto(val registerId: Long, val day: Int, val title: String)

/** Amounts in minor units. */
@Serializable
internal class FeeEntryDto(
    val registerId: Long,
    val enrollmentId: Long,
    val installment: Int,
    val ftf: Long = 0,
    val ff: Long = 0,
    val arrears: Long = 0,
    val lateCertificate: Long = 0,
    val slc: Long = 0,
    val dcf: Long = 0,
)

@Serializable
internal class InstallmentMetaDto(val registerId: Long, val installment: Int, val rate: Long, val receiverName: String)

@Serializable
internal class RemarkDto(val registerId: Long, val enrollmentId: Long, val text: String)

@Serializable
internal class SubjectDto(
    val id: Long,
    val name: String,
    val normalizedName: String,
    val archivedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal class SubjectTeacherDto(val classGroupId: Long, val subjectId: Long, val teacherName: String)

/** [totalMarks] in hundredths. */
@Serializable
internal class TestDto(
    val id: Long,
    val name: String,
    val normalizedName: String,
    val subjectId: Long,
    val classGroupId: Long,
    val date: String,
    val totalMarks: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/** [result] is a [TestResultStatus] name; [marks] in hundredths. */
@Serializable
internal class RosterEntryDto(
    val testId: Long,
    val enrollmentId: Long,
    val result: String,
    val marks: Int? = null,
    val resultUpdatedAt: Long? = null,
    val createdAt: Long,
)

@Serializable
internal class HomeworkReportDto(
    val id: Long,
    val classGroupId: Long,
    val date: String,
    val inchargeName: String,
    val parentNote: String,
    val photoFile: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
internal class HomeworkItemDto(val id: Long, val reportId: Long, val subjectId: Long, val details: String, val position: Int)

// ---- Entity -> file ------------------------------------------------------------------------------

private fun Instant.ms(): Long = toEpochMilliseconds()

internal fun SchoolSettings.toDto() = SettingsDto(
    schoolName, academicYearStartMonth, currencyLabel, classInchargeName, logoFile, absenceMessageTemplate, updatedAt.ms(),
)

internal fun ClassGroup.toDto() = ClassGroupDto(id, className, section, createdAt.ms())

internal fun Student.toDto() =
    StudentDto(id, admissionNumber, name, phone, dateOfBirth?.toString(), photoFile, createdAt.ms())

internal fun Enrollment.toDto() =
    EnrollmentDto(id, studentId, classGroupId, rollNumber, admittedOn.toString(), struckOffOn?.toString())

internal fun Register.toDto() = RegisterDto(id, classGroupId, year, month, createdAt.ms())

internal fun AttendanceMark.toDto() = AttendanceDto(registerId, enrollmentId, day, session.number, status.code)

internal fun Holiday.toDto() = HolidayDto(registerId, day, title)

internal fun FeeEntry.toDto() = FeeEntryDto(
    registerId, enrollmentId, installment.number,
    amounts.ftf, amounts.ff, amounts.arrears, amounts.lateCertificate, amounts.slc, amounts.dcf,
)

internal fun InstallmentMeta.toDto() = InstallmentMetaDto(registerId, installment.number, rate, receiverName)

internal fun StudentRemark.toDto() = RemarkDto(registerId, enrollmentId, text)

internal fun Subject.toDto() = SubjectDto(id, name, normalizedName, archivedAt?.ms(), createdAt.ms(), updatedAt.ms())

internal fun SubjectTeacher.toDto() = SubjectTeacherDto(classGroupId, subjectId, teacherName)

internal fun TestRecord.toDto() = TestDto(
    id, name, normalizedName, subjectId, classGroupId, date.toString(), totalMarks, createdAt.ms(), updatedAt.ms(),
)

internal fun TestRosterEntry.toDto() =
    RosterEntryDto(testId, enrollmentId, result.name, marks, resultUpdatedAt?.ms(), createdAt.ms())

internal fun HomeworkReport.toDto() = HomeworkReportDto(
    id, classGroupId, date.toString(), inchargeName, parentNote, photoFile, createdAt.ms(), updatedAt.ms(),
)

internal fun HomeworkItem.toDto() = HomeworkItemDto(id, reportId, subjectId, details, position)

// ---- File -> entity ------------------------------------------------------------------------------
// Each mapping throws IllegalArgumentException for a value the app could never have written; the
// reader turns that into a "damaged backup" message.

private fun instant(ms: Long): Instant = Instant.fromEpochMilliseconds(ms)

private fun date(text: String): LocalDate = LocalDate.parse(text)

private fun session(number: Int): Session =
    requireNotNull(Session.fromNumber(number)) { "Unknown session $number" }

private fun installment(number: Int): InstallmentNumber =
    requireNotNull(InstallmentNumber.fromNumber(number)) { "Unknown installment $number" }

/** [photo] maps the file name stored in the backup to the one written on this phone (null when missing). */
internal fun SettingsDto.toEntity(photo: (String?) -> String?): SchoolSettings {
    require(academicYearStartMonth in 1..12) { "Bad academic year start month" }
    return SchoolSettings(
        schoolName = schoolName,
        academicYearStartMonth = academicYearStartMonth,
        currencyLabel = currencyLabel,
        classInchargeName = classInchargeName,
        logoFile = photo(logoFile),
        absenceMessageTemplate = absenceMessageTemplate,
        updatedAt = instant(updatedAt),
    )
}

internal fun ClassGroupDto.toEntity() = ClassGroup(id, className, section, instant(createdAt))

internal fun StudentDto.toEntity(photo: (String?) -> String?) =
    Student(id, admissionNumber, name, phone, dateOfBirth?.let(::date), photo(photoFile), instant(createdAt))

internal fun EnrollmentDto.toEntity() =
    Enrollment(id, studentId, classGroupId, rollNumber, date(admittedOn), struckOffOn?.let(::date))

internal fun RegisterDto.toEntity(): Register {
    require(month in 1..12) { "Bad register month" }
    return Register(id, classGroupId, year, month, instant(createdAt))
}

internal fun AttendanceDto.toEntity() = AttendanceMark(
    registerId, enrollmentId, day, session(session),
    requireNotNull(AttendanceStatus.fromCode(status)) { "Unknown attendance status $status" },
)

internal fun HolidayDto.toEntity() = Holiday(registerId, day, title)

internal fun FeeEntryDto.toEntity() = FeeEntry(
    registerId, enrollmentId, installment(installment), FeeAmounts(ftf, ff, arrears, lateCertificate, slc, dcf),
)

internal fun InstallmentMetaDto.toEntity() = InstallmentMeta(registerId, installment(installment), rate, receiverName)

internal fun RemarkDto.toEntity() = StudentRemark(registerId, enrollmentId, text)

internal fun SubjectDto.toEntity() =
    Subject(id, name, normalizedName, archivedAt?.let(::instant), instant(createdAt), instant(updatedAt))

internal fun SubjectTeacherDto.toEntity() = SubjectTeacher(classGroupId, subjectId, teacherName)

internal fun TestDto.toEntity() = TestRecord(
    id, name, normalizedName, subjectId, classGroupId, date(date), totalMarks, instant(createdAt), instant(updatedAt),
)

internal fun RosterEntryDto.toEntity(): TestRosterEntry {
    val status = TestResultStatus.entries.firstOrNull { it.name == result }
        ?: throw IllegalArgumentException("Unknown result $result")
    require((status == TestResultStatus.MARKS) == (marks != null)) { "Marks do not match the result" }
    return TestRosterEntry(testId, enrollmentId, status, marks, resultUpdatedAt?.let(::instant), instant(createdAt))
}

internal fun HomeworkReportDto.toEntity(photo: (String?) -> String?) = HomeworkReport(
    id, classGroupId, date(date), inchargeName, parentNote, photo(photoFile), instant(createdAt), instant(updatedAt),
)

internal fun HomeworkItemDto.toEntity() = HomeworkItem(id, reportId, subjectId, details, position)

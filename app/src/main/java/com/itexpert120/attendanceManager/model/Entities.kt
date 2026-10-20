package com.itexpert120.attendanceManager.model

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/*
 * The persisted model. These classes are the single source of truth for both the Room schema and the
 * domain logic in `domain/`, so they deliberately avoid Android types: the only platform-specific
 * pieces are the Room annotations, which also exist for Kotlin Multiplatform.
 *
 * Differences from the PWA's Dexie schema (the importer converts old backups):
 *  - Surrogate Long keys instead of UUID strings; composite keys where a row is naturally identified
 *    by its parents (attendance marks, fees, holidays, remarks, roster entries).
 *  - Real foreign keys, so a deleted class or register can't leave orphan rows behind.
 *  - Dates are LocalDate and timestamps are Instant; the PWA kept both as strings.
 *  - Marks are integer hundredths ("Marks ... at most two decimal places") so sums never drift.
 *  - A Test Result lives on its Test Roster entry rather than in a second table. The domain rule
 *    "one Result per roster member" then holds by construction and a result can't outlive its entry.
 *  - Photos are files in app storage referenced by name, not base64 data URLs inside the database.
 */

enum class AttendanceStatus(val code: String) {
    PRESENT("P"),
    ABSENT("A"),
    LEAVE("L");

    companion object {
        fun fromCode(code: String): AttendanceStatus? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The register's timing. Attendance is taken once a day, always as [FIRST]; [SECOND] only survives in the
 * schema so old backups can be read, and every import merges it into [FIRST] (see `mergeTimings`).
 */
enum class Session(val number: Int) {
    FIRST(1),
    SECOND(2);

    companion object {
        fun fromNumber(number: Int): Session? = entries.firstOrNull { it.number == number }
    }
}

enum class TestResultStatus { NOT_ENTERED, ABSENT, MARKS }

enum class InstallmentNumber(val number: Int) {
    FIRST(1),
    SECOND(2),
    THIRD(3);

    companion object {
        fun fromNumber(number: Int): InstallmentNumber? = entries.firstOrNull { it.number == number }
    }
}

@Entity(tableName = "school_settings")
data class SchoolSettings(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val schoolName: String,
    /** 1-12; the first month of the school's academic year. */
    val academicYearStartMonth: Int,
    val currencyLabel: String = "Rs.",
    val classInchargeName: String = "",
    val logoFile: String? = null,
    val absenceMessageTemplate: String? = null,
    val updatedAt: Instant,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(
    tableName = "class_groups",
    indices = [Index(value = ["className", "section"], unique = true)],
)
data class ClassGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val className: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val section: String,
    val createdAt: Instant,
)

@Entity(
    tableName = "students",
    indices = [Index(value = ["admissionNumber"], unique = true), Index("name")],
)
data class Student(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The student's B-Form (NADRA child registration) number; the column keeps its original name. */
    @ColumnInfo(collate = ColumnInfo.NOCASE) val admissionNumber: String,
    val name: String,
    /** E.164 once normalised, or empty when the student has no phone number. */
    val phone: String = "",
    val dateOfBirth: LocalDate? = null,
    val photoFile: String? = null,
    val createdAt: Instant,
)

@Entity(
    tableName = "enrollments",
    foreignKeys = [
        ForeignKey(Student::class, parentColumns = ["id"], childColumns = ["studentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ClassGroup::class, parentColumns = ["id"], childColumns = ["classGroupId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("studentId"), Index("classGroupId", "rollNumber")],
)
data class Enrollment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    val classGroupId: Long,
    val rollNumber: String,
    /** Inclusive. */
    val admittedOn: LocalDate,
    /** Inclusive; null while the student is still on the roll. */
    val struckOffOn: LocalDate? = null,
)

/** An enrollment together with its student; the unit every register and report iterates over. */
data class EnrollmentRow(
    @Embedded val enrollment: Enrollment,
    @Relation(parentColumns = ["studentId"], entityColumns = ["id"]) val student: Student,
)

@Entity(
    tableName = "registers",
    foreignKeys = [
        ForeignKey(ClassGroup::class, parentColumns = ["id"], childColumns = ["classGroupId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["classGroupId", "year", "month"], unique = true)],
)
data class Register(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val classGroupId: Long,
    val year: Int,
    /** 1-12. */
    val month: Int,
    val createdAt: Instant,
)

@Entity(
    tableName = "attendance_marks",
    primaryKeys = ["registerId", "enrollmentId", "day", "session"],
    foreignKeys = [
        ForeignKey(Register::class, parentColumns = ["id"], childColumns = ["registerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Enrollment::class, parentColumns = ["id"], childColumns = ["enrollmentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("enrollmentId")],
)
data class AttendanceMark(
    val registerId: Long,
    val enrollmentId: Long,
    val day: Int,
    val session: Session,
    val status: AttendanceStatus,
)

@Entity(
    tableName = "holidays",
    primaryKeys = ["registerId", "day"],
    foreignKeys = [
        ForeignKey(Register::class, parentColumns = ["id"], childColumns = ["registerId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class Holiday(
    val registerId: Long,
    val day: Int,
    val title: String,
)

/** Fee categories in minor units (paisa); the same six columns the printed register uses. */
data class FeeAmounts(
    val ftf: Long = 0,
    val ff: Long = 0,
    val arrears: Long = 0,
    val lateCertificate: Long = 0,
    val slc: Long = 0,
    val dcf: Long = 0,
) {
    val total: Long get() = ftf + ff + arrears + lateCertificate + slc + dcf

    operator fun plus(other: FeeAmounts) = FeeAmounts(
        ftf = ftf + other.ftf,
        ff = ff + other.ff,
        arrears = arrears + other.arrears,
        lateCertificate = lateCertificate + other.lateCertificate,
        slc = slc + other.slc,
        dcf = dcf + other.dcf,
    )

    companion object {
        val EMPTY = FeeAmounts()
    }
}

@Entity(
    tableName = "fee_entries",
    primaryKeys = ["registerId", "enrollmentId", "installment"],
    foreignKeys = [
        ForeignKey(Register::class, parentColumns = ["id"], childColumns = ["registerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Enrollment::class, parentColumns = ["id"], childColumns = ["enrollmentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("enrollmentId")],
)
data class FeeEntry(
    val registerId: Long,
    val enrollmentId: Long,
    val installment: InstallmentNumber,
    @Embedded val amounts: FeeAmounts = FeeAmounts.EMPTY,
)

@Entity(
    tableName = "installment_meta",
    primaryKeys = ["registerId", "installment"],
    foreignKeys = [
        ForeignKey(Register::class, parentColumns = ["id"], childColumns = ["registerId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class InstallmentMeta(
    val registerId: Long,
    val installment: InstallmentNumber,
    /** Minor units. */
    val rate: Long,
    val receiverName: String,
)

@Entity(
    tableName = "student_remarks",
    primaryKeys = ["registerId", "enrollmentId"],
    foreignKeys = [
        ForeignKey(Register::class, parentColumns = ["id"], childColumns = ["registerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Enrollment::class, parentColumns = ["id"], childColumns = ["enrollmentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("enrollmentId")],
)
data class StudentRemark(
    val registerId: Long,
    val enrollmentId: Long,
    val text: String,
)

/** School-wide catalogue entry; archived rather than deleted so historical Tests keep their Subject. */
@Entity(
    tableName = "subjects",
    indices = [Index(value = ["normalizedName"], unique = true)],
)
data class Subject(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val archivedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isArchived: Boolean get() = archivedAt != null
}

@Entity(
    tableName = "tests",
    foreignKeys = [
        ForeignKey(Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(ClassGroup::class, parentColumns = ["id"], childColumns = ["classGroupId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["classGroupId", "subjectId", "date", "normalizedName"], unique = true),
        Index("subjectId"),
        Index("date"),
    ],
)
data class TestRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val normalizedName: String,
    val subjectId: Long,
    val classGroupId: Long,
    val date: LocalDate,
    /** Hundredths of a mark, so 40 marks is 4000. */
    val totalMarks: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Membership of one Enrollment in one Test, plus that member's Test Result. A result is Not Entered
 * until marked Absent or given Marks; [marks] is non-null exactly when [result] is MARKS.
 */
@Entity(
    tableName = "test_roster_entries",
    primaryKeys = ["testId", "enrollmentId"],
    foreignKeys = [
        ForeignKey(TestRecord::class, parentColumns = ["id"], childColumns = ["testId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Enrollment::class, parentColumns = ["id"], childColumns = ["enrollmentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("enrollmentId")],
)
data class TestRosterEntry(
    val testId: Long,
    val enrollmentId: Long,
    val result: TestResultStatus = TestResultStatus.NOT_ENTERED,
    /** Hundredths of a mark. */
    val marks: Int? = null,
    val resultUpdatedAt: Instant? = null,
    val createdAt: Instant,
)

/** Who teaches one Subject in one Class Group; the Teacher Performance report groups results by this name. */
@Entity(
    tableName = "subject_teachers",
    primaryKeys = ["classGroupId", "subjectId"],
    foreignKeys = [
        ForeignKey(ClassGroup::class, parentColumns = ["id"], childColumns = ["classGroupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("subjectId")],
)
data class SubjectTeacher(
    val classGroupId: Long,
    val subjectId: Long,
    val teacherName: String,
)

@Entity(
    tableName = "homework_reports",
    foreignKeys = [
        ForeignKey(ClassGroup::class, parentColumns = ["id"], childColumns = ["classGroupId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["classGroupId", "date"], unique = true)],
)
data class HomeworkReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val classGroupId: Long,
    val date: LocalDate,
    val inchargeName: String,
    val parentNote: String,
    /** A photographed diary page; mutually exclusive with typed [HomeworkItem]s. */
    val photoFile: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(
    tableName = "homework_items",
    foreignKeys = [
        ForeignKey(HomeworkReport::class, parentColumns = ["id"], childColumns = ["reportId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Subject::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["reportId", "subjectId"], unique = true), Index("subjectId")],
)
data class HomeworkItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reportId: Long,
    val subjectId: Long,
    val details: String,
    /** Display order within the report, from 0. */
    val position: Int,
)

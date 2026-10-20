 package com.itexpert120.attendanceManager.data.db

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.Holiday
import com.itexpert120.attendanceManager.model.HomeworkItem
import com.itexpert120.attendanceManager.model.HomeworkReport
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.StudentRemark
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.SubjectTeacher
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/*
 * Plain data access. Business rules (validation, roster locking, "editable for seven days" ...) live in
 * the repositories, which call these inside `withWriteTransaction` whenever a change spans several rows.
 * Lists that the UI sorts by natural order (class names, roll numbers) are sorted in Kotlin, not SQL.
 */

@Dao
interface SettingsDao {
    @Query("SELECT * FROM school_settings WHERE id = ${SchoolSettings.SINGLETON_ID}")
    fun observe(): Flow<SchoolSettings?>

    @Query("SELECT * FROM school_settings WHERE id = ${SchoolSettings.SINGLETON_ID}")
    suspend fun get(): SchoolSettings?

    @Upsert
    suspend fun upsert(settings: SchoolSettings)
}

@Dao
interface ClassGroupDao {
    @Query("SELECT * FROM class_groups")
    fun observeAll(): Flow<List<ClassGroup>>

    @Query("SELECT * FROM class_groups")
    suspend fun getAll(): List<ClassGroup>

    @Query("SELECT * FROM class_groups WHERE id = :id")
    suspend fun getById(id: Long): ClassGroup?

    /** Class name and section compare case-insensitively (their columns are NOCASE). */
    @Query("SELECT * FROM class_groups WHERE className = :className AND section = :section")
    suspend fun find(className: String, section: String): ClassGroup?

    @Insert
    suspend fun insert(group: ClassGroup): Long

    /** Backup restore: rows keep their ids. */
    @Insert
    suspend fun insertAll(groups: List<ClassGroup>)

    @Update
    suspend fun update(group: ClassGroup)

    /** Cascades to the class's enrollments, registers (with marks and fees), Tests and homework. */
    @Delete
    suspend fun delete(group: ClassGroup)

    @Query(
        """SELECT
            (SELECT COUNT(*) FROM enrollments WHERE classGroupId = :id) AS enrollments,
            (SELECT COUNT(*) FROM registers WHERE classGroupId = :id) AS registers,
            (SELECT COUNT(*) FROM tests WHERE classGroupId = :id) AS tests,
            (SELECT COUNT(*) FROM homework_reports WHERE classGroupId = :id) AS homeworkReports""",
    )
    suspend fun usage(id: Long): ClassUsage

    @Query("SELECT photoFile FROM homework_reports WHERE classGroupId = :id AND photoFile IS NOT NULL")
    suspend fun homeworkPhotoFiles(id: Long): List<String>
}

/** What deleting a class would remove with it, for the confirmation. */
data class ClassUsage(val enrollments: Int, val registers: Int, val tests: Int, val homeworkReports: Int) {
    val isEmpty: Boolean get() = enrollments == 0 && registers == 0 && tests == 0 && homeworkReports == 0
}

@Dao
interface StudentDao {
    @Query("SELECT * FROM students")
    fun observeAll(): Flow<List<Student>>

    @Query("SELECT * FROM students")
    suspend fun getAll(): List<Student>

    @Query("SELECT * FROM students WHERE id = :id")
    suspend fun getById(id: Long): Student?

    @Query("SELECT * FROM students WHERE admissionNumber = :admissionNumber")
    suspend fun findByAdmissionNumber(admissionNumber: String): Student?

    @Insert
    suspend fun insert(student: Student): Long

    /** Backup restore: rows keep their ids. */
    @Insert
    suspend fun insertAll(students: List<Student>)

    @Update
    suspend fun update(student: Student)
}

@Dao
interface EnrollmentDao {
    @Transaction
    @Query("SELECT * FROM enrollments WHERE classGroupId = :classGroupId")
    fun observeRowsForClass(classGroupId: Long): Flow<List<EnrollmentRow>>

    @Transaction
    @Query("SELECT * FROM enrollments WHERE classGroupId = :classGroupId")
    suspend fun getRowsForClass(classGroupId: Long): List<EnrollmentRow>

    @Transaction
    @Query("SELECT * FROM enrollments")
    fun observeAllRows(): Flow<List<EnrollmentRow>>

    @Query("SELECT * FROM enrollments WHERE classGroupId = :classGroupId")
    suspend fun getForClass(classGroupId: Long): List<Enrollment>

    @Query("SELECT * FROM enrollments WHERE id = :id")
    suspend fun getById(id: Long): Enrollment?

    /** Roll numbers must be unique among students still on the roll at [admittedOn]; compares case-insensitively. */
    @Query(
        """
        SELECT COUNT(*) FROM enrollments
        WHERE classGroupId = :classGroupId
          AND rollNumber = :rollNumber COLLATE NOCASE
          AND id != :excludingEnrollmentId
          AND (struckOffOn IS NULL OR struckOffOn >= :admittedOn)
        """,
    )
    suspend fun countRollNumberConflicts(
        classGroupId: Long,
        rollNumber: String,
        excludingEnrollmentId: Long,
        admittedOn: LocalDate,
    ): Int

    @Insert
    suspend fun insert(enrollment: Enrollment): Long

    @Insert
    suspend fun insertAll(enrollments: List<Enrollment>)

    @Update
    suspend fun update(enrollment: Enrollment)

    /** Every enrollment, for the backup. */
    @Query("SELECT * FROM enrollments")
    suspend fun getAll(): List<Enrollment>
}

@Dao
interface RegisterDao {
    @Query("SELECT * FROM registers ORDER BY year DESC, month DESC")
    fun observeAll(): Flow<List<Register>>

    @Query("SELECT * FROM registers ORDER BY year DESC, month DESC")
    suspend fun getAll(): List<Register>

    @Query("SELECT * FROM registers WHERE id = :id")
    suspend fun getById(id: Long): Register?

    @Query("SELECT * FROM registers WHERE id = :id")
    fun observeById(id: Long): Flow<Register?>

    @Query("SELECT * FROM registers WHERE classGroupId = :classGroupId AND year = :year AND month = :month")
    suspend fun find(classGroupId: Long, year: Int, month: Int): Register?

    @Insert
    suspend fun insert(register: Register): Long

    /** Backup restore: rows keep their ids. */
    @Insert
    suspend fun insertAll(registers: List<Register>)
}

/** Present-session totals per enrollment, used for the register's "brought forward" column. */
data class PresentSessionCount(val enrollmentId: Long, val sessions: Int)

/** How many students of a register have at least one mark on one day (the home screen's progress). */
data class MarkedCount(val registerId: Long, val marked: Int)

@Dao
interface AttendanceDao {
    @Query(
        """SELECT m.registerId AS registerId, COUNT(DISTINCT m.enrollmentId) AS marked
        FROM attendance_marks m JOIN registers r ON r.id = m.registerId
        WHERE r.year = :year AND r.month = :month AND m.day = :day
        GROUP BY m.registerId""",
    )
    fun observeMarkedCounts(year: Int, month: Int, day: Int): Flow<List<MarkedCount>>

    @Query("SELECT * FROM attendance_marks WHERE registerId = :registerId")
    fun observeForRegister(registerId: Long): Flow<List<AttendanceMark>>

    @Query("SELECT * FROM attendance_marks WHERE registerId = :registerId")
    suspend fun getForRegister(registerId: Long): List<AttendanceMark>

    /** Every mark, for the backup. */
    @Query("SELECT * FROM attendance_marks")
    suspend fun getAll(): List<AttendanceMark>

    @Query("SELECT * FROM attendance_marks WHERE registerId IN (:registerIds)")
    suspend fun getForRegisters(registerIds: List<Long>): List<AttendanceMark>

    @Query(
        """
        SELECT enrollmentId, COUNT(*) AS sessions FROM attendance_marks
        WHERE status = 'PRESENT' AND registerId IN (:registerIds)
        GROUP BY enrollmentId
        """,
    )
    suspend fun presentSessionCounts(registerIds: List<Long>): List<PresentSessionCount>

    @Upsert
    suspend fun upsert(mark: AttendanceMark)

    @Upsert
    suspend fun upsertAll(marks: List<AttendanceMark>)

    @Query(
        "DELETE FROM attendance_marks WHERE registerId = :registerId AND enrollmentId = :enrollmentId " +
            "AND day = :day AND session = :session",
    )
    suspend fun delete(registerId: Long, enrollmentId: Long, day: Int, session: Session)

    @Query(
        "DELETE FROM attendance_marks WHERE registerId = :registerId AND day = :day " +
            "AND session = :session AND enrollmentId IN (:enrollmentIds)",
    )
    suspend fun deleteSession(registerId: Long, day: Int, session: Session, enrollmentIds: List<Long>)

    @Query("DELETE FROM attendance_marks WHERE registerId = :registerId AND day = :day")
    suspend fun deleteDay(registerId: Long, day: Int)

    /** Live version of [presentSessionCounts]; callers must not pass an empty list. */
    @Query(
        """
        SELECT enrollmentId, COUNT(*) AS sessions FROM attendance_marks
        WHERE status = 'PRESENT' AND registerId IN (:registerIds)
        GROUP BY enrollmentId
        """,
    )
    fun observePresentSessionCounts(registerIds: List<Long>): Flow<List<PresentSessionCount>>
}

@Dao
interface HolidayDao {
    @Query("SELECT * FROM holidays WHERE registerId = :registerId")
    fun observeForRegister(registerId: Long): Flow<List<Holiday>>

    @Query("SELECT * FROM holidays WHERE registerId = :registerId")
    suspend fun getForRegister(registerId: Long): List<Holiday>

    /** Every holiday, for the backup. */
    @Query("SELECT * FROM holidays")
    suspend fun getAll(): List<Holiday>

    /** Backup restore. */
    @Insert
    suspend fun insertAll(holidays: List<Holiday>)

    @Upsert
    suspend fun upsert(holiday: Holiday)

    @Query("DELETE FROM holidays WHERE registerId = :registerId AND day = :day")
    suspend fun delete(registerId: Long, day: Int)
}

@Dao
interface FeeDao {
    @Query("SELECT * FROM fee_entries WHERE registerId = :registerId")
    fun observeEntries(registerId: Long): Flow<List<FeeEntry>>

    @Query("SELECT * FROM fee_entries WHERE registerId = :registerId")
    suspend fun getEntries(registerId: Long): List<FeeEntry>

    /** Every fee entry, for the backup. */
    @Query("SELECT * FROM fee_entries")
    suspend fun getAllEntries(): List<FeeEntry>

    /** Every installment's rate and receiver, for the backup. */
    @Query("SELECT * FROM installment_meta")
    suspend fun getAllMeta(): List<InstallmentMeta>

    /** Every remark, for the backup. */
    @Query("SELECT * FROM student_remarks")
    suspend fun getAllRemarks(): List<StudentRemark>

    /** Backup restore. */
    @Insert
    suspend fun insertAllEntries(entries: List<FeeEntry>)

    /** Backup restore. */
    @Insert
    suspend fun insertAllMeta(meta: List<InstallmentMeta>)

    /** Backup restore. */
    @Insert
    suspend fun insertAllRemarks(remarks: List<StudentRemark>)

    @Upsert
    suspend fun upsertEntries(entries: List<FeeEntry>)

    @Query("SELECT * FROM installment_meta WHERE registerId = :registerId")
    fun observeMeta(registerId: Long): Flow<List<InstallmentMeta>>

    @Upsert
    suspend fun upsertMeta(meta: InstallmentMeta)

    @Query("SELECT * FROM student_remarks WHERE registerId = :registerId")
    fun observeRemarks(registerId: Long): Flow<List<StudentRemark>>

    @Upsert
    suspend fun upsertRemark(remark: StudentRemark)

    @Query("DELETE FROM student_remarks WHERE registerId = :registerId AND enrollmentId = :enrollmentId")
    suspend fun deleteRemark(registerId: Long, enrollmentId: Long)
}

@Dao
interface SubjectDao {
    /** Active Subjects first, then alphabetical; natural ordering is applied by the repository. */
    @Query("SELECT * FROM subjects")
    fun observeAll(): Flow<List<Subject>>

    @Query("SELECT * FROM subjects")
    suspend fun getAll(): List<Subject>

    @Query("SELECT * FROM subjects WHERE id = :id")
    suspend fun getById(id: Long): Subject?

    @Query("SELECT * FROM subjects WHERE normalizedName = :normalizedName")
    suspend fun findByNormalizedName(normalizedName: String): Subject?

    @Insert
    suspend fun insert(subject: Subject): Long

    /** Backup restore: rows keep their ids. */
    @Insert
    suspend fun insertAll(subjects: List<Subject>)

    @Update
    suspend fun update(subject: Subject)
}

@Dao
interface SubjectTeacherDao {
    @Query("SELECT * FROM subject_teachers")
    fun observeAll(): Flow<List<SubjectTeacher>>

    @Query("SELECT * FROM subject_teachers")
    suspend fun getAll(): List<SubjectTeacher>

    @Upsert
    suspend fun upsert(teacher: SubjectTeacher)

    @Query("DELETE FROM subject_teachers WHERE classGroupId = :classGroupId AND subjectId = :subjectId")
    suspend fun delete(classGroupId: Long, subjectId: Long)

    /** Backup restore: rows keep their keys. */
    @Insert
    suspend fun insertAll(teachers: List<SubjectTeacher>)
}

@Dao
interface TestDao {
    @Query("SELECT * FROM tests ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<TestRecord>>

    @Query("SELECT * FROM tests WHERE id = :id")
    suspend fun getById(id: Long): TestRecord?

    /** Every Test, for the backup. */
    @Query("SELECT * FROM tests")
    suspend fun getAll(): List<TestRecord>

    /** Every roster entry (with its result), for the backup. */
    @Query("SELECT * FROM test_roster_entries")
    suspend fun getAllRosterEntries(): List<TestRosterEntry>

    /** Backup restore: rows keep their ids. */
    @Insert
    suspend fun insertAll(tests: List<TestRecord>)

    @Query("SELECT * FROM tests WHERE id = :id")
    fun observeById(id: Long): Flow<TestRecord?>

    /** True when another Test already uses the same class, subject, date and (normalised) name. */
    @Query(
        """
        SELECT COUNT(*) FROM tests
        WHERE classGroupId = :classGroupId AND subjectId = :subjectId AND date = :date
          AND normalizedName = :normalizedName AND id != :excludingTestId
        """,
    )
    suspend fun countDuplicates(
        classGroupId: Long,
        subjectId: Long,
        date: LocalDate,
        normalizedName: String,
        excludingTestId: Long,
    ): Int

    @Insert
    suspend fun insert(test: TestRecord): Long

    @Update
    suspend fun update(test: TestRecord)

    /** Cascades to the roster entries, and with them the results. */
    @Delete
    suspend fun delete(test: TestRecord)

    @Query("SELECT * FROM test_roster_entries WHERE testId = :testId")
    fun observeRoster(testId: Long): Flow<List<TestRosterEntry>>

    @Query("SELECT * FROM test_roster_entries WHERE testId = :testId")
    suspend fun getRoster(testId: Long): List<TestRosterEntry>

    /** Every roster entry, for the Tests list and reports where per-Test progress is shown. */
    @Query("SELECT * FROM test_roster_entries")
    fun observeAllRosterEntries(): Flow<List<TestRosterEntry>>

    @Query("SELECT * FROM test_roster_entries WHERE testId = :testId AND enrollmentId = :enrollmentId")
    suspend fun getRosterEntry(testId: Long, enrollmentId: Long): TestRosterEntry?

    @Transaction
    @Query(
        """
        SELECT e.* FROM enrollments e
        JOIN test_roster_entries r ON r.enrollmentId = e.id
        WHERE r.testId = :testId
        """,
    )
    fun observeRosterRows(testId: Long): Flow<List<EnrollmentRow>>

    @Query("SELECT COUNT(*) FROM test_roster_entries WHERE testId = :testId AND result != 'NOT_ENTERED'")
    suspend fun countEnteredResults(testId: Long): Int

    @Query("SELECT MAX(marks) FROM test_roster_entries WHERE testId = :testId AND result = 'MARKS'")
    suspend fun highestMarks(testId: Long): Int?

    @Insert
    suspend fun insertRoster(entries: List<TestRosterEntry>)

    @Upsert
    suspend fun upsertRosterEntry(entry: TestRosterEntry)

    @Query("DELETE FROM test_roster_entries WHERE testId = :testId")
    suspend fun deleteRoster(testId: Long)

    @Query("DELETE FROM test_roster_entries WHERE testId = :testId AND enrollmentId = :enrollmentId")
    suspend fun deleteRosterEntry(testId: Long, enrollmentId: Long)
}

@Dao
interface HomeworkDao {
    @Query("SELECT * FROM homework_reports ORDER BY date DESC, updatedAt DESC")
    fun observeReports(): Flow<List<HomeworkReport>>

    @Query("SELECT * FROM homework_reports WHERE id = :id")
    suspend fun getReport(id: Long): HomeworkReport?

    /** Every report, for the backup. */
    @Query("SELECT * FROM homework_reports")
    suspend fun getAllReports(): List<HomeworkReport>

    /** Every typed item, for the backup. */
    @Query("SELECT * FROM homework_items")
    suspend fun getAllItems(): List<HomeworkItem>

    /** Backup restore: rows keep their ids. */
    @Insert
    suspend fun insertAllReports(reports: List<HomeworkReport>)

    @Query("SELECT * FROM homework_reports WHERE id = :id")
    fun observeReport(id: Long): Flow<HomeworkReport?>

    @Query("SELECT * FROM homework_reports WHERE classGroupId = :classGroupId AND date = :date")
    suspend fun findReport(classGroupId: Long, date: LocalDate): HomeworkReport?

    @Query("SELECT * FROM homework_items ORDER BY reportId, position")
    fun observeAllItems(): Flow<List<HomeworkItem>>

    @Query("SELECT * FROM homework_items WHERE reportId = :reportId ORDER BY position")
    suspend fun getItems(reportId: Long): List<HomeworkItem>

    @Query("SELECT * FROM homework_items WHERE reportId = :reportId ORDER BY position")
    fun observeItems(reportId: Long): Flow<List<HomeworkItem>>

    @Insert
    suspend fun insertReport(report: HomeworkReport): Long

    @Update
    suspend fun updateReport(report: HomeworkReport)

    @Delete
    suspend fun deleteReport(report: HomeworkReport)

    @Insert
    suspend fun insertItems(items: List<HomeworkItem>)

    @Query("DELETE FROM homework_items WHERE reportId = :reportId")
    suspend fun deleteItems(reportId: Long)
}

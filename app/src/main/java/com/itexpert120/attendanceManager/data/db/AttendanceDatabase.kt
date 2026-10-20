package com.itexpert120.attendanceManager.data.db

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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

@Database(
    entities = [
        SchoolSettings::class,
        ClassGroup::class,
        Student::class,
        Enrollment::class,
        Register::class,
        AttendanceMark::class,
        Holiday::class,
        FeeEntry::class,
        InstallmentMeta::class,
        StudentRemark::class,
        Subject::class,
        TestRecord::class,
        TestRosterEntry::class,
        HomeworkReport::class,
        HomeworkItem::class,
        SubjectTeacher::class,
    ],
    version = 2,
    exportSchema = true,
)
@ColumnTypeConverters(Converters::class)
abstract class AttendanceDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun classGroups(): ClassGroupDao
    abstract fun students(): StudentDao
    abstract fun enrollments(): EnrollmentDao
    abstract fun registers(): RegisterDao
    abstract fun attendance(): AttendanceDao
    abstract fun holidays(): HolidayDao
    abstract fun fees(): FeeDao
    abstract fun subjects(): SubjectDao
    abstract fun tests(): TestDao
    abstract fun homework(): HomeworkDao
    abstract fun subjectTeachers(): SubjectTeacherDao

    companion object {
        const val FILE_NAME = "attendance.db"

        fun create(context: Context, fileName: String = FILE_NAME): AttendanceDatabase =
            Room.databaseBuilder<AttendanceDatabase>(context.applicationContext, fileName)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * Version 2: attendance is taken once a day, so each student's two timings merge into one FIRST mark
         * (Present if either was Present, else Leave if either was Leave, else Absent; same rule as
         * `mergeTimings`), and Subject Teachers get their table.
         */
        val MIGRATION_1_2 = Migration(1, 2) { connection ->
            connection.execSQL(
                """
                INSERT INTO attendance_marks (registerId, enrollmentId, day, session, status)
                SELECT registerId, enrollmentId, day, 'FIRST', status FROM attendance_marks s
                WHERE s.session = 'SECOND' AND NOT EXISTS (
                    SELECT 1 FROM attendance_marks f WHERE f.registerId = s.registerId
                    AND f.enrollmentId = s.enrollmentId AND f.day = s.day AND f.session = 'FIRST'
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                UPDATE attendance_marks SET status = (
                    SELECT CASE
                        WHEN SUM(m.status = 'PRESENT') > 0 THEN 'PRESENT'
                        WHEN SUM(m.status = 'LEAVE') > 0 THEN 'LEAVE'
                        ELSE 'ABSENT'
                    END
                    FROM attendance_marks m WHERE m.registerId = attendance_marks.registerId
                    AND m.enrollmentId = attendance_marks.enrollmentId AND m.day = attendance_marks.day
                )
                WHERE session = 'FIRST'
                """.trimIndent(),
            )
            connection.execSQL("DELETE FROM attendance_marks WHERE session = 'SECOND'")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `subject_teachers` (`classGroupId` INTEGER NOT NULL, `subjectId` INTEGER NOT NULL, " +
                    "`teacherName` TEXT NOT NULL, PRIMARY KEY(`classGroupId`, `subjectId`), " +
                    "FOREIGN KEY(`classGroupId`) REFERENCES `class_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_subject_teachers_subjectId` ON `subject_teachers` (`subjectId`)")
        }
    }
}

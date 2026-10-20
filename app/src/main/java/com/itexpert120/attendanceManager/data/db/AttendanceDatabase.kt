package com.itexpert120.attendanceManager.data.db

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
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
    ],
    version = 1,
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

    companion object {
        const val FILE_NAME = "attendance.db"

        fun create(context: Context): AttendanceDatabase =
            Room.databaseBuilder<AttendanceDatabase>(context.applicationContext, FILE_NAME)
                .setDriver(BundledSQLiteDriver())
                .build()
    }
}

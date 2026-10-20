package com.itexpert120.attendanceManager.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.Session
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens a database written with schema version 1 and checks that the 1 -> 2 migration merges the two
 * attendance timings into one mark per day and creates the subject_teachers table.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun migrationOneToTwoMergesTimings() = runTest {
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            V1_SCHEMA.forEach(db::execSQL)
            db.execSQL("INSERT INTO class_groups (id, className, section, createdAt) VALUES (1, '5', 'A', 0)")
            for (id in 1..5) {
                db.execSQL("INSERT INTO students (id, admissionNumber, name, phone, createdAt) VALUES ($id, 'A$id', 'S$id', '', 0)")
                db.execSQL(
                    "INSERT INTO enrollments (id, studentId, classGroupId, rollNumber, admittedOn) VALUES ($id, $id, 1, '$id', '2026-04-01')",
                )
            }
            db.execSQL("INSERT INTO registers (id, classGroupId, year, month, createdAt) VALUES (1, 1, 2026, 8, 0)")
            fun mark(enrollment: Int, session: String, status: String) = db.execSQL(
                "INSERT INTO attendance_marks (registerId, enrollmentId, day, session, status) " +
                    "VALUES (1, $enrollment, 3, '$session', '$status')",
            )
            mark(1, "FIRST", "ABSENT"); mark(1, "SECOND", "PRESENT") // present in either timing
            mark(2, "FIRST", "ABSENT"); mark(2, "SECOND", "LEAVE") // leave beats absent
            mark(3, "FIRST", "ABSENT"); mark(3, "SECOND", "ABSENT")
            mark(4, "SECOND", "LEAVE") // only the second timing was marked
            mark(5, "FIRST", "PRESENT") // only the first timing was marked
            db.version = 1
        }

        val database = AttendanceDatabase.create(context, name)
        try {
            val marks = database.attendance().getForRegister(1).associateBy { it.enrollmentId }
            assertEquals(5, marks.size)
            assertTrue(marks.values.all { it.session == Session.FIRST && it.day == 3 })
            assertEquals(AttendanceStatus.PRESENT, marks.getValue(1).status)
            assertEquals(AttendanceStatus.LEAVE, marks.getValue(2).status)
            assertEquals(AttendanceStatus.ABSENT, marks.getValue(3).status)
            assertEquals(AttendanceStatus.LEAVE, marks.getValue(4).status)
            assertEquals(AttendanceStatus.PRESENT, marks.getValue(5).status)
            assertTrue(database.subjectTeachers().getAll().isEmpty())
        } finally {
            database.close()
        }
    }

    private companion object {
        /** Version 1 exactly as exported in `app/schemas/.../1.json`, including Room's identity hash. */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `school_settings` (`id` INTEGER NOT NULL, `schoolName` TEXT NOT NULL, `academicYearStartMonth` INTEGER NOT NULL, `currencyLabel` TEXT NOT NULL, `classInchargeName` TEXT NOT NULL, `logoFile` TEXT, `absenceMessageTemplate` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `class_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `className` TEXT NOT NULL COLLATE NOCASE, `section` TEXT NOT NULL COLLATE NOCASE, `createdAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_class_groups_className_section` ON `class_groups` (`className`, `section`)",
            "CREATE TABLE IF NOT EXISTS `students` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `admissionNumber` TEXT NOT NULL COLLATE NOCASE, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `dateOfBirth` TEXT, `photoFile` TEXT, `createdAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_students_admissionNumber` ON `students` (`admissionNumber`)",
            "CREATE INDEX IF NOT EXISTS `index_students_name` ON `students` (`name`)",
            "CREATE TABLE IF NOT EXISTS `enrollments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `studentId` INTEGER NOT NULL, `classGroupId` INTEGER NOT NULL, `rollNumber` TEXT NOT NULL, `admittedOn` TEXT NOT NULL, `struckOffOn` TEXT, FOREIGN KEY(`studentId`) REFERENCES `students`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`classGroupId`) REFERENCES `class_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_enrollments_studentId` ON `enrollments` (`studentId`)",
            "CREATE INDEX IF NOT EXISTS `index_enrollments_classGroupId_rollNumber` ON `enrollments` (`classGroupId`, `rollNumber`)",
            "CREATE TABLE IF NOT EXISTS `registers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `classGroupId` INTEGER NOT NULL, `year` INTEGER NOT NULL, `month` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`classGroupId`) REFERENCES `class_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_registers_classGroupId_year_month` ON `registers` (`classGroupId`, `year`, `month`)",
            "CREATE TABLE IF NOT EXISTS `attendance_marks` (`registerId` INTEGER NOT NULL, `enrollmentId` INTEGER NOT NULL, `day` INTEGER NOT NULL, `session` TEXT NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`registerId`, `enrollmentId`, `day`, `session`), FOREIGN KEY(`registerId`) REFERENCES `registers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`enrollmentId`) REFERENCES `enrollments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_attendance_marks_enrollmentId` ON `attendance_marks` (`enrollmentId`)",
            "CREATE TABLE IF NOT EXISTS `holidays` (`registerId` INTEGER NOT NULL, `day` INTEGER NOT NULL, `title` TEXT NOT NULL, PRIMARY KEY(`registerId`, `day`), FOREIGN KEY(`registerId`) REFERENCES `registers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `fee_entries` (`registerId` INTEGER NOT NULL, `enrollmentId` INTEGER NOT NULL, `installment` TEXT NOT NULL, `ftf` INTEGER NOT NULL, `ff` INTEGER NOT NULL, `arrears` INTEGER NOT NULL, `lateCertificate` INTEGER NOT NULL, `slc` INTEGER NOT NULL, `dcf` INTEGER NOT NULL, PRIMARY KEY(`registerId`, `enrollmentId`, `installment`), FOREIGN KEY(`registerId`) REFERENCES `registers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`enrollmentId`) REFERENCES `enrollments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_fee_entries_enrollmentId` ON `fee_entries` (`enrollmentId`)",
            "CREATE TABLE IF NOT EXISTS `installment_meta` (`registerId` INTEGER NOT NULL, `installment` TEXT NOT NULL, `rate` INTEGER NOT NULL, `receiverName` TEXT NOT NULL, PRIMARY KEY(`registerId`, `installment`), FOREIGN KEY(`registerId`) REFERENCES `registers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `student_remarks` (`registerId` INTEGER NOT NULL, `enrollmentId` INTEGER NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`registerId`, `enrollmentId`), FOREIGN KEY(`registerId`) REFERENCES `registers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`enrollmentId`) REFERENCES `enrollments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_student_remarks_enrollmentId` ON `student_remarks` (`enrollmentId`)",
            "CREATE TABLE IF NOT EXISTS `subjects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `archivedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_subjects_normalizedName` ON `subjects` (`normalizedName`)",
            "CREATE TABLE IF NOT EXISTS `tests` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `subjectId` INTEGER NOT NULL, `classGroupId` INTEGER NOT NULL, `date` TEXT NOT NULL, `totalMarks` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`classGroupId`) REFERENCES `class_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_tests_classGroupId_subjectId_date_normalizedName` ON `tests` (`classGroupId`, `subjectId`, `date`, `normalizedName`)",
            "CREATE INDEX IF NOT EXISTS `index_tests_subjectId` ON `tests` (`subjectId`)",
            "CREATE INDEX IF NOT EXISTS `index_tests_date` ON `tests` (`date`)",
            "CREATE TABLE IF NOT EXISTS `test_roster_entries` (`testId` INTEGER NOT NULL, `enrollmentId` INTEGER NOT NULL, `result` TEXT NOT NULL, `marks` INTEGER, `resultUpdatedAt` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`testId`, `enrollmentId`), FOREIGN KEY(`testId`) REFERENCES `tests`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`enrollmentId`) REFERENCES `enrollments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_test_roster_entries_enrollmentId` ON `test_roster_entries` (`enrollmentId`)",
            "CREATE TABLE IF NOT EXISTS `homework_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `classGroupId` INTEGER NOT NULL, `date` TEXT NOT NULL, `inchargeName` TEXT NOT NULL, `parentNote` TEXT NOT NULL, `photoFile` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`classGroupId`) REFERENCES `class_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_homework_reports_classGroupId_date` ON `homework_reports` (`classGroupId`, `date`)",
            "CREATE TABLE IF NOT EXISTS `homework_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `reportId` INTEGER NOT NULL, `subjectId` INTEGER NOT NULL, `details` TEXT NOT NULL, `position` INTEGER NOT NULL, FOREIGN KEY(`reportId`) REFERENCES `homework_reports`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`subjectId`) REFERENCES `subjects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_homework_items_reportId_subjectId` ON `homework_items` (`reportId`, `subjectId`)",
            "CREATE INDEX IF NOT EXISTS `index_homework_items_subjectId` ON `homework_items` (`subjectId`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '9420c22b2d83ed6ef640cc44547e4888')",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '9420c22b2d83ed6ef640cc44547e4888')",
        )
    }
}

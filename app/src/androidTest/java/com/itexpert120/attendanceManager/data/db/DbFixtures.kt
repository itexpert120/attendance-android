package com.itexpert120.attendanceManager.data.db

import androidx.room3.useWriterConnection
import androidx.room3.executeSQL
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.HomeworkReport
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import kotlinx.datetime.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** Shared builders for the DAO / schema tests. */
internal val T0: Instant = Instant.parse("2026-08-15T09:00:00Z")

internal fun d(text: String): LocalDate = LocalDate.parse(text)

internal suspend fun AttendanceDatabase.addClass(name: String = "5", section: String = "A"): Long =
    classGroups().insert(ClassGroup(className = name, section = section, createdAt = T0))

internal suspend fun AttendanceDatabase.addStudent(admission: String, name: String = "Student $admission"): Long =
    students().insert(Student(admissionNumber = admission, name = name, createdAt = T0))

internal suspend fun AttendanceDatabase.enroll(
    studentId: Long,
    classGroupId: Long,
    roll: String = "1",
    admittedOn: String = "2026-04-01",
    struckOffOn: String? = null,
): Long = enrollments().insert(
    Enrollment(
        studentId = studentId,
        classGroupId = classGroupId,
        rollNumber = roll,
        admittedOn = d(admittedOn),
        struckOffOn = struckOffOn?.let(::d),
    ),
)

internal suspend fun AttendanceDatabase.addRegister(classGroupId: Long, year: Int = 2026, month: Int = 8): Long =
    registers().insert(Register(classGroupId = classGroupId, year = year, month = month, createdAt = T0))

internal suspend fun AttendanceDatabase.addSubject(name: String = "Maths"): Long =
    subjects().insert(
        Subject(name = name, normalizedName = name.trim().lowercase(), createdAt = T0, updatedAt = T0),
    )

internal suspend fun AttendanceDatabase.addTest(
    classGroupId: Long,
    subjectId: Long,
    name: String = "Unit 1",
    date: String = "2026-08-10",
    totalMarks: Int = 4000,
): Long = tests().insert(
    TestRecord(
        name = name,
        normalizedName = name.trim().lowercase(),
        subjectId = subjectId,
        classGroupId = classGroupId,
        date = d(date),
        totalMarks = totalMarks,
        createdAt = T0,
        updatedAt = T0,
    ),
)

internal suspend fun AttendanceDatabase.addHomeworkReport(classGroupId: Long, date: String = "2026-08-10"): Long =
    homework().insertReport(
        HomeworkReport(
            classGroupId = classGroupId,
            date = d(date),
            inchargeName = "Ms Khan",
            parentNote = "",
            createdAt = T0,
            updatedAt = T0,
        ),
    )

/** Runs raw SQL (the DAOs have no delete for classes, registers, students or subjects). */
internal suspend fun AttendanceDatabase.exec(sql: String) {
    useWriterConnection { it.executeSQL(sql) }
}

/** Asserts that [block] fails (a unique / foreign-key violation); any thrown exception counts. */
internal suspend fun assertViolation(message: String, block: suspend () -> Unit) {
    val failure = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e
    }
    if (failure == null) throw AssertionError("Expected a constraint violation but none was thrown: $message")
}

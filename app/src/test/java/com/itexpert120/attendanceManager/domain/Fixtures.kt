package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

val EPOCH: Instant = Instant.fromEpochMilliseconds(0)

/** April is the academic-year boundary the PWA tests used. */
const val APRIL = 4

fun date(text: String): LocalDate = LocalDate.parse(text)

fun register(id: Long, year: Int, month: Int, classGroupId: Long = 1) =
    Register(id = id, classGroupId = classGroupId, year = year, month = month, createdAt = EPOCH)

fun enrollment(
    id: Long,
    admittedOn: String,
    struckOffOn: String? = null,
    rollNumber: String = id.toString(),
    classGroupId: Long = 1,
) = Enrollment(
    id = id,
    studentId = id,
    classGroupId = classGroupId,
    rollNumber = rollNumber,
    admittedOn = date(admittedOn),
    struckOffOn = struckOffOn?.let(::date),
)

fun row(enrollment: Enrollment, name: String = "Student ${enrollment.id}") = EnrollmentRow(
    enrollment = enrollment,
    student = Student(id = enrollment.studentId, admissionNumber = "A${enrollment.id}", name = name, createdAt = EPOCH),
)

fun mark(
    registerId: Long,
    enrollmentId: Long,
    day: Int,
    session: Session,
    status: AttendanceStatus,
) = AttendanceMark(registerId, enrollmentId, day, session, status)

fun rosterEntry(
    testId: Long,
    enrollmentId: Long,
    result: TestResultStatus = TestResultStatus.NOT_ENTERED,
    marks: Int? = null,
) = TestRosterEntry(testId = testId, enrollmentId = enrollmentId, result = result, marks = marks, createdAt = EPOCH)

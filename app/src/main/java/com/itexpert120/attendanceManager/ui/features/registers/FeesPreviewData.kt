package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.Student
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Sample [RegisterData] for the Fees and Summary `@Preview`s. Not used at runtime. */
internal object SamplePreviewData {
    private val epoch = Instant.fromEpochMilliseconds(0)

    private val names = listOf("Ayesha Khan", "Bilal Ahmed", "Fatima Noor", "Hamza Ali", "Zainab Rizvi")

    fun register(withStudents: Boolean = true): RegisterData {
        val register = Register(id = 1, classGroupId = 1, year = 2026, month = 8, createdAt = epoch)
        val rows = if (withStudents) {
            names.mapIndexed { index, name ->
                val id = index + 1L
                EnrollmentRow(
                    enrollment = Enrollment(
                        id = id,
                        studentId = id,
                        classGroupId = 1,
                        rollNumber = id.toString(),
                        admittedOn = LocalDate(2026, if (id == 4L) 8 else 4, if (id == 4L) 12 else 1),
                        struckOffOn = if (id == 5L) LocalDate(2026, 8, 20) else null,
                    ),
                    student = Student(id = id, admissionNumber = "A-10$id", name = name, createdAt = epoch),
                )
            }
        } else {
            emptyList()
        }
        val fees = if (withStudents) {
            listOf(
                FeeEntry(1, 1, InstallmentNumber.FIRST, FeeAmounts(ftf = 150_000, ff = 5_000)),
                FeeEntry(1, 1, InstallmentNumber.SECOND, FeeAmounts(ftf = 150_000)),
                FeeEntry(1, 2, InstallmentNumber.FIRST, FeeAmounts(ftf = 80_000, arrears = 20_000)),
                FeeEntry(1, 3, InstallmentNumber.FIRST, FeeAmounts(ftf = 150_000, slc = 10_000, dcf = 2_500)),
            )
        } else {
            emptyList()
        }
        val marks = if (withStudents) {
            (1..3).flatMap { day ->
                (1L..4L).flatMap { id ->
                    Session.entries.map { AttendanceMark(1, id, day + 2, it, AttendanceStatus.PRESENT) }
                }
            }
        } else {
            emptyList()
        }
        return RegisterData(
            register = register,
            classGroup = ClassGroup(id = 1, className = "5", section = "A", createdAt = epoch),
            settings = SchoolSettings(
                schoolName = "Sample School",
                academicYearStartMonth = 4,
                classInchargeName = "Ms. Sara Malik",
                updatedAt = epoch,
            ),
            monthRows = rows,
            attendanceRows = rows.filter { it.enrollment.struckOffOn == null },
            marks = marks,
            holidayTitles = mapOf(14 to "Independence Day"),
            feeEntries = fees,
            installmentMeta = mapOf(
                InstallmentNumber.FIRST to InstallmentMeta(1, InstallmentNumber.FIRST, rate = 150_000, receiverName = "Mr. Tariq"),
                InstallmentNumber.SECOND to InstallmentMeta(1, InstallmentNumber.SECOND, rate = 150_000, receiverName = ""),
            ),
            remarks = if (withStudents) mapOf(2L to "Balance promised next week") else emptyMap(),
            broughtForwardByEnrollment = if (withStudents) mapOf(1L to 60, 2L to 58, 3L to 61, 4L to 0) else emptyMap(),
        )
    }
}

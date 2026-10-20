package com.itexpert120.attendanceManager.data.repo

import com.itexpert120.attendanceManager.domain.AttendanceReport
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.addFees
import com.itexpert120.attendanceManager.domain.attendanceReport
import com.itexpert120.attendanceManager.domain.days
import com.itexpert120.attendanceManager.domain.feesByEnrollment as domainFeesByEnrollment
import com.itexpert120.attendanceManager.domain.installmentStudentCount
import com.itexpert120.attendanceManager.domain.isAttendanceDateEditable
import com.itexpert120.attendanceManager.domain.isHolidayDay
import com.itexpert120.attendanceManager.domain.monthlyMovement
import com.itexpert120.attendanceManager.domain.presentSessionsByEnrollment
import com.itexpert120.attendanceManager.domain.workingTimings as domainWorkingTimings
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Session
import kotlinx.datetime.LocalDate

/** A register paired with its class, for the home list; [classGroup] is null when the class no longer exists. */
data class RegisterListItem(val register: Register, val classGroup: ClassGroup?)

/**
 * Everything the register screens (attendance, fees, summary, reports and the print layouts) need for
 * one register, as a single immutable snapshot. Raw lists are exposed alongside derived lookups; the
 * derived values are computed lazily and at most once per snapshot, using the domain functions.
 *
 * Two student lists exist on purpose, mirroring the PWA:
 *  - [attendanceRows]: students shown in the attendance grid. Anyone struck off during the month is hidden
 *    for the whole month (their marks stay in [marks]).
 *  - [monthRows]: everyone on the roll on any day of the month, struck-off included. Fees, summary
 *    movement and reports use this list.
 * Both are in natural roll-number order.
 *
 * Attendance is taken once a day (stored as [Session.FIRST]), so every attendance count is in days.
 */
data class RegisterData(
    val register: Register,
    val classGroup: ClassGroup,
    /** Null until the school settings have been saved; brought-forward totals are then empty. */
    val settings: SchoolSettings?,
    val monthRows: List<EnrollmentRow>,
    val attendanceRows: List<EnrollmentRow>,
    /** Every mark of this register, including those of students hidden from attendance. */
    val marks: List<AttendanceMark>,
    /** School-declared holidays: day number to the reason. Weekends are not listed here. */
    val holidayTitles: Map<Int, String>,
    val feeEntries: List<FeeEntry>,
    /** Rate and receiver per installment; an installment never saved has no entry. */
    val installmentMeta: Map<InstallmentNumber, InstallmentMeta>,
    /** Non-blank remarks by enrollment id. */
    val remarks: Map<Long, String>,
    /**
     * Present sessions per enrollment in earlier registers of the same class and academic year (the
     * register's "brought forward"), computed by a SQL aggregate. Missing enrollments have zero.
     */
    val broughtForwardByEnrollment: Map<Long, Int>,
) {
    val days: IntRange get() = register.days

    /** Days declared as holidays in this register. */
    val holidayDays: Set<Int> get() = holidayTitles.keys

    /** Weekend or declared holiday. */
    fun isHoliday(day: Int): Boolean = isHolidayDay(register, day, holidayDays)

    /** The reason for a declared (non-weekend) holiday, or null. */
    fun holidayTitle(day: Int): String? = holidayTitles[day]

    /** Whether [day] is inside the seven-day edit window as of [today]. */
    fun isDayEditable(day: Int, today: LocalDate): Boolean = isAttendanceDateEditable(register, day, today)

    private val statusByCell: Map<MarkKey, AttendanceStatus> by lazy {
        marks.filter { it.session == Session.FIRST }.associate { MarkKey(it.enrollmentId, it.day) to it.status }
    }

    /** One student's mark for [day], or null when unmarked. */
    fun statusOf(enrollmentId: Long, day: Int): AttendanceStatus? = statusByCell[MarkKey(enrollmentId, day)]

    /** Whether any enrollment has a mark on [day]; used to warn before a holiday clears them. */
    fun hasMarksOn(day: Int): Boolean = marks.any { it.day == day }

    /** Present days this month per enrollment (hidden students included). */
    val presentByEnrollment: Map<Long, Int> by lazy { presentSessionsByEnrollment(marks, setOf(register.id)) }

    fun presentSessionsOf(enrollmentId: Long): Int = presentByEnrollment[enrollmentId] ?: 0

    fun broughtForwardOf(enrollmentId: Long): Int = broughtForwardByEnrollment[enrollmentId] ?: 0

    /** Present days this month across all students; the summary's "Month attendance". */
    val monthPresentSessions: Int by lazy { presentByEnrollment.values.sum() }

    /** Brought-forward present days across all students; the summary's year-to-date minus this month. */
    val broughtForwardTotal: Int by lazy { broughtForwardByEnrollment.values.sum() }

    /** Footer totals of the attendance grid / printed register: visible students only. */
    val visiblePresentTotal: Int by lazy { attendanceRows.sumOf { presentSessionsOf(it.enrollment.id) } }
    val visibleBroughtForwardTotal: Int by lazy { attendanceRows.sumOf { broughtForwardOf(it.enrollment.id) } }

    /** Present students per day, counting only students shown in the attendance grid. */
    val presentByDay: Map<Int, Int> by lazy {
        val visibleIds = attendanceRows.mapTo(HashSet()) { it.enrollment.id }
        val totals = HashMap<Int, Int>()
        for (mark in marks) {
            if (mark.status != AttendanceStatus.PRESENT || mark.enrollmentId !in visibleIds) continue
            totals[mark.day] = (totals[mark.day] ?: 0) + 1
        }
        totals
    }

    fun presentOn(day: Int): Int = presentByDay[day] ?: 0

    /** Working days in the month: days that are neither weekends nor declared holidays. */
    val workingDays: Int by lazy { domainWorkingTimings(register, holidayDays) }

    /** Present students per working day, as the PWA's "Average attendance" (0.0 with no working days). */
    val averageAttendance: Double
        get() = if (workingDays == 0) 0.0 else monthPresentSessions.toDouble() / workingDays

    val movement by lazy { monthlyMovement(monthRows, register) }

    /** Fee totals across all installments per enrollment. */
    val feesByEnrollment: Map<Long, FeeAmounts> by lazy { domainFeesByEnrollment(feeEntries, register.id) }

    fun feesOf(enrollmentId: Long): FeeAmounts = feesByEnrollment[enrollmentId] ?: FeeAmounts.EMPTY

    /** One student's per-installment amounts (all three installments, empty when never saved). */
    fun feeEntriesOf(enrollmentId: Long): Map<InstallmentNumber, FeeAmounts> {
        val saved = feeEntries.filter { it.enrollmentId == enrollmentId }.associate { it.installment to it.amounts }
        return InstallmentNumber.entries.associateWith { saved[it] ?: FeeAmounts.EMPTY }
    }

    /** The Fees tab's month total: month students only, matching the PWA. */
    val feeMonthTotals: FeeAmounts by lazy { addFees(monthRows.map { feesOf(it.enrollment.id) }) }

    /** Fee totals per installment across all students. */
    val installmentTotals: Map<InstallmentNumber, FeeAmounts> by lazy {
        InstallmentNumber.entries.associateWith { installment ->
            addFees(feeEntries.filter { it.installment == installment }.map { it.amounts })
        }
    }

    /** The summary's grand totals over all installments. */
    val installmentGrandTotals: FeeAmounts by lazy { addFees(installmentTotals.values) }

    /** Students with a non-zero entry, per installment. */
    val installmentStudentCounts: Map<InstallmentNumber, Int> by lazy {
        InstallmentNumber.entries.associateWith { installmentStudentCount(feeEntries, register.id, it) }
    }

    val totalInstallmentStudents: Int by lazy { installmentStudentCounts.values.sum() }

    val totalInstallmentRate: Long by lazy { installmentMeta.values.sumOf { it.rate } }

    fun remarkOf(enrollmentId: Long): String = remarks[enrollmentId].orEmpty()

    /** The daily, weekly or monthly attendance report over [monthRows] (struck-off students included). */
    fun report(type: ReportPeriodType, selectedDay: Int): AttendanceReport =
        attendanceReport(monthRows, marks, holidayDays, register, type, selectedDay)

    private data class MarkKey(val enrollmentId: Long, val day: Int)
}

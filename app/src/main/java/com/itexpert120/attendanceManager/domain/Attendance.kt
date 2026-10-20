package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.Session

/*
 * Attendance is counted in whole sessions. Attendance is now taken once a day, so a session is a day;
 * [sessionsToDays] stays as the single place that converts counts for display.
 */

/**
 * Collapses two-timing data (old databases, native backups, PWA imports) to one mark per student and day,
 * stored as [Session.FIRST]: Present if either timing was Present, else Leave if either was Leave, else Absent.
 * Mirrors the SQL in the database's 1 -> 2 migration.
 */
fun mergeTimings(marks: List<AttendanceMark>): List<AttendanceMark> {
    if (marks.none { it.session != Session.FIRST }) return marks
    return marks
        .groupBy { Triple(it.registerId, it.enrollmentId, it.day) }
        .map { (_, cell) ->
            val status = when {
                cell.any { it.status == AttendanceStatus.PRESENT } -> AttendanceStatus.PRESENT
                cell.any { it.status == AttendanceStatus.LEAVE } -> AttendanceStatus.LEAVE
                else -> AttendanceStatus.ABSENT
            }
            cell.first().copy(session = Session.FIRST, status = status)
        }
}

fun sessionsToDays(sessions: Int): Double = sessions.toDouble() / SESSIONS_PER_DAY

/** Sessions marked present, optionally for a single enrollment. */
fun presentSessions(marks: List<AttendanceMark>, registerId: Long, enrollmentId: Long? = null): Int =
    marks.count {
        it.registerId == registerId &&
            it.status == AttendanceStatus.PRESENT &&
            (enrollmentId == null || it.enrollmentId == enrollmentId)
    }

fun presentSessionsByEnrollment(marks: List<AttendanceMark>, registerIds: Set<Long>): Map<Long, Int> {
    val totals = HashMap<Long, Int>()
    for (mark in marks) {
        if (mark.status != AttendanceStatus.PRESENT || mark.registerId !in registerIds) continue
        totals[mark.enrollmentId] = (totals[mark.enrollmentId] ?: 0) + 1
    }
    return totals
}

/** Present sessions in earlier months of the same academic year and class: the register's "brought forward". */
fun broughtForwardSessions(
    marks: List<AttendanceMark>,
    registers: List<Register>,
    current: Register,
    academicYearStartMonth: Int,
    enrollmentId: Long? = null,
): Int {
    val earlier = registers
        .filter { isRegisterEarlierInAcademicYear(it, current, academicYearStartMonth) }
        .mapTo(HashSet()) { it.id }
    return marks.count {
        it.registerId in earlier &&
            it.status == AttendanceStatus.PRESENT &&
            (enrollmentId == null || it.enrollmentId == enrollmentId)
    }
}

data class MonthlyMovement(val beginning: Int, val end: Int, val admitted: Int, val struckOff: Int)

/** Roll strength at the start and end of the month plus admissions and strike-offs within it. */
fun monthlyMovement(rows: List<EnrollmentRow>, register: Register): MonthlyMovement {
    val start = register.firstDate
    val end = register.lastDate
    return MonthlyMovement(
        beginning = rows.count { it.enrollment.isActiveOn(start) },
        end = rows.count { it.enrollment.isActiveOn(end) },
        admitted = rows.count { it.enrollment.admittedOn in start..end },
        struckOff = rows.count { row -> row.enrollment.struckOffOn?.let { it in start..end } == true },
    )
}

enum class ReportPeriodType { DAILY, WEEKLY, MONTHLY }

/** An inclusive run of days inside one register's month. */
data class ReportPeriod(val startDay: Int, val endDay: Int, val label: String)

/**
 * The days covered by a daily, Monday-to-Sunday weekly, or monthly report anchored on [selectedDay].
 * A week that straddles a month boundary is clipped to this register's month.
 */
fun reportPeriod(register: Register, type: ReportPeriodType, selectedDay: Int): ReportPeriod {
    val lastDay = register.dayCount
    val day = selectedDay.coerceIn(1, lastDay)
    return when (type) {
        ReportPeriodType.MONTHLY -> ReportPeriod(1, lastDay, monthLabel(register.month, register.year))
        ReportPeriodType.WEEKLY -> {
            val weekStart = day - mondayOffset(register.dateOf(day))
            val startDay = maxOf(1, weekStart)
            val endDay = minOf(lastDay, weekStart + 6)
            ReportPeriod(
                startDay,
                endDay,
                "${shortDayMonthLabel(register.dateOf(startDay))} – ${shortDayMonthLabel(register.dateOf(endDay))}",
            )
        }
        ReportPeriodType.DAILY -> ReportPeriod(day, day, longDateLabel(register.dateOf(day)))
    }
}

data class AttendanceReportStudent(
    val row: EnrollmentRow,
    val present: Int,
    val absent: Int,
    val leave: Int,
    val unmarked: Int,
    /** Which sessions were missed; only filled for daily reports, where the parent message needs it. */
    val absentSessions: List<Session>,
)

data class AttendanceReport(
    val type: ReportPeriodType,
    val period: ReportPeriod,
    val present: Int,
    val absent: Int,
    val leave: Int,
    val unmarked: Int,
    val students: List<AttendanceReportStudent>,
) {
    /** Sessions that could have been marked: present + absent + leave + unmarked. */
    val possible: Int get() = present + absent + leave + unmarked
}

/**
 * Counts present, absent, leave and unmarked sessions per student over the report period. Holidays
 * and days outside a student's enrollment are skipped entirely, so they count as neither possible nor
 * unmarked.
 */
fun attendanceReport(
    rows: List<EnrollmentRow>,
    marks: List<AttendanceMark>,
    holidayDays: Set<Int>,
    register: Register,
    type: ReportPeriodType,
    selectedDay: Int,
): AttendanceReport {
    val period = reportPeriod(register, type, selectedDay)
    val statusByCell = marks
        .filter { it.registerId == register.id }
        .associate { Triple(it.enrollmentId, it.day, it.session) to it.status }

    val students = rows.map { row ->
        var present = 0
        var absent = 0
        var leave = 0
        var unmarked = 0
        val absentSessions = mutableListOf<Session>()
        for (day in period.startDay..period.endDay) {
            if (isHolidayDay(register, day, holidayDays) || !row.enrollment.isActiveOn(register.dateOf(day))) continue
            for (session in Session.entries) {
                when (statusByCell[Triple(row.enrollment.id, day, session)]) {
                    AttendanceStatus.PRESENT -> present++
                    AttendanceStatus.ABSENT -> {
                        absent++
                        if (type == ReportPeriodType.DAILY) absentSessions += session
                    }
                    AttendanceStatus.LEAVE -> leave++
                    null -> unmarked++
                }
            }
        }
        AttendanceReportStudent(row, present, absent, leave, unmarked, absentSessions)
    }

    return AttendanceReport(
        type = type,
        period = period,
        present = students.sumOf { it.present },
        absent = students.sumOf { it.absent },
        leave = students.sumOf { it.leave },
        unmarked = students.sumOf { it.unmarked },
        students = students,
    )
}

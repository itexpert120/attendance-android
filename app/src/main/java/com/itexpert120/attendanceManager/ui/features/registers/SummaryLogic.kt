package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.data.repo.RegisterData

/**
 * The attendance figures of the monthly summary, already formatted the way the PWA's Summary tab shows them
 * (attendance in days; the average to two decimals).
 */
data class SummaryFigures(
    /** Present days this month across all students, struck-off included. */
    val monthAttendanceDays: String,
    val averageAttendance: String,
    /** Days that are neither weekends nor declared holidays. */
    val workingDays: String,
    val broughtForwardDays: String,
    /** Brought forward plus this month. */
    val academicYearDays: String,
    val beginning: Int,
    val end: Int,
    val admitted: Int,
    val struckOff: Int,
)

fun summaryFigures(data: RegisterData): SummaryFigures {
    val movement = data.movement
    val month = data.monthPresentSessions
    val broughtForward = data.broughtForwardTotal
    return SummaryFigures(
        monthAttendanceDays = summaryDaysLabel(month),
        averageAttendance = summaryAverageLabel(data.averageAttendance),
        workingDays = summaryDaysLabel(data.workingDays),
        broughtForwardDays = summaryDaysLabel(broughtForward),
        academicYearDays = summaryDaysLabel(month + broughtForward),
        beginning = movement.beginning,
        end = movement.end,
        admitted = movement.admitted,
        struckOff = movement.struckOff,
    )
}

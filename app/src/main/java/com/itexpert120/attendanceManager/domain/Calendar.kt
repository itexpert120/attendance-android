package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Register
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/** Attendance is taken once per school day, so one session is one day. */
const val SESSIONS_PER_DAY = 1

/** How many days an attendance entry stays editable: today and the seven days before it. */
const val ATTENDANCE_EDIT_WINDOW_DAYS = 7

fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

val Register.dayCount: Int get() = daysInMonth(year, month)

val Register.days: IntRange get() = 1..dayCount

val Register.firstDate: LocalDate get() = LocalDate(year, month, 1)

val Register.lastDate: LocalDate get() = LocalDate(year, month, dayCount)

/** The calendar date of [day] in this register's month. [day] must be within the month. */
fun Register.dateOf(day: Int): LocalDate = LocalDate(year, month, day)

fun isWeekend(date: LocalDate): Boolean =
    date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

/** Zero for Monday through six for Sunday. */
fun mondayOffset(date: LocalDate): Int = date.dayOfWeek.ordinal

/**
 * Whether attendance for [day] may still be changed on [today]. Only today and the previous
 * [ATTENDANCE_EDIT_WINDOW_DAYS] dates are open, and future dates never are.
 */
fun isAttendanceDateEditable(register: Register, day: Int, today: LocalDate): Boolean {
    if (day !in register.days) return false
    val daysAgo = today.toEpochDays() - register.dateOf(day).toEpochDays()
    return daysAgo in 0..ATTENDANCE_EDIT_WINDOW_DAYS
}

/** Admission and struck-off dates are both inclusive. */
fun Enrollment.isActiveOn(date: LocalDate): Boolean =
    admittedOn <= date && (struckOffOn == null || struckOffOn >= date)

/** Whether the student was on the roll on any day of the register's month. */
fun Enrollment.isInMonth(register: Register): Boolean =
    admittedOn <= register.lastDate && (struckOffOn == null || struckOffOn >= register.firstDate)

/**
 * Attendance tables hide a student for the whole month they were struck off, though their historical
 * marks stay in the database and they still appear in [isInMonth] based views such as reports.
 */
fun Enrollment.isVisibleInAttendance(register: Register): Boolean {
    if (!isInMonth(register)) return false
    val struckOff = struckOffOn ?: return true
    return struckOff.year * 12 + struckOff.month.number > register.year * 12 + register.month
}

/** Whether [candidate] precedes [current] within the same class and academic year. */
fun isRegisterEarlierInAcademicYear(
    candidate: Register,
    current: Register,
    academicYearStartMonth: Int,
): Boolean {
    if (candidate.classGroupId != current.classGroupId) return false
    if (academicYearStartYear(candidate, academicYearStartMonth) != academicYearStartYear(current, academicYearStartMonth)) {
        return false
    }
    return candidate.year * 12 + candidate.month < current.year * 12 + current.month
}

/** The calendar year in which the academic year containing [register] began. */
fun academicYearStartYear(register: Register, academicYearStartMonth: Int): Int =
    if (register.month >= academicYearStartMonth) register.year else register.year - 1

/** The calendar year in which the academic year containing [date] began, using the current boundary. */
fun academicYearStartYear(date: LocalDate, academicYearStartMonth: Int): Int =
    if (date.month.number >= academicYearStartMonth) date.year else date.year - 1

/** Weekends and school-declared holidays; [holidayDays] holds the day numbers declared in the register. */
fun isHolidayDay(register: Register, day: Int, holidayDays: Set<Int>): Boolean =
    isWeekend(register.dateOf(day)) || day in holidayDays

/** Number of timings (sessions) a student can attend in the register's month. */
fun workingTimings(register: Register, holidayDays: Set<Int>): Int =
    register.days.count { !isHolidayDay(register, it, holidayDays) } * SESSIONS_PER_DAY

/** Students sorted by roll number, comparing digit runs numerically so "2" precedes "10". */
fun List<EnrollmentRow>.sortedByRollNumber(): List<EnrollmentRow> =
    sortedWith { a, b -> naturalCompare(a.enrollment.rollNumber, b.enrollment.rollNumber) }

/**
 * Human-friendly ordering: digit runs compare as numbers, everything else case-insensitively. This
 * matches how the PWA sorted roll numbers and class names with `localeCompare(..., {numeric: true})`.
 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val endA = a.digitRunEnd(i)
            val endB = b.digitRunEnd(j)
            val numA = a.substring(i, endA).trimStart('0')
            val numB = b.substring(j, endB).trimStart('0')
            if (numA.length != numB.length) return numA.length.compareTo(numB.length)
            val byValue = numA.compareTo(numB)
            if (byValue != 0) return byValue
            i = endA
            j = endB
        } else {
            val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    return (a.length - i).compareTo(b.length - j)
}

private fun String.digitRunEnd(start: Int): Int {
    var end = start
    while (end < length && this[end].isDigit()) end++
    return end
}

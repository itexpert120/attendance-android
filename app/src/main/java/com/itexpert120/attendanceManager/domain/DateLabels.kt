package com.itexpert120.attendanceManager.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/*
 * Deterministic English date labels for registers and printed reports. They avoid platform locale APIs
 * so the same text comes out on every device (and on the JVM in unit tests).
 */

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private val WEEKDAY_NAMES = mapOf(
    DayOfWeek.MONDAY to "Monday",
    DayOfWeek.TUESDAY to "Tuesday",
    DayOfWeek.WEDNESDAY to "Wednesday",
    DayOfWeek.THURSDAY to "Thursday",
    DayOfWeek.FRIDAY to "Friday",
    DayOfWeek.SATURDAY to "Saturday",
    DayOfWeek.SUNDAY to "Sunday",
)

fun monthName(month: Int): String = MONTH_NAMES[month - 1]

fun shortMonthName(month: Int): String = monthName(month).take(3)

fun weekdayName(date: LocalDate): String = WEEKDAY_NAMES.getValue(date.dayOfWeek)

fun shortWeekdayName(date: LocalDate): String = weekdayName(date).take(3)

/** "August 2026" */
fun monthLabel(month: Int, year: Int): String = "${monthName(month)} $year"

/** "Aug 2026" */
fun shortMonthLabel(month: Int, year: Int): String = "${shortMonthName(month)} $year"

/** "Monday, August 3, 2026" */
fun longDateLabel(date: LocalDate): String =
    "${weekdayName(date)}, ${monthName(date.month.number)} ${date.day}, ${date.year}"

/** "August 3, 2026" */
fun dateLabel(date: LocalDate): String = "${monthName(date.month.number)} ${date.day}, ${date.year}"

/** "Aug 3" */
fun shortDayMonthLabel(date: LocalDate): String = "${shortMonthName(date.month.number)} ${date.day}"

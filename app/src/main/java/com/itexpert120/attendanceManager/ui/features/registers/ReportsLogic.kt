package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.domain.AttendanceReport
import com.itexpert120.attendanceManager.domain.AttendanceReportStudent
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.reportPeriod
import com.itexpert120.attendanceManager.domain.dayCount
import com.itexpert120.attendanceManager.model.Register
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import java.net.URLEncoder
import kotlin.math.roundToInt

/*
 * Pure helpers for the Reports tab and the parent-message sheet: percentages, ordering, stepping the report
 * period and the text of the SMS / WhatsApp / dial links. No Android types, so they run in JVM unit tests.
 */

/** Present as a share of the marked timings (present + absent + leave), or null when nothing is marked. */
internal fun attendanceRate(present: Int, absent: Int, leave: Int): Double? {
    val marked = present + absent + leave
    return if (marked == 0) null else present * 100.0 / marked
}

/** "92%" or an em dash when there is nothing to divide. */
internal fun formatRate(rate: Double?): String = rate?.let { "${it.roundToInt()}%" } ?: "—"

/** The one-decimal figure of the summary card, "92.5". */
internal fun formatRatePrecise(rate: Double?): String =
    rate?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "—"

internal fun studentRate(student: AttendanceReportStudent): Double? =
    attendanceRate(student.present, student.absent, student.leave)

internal fun reportRate(report: AttendanceReport): Double? = attendanceRate(report.present, report.absent, report.leave)

/** Students with the most absences first; ties keep roll-number order. */
internal fun absenteesFirst(students: List<AttendanceReportStudent>): List<AttendanceReportStudent> =
    students.sortedByDescending { it.absent }

/**
 * The anchor day after stepping the report one period back or forward, or null at the edge of the month.
 * Daily steps by one day, weekly by one Monday-to-Sunday week (clipped to the month), monthly never moves.
 */
internal fun steppedReportDay(register: Register, type: ReportPeriodType, day: Int, forward: Boolean): Int? {
    val period = reportPeriod(register, type, day)
    return when (type) {
        ReportPeriodType.MONTHLY -> null
        ReportPeriodType.DAILY -> {
            val target = if (forward) period.startDay + 1 else period.startDay - 1
            target.takeIf { it in 1..com.itexpert120.attendanceManager.domain.daysInMonth(register.year, register.month) }
        }
        ReportPeriodType.WEEKLY -> {
            val target = if (forward) period.endDay + 1 else period.startDay - 1
            target.takeIf { it in 1..com.itexpert120.attendanceManager.domain.daysInMonth(register.year, register.month) }
        }
    }
}

/** The day a register's reports open on, as in the PWA: today in the current month, otherwise the last day. */
internal fun defaultReportDay(register: Register, today: LocalDate): Int =
    if (register.year == today.year && register.month == today.month.number) today.day else register.dayCount

/** The register day of a date picked in the date picker, or null when it falls outside the register's month. */
internal fun reportDayOf(register: Register, date: LocalDate): Int? =
    date.day.takeIf { date.year == register.year && date.month.number == register.month }

internal fun reportPeriodTypeLabel(type: ReportPeriodType): String = when (type) {
    ReportPeriodType.DAILY -> "Daily"
    ReportPeriodType.WEEKLY -> "Weekly"
    ReportPeriodType.MONTHLY -> "Monthly"
}

/** The line under the period label of the picker card. */
internal fun reportPeriodHint(type: ReportPeriodType): String = when (type) {
    ReportPeriodType.DAILY -> "Tap the date to pick another day"
    ReportPeriodType.WEEKLY -> "Monday to Sunday, within this month"
    ReportPeriodType.MONTHLY -> "The whole register month"
}

/**
 * "P 2 · A 1 · L 0", plus the blank count only when there is any (the PWA's student row). Figures are days:
 * each timing counts as 0.5, as in the PWA.
 */
internal fun reportCountsLine(student: AttendanceReportStudent): String = buildString {
    append("P ${formatSessionsAsDays(student.present)} · A ${formatSessionsAsDays(student.absent)} · L ${formatSessionsAsDays(student.leave)}")
    if (student.unmarked > 0) append(" · blank ${formatSessionsAsDays(student.unmarked)}")
}

/** The PWA's note under the report figures. */
internal const val REPORT_TIMING_NOTE =
    "Each timing counts as 0.5. Weekends, holidays and dates before enrollment are excluded."

/** Students absent at least once in the period, in roll order. */
internal fun reportAbsentees(report: AttendanceReport): List<AttendanceReportStudent> =
    report.students.filter { it.absent > 0 }

/** The period part of a [SentKey], so a tick given for the 14th does not show on the 15th. */
internal fun reportSentPeriod(report: AttendanceReport): String =
    "${report.type.name.lowercase()}-${report.period.startDay}"

/** The sheet's progress line, "2 of 5 parents messaged". */
internal fun messagedProgressLabel(messaged: Int, total: Int): String = when {
    total == 0 -> "No parents to message"
    messaged >= total -> if (total == 1) "Parent messaged" else "All $total parents messaged"
    else -> "$messaged of $total ${if (total == 1) "parent" else "parents"} messaged"
}

// region Contact links

/** Digits only, the way wa.me wants a number: "+923001234567" becomes "923001234567". */
internal fun phoneDigits(phone: String): String = phone.filter { it.isDigit() }

/** Percent-encoding with %20 for spaces, which messaging apps accept in both query strings and bodies. */
internal fun encodeForUrl(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

internal fun smsUriString(phone: String): String = "smsto:${phone.trim()}"

internal fun dialUriString(phone: String): String = "tel:${phone.trim()}"

internal fun whatsappUrl(phone: String, message: String): String =
    "https://wa.me/${phoneDigits(phone)}?text=${encodeForUrl(message)}"

/** Whether a stored phone number can be used for a link (it is E.164 once normalised, empty when missing). */
internal fun hasUsablePhone(phone: String): Boolean = phoneDigits(phone).length >= 5

// endregion

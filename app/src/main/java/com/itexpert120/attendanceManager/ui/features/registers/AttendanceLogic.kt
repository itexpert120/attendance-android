package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.dateOf
import com.itexpert120.attendanceManager.domain.isActiveOn
import com.itexpert120.attendanceManager.domain.isWeekend
import com.itexpert120.attendanceManager.domain.longDateLabel
import com.itexpert120.attendanceManager.domain.shortWeekdayName
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.Session
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/*
 * Pure, JVM-testable rules behind the attendance screen: which days can be marked, the tap-to-cycle
 * order, the live counts and the month heat strip. Nothing here touches Compose or Android.
 */

/** Tap-to-cycle order of one timing: not marked, present, absent, leave, then back to not marked. */
internal fun nextAttendanceStatus(current: AttendanceStatus?): AttendanceStatus? = when (current) {
    null -> AttendanceStatus.PRESENT
    AttendanceStatus.PRESENT -> AttendanceStatus.ABSENT
    AttendanceStatus.ABSENT -> AttendanceStatus.LEAVE
    AttendanceStatus.LEAVE -> null
}

/** What kind of day a calendar day is, regardless of whether it can still be edited. */
internal enum class DayKind { WORKING, WEEKEND, HOLIDAY }

/** Whether the seven-day edit window allows changes to a day. */
internal enum class DayLock { OPEN, LOCKED_PAST, FUTURE }

internal data class DayInfo(
    val day: Int,
    val date: LocalDate,
    val kind: DayKind,
    /** The reason of a declared holiday. */
    val holidayTitle: String?,
    val lock: DayLock,
) {
    /** Marks may be changed: a school day inside the edit window. */
    val canMark: Boolean get() = kind == DayKind.WORKING && lock == DayLock.OPEN

    /** A holiday may be declared or removed (weekends are never declared). */
    val canToggleHoliday: Boolean get() = kind != DayKind.WEEKEND && lock == DayLock.OPEN
}

internal fun dayInfo(data: RegisterData, day: Int, today: LocalDate): DayInfo {
    val date = data.register.dateOf(day)
    val declared = day in data.holidayDays
    val kind = when {
        declared -> DayKind.HOLIDAY
        isWeekend(date) -> DayKind.WEEKEND
        else -> DayKind.WORKING
    }
    val lock = when {
        data.isDayEditable(day, today) -> DayLock.OPEN
        date > today -> DayLock.FUTURE
        else -> DayLock.LOCKED_PAST
    }
    return DayInfo(day, date, kind, data.holidayTitle(day), lock)
}

/** One chip of the horizontally scrolling date strip. */
internal data class StripDay(
    val day: Int,
    /** "Mon" */
    val weekday: String,
    val kind: DayKind,
    val lock: DayLock,
    val isToday: Boolean,
) {
    val canMark: Boolean get() = kind == DayKind.WORKING && lock == DayLock.OPEN
}

internal fun buildDateStrip(data: RegisterData, today: LocalDate): List<StripDay> =
    data.days.map { day ->
        val info = dayInfo(data, day, today)
        StripDay(day, shortWeekdayName(info.date), info.kind, info.lock, isToday = info.date == today)
    }

/**
 * The day the screen opens on, as in the PWA: today when it falls in this register's month, otherwise the
 * first day of the month (past and future months alike).
 */
internal fun defaultSelectedDay(register: Register, today: LocalDate): Int =
    if (register.year == today.year && register.month == today.month.number) {
        today.day.coerceAtMost(com.itexpert120.attendanceManager.domain.daysInMonth(register.year, register.month))
    } else {
        1
    }

/** The text of the banner that explains why a day cannot be edited, or null when it can. */
internal fun dayExplanation(info: DayInfo): String? = when {
    info.kind == DayKind.HOLIDAY -> "Holiday: ${info.holidayTitle ?: "School holiday"}. No attendance is taken."
    info.kind == DayKind.WEEKEND -> "${longDateLabel(info.date).substringBefore(',')} is a weekend, so no attendance is taken."
    info.lock == DayLock.FUTURE -> "This date is in the future. Attendance can be marked from the day itself."
    info.lock == DayLock.LOCKED_PAST ->
        "Locked. Attendance can only be changed for today and the 7 days before it."
    else -> null
}

/** Whether a student can be marked on [day]: they must have been on the roll. */
internal fun isOnRoll(row: EnrollmentRow, register: Register, day: Int): Boolean =
    row.enrollment.isActiveOn(register.dateOf(day))

/** Students shown for [day]: everyone in the attendance list who was on the roll that day. */
internal fun activeRowsOn(data: RegisterData, day: Int): List<EnrollmentRow> =
    data.attendanceRows.filter { isOnRoll(it, data.register, day) }

/**
 * The "No students were enrolled on ..." banner applies: the day can be marked and the class has students
 * this month, but none of them had been admitted yet (the PWA's AttendanceGrid banner).
 */
internal fun nobodyOnRoll(data: RegisterData, info: DayInfo): Boolean =
    info.canMark && data.attendanceRows.isNotEmpty() && activeRowsOn(data, info.day).isEmpty()

/**
 * The second line of a student's row in the day view, as in the PWA: "Roll 4 · Month 12.5 · Total 40" (present
 * days this month and including earlier months of the academic year), or "Enrolled from <date>" when the
 * student was not yet on the roll on [day].
 */
internal fun dayRowSubtitle(data: RegisterData, row: EnrollmentRow, day: Int): String {
    if (!isOnRoll(row, data.register, day)) {
        return "Enrolled from ${com.itexpert120.attendanceManager.domain.dateLabel(row.enrollment.admittedOn)}"
    }
    val id = row.enrollment.id
    val month = data.presentSessionsOf(id)
    val total = month + data.broughtForwardOf(id)
    return "Roll ${row.enrollment.rollNumber} · Month ${formatSessionsAsDays(month)} · Total ${formatSessionsAsDays(total)}"
}

internal data class SessionCounts(val present: Int, val absent: Int, val leave: Int, val unmarked: Int) {
    val total: Int get() = present + absent + leave + unmarked
}

/** Live counts per timing for the day bar, over the students on the roll that day. */
internal fun dayCounts(data: RegisterData, day: Int): Map<Session, SessionCounts> {
    val rows = activeRowsOn(data, day)
    return Session.entries.associateWith { session ->
        var present = 0
        var absent = 0
        var leave = 0
        var unmarked = 0
        for (row in rows) {
            when (data.statusOf(row.enrollment.id, day, session)) {
                AttendanceStatus.PRESENT -> present++
                AttendanceStatus.ABSENT -> absent++
                AttendanceStatus.LEAVE -> leave++
                null -> unmarked++
            }
        }
        SessionCounts(present, absent, leave, unmarked)
    }
}

/** One mark as it was before an action, so the action can be undone. */
internal data class MarkChange(val enrollmentId: Long, val session: Session, val previous: AttendanceStatus?)

/** The current marks of every student on the roll for the given timings. */
internal fun snapshotMarks(data: RegisterData, day: Int, sessions: Collection<Session>): List<MarkChange> =
    activeRowsOn(data, day).flatMap { row ->
        sessions.map { MarkChange(row.enrollment.id, it, data.statusOf(row.enrollment.id, day, it)) }
    }

/** Cells that are blank in the given timings, for "fill blanks as present". */
internal fun blankCells(data: RegisterData, day: Int): List<MarkChange> =
    snapshotMarks(data, day, Session.entries).filter { it.previous == null }

/** What a day looks like in a student's month heat strip. */
internal enum class HeatKind { NO_SCHOOL, HOLIDAY, OFF_ROLL, SCHOOL }

internal data class HeatCell(
    val day: Int,
    val kind: HeatKind,
    val first: AttendanceStatus?,
    val second: AttendanceStatus?,
)

internal fun heatCells(data: RegisterData, row: EnrollmentRow): List<HeatCell> =
    data.days.map { day ->
        val date = data.register.dateOf(day)
        val declared = day in data.holidayDays
        when {
            declared -> HeatCell(day, HeatKind.HOLIDAY, null, null)
            isWeekend(date) -> HeatCell(day, HeatKind.NO_SCHOOL, null, null)
            !row.enrollment.isActiveOn(date) -> HeatCell(day, HeatKind.OFF_ROLL, null, null)
            else -> HeatCell(
                day,
                HeatKind.SCHOOL,
                data.statusOf(row.enrollment.id, day, Session.FIRST),
                data.statusOf(row.enrollment.id, day, Session.SECOND),
            )
        }
    }

/** Sessions per status across a heat strip. */
internal fun heatTotals(cells: List<HeatCell>): Map<AttendanceStatus, Int> {
    val totals = HashMap<AttendanceStatus, Int>()
    for (cell in cells) {
        if (cell.kind != HeatKind.SCHOOL) continue
        cell.first?.let { totals[it] = (totals[it] ?: 0) + 1 }
        cell.second?.let { totals[it] = (totals[it] ?: 0) + 1 }
    }
    return totals
}

/** A day count from sessions: "12", "12.5" (one session is half a day). */
internal fun formatSessionsAsDays(sessions: Int): String =
    if (sessions % 2 == 0) (sessions / 2).toString() else "${sessions / 2}.5"

/** Name of a status for spoken descriptions. */
internal fun attendanceStatusWord(status: AttendanceStatus?): String = when (status) {
    AttendanceStatus.PRESENT -> "present"
    AttendanceStatus.ABSENT -> "absent"
    AttendanceStatus.LEAVE -> "on leave"
    null -> "not marked"
}

internal fun attendanceSessionWord(session: Session): String = when (session) {
    Session.FIRST -> "first timing"
    Session.SECOND -> "second timing"
}

/** The letter shown on a timing button; a dash when nothing is marked. */
internal fun attendanceStatusLetter(status: AttendanceStatus?): String = status?.code ?: "–"

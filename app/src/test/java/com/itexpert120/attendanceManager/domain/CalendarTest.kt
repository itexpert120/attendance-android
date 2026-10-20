package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.AttendanceStatus.ABSENT
import com.itexpert120.attendanceManager.model.AttendanceStatus.PRESENT
import com.itexpert120.attendanceManager.model.Session.FIRST
import com.itexpert120.attendanceManager.model.Session.SECOND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarTest {
    @Test
    fun `uses April as the academic-year boundary`() {
        assertEquals(2025, academicYearStartYear(register(1, 2026, 3), APRIL))
        assertEquals(2026, academicYearStartYear(register(2, 2026, 4), APRIL))
    }

    @Test
    fun `derives a Test academic year from its date and the current school boundary`() {
        assertEquals(2025, academicYearStartYear(date("2026-03-31"), APRIL))
        assertEquals(2026, academicYearStartYear(date("2026-04-01"), APRIL))
    }

    @Test
    fun `handles leap-year month lengths`() {
        assertEquals(29, daysInMonth(2024, 2))
        assertEquals(28, daysInMonth(2025, 2))
        assertEquals(28, daysInMonth(2100, 2)) // century years are not leap years...
        assertEquals(29, daysInMonth(2000, 2)) // ...unless divisible by 400
        assertEquals(30, daysInMonth(2026, 9))
        assertEquals(31, daysInMonth(2026, 8))
    }

    @Test
    fun `allows attendance edits only for today and the previous seven dates`() {
        val august = register(1, 2026, 8)
        val today = date("2026-08-30")
        assertTrue(isAttendanceDateEditable(august, 30, today))
        assertTrue(isAttendanceDateEditable(august, 23, today))
        assertFalse(isAttendanceDateEditable(august, 22, today))
        assertFalse(isAttendanceDateEditable(august, 31, today)) // the future
    }

    @Test
    fun `rejects days that do not exist in the month`() {
        assertFalse(isAttendanceDateEditable(register(1, 2026, 9), 31, date("2026-09-30")))
        assertFalse(isAttendanceDateEditable(register(1, 2026, 9), 0, date("2026-09-30")))
    }

    @Test
    fun `treats weekends and custom dates as holidays`() {
        val august = register(1, 2026, 8)
        val holidays = setOf(3)
        assertTrue(isHolidayDay(august, 1, holidays)) // Saturday
        assertTrue(isHolidayDay(august, 2, holidays)) // Sunday
        assertTrue(isHolidayDay(august, 3, holidays)) // declared
        assertFalse(isHolidayDay(august, 4, holidays))
        assertEquals(40, workingTimings(august, holidays)) // 31 days - 10 weekend days - 1 holiday = 20 days
    }

    @Test
    fun `links brought-forward present sessions within the academic year`() {
        val march = register(1, 2026, 3)
        val april = register(2, 2026, 4)
        val may = register(3, 2026, 5)
        val marks = listOf(
            mark(april.id, 1, 1, FIRST, PRESENT),
            mark(april.id, 1, 1, SECOND, ABSENT),
            mark(may.id, 1, 1, FIRST, PRESENT),
            mark(march.id, 1, 1, FIRST, PRESENT),
        )
        assertEquals(1, broughtForwardSessions(marks, listOf(march, april, may), may, APRIL, enrollmentId = 1))
        assertEquals(1, presentSessions(marks, may.id, enrollmentId = 1))
    }

    @Test
    fun `excludes another class and the previous academic year from brought-forward totals`() {
        val current = register(1, 2026, 5)
        val sameYear = register(2, 2026, 4)
        val oldYear = register(3, 2026, 3)
        val otherClass = register(4, 2026, 4, classGroupId = 2)
        val marks = listOf(sameYear, oldYear, otherClass).map { mark(it.id, 1, 1, FIRST, PRESENT) }
        assertEquals(
            1,
            broughtForwardSessions(marks, listOf(current, sameYear, oldYear, otherClass), current, APRIL, enrollmentId = 1),
        )
    }

    @Test
    fun `sums present sessions per enrollment across the given registers`() {
        val marks = listOf(
            mark(1, 10, 1, FIRST, PRESENT),
            mark(1, 10, 1, SECOND, PRESENT),
            mark(2, 10, 3, FIRST, PRESENT),
            mark(1, 11, 1, FIRST, ABSENT),
            mark(3, 10, 1, FIRST, PRESENT), // register not requested
        )
        assertEquals(mapOf(10L to 3), presentSessionsByEnrollment(marks, setOf(1, 2)))
    }

    @Test
    fun `treats admission and struck-off dates as inclusive attendance boundaries`() {
        val enrollment = enrollment(1, admittedOn = "2026-08-10", struckOffOn = "2026-08-20")
        assertFalse(enrollment.isActiveOn(date("2026-08-09")))
        assertTrue(enrollment.isActiveOn(date("2026-08-10")))
        assertTrue(enrollment.isActiveOn(date("2026-08-20")))
        assertFalse(enrollment.isActiveOn(date("2026-08-21")))
        assertTrue(enrollment.isInMonth(register(1, 2026, 8)))
        assertFalse(enrollment.isInMonth(register(2, 2026, 9)))
    }

    @Test
    fun `hides struck-off students from their whole struck-off month without changing historical eligibility`() {
        val enrollment = enrollment(1, admittedOn = "2026-07-01", struckOffOn = "2026-08-20")
        assertTrue(enrollment.isVisibleInAttendance(register(1, 2026, 7)))
        for (struckOff in listOf("2026-08-01", "2026-08-20", "2026-08-31")) {
            assertFalse(enrollment.copy(struckOffOn = date(struckOff)).isVisibleInAttendance(register(2, 2026, 8)))
        }
        assertFalse(enrollment.isVisibleInAttendance(register(3, 2026, 9)))
        assertTrue(enrollment.copy(struckOffOn = null).isVisibleInAttendance(register(2, 2026, 8)))
        assertTrue(enrollment.isInMonth(register(2, 2026, 8)))
        assertTrue(enrollment.isActiveOn(date("2026-08-19")))
    }

    @Test
    fun `strike-offs in a later month year still keep the student visible`() {
        val enrollment = enrollment(1, admittedOn = "2025-11-01", struckOffOn = "2026-01-15")
        assertTrue(enrollment.isVisibleInAttendance(register(1, 2025, 12)))
        assertFalse(enrollment.isVisibleInAttendance(register(2, 2026, 1)))
    }

    @Test
    fun `derives beginning, end, admitted, and struck-off counts from enrollment dates`() {
        val rows = listOf(
            row(enrollment(1, "2026-07-01")),
            row(enrollment(2, "2026-08-10")),
            row(enrollment(3, "2026-07-01", struckOffOn = "2026-08-20")),
        )
        assertEquals(MonthlyMovement(beginning = 2, end = 2, admitted = 1, struckOff = 1), monthlyMovement(rows, register(1, 2026, 8)))
    }

    @Test
    fun `sorts roll numbers numerically`() {
        val rows = listOf("10", "2", "1", "A3", "A12", "a2")
            .mapIndexed { index, roll -> row(enrollment(index + 1L, "2026-01-01", rollNumber = roll)) }
        // Digits sort before letters; letters ignore case, then their numbers decide: a2 < A3 < A12.
        assertEquals(
            listOf("1", "2", "10", "a2", "A3", "A12"),
            rows.sortedByRollNumber().map { it.enrollment.rollNumber },
        )
        assertEquals(0, naturalCompare("007", "7"))
        assertTrue(naturalCompare("Roll 9", "Roll 10") < 0)
        assertTrue(naturalCompare("7", "7A") < 0)
    }

    @Test
    fun `builds daily reports with explicit absentees and unmarked timings`() {
        val august = register(1, 2026, 8)
        val student = row(enrollment(1, "2026-08-01"), name = "Ayaan S/O Awaan")
        val report = attendanceReport(
            rows = listOf(student),
            marks = listOf(mark(1, 1, 3, FIRST, ABSENT)),
            holidayDays = emptySet(),
            register = august,
            type = ReportPeriodType.DAILY,
            selectedDay = 3,
        )
        assertEquals("Monday, August 3, 2026", report.period.label)
        assertEquals(1, report.absent)
        assertEquals(1, report.unmarked)
        assertEquals(2, report.possible)
        assertEquals(listOf(FIRST), report.students.single().absentSessions)
    }

    @Test
    fun `reports skip holidays and days outside the enrollment`() {
        val august = register(1, 2026, 8)
        val joinedMidWeek = row(enrollment(1, "2026-08-12"))
        val report = attendanceReport(
            rows = listOf(joinedMidWeek),
            marks = emptyList(),
            holidayDays = setOf(14),
            register = august,
            type = ReportPeriodType.WEEKLY,
            selectedDay = 12,
        )
        // Mon 10 - Sun 16: not enrolled until Wed 12, Fri 14 is a holiday, Sat/Sun are weekend.
        // Counted days: Wed 12 and Thu 13 = 4 unmarked sessions.
        assertEquals(4, report.unmarked)
        assertEquals(4, report.possible)
    }

    @Test
    fun `creates a Monday-to-Sunday weekly report within the register month`() {
        val period = reportPeriod(register(1, 2026, 8), ReportPeriodType.WEEKLY, 12)
        assertEquals(10, period.startDay)
        assertEquals(16, period.endDay)
        assertEquals("Aug 10 – Aug 16", period.label)
    }

    @Test
    fun `clips a weekly report to the register month on both sides`() {
        // 1 Aug 2026 is a Saturday, so its Mon-Sun week began in July and ends on Sunday the 2nd.
        val first = reportPeriod(register(1, 2026, 8), ReportPeriodType.WEEKLY, 1)
        assertEquals(1, first.startDay)
        assertEquals(2, first.endDay)
        // 31 Aug 2026 is a Monday; its week runs into September.
        val last = reportPeriod(register(1, 2026, 8), ReportPeriodType.WEEKLY, 31)
        assertEquals(31, last.startDay)
        assertEquals(31, last.endDay)
    }

    @Test
    fun `monthly and clamped daily report periods`() {
        val august = register(1, 2026, 8)
        val monthly = reportPeriod(august, ReportPeriodType.MONTHLY, 5)
        assertEquals(1, monthly.startDay)
        assertEquals(31, monthly.endDay)
        assertEquals("August 2026", monthly.label)
        assertEquals(31, reportPeriod(august, ReportPeriodType.DAILY, 99).startDay)
        assertEquals(1, reportPeriod(august, ReportPeriodType.DAILY, -4).startDay)
    }

    @Test
    fun `converts sessions to days`() {
        assertEquals(0.5, sessionsToDays(1), 0.0)
        assertEquals(2.5, sessionsToDays(5), 0.0)
    }
}

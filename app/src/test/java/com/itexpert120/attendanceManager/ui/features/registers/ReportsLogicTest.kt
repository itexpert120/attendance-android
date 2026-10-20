package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.attendanceReport
import com.itexpert120.attendanceManager.domain.date
import com.itexpert120.attendanceManager.domain.enrollment
import com.itexpert120.attendanceManager.domain.mark
import com.itexpert120.attendanceManager.domain.register
import com.itexpert120.attendanceManager.domain.row
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportsLogicTest {
    // October 2026: the 1st is a Thursday, the 5th a Monday, the 31st a Saturday.
    private val october = register(1, 2026, 10)

    @Test
    fun defaultDayIsTodayInTheCurrentMonthOtherwiseTheLastDay() {
        assertEquals(7, defaultReportDay(october, date("2026-10-07")))
        assertEquals(31, defaultReportDay(october, date("2026-11-02")))
        assertEquals(31, defaultReportDay(october, date("2026-09-30")))
    }

    @Test
    fun pickedDatesOutsideTheMonthAreRejected() {
        assertEquals(14, reportDayOf(october, date("2026-10-14")))
        assertNull(reportDayOf(october, date("2026-11-14")))
        assertNull(reportDayOf(october, date("2025-10-14")))
    }

    @Test
    fun steppingStopsAtTheMonthEdges() {
        assertEquals(15, steppedReportDay(october, ReportPeriodType.DAILY, 14, forward = true))
        assertNull(steppedReportDay(october, ReportPeriodType.DAILY, 1, forward = false))
        assertNull(steppedReportDay(october, ReportPeriodType.DAILY, 31, forward = true))
        // The week of the 7th runs Monday 5th to Sunday 11th.
        assertEquals(12, steppedReportDay(october, ReportPeriodType.WEEKLY, 7, forward = true))
        assertEquals(4, steppedReportDay(october, ReportPeriodType.WEEKLY, 7, forward = false))
        // The first week is clipped to the 1st..4th, so there is nothing before it.
        assertNull(steppedReportDay(october, ReportPeriodType.WEEKLY, 2, forward = false))
        assertNull(steppedReportDay(october, ReportPeriodType.MONTHLY, 7, forward = true))
    }

    @Test
    fun ratesAndCounts() {
        assertNull(attendanceRate(0, 0, 0))
        assertEquals("—", formatRate(null))
        assertEquals("75%", formatRate(attendanceRate(3, 1, 0)))
        assertEquals("66.7", formatRatePrecise(attendanceRate(2, 1, 0)))
    }

    @Test
    fun countsLineAbsenteesAndSentPeriod() {
        val rows = listOf(row(enrollment(1, "2026-04-01")), row(enrollment(2, "2026-04-01")))
        val marks = listOf(
            mark(1, 1, 6, Session.FIRST, AttendanceStatus.PRESENT),
            mark(1, 2, 6, Session.FIRST, AttendanceStatus.ABSENT),
            mark(1, 2, 6, Session.SECOND, AttendanceStatus.LEAVE),
        )
        val report = attendanceReport(rows, marks, emptySet(), october, ReportPeriodType.DAILY, 6)
        // Figures are days: each timing counts as 0.5, as in the PWA.
        assertEquals("P 0.5 · A 0 · L 0 · blank 0.5", reportCountsLine(report.students[0]))
        assertEquals("P 0 · A 0.5 · L 0.5", reportCountsLine(report.students[1]))
        assertEquals(listOf(2L), reportAbsentees(report).map { it.row.enrollment.id })
        assertEquals("daily-6", reportSentPeriod(report))
        val weekly = attendanceReport(rows, marks, emptySet(), october, ReportPeriodType.WEEKLY, 7)
        assertEquals("weekly-5", reportSentPeriod(weekly))
    }

    @Test
    fun messagedProgress() {
        assertEquals("No parents to message", messagedProgressLabel(0, 0))
        assertEquals("0 of 1 parent messaged", messagedProgressLabel(0, 1))
        assertEquals("Parent messaged", messagedProgressLabel(1, 1))
        assertEquals("2 of 5 parents messaged", messagedProgressLabel(2, 5))
        assertEquals("All 5 parents messaged", messagedProgressLabel(5, 5))
    }

    @Test
    fun contactLinks() {
        assertEquals("smsto:+923001234567", smsUriString(" +923001234567 "))
        assertEquals("tel:+923001234567", dialUriString("+923001234567"))
        assertEquals("https://wa.me/923001234567?text=Hi%20there%21", whatsappUrl("+923001234567", "Hi there!"))
    }
}

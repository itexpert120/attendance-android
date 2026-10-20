package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.date
import com.itexpert120.attendanceManager.domain.enrollment
import com.itexpert120.attendanceManager.domain.mark
import com.itexpert120.attendanceManager.domain.register
import com.itexpert120.attendanceManager.domain.row
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceLogicTest {
    // October 2026: the 1st is a Thursday, the 5th a Monday.
    private val october = register(1, 2026, 10)

    private fun data(
        rows: List<EnrollmentRow>,
        marks: List<AttendanceMark> = emptyList(),
        broughtForward: Map<Long, Int> = emptyMap(),
    ) = RegisterData(
        register = october,
        classGroup = ClassGroup(id = 1, className = "5", section = "A", createdAt = EPOCH),
        settings = null,
        monthRows = rows,
        attendanceRows = rows,
        marks = marks,
        holidayTitles = emptyMap(),
        feeEntries = emptyList(),
        installmentMeta = emptyMap(),
        remarks = emptyMap(),
        broughtForwardByEnrollment = broughtForward,
    )

    @Test
    fun opensOnTodayInTheCurrentMonthOtherwiseOnDayOne() {
        assertEquals(7, defaultSelectedDay(october, date("2026-10-07")))
        // A past month opens on the 1st, as in the PWA (not on its last day).
        assertEquals(1, defaultSelectedDay(october, date("2026-11-02")))
        assertEquals(1, defaultSelectedDay(october, date("2027-03-31")))
        // A month that has not started yet also opens on the 1st.
        assertEquals(1, defaultSelectedDay(october, date("2026-09-30")))
    }

    @Test
    fun rowSubtitleShowsRollMonthAndTotalInDaysOrTheAdmissionDate() {
        val ayesha = row(enrollment(1, "2026-04-01", rollNumber = "4"))
        val late = row(enrollment(2, "2026-10-20"))
        val marks = listOf(
            mark(1, 1, 5, Session.FIRST, AttendanceStatus.PRESENT),
            mark(1, 1, 6, Session.FIRST, AttendanceStatus.PRESENT),
            mark(1, 1, 7, Session.FIRST, AttendanceStatus.ABSENT),
        )
        val snapshot = data(listOf(ayesha, late), marks, broughtForward = mapOf(1L to 40))
        assertEquals("Roll 4 · Month 2 · Total 42", dayRowSubtitle(snapshot, ayesha, 6))
        assertEquals("Enrolled from October 20, 2026", dayRowSubtitle(snapshot, late, 6))
    }

    @Test
    fun bannerOnlyWhenAMarkableDayHasStudentsButNobodyOnTheRoll() {
        val today = date("2026-10-07")
        val late = row(enrollment(2, "2026-10-20"))
        val onRoll = row(enrollment(1, "2026-04-01"))
        assertTrue(nobodyOnRoll(data(listOf(late)), dayInfo(data(listOf(late)), 6, today)))
        assertFalse(nobodyOnRoll(data(listOf(late, onRoll)), dayInfo(data(listOf(late, onRoll)), 6, today)))
        // No students at all is the empty state, not the banner.
        assertFalse(nobodyOnRoll(data(emptyList()), dayInfo(data(emptyList()), 6, today)))
        // A weekend (the 3rd is a Saturday) cannot be marked, so no banner.
        assertFalse(nobodyOnRoll(data(listOf(late)), dayInfo(data(listOf(late)), 3, today)))
    }

    @Test
    fun countsReadAsWholeDays() {
        assertEquals("0", formatDays(0))
        assertEquals("1", formatDays(1))
        assertEquals("13", formatDays(13))
    }

    @Test
    fun dayCountsAndBlanksCoverOneMarkPerStudent() {
        val a = row(enrollment(1, "2026-04-01"))
        val b = row(enrollment(2, "2026-04-01"))
        val c = row(enrollment(3, "2026-04-01"))
        val snapshot = data(
            listOf(a, b, c),
            listOf(
                mark(1, 1, 6, Session.FIRST, AttendanceStatus.PRESENT),
                mark(1, 2, 6, Session.FIRST, AttendanceStatus.ABSENT),
            ),
        )
        assertEquals(DayCounts(present = 1, absent = 1, leave = 0, unmarked = 1), dayCounts(snapshot, 6))
        assertEquals(listOf(MarkChange(3, null)), blankCells(snapshot, 6))
        assertEquals(3, snapshotMarks(snapshot, 6).size)
    }

    @Test
    fun heatStripHasOneCellPerDay() {
        val a = row(enrollment(1, "2026-04-01"))
        val snapshot = data(
            listOf(a),
            listOf(
                mark(1, 1, 5, Session.FIRST, AttendanceStatus.PRESENT),
                mark(1, 1, 6, Session.FIRST, AttendanceStatus.LEAVE),
            ),
        )
        val cells = heatCells(snapshot, a)
        assertEquals(31, cells.size)
        assertEquals(AttendanceStatus.PRESENT, cells[4].status)
        assertEquals(HeatKind.NO_SCHOOL, cells[2].kind) // Saturday the 3rd
        assertEquals(mapOf(AttendanceStatus.PRESENT to 1, AttendanceStatus.LEAVE to 1), heatTotals(cells))
    }

    @Test
    fun cycleOrderIsNonePresentAbsentLeave() {
        assertEquals(AttendanceStatus.PRESENT, nextAttendanceStatus(null))
        assertEquals(AttendanceStatus.ABSENT, nextAttendanceStatus(AttendanceStatus.PRESENT))
        assertEquals(AttendanceStatus.LEAVE, nextAttendanceStatus(AttendanceStatus.ABSENT))
        assertEquals(null, nextAttendanceStatus(AttendanceStatus.LEAVE))
    }
}

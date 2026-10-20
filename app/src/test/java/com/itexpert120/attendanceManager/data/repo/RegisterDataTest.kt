package com.itexpert120.attendanceManager.data.repo

import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.date
import com.itexpert120.attendanceManager.domain.enrollment
import com.itexpert120.attendanceManager.domain.mark
import com.itexpert120.attendanceManager.domain.register
import com.itexpert120.attendanceManager.domain.row
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus.ABSENT
import com.itexpert120.attendanceManager.model.AttendanceStatus.PRESENT
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.InstallmentNumber.FIRST
import com.itexpert120.attendanceManager.model.InstallmentNumber.SECOND
import com.itexpert120.attendanceManager.model.InstallmentNumber.THIRD
import com.itexpert120.attendanceManager.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterDataTest {
    private val august = register(id = 1, year = 2026, month = 8)
    private val visible = row(enrollment(1, "2026-04-01"))
    private val hidden = row(enrollment(2, "2026-04-01", struckOffOn = "2026-08-05"))

    private fun data(
        marks: List<AttendanceMark> = emptyList(),
        holidays: Map<Int, String> = emptyMap(),
        fees: List<FeeEntry> = emptyList(),
        meta: List<InstallmentMeta> = emptyList(),
        broughtForward: Map<Long, Int> = emptyMap(),
    ) = RegisterData(
        register = august,
        classGroup = ClassGroup(id = 1, className = "5", section = "A", createdAt = EPOCH),
        settings = null,
        monthRows = listOf(visible, hidden),
        attendanceRows = listOf(visible),
        marks = marks,
        holidayTitles = holidays,
        feeEntries = fees,
        installmentMeta = meta.associateBy { it.installment },
        remarks = mapOf(1L to "Sick"),
        broughtForwardByEnrollment = broughtForward,
    )

    @Test
    fun `status lookup and presence totals`() {
        val data = data(
            marks = listOf(
                mark(1, 1, 3, Session.FIRST, PRESENT),
                mark(1, 1, 3, Session.SECOND, ABSENT),
                mark(1, 2, 3, Session.FIRST, PRESENT),
            ),
        )

        assertEquals(PRESENT, data.statusOf(1, 3, Session.FIRST))
        assertEquals(ABSENT, data.statusOf(1, 3, Session.SECOND))
        assertNull(data.statusOf(1, 4, Session.FIRST))
        assertEquals(2, data.monthPresentSessions)
        assertEquals(1, data.visiblePresentTotal)
        assertEquals(1, data.presentOn(3, Session.FIRST))
        assertEquals(0, data.presentOn(3, Session.SECOND))
        assertTrue(data.hasMarksOn(3))
        assertFalse(data.hasMarksOn(4))
    }

    @Test
    fun `holidays include weekends and declared days`() {
        val data = data(holidays = mapOf(10 to "Closed"))

        assertTrue(data.isHoliday(8)) // Saturday
        assertTrue(data.isHoliday(10))
        assertFalse(data.isHoliday(11))
        assertEquals("Closed", data.holidayTitle(10))
        assertNull(data.holidayTitle(8))
        // 21 weekdays minus the declared Monday, two timings each.
        assertEquals(40, data.workingTimings)
    }

    @Test
    fun `average attendance is present sessions per working timing`() {
        val data = data(marks = (1..4).map { mark(1, 1, it + 2, Session.FIRST, PRESENT) })

        assertEquals(4.0 / 42, data.averageAttendance, 1e-9)
    }

    @Test
    fun `brought forward totals`() {
        val data = data(broughtForward = mapOf(1L to 6, 2L to 4))

        assertEquals(6, data.broughtForwardOf(1))
        assertEquals(0, data.broughtForwardOf(99))
        assertEquals(10, data.broughtForwardTotal)
        assertEquals(6, data.visibleBroughtForwardTotal)
    }

    @Test
    fun `fee totals per student, installment and month`() {
        val data = data(
            fees = listOf(
                FeeEntry(1, 1, FIRST, FeeAmounts(ftf = 1000)),
                FeeEntry(1, 1, SECOND, FeeAmounts(ftf = 500, ff = 50)),
                FeeEntry(1, 2, FIRST, FeeAmounts(dcf = 250)),
            ),
            meta = listOf(
                InstallmentMeta(1, FIRST, 3000, "Ali"),
                InstallmentMeta(1, THIRD, 1000, "Sara"),
            ),
        )

        assertEquals(FeeAmounts(ftf = 1500, ff = 50), data.feesOf(1))
        assertEquals(FeeAmounts.EMPTY, data.feesOf(3))
        assertEquals(
            mapOf(FIRST to FeeAmounts(ftf = 1000), SECOND to FeeAmounts(ftf = 500, ff = 50), THIRD to FeeAmounts.EMPTY),
            data.feeEntriesOf(1),
        )
        assertEquals(FeeAmounts(ftf = 1000, dcf = 250), data.installmentTotals.getValue(FIRST))
        assertEquals(mapOf(FIRST to 2, SECOND to 1, THIRD to 0), data.installmentStudentCounts)
        assertEquals(3, data.totalInstallmentStudents)
        assertEquals(4000L, data.totalInstallmentRate)
        assertEquals(1800L, data.feeMonthTotals.total)
        assertEquals(1800L, data.installmentGrandTotals.total)
        assertEquals("Sick", data.remarkOf(1))
        assertEquals("", data.remarkOf(2))
    }

    @Test
    fun `edit window and movement delegate to the domain`() {
        val data = data()

        assertTrue(data.isDayEditable(11, date("2026-08-12")))
        assertFalse(data.isDayEditable(4, date("2026-08-12")))
        assertEquals(2, data.movement.beginning)
        assertEquals(1, data.movement.end)
        assertEquals(1, data.movement.struckOff)
    }
}

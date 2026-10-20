package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.data.repo.InstallmentFees
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.FeeField
import com.itexpert120.attendanceManager.domain.enrollment
import com.itexpert120.attendanceManager.domain.mark
import com.itexpert120.attendanceManager.domain.register
import com.itexpert120.attendanceManager.domain.row
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
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

class FeesLogicTest {
    private val august = register(id = 1, year = 2026, month = 8)
    private val ayesha = row(enrollment(1, "2026-04-01"), "Ayesha Khan")
    private val bilal = row(enrollment(2, "2026-04-01", struckOffOn = "2026-08-05", rollNumber = "7"), "Bilal Ahmed")
    private val carol = row(enrollment(3, "2026-04-01"), "Carol")

    private fun data(
        fees: List<FeeEntry> = emptyList(),
        meta: List<InstallmentMeta> = emptyList(),
        marks: List<AttendanceMark> = emptyList(),
        broughtForward: Map<Long, Int> = emptyMap(),
    ) = RegisterData(
        register = august,
        classGroup = ClassGroup(id = 1, className = "5", section = "A", createdAt = EPOCH),
        settings = null,
        monthRows = listOf(ayesha, bilal, carol),
        attendanceRows = listOf(ayesha, carol),
        marks = marks,
        holidayTitles = emptyMap(),
        feeEntries = fees,
        installmentMeta = meta.associateBy { it.installment },
        remarks = mapOf(1L to "Paid in cash"),
        broughtForwardByEnrollment = broughtForward,
    )

    @Test
    fun `money formats with the currency label and tolerates a blank one`() {
        assertEquals("Rs. 12,345.5", feeMoney(1_234_550, "Rs."))
        assertEquals("Rs. 0", feeMoney(0, null))
        assertEquals("1,000", feeMoney(100_000, ""))
        assertEquals("PKR 5", feeMoney(500, " PKR "))
    }

    @Test
    fun `status is none without money, paid without a rate and compares F T F to the rate`() {
        assertEquals(FeeStatus.NONE, installmentStatus(FeeAmounts.EMPTY, 50_000))
        assertEquals(FeeStatus.PAID, installmentStatus(FeeAmounts(ff = 100), 0))
        assertEquals(FeeStatus.PAID, installmentStatus(FeeAmounts(ftf = 50_000), 50_000))
        assertEquals(FeeStatus.PAID, installmentStatus(FeeAmounts(ftf = 60_000), 50_000))
        assertEquals(FeeStatus.PARTIAL, installmentStatus(FeeAmounts(ftf = 49_999), 50_000))
        assertEquals(FeeStatus.PARTIAL, installmentStatus(FeeAmounts(arrears = 90_000), 50_000))
    }

    @Test
    fun `overall status is partial when any entered installment is partial`() {
        assertEquals(FeeStatus.NONE, overallStatus(listOf(FeeStatus.NONE, FeeStatus.NONE)))
        assertEquals(FeeStatus.PAID, overallStatus(listOf(FeeStatus.PAID, FeeStatus.NONE, FeeStatus.NONE)))
        assertEquals(FeeStatus.PARTIAL, overallStatus(listOf(FeeStatus.PAID, FeeStatus.PARTIAL, FeeStatus.NONE)))
    }

    @Test
    fun `cards cover every student on the roll including struck off ones`() {
        val data = data(
            fees = listOf(
                FeeEntry(1, 1, FIRST, FeeAmounts(ftf = 50_000)),
                FeeEntry(1, 1, SECOND, FeeAmounts(ftf = 20_000)),
                FeeEntry(1, 2, FIRST, FeeAmounts(ftf = 50_000, ff = 100)),
            ),
            meta = listOf(InstallmentMeta(1, FIRST, 50_000, "Tariq"), InstallmentMeta(1, SECOND, 50_000, "")),
        )
        val cards = feeCards(data)

        assertEquals(listOf(1L, 2L, 3L), cards.map { it.enrollmentId })
        assertEquals(70_000L, cards[0].total)
        assertEquals(FeeStatus.PARTIAL, cards[0].overall)
        assertEquals(mapOf(FIRST to FeeStatus.PAID, SECOND to FeeStatus.PARTIAL, THIRD to FeeStatus.NONE), cards[0].statuses)
        assertEquals("Paid in cash", cards[0].remark)
        assertEquals(FeeStatus.PAID, cards[1].overall)
        assertEquals(FeeStatus.NONE, cards[2].overall)
        assertEquals("", cards[2].remark)
    }

    @Test
    fun `filter matches name, roll and admission number and the status chip`() {
        val data = data(fees = listOf(FeeEntry(1, 1, FIRST, FeeAmounts(ftf = 100))))
        val cards = feeCards(data)

        assertEquals(listOf(1L), cards.filtered("  AYESHA ", FeeFilter.ALL).map { it.enrollmentId })
        assertEquals(listOf(2L), cards.filtered("7", FeeFilter.ALL).map { it.enrollmentId })
        assertEquals(listOf(3L), cards.filtered("a3", FeeFilter.ALL).map { it.enrollmentId })
        assertEquals(listOf(1L), cards.filtered("", FeeFilter.PAID).map { it.enrollmentId })
        assertEquals(listOf(2L, 3L), cards.filtered("", FeeFilter.NONE).map { it.enrollmentId })
        assertTrue(cards.filtered("zzz", FeeFilter.ALL).isEmpty())
    }

    @Test
    fun `amount input keeps digits and two decimals only`() {
        assertEquals("12", filterAmountInput("1", "12"))
        assertEquals("12.5", filterAmountInput("12.", "12.5"))
        assertEquals("12.50", filterAmountInput("12.5", "12.50"))
        assertEquals("12.50", filterAmountInput("12.50", "12.505"))
        assertEquals("12", filterAmountInput("12", "12a"))
        assertEquals("12", filterAmountInput("12", "-12"))
        assertEquals("1.5", filterAmountInput("1", "1,5"))
        assertEquals("", filterAmountInput("1", ""))
        assertEquals("123456789", filterAmountInput("123456789", "1234567890"))
    }

    @Test
    fun `minor units become plain editable text`() {
        assertEquals("", minorToInput(0))
        assertEquals("5", minorToInput(500))
        assertEquals("12345.5", minorToInput(1_234_550))
        assertEquals("0.05", minorToInput(5))
        assertEquals("1000", minorToInput(100_000))
    }

    @Test
    fun `form loads from the snapshot, totals live and saves all three installments`() {
        val data = data(
            fees = listOf(
                FeeEntry(1, 1, FIRST, FeeAmounts(ftf = 150_050, ff = 500)),
                FeeEntry(1, 1, THIRD, FeeAmounts(dcf = 250)),
            ),
        )
        val form = FeeForm.of(data, 1)

        assertEquals("1500.5", form.valueOf(FIRST, FeeField.FTF))
        assertEquals("", form.valueOf(SECOND, FeeField.FTF))
        assertEquals("Paid in cash", form.remark)
        assertEquals(150_800L, form.grandTotal)

        val edited = form.withValue(SECOND, FeeField.ARREARS, "20.5").withValue(SECOND, FeeField.SLC, "abc")
        assertEquals(2_050L, edited.totalOf(SECOND))
        assertEquals("", edited.valueOf(SECOND, FeeField.SLC))

        val saved = edited.toInstallmentFees()
        assertEquals(listOf(FIRST, SECOND, THIRD), saved.map(InstallmentFees::installment))
        assertEquals(FeeAmounts(arrears = 2_050), saved[1].amounts)
        assertEquals(FeeAmounts(dcf = 250), saved[2].amounts)
    }

    @Test
    fun `dirty tracking compares parsed values and the trimmed remark`() {
        val initial = FeeForm.of(data(fees = listOf(FeeEntry(1, 1, FIRST, FeeAmounts(ftf = 10_000)))), 1)

        assertTrue(initial.sameAs(initial))
        assertTrue(initial.sameAs(initial.withValue(SECOND, FeeField.FF, "0")))
        assertTrue(initial.sameAs(initial.withValue(FIRST, FeeField.FTF, "100.00")))
        assertTrue(initial.sameAs(initial.withRemark("Paid in cash  ")))
        assertFalse(initial.sameAs(initial.withValue(FIRST, FeeField.FTF, "101")))
        assertFalse(initial.sameAs(initial.withRemark("Different")))
    }

    @Test
    fun `remark is capped`() {
        val form = FeeForm.of(emptyMap(), "").withRemark("x".repeat(FEE_MAX_REMARK_LENGTH + 50))
        assertEquals(FEE_MAX_REMARK_LENGTH, form.remark.length)
    }

    @Test
    fun `form survives the saveable round trip and rejects a malformed one`() {
        val form = FeeForm.of(data(fees = listOf(FeeEntry(1, 1, SECOND, FeeAmounts(slc = 1_250)))), 1)
            .withValue(FIRST, FeeField.FTF, "9.5")
        assertEquals(form, FeeForm.fromSaveable(form.toSaveable()))
        assertNull(FeeForm.fromSaveable(listOf("a", "b")))
    }

    @Test
    fun `installment form parses the rate and compares trimmed receivers`() {
        val initial = InstallmentMetaForm.of(InstallmentMeta(1, FIRST, 150_000, "Tariq"))
        assertEquals("1500", initial.rate)
        assertEquals("Tariq", initial.receiver)
        assertTrue(initial.sameAs(initial.withReceiver("Tariq ").withRate("1500.00")))
        assertFalse(initial.sameAs(initial.withRate("1500.5")))
        assertEquals(150_050L, initial.withRate("1500.5").rateMinor)
        assertEquals(InstallmentMetaForm("", ""), InstallmentMetaForm.of(null))
        assertEquals("1500", initial.withRate("15x").rate)
    }

    @Test
    fun `labels`() {
        assertEquals("1st installment", FIRST.longLabel)
        assertEquals("2nd", SECOND.shortLabel)
        assertEquals("3rd", THIRD.shortLabel)
        assertEquals("1 student", feeStudentCountLabel(1))
        assertEquals("0 students", feeStudentCountLabel(0))
    }

    @Test
    fun `summary figures use days, one mark per working day and brought forward`() {
        val marks = listOf(
            mark(1, 1, 3, Session.FIRST, AttendanceStatus.PRESENT),
            mark(1, 1, 4, Session.FIRST, AttendanceStatus.PRESENT),
            mark(1, 2, 3, Session.FIRST, AttendanceStatus.PRESENT), // struck-off student still counts
            mark(1, 3, 3, Session.FIRST, AttendanceStatus.ABSENT),
        )
        val data = data(marks = marks, broughtForward = mapOf(1L to 3, 3L to 2))
        val figures = summaryFigures(data)

        assertEquals("3", figures.monthAttendanceDays)
        assertEquals("5", figures.broughtForwardDays)
        assertEquals("8", figures.academicYearDays)
        assertEquals(data.workingDays.toString(), figures.workingDays)
        assertEquals(summaryAverageLabel(3.0 / data.workingDays), figures.averageAttendance)
        assertEquals(3, figures.beginning)
        assertEquals(2, figures.end)
        assertEquals(1, figures.struckOff)
        assertEquals(0, figures.admitted)
    }

    @Test
    fun `day and average labels`() {
        assertEquals("0", summaryDaysLabel(0))
        assertEquals("1", summaryDaysLabel(1))
        assertEquals("25", summaryDaysLabel(25))
        assertEquals("0.00", summaryAverageLabel(0.0))
        assertEquals("1.67", summaryAverageLabel(5.0 / 3))
    }

    @Test
    fun `card categories show only non-zero amounts compactly`() {
        assertEquals(null, feeCategoriesLine(FeeAmounts.EMPTY))
        assertEquals("F.T.F 1,500 · F.F 50", feeCategoriesLine(FeeAmounts(ftf = 150_000, ff = 5_000)))
        assertEquals("Arrears 12.5 · D.C.F 1", feeCategoriesLine(FeeAmounts(arrears = 1_250, dcf = 100)))
    }
}

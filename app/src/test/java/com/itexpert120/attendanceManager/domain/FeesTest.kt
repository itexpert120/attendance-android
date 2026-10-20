package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.InstallmentNumber.FIRST
import com.itexpert120.attendanceManager.model.InstallmentNumber.SECOND
import org.junit.Assert.assertEquals
import org.junit.Test

class FeesTest {
    private fun entry(registerId: Long, enrollmentId: Long, installment: InstallmentNumber, amounts: FeeAmounts) =
        FeeEntry(registerId, enrollmentId, installment, amounts)

    @Test
    fun `adds category totals and calculates the grand total in minor units`() {
        val total = addFees(
            listOf(
                FeeAmounts(ftf = 10000, ff = 500, slc = 1000),
                FeeAmounts(ftf = 5000, ff = 500, arrears = 2500, lateCertificate = 750, dcf = 250),
            ),
        )
        assertEquals(
            FeeAmounts(ftf = 15000, ff = 1000, arrears = 2500, lateCertificate = 750, slc = 1000, dcf = 250),
            total,
        )
        assertEquals(20500, total.total)
    }

    @Test
    fun `counts installment students from fee columns without including record metadata`() {
        val entries = listOf(
            entry(1, 1, FIRST, FeeAmounts(ftf = 10000)),
            entry(1, 2, FIRST, FeeAmounts.EMPTY),
        )
        assertEquals(10000, entries[0].amounts.total)
        assertEquals(1, installmentStudentCount(entries, registerId = 1, installment = FIRST))
    }

    @Test
    fun `indexes and aggregates all installments by enrollment`() {
        val entries = listOf(
            entry(1, 1, FIRST, FeeAmounts(ftf = 1000, ff = 100)),
            entry(1, 1, SECOND, FeeAmounts(ftf = 500, arrears = 200)),
            entry(2, 1, FIRST, FeeAmounts(ftf = 9999)),
        )
        assertEquals(
            FeeAmounts(ftf = 1500, ff = 100, arrears = 200),
            feesByEnrollment(entries, registerId = 1).getValue(1),
        )
        assertEquals(FeeAmounts(ftf = 1500, ff = 100, arrears = 200), feesForStudent(entries, 1, 1))
        assertEquals(FeeAmounts(ftf = 500, arrears = 200), feesForInstallment(entries, 1, SECOND))
    }

    @Test
    fun `converts currency input safely and rounds to the nearest minor unit`() {
        assertEquals(1235, displayToMinor("12.345"))
        assertEquals(0, displayToMinor("not a number"))
        assertEquals(0, displayToMinor("-5"))
        assertEquals(0, displayToMinor(""))
        assertEquals(999, displayToMinor("9.99"))
        assertEquals(0, displayToMinor("NaN"))
    }

    @Test
    fun `formats minor units with grouping and no trailing zeros`() {
        assertEquals("0", minorToDisplay(0))
        assertEquals("12", minorToDisplay(1200))
        assertEquals("12.5", minorToDisplay(1250))
        assertEquals("12.05", minorToDisplay(1205))
        assertEquals("1,234.56", minorToDisplay(123456))
        assertEquals("1,000,000", minorToDisplay(100000000))
        assertEquals("-5", minorToDisplay(-500))
    }

    @Test
    fun `reads and writes each fee field`() {
        var amounts = FeeAmounts.EMPTY
        FeeField.entries.forEachIndexed { index, field -> amounts = field.withValue(amounts, index + 1L) }
        assertEquals(FeeAmounts(1, 2, 3, 4, 5, 6), amounts)
        assertEquals(listOf(1L, 2, 3, 4, 5, 6), FeeField.entries.map { it.valueIn(amounts) })
    }
}

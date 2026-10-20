package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.InstallmentNumber
import kotlin.math.floor

/** The fee columns in register order, with the labels the printed register uses. */
enum class FeeField(val label: String, val shortLabel: String) {
    FTF("F.T.F", "F.T.F"),
    FF("Fine Fund", "F.F"),
    ARREARS("Arrears", "Arrears"),
    LATE_CERTIFICATE("Late Certificate Fee", "Late Cert."),
    SLC("S.L.C", "S.L.C"),
    DCF("D.C.F", "D.C.F");

    fun valueIn(amounts: FeeAmounts): Long = when (this) {
        FTF -> amounts.ftf
        FF -> amounts.ff
        ARREARS -> amounts.arrears
        LATE_CERTIFICATE -> amounts.lateCertificate
        SLC -> amounts.slc
        DCF -> amounts.dcf
    }

    fun withValue(amounts: FeeAmounts, value: Long): FeeAmounts = when (this) {
        FTF -> amounts.copy(ftf = value)
        FF -> amounts.copy(ff = value)
        ARREARS -> amounts.copy(arrears = value)
        LATE_CERTIFICATE -> amounts.copy(lateCertificate = value)
        SLC -> amounts.copy(slc = value)
        DCF -> amounts.copy(dcf = value)
    }
}

fun addFees(amounts: Iterable<FeeAmounts>): FeeAmounts = amounts.fold(FeeAmounts.EMPTY) { sum, next -> sum + next }

/** Everything one student owes or paid across all installments of a register. */
fun feesForStudent(entries: List<FeeEntry>, registerId: Long, enrollmentId: Long): FeeAmounts =
    addFees(entries.filter { it.registerId == registerId && it.enrollmentId == enrollmentId }.map { it.amounts })

fun feesByEnrollment(entries: List<FeeEntry>, registerId: Long): Map<Long, FeeAmounts> =
    entries
        .filter { it.registerId == registerId }
        .groupBy({ it.enrollmentId }, { it.amounts })
        .mapValues { (_, amounts) -> addFees(amounts) }

fun feesForInstallment(entries: List<FeeEntry>, registerId: Long, installment: InstallmentNumber): FeeAmounts =
    addFees(entries.filter { it.registerId == registerId && it.installment == installment }.map { it.amounts })

/** Students with a non-zero entry in the installment. */
fun installmentStudentCount(entries: List<FeeEntry>, registerId: Long, installment: InstallmentNumber): Int =
    entries
        .filter { it.registerId == registerId && it.installment == installment && it.amounts.total > 0 }
        .mapTo(HashSet()) { it.enrollmentId }
        .size

/** Minor units as a grouped decimal with no trailing zeros: 1234550 becomes "12,345.5". */
fun minorToDisplay(value: Long): String {
    val sign = if (value < 0) "-" else ""
    val absolute = if (value < 0) -value else value
    val whole = (absolute / 100).toString().reversed().chunked(3).joinToString(",").reversed()
    val fraction = (absolute % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}

/**
 * Parses what the user typed into minor units, rounding half up. Anything that isn't a non-negative
 * number counts as zero, so a half-typed field never blocks saving.
 */
fun displayToMinor(input: String): Long {
    val value = input.trim().toDoubleOrNull() ?: return 0
    if (!value.isFinite() || value < 0) return 0
    return floor(value * 100 + 0.5).toLong()
}

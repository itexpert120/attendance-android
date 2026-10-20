package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.data.repo.InstallmentFees
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.FeeField
import com.itexpert120.attendanceManager.domain.displayToMinor
import com.itexpert120.attendanceManager.domain.minorToDisplay
import com.itexpert120.attendanceManager.domain.sessionsToDays
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.InstallmentNumber
import java.util.Locale

/*
 * Plain, JVM-testable logic shared by the Fees and Summary sections: money formatting, the paid / partial /
 * none classification, the fee form's parsing and dirty tracking, and the student list filter.
 */

const val FEES_DEFAULT_CURRENCY = "Rs."
const val FEE_MAX_REMARK_LENGTH = 300
const val FEE_MAX_RECEIVER_LENGTH = 60

/** "Rs. 12,345.5"; the label is optional so a school can use bare numbers. */
fun feeMoney(minor: Long, currencyLabel: String?): String {
    val label = currencyLabel ?: FEES_DEFAULT_CURRENCY
    return if (label.isBlank()) minorToDisplay(minor) else "${label.trim()} ${minorToDisplay(minor)}"
}

fun RegisterData.feeCurrency(): String = settings?.currencyLabel ?: FEES_DEFAULT_CURRENCY

/** "1st", "2nd", "3rd". */
val InstallmentNumber.shortLabel: String
    get() = when (this) {
        InstallmentNumber.FIRST -> "1st"
        InstallmentNumber.SECOND -> "2nd"
        InstallmentNumber.THIRD -> "3rd"
    }

val InstallmentNumber.longLabel: String get() = "$shortLabel installment"

/** "student" or "students" with the count in front: "1 student", "12 students". */
fun feeStudentCountLabel(count: Int): String = if (count == 1) "1 student" else "$count students"

/** Attendance is stored in sessions (half days); the register shows days, with ".5" for an odd session. */
fun summaryDaysLabel(sessions: Int): String {
    val days = sessionsToDays(sessions)
    return if (sessions % 2 == 0) days.toLong().toString() else String.format(Locale.US, "%.1f", days)
}

/** The PWA's `average.toFixed(2)`. */
fun summaryAverageLabel(average: Double): String = String.format(Locale.US, "%.2f", average)

// region Paid / partial / none

enum class FeeStatus { NONE, PARTIAL, PAID }

/**
 * Classifies one installment of one student. The web app only stores the installment [rate] (the amount
 * the register expects per student, compared with the F.T.F column) and the amounts, so:
 *  - nothing entered: [FeeStatus.NONE];
 *  - something entered and F.T.F reaches the rate (or no rate is set, so there is nothing to fall short of):
 *    [FeeStatus.PAID];
 *  - something entered but F.T.F is below the rate: [FeeStatus.PARTIAL].
 */
fun installmentStatus(amounts: FeeAmounts, rate: Long): FeeStatus = when {
    amounts.total <= 0 -> FeeStatus.NONE
    rate <= 0 -> FeeStatus.PAID
    amounts.ftf >= rate -> FeeStatus.PAID
    else -> FeeStatus.PARTIAL
}

/** The student's overall chip: partial if any installment is partial, paid if all entered ones are paid. */
fun overallStatus(statuses: Collection<FeeStatus>): FeeStatus = when {
    statuses.all { it == FeeStatus.NONE } -> FeeStatus.NONE
    statuses.any { it == FeeStatus.PARTIAL } -> FeeStatus.PARTIAL
    else -> FeeStatus.PAID
}

/** Everything one fee card needs, computed once per snapshot. */
data class FeeCard(
    val row: EnrollmentRow,
    val total: Long,
    val statuses: Map<InstallmentNumber, FeeStatus>,
    val amounts: Map<InstallmentNumber, FeeAmounts>,
    val overall: FeeStatus,
    val remark: String,
    /** The student's six category amounts summed over all installments (the PWA card's grid). */
    val categories: FeeAmounts = FeeAmounts.EMPTY,
) {
    val enrollmentId: Long get() = row.enrollment.id
}

fun feeCards(data: RegisterData): List<FeeCard> = data.monthRows.map { row ->
    val id = row.enrollment.id
    val amounts = data.feeEntriesOf(id)
    val statuses = amounts.mapValues { (installment, value) ->
        installmentStatus(value, data.installmentMeta[installment]?.rate ?: 0)
    }
    val fees = data.feesOf(id)
    FeeCard(row, fees.total, statuses, amounts, overallStatus(statuses.values), data.remarkOf(id), fees)
}

/**
 * The non-zero fee categories as one compact line, "F.T.F 1,500 · F.F 50", or null when nothing is entered.
 * The phone counterpart of the PWA card's six-cell grid, without the zero cells.
 */
fun feeCategoriesLine(amounts: FeeAmounts): String? =
    FeeField.entries
        .filter { it.valueIn(amounts) != 0L }
        .joinToString(" · ") { "${it.shortLabel} ${minorToDisplay(it.valueIn(amounts))}" }
        .ifEmpty { null }

// endregion

// region List filter

enum class FeeFilter(val label: String) {
    ALL("All"),
    PAID("Paid"),
    PARTIAL("Partial"),
    NONE("No fees"),
}

fun FeeFilter.accepts(status: FeeStatus): Boolean = when (this) {
    FeeFilter.ALL -> true
    FeeFilter.PAID -> status == FeeStatus.PAID
    FeeFilter.PARTIAL -> status == FeeStatus.PARTIAL
    FeeFilter.NONE -> status == FeeStatus.NONE
}

/** Matches name, roll number or admission number, ignoring case and surrounding spaces. */
fun List<FeeCard>.filtered(query: String, filter: FeeFilter): List<FeeCard> {
    val needle = query.trim()
    return filter { card ->
        filter.accepts(card.overall) && (
            needle.isEmpty() ||
                card.row.student.name.contains(needle, ignoreCase = true) ||
                card.row.enrollment.rollNumber.contains(needle, ignoreCase = true) ||
                card.row.student.admissionNumber.contains(needle, ignoreCase = true)
            )
    }
}

// endregion

// region Amount input

private val AmountPattern = Regex("^\\d{0,9}(\\.\\d{0,2})?$")

/**
 * Keeps a numeric field well-formed while typing: digits with at most two decimals. A comma (decimal keypads
 * of some locales) counts as the decimal point. Anything else is rejected and [old] is kept.
 */
fun filterAmountInput(old: String, new: String): String {
    val candidate = new.replace(',', '.')
    return if (AmountPattern.matches(candidate)) candidate else old
}

/** Minor units as editable text without grouping separators, empty for zero: 1234550 becomes "12345.5". */
fun minorToInput(value: Long): String {
    if (value <= 0) return ""
    val whole = (value / 100).toString()
    val fraction = (value % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole else "$whole.$fraction"
}

// endregion

// region Student fee form

/** The student fee sheet's editable state: one text per (installment, fee category) plus the remark. */
data class FeeForm(
    val values: Map<InstallmentNumber, Map<FeeField, String>>,
    val remark: String,
) {
    fun valueOf(installment: InstallmentNumber, field: FeeField): String = values[installment]?.get(field).orEmpty()

    /** Applies typed [text] through [filterAmountInput]. */
    fun withValue(installment: InstallmentNumber, field: FeeField, text: String): FeeForm {
        val cleaned = filterAmountInput(valueOf(installment, field), text)
        val row = values[installment].orEmpty() + (field to cleaned)
        return copy(values = values + (installment to row))
    }

    fun withRemark(text: String): FeeForm = copy(remark = text.take(FEE_MAX_REMARK_LENGTH))

    fun amountsOf(installment: InstallmentNumber): FeeAmounts =
        FeeField.entries.fold(FeeAmounts.EMPTY) { acc, field -> field.withValue(acc, displayToMinor(valueOf(installment, field))) }

    fun totalOf(installment: InstallmentNumber): Long = amountsOf(installment).total

    val grandTotal: Long get() = InstallmentNumber.entries.sumOf { totalOf(it) }

    fun toInstallmentFees(): List<InstallmentFees> =
        InstallmentNumber.entries.map { InstallmentFees(it, amountsOf(it)) }

    /** Equal once parsed, so "" and "0" or a trailing space in the remark don't count as edits. */
    fun sameAs(other: FeeForm): Boolean =
        InstallmentNumber.entries.all { amountsOf(it) == other.amountsOf(it) } && remark.trim() == other.remark.trim()

    /** Flat form for `rememberSaveable`: 3 x 6 amounts in enum order, then the remark. */
    fun toSaveable(): List<String> =
        InstallmentNumber.entries.flatMap { installment -> FeeField.entries.map { valueOf(installment, it) } } + remark

    companion object {
        private val SaveableSize = InstallmentNumber.entries.size * FeeField.entries.size + 1

        fun of(entries: Map<InstallmentNumber, FeeAmounts>, remark: String): FeeForm = FeeForm(
            values = InstallmentNumber.entries.associateWith { installment ->
                val amounts = entries[installment] ?: FeeAmounts.EMPTY
                FeeField.entries.associateWith { minorToInput(it.valueIn(amounts)) }
            },
            remark = remark,
        )

        fun fromSaveable(saved: List<String>): FeeForm? {
            if (saved.size != SaveableSize) return null
            var index = 0
            val values = InstallmentNumber.entries.associateWith {
                FeeField.entries.associateWith { saved[index++] }
            }
            return FeeForm(values, saved[index])
        }

        fun of(data: RegisterData, enrollmentId: Long): FeeForm =
            of(data.feeEntriesOf(enrollmentId), data.remarkOf(enrollmentId))
    }
}

// endregion

// region Installment rate / receiver form

data class InstallmentMetaForm(val rate: String, val receiver: String) {
    fun withRate(text: String): InstallmentMetaForm = copy(rate = filterAmountInput(rate, text))

    fun withReceiver(text: String): InstallmentMetaForm = copy(receiver = text.take(FEE_MAX_RECEIVER_LENGTH))

    val rateMinor: Long get() = displayToMinor(rate)

    fun sameAs(other: InstallmentMetaForm): Boolean =
        rateMinor == other.rateMinor && receiver.trim() == other.receiver.trim()

    companion object {
        fun of(meta: InstallmentMeta?): InstallmentMetaForm =
            InstallmentMetaForm(minorToInput(meta?.rate ?: 0), meta?.receiverName.orEmpty())
    }
}

// endregion

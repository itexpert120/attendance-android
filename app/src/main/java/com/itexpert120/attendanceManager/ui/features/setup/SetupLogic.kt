package com.itexpert120.attendanceManager.ui.features.setup

import com.itexpert120.attendanceManager.data.files.PickedPhoto
import com.itexpert120.attendanceManager.domain.monthName

/** The onboarding pages, in order. [REVIEW] is shown before saving because the app swaps to the tabs the moment settings exist. */
enum class SetupStep {
    WELCOME,
    SCHOOL,
    CLASS,
    PREFERENCES,
    REVIEW;

    /** 1-based position among the pages that ask for something; 0 for the welcome page. */
    val number: Int get() = ordinal

    fun nextStep(): SetupStep = entries[minOf(ordinal + 1, entries.lastIndex)]
    fun previousStep(): SetupStep = entries[maxOf(ordinal - 1, 0)]

    companion object {
        /** Pages that count towards "Step n of N". */
        const val COUNTED = 4
    }
}

const val DEFAULT_ACADEMIC_YEAR_START_MONTH = 4

data class SetupForm(
    val step: SetupStep = SetupStep.WELCOME,
    val schoolName: String = "",
    val logo: PickedPhoto? = null,
    val className: String = "",
    val section: String = "",
    val academicYearStartMonth: Int = DEFAULT_ACADEMIC_YEAR_START_MONTH,
    val currencyLabel: String = "Rs.",
    val classInchargeName: String = "",
    val saving: Boolean = false,
    /** Inline message for the current page: a missing field, or a repository ValidationException. */
    val error: String? = null,
)

/** The message for what is missing on [step], or null when the user may continue. Same wording as the repository's. */
fun stepError(step: SetupStep, form: SetupForm): String? = when (step) {
    SetupStep.SCHOOL -> if (form.schoolName.isBlank()) "School name is required." else null
    SetupStep.CLASS -> if (form.className.isBlank() || form.section.isBlank()) "Class and section are required." else null
    else -> null
}

/** Moves to the next page, or stays put with an inline [SetupForm.error] when this page is incomplete. */
fun SetupForm.advance(): SetupForm {
    val problem = stepError(step, this)
    return if (problem != null) copy(error = problem) else copy(step = step.nextStep(), error = null)
}

fun SetupForm.retreat(): SetupForm = copy(step = step.previousStep(), error = null)

/** Jumps to an earlier page from the review list so a value can be corrected. */
fun SetupForm.jumpTo(target: SetupStep): SetupForm = copy(step = target, error = null)

/** "Class 7 · A", matching the registers list. */
fun SetupForm.classSummary(): String = "Class ${className.trim()} · ${section.trim()}"

fun academicYearSummary(month: Int): String = "Starts in ${monthName(month)}"

/** The label used when the currency field is left empty; mirrors `SettingsRepository.completeSetup`. */
fun SetupForm.effectiveCurrency(): String = currencyLabel.trim().ifEmpty { "Rs." }

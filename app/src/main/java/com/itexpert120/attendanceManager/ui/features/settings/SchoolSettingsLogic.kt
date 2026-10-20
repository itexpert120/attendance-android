package com.itexpert120.attendanceManager.ui.features.settings

import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.domain.AbsenceMessageContext
import com.itexpert120.attendanceManager.domain.AbsenceMessageToken
import com.itexpert120.attendanceManager.domain.DEFAULT_ABSENCE_MESSAGE_TEMPLATE
import com.itexpert120.attendanceManager.domain.formatAbsenceMessage
import com.itexpert120.attendanceManager.domain.resolveAbsenceMessageTemplate
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.SchoolSettings

/** The PWA's textarea limit for the absence message template. */
const val TEMPLATE_MAX_LENGTH = 1000

/** Same wording as `SettingsRepository`, so the client-side check and the repository agree. */
const val SCHOOL_NAME_REQUIRED = "School name is required."

/** The editable text of the School settings form; the logo is tracked separately as a `PhotoChange`. */
data class SchoolSettingsForm(
    val schoolName: String = "",
    val academicYearStartMonth: Int = 4,
    val currencyLabel: String = SettingsRepository.DEFAULT_CURRENCY,
    val classInchargeName: String = "",
    /** Never blank on load: a stored null shows the default message so it can be edited. */
    val template: String = DEFAULT_ABSENCE_MESSAGE_TEMPLATE,
) {
    val schoolNameError: String? get() = if (schoolName.isBlank()) SCHOOL_NAME_REQUIRED else null

    val isDefaultTemplate: Boolean get() = resolveAbsenceMessageTemplate(template) == DEFAULT_ABSENCE_MESSAGE_TEMPLATE
}

fun SchoolSettings.toForm(): SchoolSettingsForm = SchoolSettingsForm(
    schoolName = schoolName,
    academicYearStartMonth = academicYearStartMonth,
    currencyLabel = currencyLabel,
    classInchargeName = classInchargeName,
    template = resolveAbsenceMessageTemplate(absenceMessageTemplate),
)

/**
 * What to store: null (meaning "use the default") for a blank template or one equal to the default, so the
 * school keeps following the default wording until it really customises it.
 */
fun templateForSave(template: String): String? =
    template.trim().takeUnless { it.isEmpty() || it == DEFAULT_ABSENCE_MESSAGE_TEMPLATE }

/** The text after inserting a placeholder, and where the cursor goes (just after it). */
data class TokenInsertion(val text: String, val cursor: Int)

/**
 * Replaces the selection [selectionStart]..[selectionEnd] (either order, clamped to the text) with `{token}`.
 * A space is added on a side where the placeholder would otherwise run into a letter or digit, so tapping
 * "Student" after "Dear" gives "Dear {student}". Returns null when the result would exceed [maxLength].
 */
fun insertToken(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    token: AbsenceMessageToken,
    maxLength: Int = TEMPLATE_MAX_LENGTH,
): TokenInsertion? {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val before = text.substring(0, start)
    val after = text.substring(end)
    val leading = if (before.lastOrNull()?.isLetterOrDigit() == true) " " else ""
    val trailing = if (after.firstOrNull()?.isLetterOrDigit() == true) " " else ""
    val inserted = "$leading{${token.token}}$trailing"
    val result = before + inserted + after
    if (result.length > maxLength) return null
    return TokenInsertion(result, before.length + inserted.length)
}

/** The chip label for a placeholder. */
val AbsenceMessageToken.chipLabel: String
    get() = when (this) {
        AbsenceMessageToken.STUDENT -> "Student"
        AbsenceMessageToken.DATE -> "Date"
        AbsenceMessageToken.SESSION -> "Timing"
        AbsenceMessageToken.SCHOOL -> "School"
        AbsenceMessageToken.CLASS -> "Class"
        AbsenceMessageToken.ROLL -> "Roll no."
        AbsenceMessageToken.INCHARGE -> "Incharge"
    }

/** The live preview: the form's template filled with a sample student, using the form's own school details. */
fun previewMessage(form: SchoolSettingsForm, dateText: String): String {
    val incharge = form.classInchargeName.trim()
    return formatAbsenceMessage(
        form.template,
        AbsenceMessageContext(
            student = "Ayaan Khan",
            date = dateText,
            session = "the first timing",
            school = form.schoolName.trim().ifEmpty { "Your School" },
            className = "Class 7, Section A",
            roll = "12",
            incharge = if (incharge.isNotEmpty()) "$incharge, the class incharge" else "the class incharge",
        ),
    )
}

/** "Class 7 · A", matching the other tabs. */
internal fun classTitle(group: ClassGroup): String = "Class ${group.className} · ${group.section}"

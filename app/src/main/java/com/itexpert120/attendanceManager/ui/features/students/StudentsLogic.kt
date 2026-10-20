package com.itexpert120.attendanceManager.ui.features.students

import com.itexpert120.attendanceManager.data.repo.PhotoChange
import com.itexpert120.attendanceManager.data.repo.StudentInput
import com.itexpert120.attendanceManager.domain.isActiveOn
import com.itexpert120.attendanceManager.domain.shortMonthName
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/*
 * Plain, platform-free logic of the Students screen so it can be unit-tested on the JVM: which class is
 * selected, search matching, the on-roll / struck-off split, initials, roll-number suggestions and the
 * form's validation (mirroring the repository's messages so errors land under the right field).
 */

/** "Class 7 · A", the way the web app labelled a class. */
fun classLabel(group: ClassGroup): String = "Class ${group.className} · ${group.section}"

/** The compact label used on a filter chip: "7 · A". */
fun classChipLabel(group: ClassGroup): String = "${group.className} · ${group.section}"

/**
 * The class the screen shows: [selected] while it still exists, otherwise the first class, or null when
 * there are no classes at all (so a deleted or never-selected class never leaves the screen blank).
 */
fun effectiveClassId(classes: List<ClassGroup>, selected: Long?): Long? =
    classes.firstOrNull { it.id == selected }?.id ?: classes.firstOrNull()?.id

/** Whether the row matches a search over the student's name, admission number or roll number. */
fun EnrollmentRow.matchesQuery(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return student.name.lowercase().contains(needle) ||
        student.admissionNumber.lowercase().contains(needle) ||
        enrollment.rollNumber.lowercase().contains(needle)
}

/** A class's enrollments after searching and filtering, with the totals the header shows. */
data class StudentListing(
    /** Matching enrollments on the roll, in roll order. */
    val onRoll: List<EnrollmentRow>,
    /** Matching struck-off enrollments; empty while they are hidden. */
    val struckOff: List<EnrollmentRow>,
    /** Everyone on the roll in the class, ignoring the search. */
    val totalOnRoll: Int,
    /** Everyone struck off in the class, ignoring the search and the filter. */
    val totalStruckOff: Int,
) {
    val isEmpty: Boolean get() = onRoll.isEmpty() && struckOff.isEmpty()

    companion object {
        val EMPTY = StudentListing(emptyList(), emptyList(), 0, 0)
    }
}

/** [rows] must already be in roll order; the relative order is kept. */
fun buildListing(rows: List<EnrollmentRow>, query: String, showStruckOff: Boolean): StudentListing {
    val matching = rows.filter { it.matchesQuery(query) }
    return StudentListing(
        onRoll = matching.filter { it.enrollment.struckOffOn == null },
        struckOff = if (showStruckOff) matching.filter { it.enrollment.struckOffOn != null } else emptyList(),
        totalOnRoll = rows.count { it.enrollment.struckOffOn == null },
        totalStruckOff = rows.count { it.enrollment.struckOffOn != null },
    )
}

/**
 * Up to two initials for an avatar. A parentage marker such as "S/O" or "D/O" (the web app asked for
 * "Name with parentage") is not a name, so "Ayaan S/O Awaan" gives "A" and "Sara Khan" gives "SK".
 */
fun studentInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val first = words.first().first().uppercaseChar().toString()
    val second = words.getOrNull(1)?.takeIf { '/' !in it && it.first().isLetter() }?.first()?.uppercaseChar()
    return if (second != null) first + second else first
}

/** "Aug 3, 2026" */
fun shortDateLabel(date: LocalDate): String = "${shortMonthName(date.month.number)} ${date.day}, ${date.year}"

/** "Struck off Aug 3, 2026" */
fun struckOffBadgeText(date: LocalDate): String = "Struck off ${shortDateLabel(date)}"

/** The roll number after the highest numeric one in [rows], or "" when none of them is numeric. */
fun suggestNextRollNumber(rows: List<EnrollmentRow>): String {
    val highest = rows.mapNotNull { it.enrollment.rollNumber.trim().toLongOrNull() }.maxOrNull() ?: return ""
    return (highest + 1).toString()
}

/** How many students "Duplicate class" would copy: those on the roll on [date] (struck-off dates are inclusive). */
fun countCopiedByDuplicate(rows: List<EnrollmentRow>, date: LocalDate): Int =
    rows.count { it.enrollment.isActiveOn(date) }

/** Students that can still be added to the class: everyone who has no enrollment in it yet. */
fun studentsAvailableToAdd(all: List<Student>, rows: List<EnrollmentRow>, query: String): List<Student> {
    val enrolled = rows.mapTo(HashSet()) { it.student.id }
    val needle = query.trim().lowercase()
    return all.asSequence()
        .filter { it.id !in enrolled }
        .filter { needle.isEmpty() || it.name.lowercase().contains(needle) || it.admissionNumber.lowercase().contains(needle) }
        .sortedBy { it.name.lowercase() }
        .toList()
}

/** The repository input that re-saves [row] unchanged except for its struck-off date (strike off / restore). */
fun studentInputFromRow(row: EnrollmentRow, struckOffOn: LocalDate?): StudentInput = StudentInput(
    classGroupId = row.enrollment.classGroupId,
    admissionNumber = row.student.admissionNumber,
    rollNumber = row.enrollment.rollNumber,
    name = row.student.name,
    phone = row.student.phone,
    admittedOn = row.enrollment.admittedOn,
    studentId = row.student.id,
    enrollmentId = row.enrollment.id,
    dateOfBirth = row.student.dateOfBirth,
    struckOffOn = struckOffOn,
    photo = PhotoChange.Keep,
)

/** The struck-off date to use when striking a student off on [today]: never before the admission date. */
fun strikeOffDate(row: EnrollmentRow, today: LocalDate): LocalDate =
    if (today < row.enrollment.admittedOn) row.enrollment.admittedOn else today

// ---- Contact (parent phone) -------------------------------------------------------------------------

/** Digits only, the form `wa.me` links want ("+92 300 1234567" -> "923001234567"). */
fun phoneDigits(phone: String): String = phone.filter { it.isDigit() }

/** `tel:` URI text for the dialer; only digits and a leading plus are kept. */
fun telUriText(phone: String): String = "tel:" + phone.trim().filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '+') }

/** `smsto:` URI text for the messaging app. */
fun smsUriText(phone: String): String = "smsto:" + phone.trim().filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '+') }

/** `https://wa.me/<digits>` opens a WhatsApp chat (or the web page when WhatsApp isn't installed). */
fun whatsAppUriText(phone: String): String = "https://wa.me/" + phoneDigits(phone)

// ---- Student form validation ------------------------------------------------------------------------

/** Per-field error text of the student form; null means the field is fine. */
data class StudentFormErrors(
    val name: String? = null,
    val admissionNumber: String? = null,
    val rollNumber: String? = null,
    val phone: String? = null,
    val struckOffOn: String? = null,
    /** Anything that does not belong to a single field. */
    val general: String? = null,
) {
    val hasErrors: Boolean
        get() = name != null || admissionNumber != null || rollNumber != null || phone != null || struckOffOn != null || general != null

    companion object {
        val NONE = StudentFormErrors()

        /**
         * Puts a repository `ValidationException` message under the field it is about. The messages are the
         * PWA's: "Admission number must be unique.", "Roll number is already active in this class.",
         * "Enter a valid phone number...", "Struck-off date cannot be before admission date." and the
         * "... is required." trio.
         */
        fun fromRepositoryMessage(message: String): StudentFormErrors {
            val text = message.lowercase()
            return when {
                "phone" in text -> StudentFormErrors(phone = message)
                "admission number" in text -> StudentFormErrors(admissionNumber = message)
                "roll number" in text -> StudentFormErrors(rollNumber = message)
                "struck-off" in text || "struck off" in text -> StudentFormErrors(struckOffOn = message)
                "name" in text -> StudentFormErrors(name = message)
                else -> StudentFormErrors(general = message)
            }
        }
    }
}

/** The checks the form can do before asking the repository (same wording as the repository). */
fun validateStudentForm(
    name: String,
    admissionNumber: String,
    rollNumber: String,
    admittedOn: LocalDate,
    struckOffOn: LocalDate?,
): StudentFormErrors = StudentFormErrors(
    name = if (name.isBlank()) "Student name is required." else null,
    admissionNumber = if (admissionNumber.isBlank()) "Admission number is required." else null,
    rollNumber = if (rollNumber.isBlank()) "Roll number is required." else null,
    struckOffOn = if (struckOffOn != null && struckOffOn < admittedOn) "Struck-off date cannot be before admission date." else null,
)

/** The photo edit made in the form; the picked bytes themselves live in the ViewModel. */
enum class PhotoEdit { KEEP, REMOVE, REPLACE }

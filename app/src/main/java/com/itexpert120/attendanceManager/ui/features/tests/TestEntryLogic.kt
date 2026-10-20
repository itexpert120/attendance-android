package com.itexpert120.attendanceManager.ui.features.tests

import com.itexpert120.attendanceManager.data.repo.TestResultInput
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.domain.marksPercentage
import com.itexpert120.attendanceManager.domain.parseMarks
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry

/*
 * Rapid result entry: how a typed field maps to a Test Result, when a write is needed, and which student the
 * keyboard's Next action jumps to. Blank is Not Entered; zero is a recorded result.
 */

/** What a marks text field means right now. */
sealed interface FieldOutcome {
    /** Blank: the result is Not Entered. */
    data object Clear : FieldOutcome

    data class Save(val hundredths: Int) : FieldOutcome

    data class Invalid(val message: String) : FieldOutcome
}

/** Keeps digits and at most one dot with at most two decimals; a comma typed as the decimal mark becomes a dot. */
fun sanitizeMarksInput(raw: String): String {
    val builder = StringBuilder()
    var seenDot = false
    var decimals = 0
    for (char in raw) {
        when {
            char.isDigit() -> {
                if (seenDot) {
                    if (decimals < 2) {
                        builder.append(char)
                        decimals++
                    }
                } else {
                    builder.append(char)
                }
            }
            (char == '.' || char == ',') && !seenDot -> {
                seenDot = true
                builder.append('.')
            }
        }
    }
    return builder.toString()
}

fun evaluateMarksField(text: String, totalMarks: Int): FieldOutcome {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return FieldOutcome.Clear
    val hundredths = parseMarks(trimmed) ?: return FieldOutcome.Invalid("Enter marks, up to two decimals.")
    if (hundredths > totalMarks) return FieldOutcome.Invalid("Cannot be above ${formatMarks(totalMarks)}.")
    return FieldOutcome.Save(hundredths)
}

/** The text a field shows for a stored result: the marks, or blank for Absent and Not Entered. */
fun fieldTextFor(entry: TestRosterEntry): String =
    if (entry.result == TestResultStatus.MARKS) formatMarks(entry.marks ?: 0) else ""

/**
 * The result to write for [outcome], or null (the same as "nothing to do") when the stored [entry] already
 * says the same thing. A cleared field only writes when the entry holds marks, because an Absent row has a
 * disabled, blank field that must not wipe the absence.
 */
fun writeFor(outcome: FieldOutcome, entry: TestRosterEntry): Write? = when (outcome) {
    FieldOutcome.Clear -> if (entry.result == TestResultStatus.MARKS) Write(null) else null
    is FieldOutcome.Save ->
        if (entry.result == TestResultStatus.MARKS && entry.marks == outcome.hundredths) {
            null
        } else {
            Write(TestResultInput.Marks(outcome.hundredths))
        }
    is FieldOutcome.Invalid -> null
}

/** A repository write; [input] null clears the result. Wrapped so "no write" and "clear" can be told apart. */
data class Write(val input: TestResultInput?)

/** The write an Absent toggle makes: Absent clears an existing absence, anything else becomes Absent. */
fun toggleAbsent(entry: TestRosterEntry): Write =
    if (entry.result == TestResultStatus.ABSENT) Write(null) else Write(TestResultInput.Absent)

/** The index Next moves to from [from]: the following student who is not Absent, or null at the end. */
fun nextEntryIndex(statuses: List<TestResultStatus>, from: Int): Int? =
    (from + 1 until statuses.size).firstOrNull { statuses[it] != TestResultStatus.ABSENT }

/** The first student, from [from] on, whose result is still Not Entered; wraps around once. */
fun nextPendingIndex(statuses: List<TestResultStatus>, from: Int = -1): Int? {
    if (statuses.isEmpty()) return null
    for (step in 1..statuses.size) {
        val index = (from + step).mod(statuses.size)
        if (statuses[index] == TestResultStatus.NOT_ENTERED) return index
    }
    return null
}

/** "80.0%" under a student's name once marks are saved, "Absent", or "Not entered". */
fun entryStatusText(entry: TestRosterEntry, totalMarks: Int): String = when (entry.result) {
    TestResultStatus.MARKS -> formatPercent(marksPercentage(entry.marks ?: 0, totalMarks))
    TestResultStatus.ABSENT -> "Absent"
    TestResultStatus.NOT_ENTERED -> "Not entered"
}

/** The detail line of a compact entry row, as the web app: "80.0% · A-102", "Absent · A-102". */
fun entryRowDetail(entry: TestRosterEntry, totalMarks: Int, admissionNumber: String): String =
    "${entryStatusText(entry, totalMarks)} · $admissionNumber"

/** Entered over total, e.g. "12 / 30", for the progress header. */
fun progressFraction(entered: Int, total: Int): Float = if (total <= 0) 0f else entered.toFloat() / total

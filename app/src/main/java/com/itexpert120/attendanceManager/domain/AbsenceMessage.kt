package com.itexpert120.attendanceManager.domain

const val DEFAULT_ABSENCE_MESSAGE_TEMPLATE =
    "Dear Parent/Guardian, {student} was marked absent for {session} on {date} at {school}. " +
        "Please contact {incharge}, if this is unexpected."

/** The placeholders a custom absence message may use, written as `{name}`. */
enum class AbsenceMessageToken(val token: String) {
    STUDENT("student"),
    DATE("date"),
    SESSION("session"),
    SCHOOL("school"),
    CLASS("class"),
    ROLL("roll"),
    INCHARGE("incharge"),
}

data class AbsenceMessageContext(
    val student: String,
    val date: String,
    val session: String,
    val school: String,
    val className: String,
    val roll: String,
    val incharge: String,
) {
    internal fun valueOf(token: AbsenceMessageToken): String = when (token) {
        AbsenceMessageToken.STUDENT -> student
        AbsenceMessageToken.DATE -> date
        AbsenceMessageToken.SESSION -> session
        AbsenceMessageToken.SCHOOL -> school
        AbsenceMessageToken.CLASS -> className
        AbsenceMessageToken.ROLL -> roll
        AbsenceMessageToken.INCHARGE -> incharge
    }
}

// Both braces are escaped: Android's ICU regex engine rejects a bare `}` that the JVM's accepts.
private val TOKEN_PATTERN = Regex("""\{(${AbsenceMessageToken.entries.joinToString("|") { it.token }})\}""")

/** A blank or missing custom template falls back to the default message. */
fun resolveAbsenceMessageTemplate(template: String?): String =
    template?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_ABSENCE_MESSAGE_TEMPLATE

fun formatAbsenceMessage(template: String?, context: AbsenceMessageContext): String =
    TOKEN_PATTERN.replace(resolveAbsenceMessageTemplate(template)) { match ->
        val token = AbsenceMessageToken.entries.first { it.token == match.groupValues[1] }
        context.valueOf(token)
    }

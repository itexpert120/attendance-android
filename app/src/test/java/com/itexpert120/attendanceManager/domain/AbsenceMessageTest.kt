package com.itexpert120.attendanceManager.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AbsenceMessageTest {
    private val context = AbsenceMessageContext(
        student = "Ayaan Khan",
        date = "August 31, 2026",
        session = "the first timing",
        school = "Crescent Public School",
        className = "Class 7, Section A",
        roll = "12",
        incharge = "Ms. Sana Ali, the class incharge",
    )

    @Test
    fun `preserves the existing message as the fallback`() {
        assertEquals(
            "Dear Parent/Guardian, Ayaan Khan was marked absent for the first timing on August 31, 2026 at " +
                "Crescent Public School. Please contact Ms. Sana Ali, the class incharge, if this is unexpected.",
            formatAbsenceMessage(null, context),
        )
        assertEquals(
            formatAbsenceMessage(DEFAULT_ABSENCE_MESSAGE_TEMPLATE, context),
            formatAbsenceMessage("   ", context),
        )
    }

    @Test
    fun `fills every supported placeholder in a custom message`() {
        assertEquals(
            "Ayaan Khan (roll 12) of Class 7, Section A was absent on August 31, 2026 for the first timing. " +
                "— Ms. Sana Ali, the class incharge, Crescent Public School",
            formatAbsenceMessage(
                "{student} (roll {roll}) of {class} was absent on {date} for {session}. — {incharge}, {school}",
                context,
            ),
        )
    }

    @Test
    fun `supports repeated placeholders and line breaks`() {
        assertEquals(
            "Ayaan Khan\nPlease contact Ayaan Khan.",
            formatAbsenceMessage("{student}\nPlease contact {student}.", context),
        )
    }

    @Test
    fun `leaves unknown placeholders and dollar signs untouched`() {
        assertEquals(
            "{unknown} costs \$5 for Ayaan Khan",
            formatAbsenceMessage("{unknown} costs \$5 for {student}", context),
        )
    }
}

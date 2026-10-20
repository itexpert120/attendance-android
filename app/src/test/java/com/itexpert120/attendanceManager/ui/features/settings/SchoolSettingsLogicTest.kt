package com.itexpert120.attendanceManager.ui.features.settings

import com.itexpert120.attendanceManager.domain.AbsenceMessageToken
import com.itexpert120.attendanceManager.domain.DEFAULT_ABSENCE_MESSAGE_TEMPLATE
import com.itexpert120.attendanceManager.model.SchoolSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class SchoolSettingsLogicTest {
    @Test
    fun `inserts a placeholder at the cursor and moves the cursor after it`() {
        val result = insertToken("Dear , absent", 5, 5, AbsenceMessageToken.STUDENT)!!
        assertEquals("Dear {student}, absent", result.text)
        assertEquals(14, result.cursor)
    }

    @Test
    fun `pads with a space where the placeholder would touch a word`() {
        assertEquals("Dear {student}", insertToken("Dear", 4, 4, AbsenceMessageToken.STUDENT)!!.text)
        assertEquals("{date} today", insertToken("today", 0, 0, AbsenceMessageToken.DATE)!!.text)
        assertEquals("{roll}", insertToken("", 0, 0, AbsenceMessageToken.ROLL)!!.text)
    }

    @Test
    fun `replaces a selection in either direction and clamps out of range positions`() {
        assertEquals("Hi {school}.", insertToken("Hi there.", 8, 3, AbsenceMessageToken.SCHOOL)!!.text)
        val clamped = insertToken("End", 99, 99, AbsenceMessageToken.CLASS)!!
        assertEquals("End {class}", clamped.text)
        assertEquals(clamped.text.length, clamped.cursor)
    }

    @Test
    fun `refuses an insertion that would exceed the limit`() {
        assertNull(insertToken("x".repeat(10), 10, 10, AbsenceMessageToken.INCHARGE, maxLength = 15))
    }

    @Test
    fun `stores null for a blank or default template`() {
        assertNull(templateForSave("   "))
        assertNull(templateForSave(" $DEFAULT_ABSENCE_MESSAGE_TEMPLATE "))
        assertEquals("Hello {student}", templateForSave(" Hello {student} "))
    }

    @Test
    fun `a stored null template loads as the editable default`() {
        val form = SchoolSettings(
            schoolName = "Crescent",
            academicYearStartMonth = 6,
            currencyLabel = "PKR",
            classInchargeName = "Ms. Ali",
            absenceMessageTemplate = null,
            updatedAt = Instant.fromEpochMilliseconds(0),
        ).toForm()
        assertEquals(DEFAULT_ABSENCE_MESSAGE_TEMPLATE, form.template)
        assertTrue(form.isDefaultTemplate)
        assertFalse(form.copy(template = "Hi").isDefaultTemplate)
        assertTrue(form.copy(template = "  ").isDefaultTemplate)
    }

    @Test
    fun `school name is required`() {
        assertEquals(SCHOOL_NAME_REQUIRED, SchoolSettingsForm(schoolName = "  ").schoolNameError)
        assertNull(SchoolSettingsForm(schoolName = "Crescent").schoolNameError)
    }

    @Test
    fun `preview fills placeholders from the form`() {
        val form = SchoolSettingsForm(
            schoolName = " Crescent ",
            classInchargeName = "Ms. Ali",
            template = "{student} missed {session} on {date} at {school}; call {incharge} ({class}, roll {roll}).",
        )
        assertEquals(
            "Ayaan Khan missed the first timing on May 4, 2026 at Crescent; call Ms. Ali, the class incharge " +
                "(Class 7, Section A, roll 12).",
            previewMessage(form, "May 4, 2026"),
        )
        assertTrue(previewMessage(SchoolSettingsForm(template = ""), "x").contains("Your School"))
    }
}

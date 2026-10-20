package com.itexpert120.attendanceManager.ui.features.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupLogicTest {
    @Test
    fun welcomeAdvancesWithoutValidation() {
        assertEquals(SetupStep.SCHOOL, SetupForm().advance().step)
    }

    @Test
    fun schoolPageNeedsAName() {
        val blocked = SetupForm(step = SetupStep.SCHOOL, schoolName = "   ").advance()
        assertEquals(SetupStep.SCHOOL, blocked.step)
        assertEquals("School name is required.", blocked.error)

        val passed = SetupForm(step = SetupStep.SCHOOL, schoolName = "Crescent", error = "x").advance()
        assertEquals(SetupStep.CLASS, passed.step)
        assertNull(passed.error)
    }

    @Test
    fun classPageNeedsClassAndSection() {
        assertEquals("Class and section are required.", stepError(SetupStep.CLASS, SetupForm(className = "7")))
        assertEquals("Class and section are required.", stepError(SetupStep.CLASS, SetupForm(section = "A")))
        assertNull(stepError(SetupStep.CLASS, SetupForm(className = "7", section = "A")))
    }

    @Test
    fun preferencesAreAlwaysOptional() {
        val form = SetupForm(step = SetupStep.PREFERENCES, currencyLabel = "", classInchargeName = "")
        assertNull(stepError(SetupStep.PREFERENCES, form))
        assertEquals(SetupStep.REVIEW, form.advance().step)
    }

    @Test
    fun reviewIsTheLastPageAndBackStopsAtWelcome() {
        assertEquals(SetupStep.REVIEW, SetupForm(step = SetupStep.REVIEW).advance().step)
        assertEquals(SetupStep.WELCOME, SetupForm().retreat().step)
        assertEquals(SetupStep.CLASS, SetupForm(step = SetupStep.PREFERENCES).retreat().step)
    }

    @Test
    fun jumpingBackClearsTheError() {
        val form = SetupForm(step = SetupStep.REVIEW, error = "That class and section already exists.")
        val jumped = form.jumpTo(SetupStep.CLASS)
        assertEquals(SetupStep.CLASS, jumped.step)
        assertNull(jumped.error)
    }

    @Test
    fun stepNumbersCountFourPages() {
        assertEquals(0, SetupStep.WELCOME.number)
        assertEquals(1, SetupStep.SCHOOL.number)
        assertEquals(SetupStep.COUNTED, SetupStep.REVIEW.number)
    }

    @Test
    fun summaries() {
        assertEquals("Class 7 · A", SetupForm(className = " 7 ", section = "A ").classSummary())
        assertEquals("Starts in April", academicYearSummary(4))
        assertEquals("Rs.", SetupForm(currencyLabel = "  ").effectiveCurrency())
        assertEquals("PKR", SetupForm(currencyLabel = " PKR ").effectiveCurrency())
    }
}

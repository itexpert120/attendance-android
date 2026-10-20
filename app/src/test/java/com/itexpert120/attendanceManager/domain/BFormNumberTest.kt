package com.itexpert120.attendanceManager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BFormNumberTest {
    @Test
    fun `formatting while typing inserts the dashes and stops at 13 digits`() {
        assertEquals("", BFormNumber.formatPartial(""))
        assertEquals("1234", BFormNumber.formatPartial("1234"))
        assertEquals("12345", BFormNumber.formatPartial("12345"))
        assertEquals("12345-1", BFormNumber.formatPartial("123451"))
        assertEquals("12345-1234567", BFormNumber.formatPartial("123451234567"))
        assertEquals("12345-1234567-1", BFormNumber.formatPartial("1234512345671"))
        assertEquals("12345-1234567-1", BFormNumber.formatPartial("1234512345671999"))
        assertEquals("12345-12", BFormNumber.formatPartial("12a34-5 12"))
    }

    @Test
    fun `normalising accepts 13 digits with or without dashes and spaces`() {
        assertEquals("12345-1234567-1", BFormNumber.normalize("1234512345671"))
        assertEquals("12345-1234567-1", BFormNumber.normalize(" 12345-1234567-1 "))
        assertEquals("12345-1234567-1", BFormNumber.normalize("12345 1234567 1"))
        assertEquals("12345-1234567-1", BFormNumber.normalize("1234-51234567-1"))
    }

    @Test
    fun `normalising rejects anything that is not exactly 13 digits`() {
        assertNull(BFormNumber.normalize(""))
        assertNull(BFormNumber.normalize("123451234567"))
        assertNull(BFormNumber.normalize("12345123456712"))
        assertNull(BFormNumber.normalize("A-101"))
        assertNull(BFormNumber.normalize("12345-1234567-X"))
    }

    @Test
    fun `same number compares normalised values and falls back to case-insensitive text`() {
        assertTrue(BFormNumber.sameNumber("1234512345671", "12345-1234567-1"))
        assertFalse(BFormNumber.sameNumber("12345-1234567-2", "12345-1234567-1"))
        assertTrue(BFormNumber.sameNumber("a-101", "A-101"))
        assertFalse(BFormNumber.sameNumber("A-101", "12345-1234567-1"))
    }
}

package com.itexpert120.attendanceManager.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.files.AndroidPhoneNormalizer
import com.itexpert120.attendanceManager.domain.ValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Ports src/lib/phone.test.ts. */
@RunWith(AndroidJUnit4::class)
class AndroidPhoneNormalizerTest {
    private lateinit var phones: AndroidPhoneNormalizer

    @Before
    fun setUp() {
        phones = AndroidPhoneNormalizer(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun assertInvalid(input: String) {
        try {
            phones.normalize(input)
            fail("expected a ValidationException for \"$input\"")
        } catch (e: ValidationException) {
            assertTrue(e.message!!.startsWith("Enter a valid phone number"))
            assertEquals(
                "Enter a valid phone number, for example 0300 1234567 or +92 300 1234567.",
                e.message,
            )
        }
    }

    @Test
    fun normalizesPakistaniLocalNumbersToE164() {
        assertEquals("+923001234567", phones.normalize("0300 1234567"))
    }

    @Test
    fun acceptsNumbersAlreadyInInternationalForm() {
        assertEquals("+923001234567", phones.normalize("+92 300 1234567"))
        assertEquals("+923001234567", phones.normalize("  +92-300-1234567 "))
    }

    @Test
    fun preservesValidInternationalNumbersInE164() {
        assertEquals("+12133734253", phones.normalize("+1 (213) 373-4253"))
        assertTrue(phones.display("+12133734253").contains("+1 213"))
    }

    @Test
    fun rejectsInvalidNumbersAndAllowsAnEmptyOptionalValue() {
        assertEquals("", phones.normalize(""))
        assertEquals("", phones.normalize("   "))
        assertInvalid("123")
        assertInvalid("not a number")
    }

    @Test
    fun displaysNumbersInInternationalFormat() {
        assertEquals("+92 300 1234567", phones.display("+923001234567"))
        assertEquals("+92 300 1234567", phones.display("03001234567"))
    }

    @Test
    fun displayLeavesUnparseableValuesAndBlanksAlone() {
        assertEquals("", phones.display(""))
        assertEquals("", phones.display("  "))
        assertEquals("abc", phones.display("abc"))
    }

    @Test
    fun warmUpIsHarmlessAndRepeatable() {
        phones.warmUp()
        phones.warmUp()
        assertEquals("+923001234567", phones.normalize("03001234567"))
    }
}

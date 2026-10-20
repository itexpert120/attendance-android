package com.itexpert120.attendanceManager.data.repo

import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.date
import com.itexpert120.attendanceManager.domain.enrollment
import com.itexpert120.attendanceManager.domain.isActiveOn
import com.itexpert120.attendanceManager.model.ClassGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassRulesTest {
    private fun group(className: String, section: String) =
        ClassGroup(id = 0, className = className, section = section, createdAt = EPOCH)

    @Test
    fun classesSortNaturallyByNameThenSection() {
        val sorted = listOf(group("10", "A"), group("7", "B"), group("7", "A"), group("2", "C"))
            .sortedNaturally()
            .map { it.className + it.section }

        assertEquals(listOf("2C", "7A", "7B", "10A"), sorted)
    }

    @Test
    fun classSortIgnoresCase() {
        val sorted = listOf(group("Nursery", "b"), group("nursery", "A")).sortedNaturally().map { it.section }

        assertEquals(listOf("A", "b"), sorted)
    }

    @Test
    fun duplicatedEnrollmentStartsOnTheAdmissionDateAndIsNotStruckOff() {
        val source = enrollment(id = 5, admittedOn = "2025-04-01", struckOffOn = "2026-05-01", rollNumber = "12")
        val admittedOn = date("2026-04-01")
        assertEquals(true, source.isActiveOn(admittedOn))

        val copy = source.copiedInto(classGroupId = 9, admittedOn = admittedOn)

        assertEquals(0L, copy.id)
        assertEquals(source.studentId, copy.studentId)
        assertEquals(9L, copy.classGroupId)
        assertEquals("12", copy.rollNumber)
        assertEquals(admittedOn, copy.admittedOn)
        assertNull(copy.struckOffOn)
    }
}

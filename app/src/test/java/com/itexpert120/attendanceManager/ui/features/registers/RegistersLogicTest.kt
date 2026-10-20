package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.data.repo.RegisterListItem
import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.date
import com.itexpert120.attendanceManager.domain.enrollment
import com.itexpert120.attendanceManager.domain.register
import com.itexpert120.attendanceManager.domain.row
import com.itexpert120.attendanceManager.model.ClassGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistersLogicTest {
    private val c7a = ClassGroup(1, "7", "A", EPOCH)
    private val c7b = ClassGroup(2, "7", "B", EPOCH)
    private val c10a = ClassGroup(3, "10", "A", EPOCH)
    private val today = date("2026-10-07")

    @Test
    fun labels() {
        assertEquals("Class 7 · A", classLabel(c7a))
        assertEquals("7 · Section A", classOptionLabel(c7a))
        assertEquals("1 student", studentCountLabel(1))
        assertEquals("0 students", studentCountLabel(0))
        assertEquals("32 students", studentCountLabel(32))
    }

    @Test
    fun todayEntriesPairEachClassWithItsRegisterForTheCurrentMonthOnly() {
        val registers = listOf(
            register(1, 2026, 10, classGroupId = 1),
            register(2, 2026, 9, classGroupId = 2),
            register(3, 2025, 10, classGroupId = 2),
        )
        val entries = buildTodayEntries(listOf(c7a, c7b), registers, today)
        assertEquals(1L, entries[0].register?.id)
        assertNull(entries[1].register)
        assertEquals(listOf(c7a, c7b), entries.map { it.classGroup })
    }

    @Test
    fun studentCountsHideStudentsStruckOffEarlierInTheMonthAndOtherClasses() {
        val october = register(1, 2026, 10, classGroupId = 1)
        val rows = listOf(
            row(enrollment(1, "2026-04-01")),
            row(enrollment(2, "2026-04-01", struckOffOn = "2026-10-05")), // struck off in the month: hidden
            row(enrollment(3, "2026-04-01", struckOffOn = "2026-11-05")), // still shown
            row(enrollment(4, "2026-11-01")), // not yet admitted
            row(enrollment(5, "2026-04-01", classGroupId = 2)), // another class
        )
        assertEquals(mapOf(1L to 2), registerStudentCounts(listOf(october), rows))
    }

    @Test
    fun registersAreGroupedNewestMonthFirstWithClassesInNaturalOrder() {
        val items = listOf(
            RegisterListItem(register(1, 2026, 9, 1), c7a),
            RegisterListItem(register(2, 2026, 10, 3), c10a),
            RegisterListItem(register(3, 2026, 10, 1), c7a),
            RegisterListItem(register(4, 2025, 12, 2), c7b),
        )
        val groups = groupRegistersByMonth(items, mapOf(3L to 31), today)
        assertEquals(listOf("2026-10", "2026-9", "2025-12"), groups.map { it.key })
        assertEquals(listOf(true, false, false), groups.map { it.isCurrent })
        assertEquals(listOf(3L, 2L), groups[0].registers.map { it.id }) // 7A before 10A
        assertEquals(31, groups[0].registers[0].studentCount)
        assertEquals(0, groups[0].registers[1].studentCount)
        assertEquals("October 2026", groups[0].title)
    }

    @Test
    fun testsAreCountedInTheCurrentAcademicYear() {
        val dates = listOf(date("2026-03-31"), date("2026-04-01"), date("2026-10-01"), date("2027-03-31"), date("2027-04-01"))
        // Academic year from April: today (Oct 2026) is in 2026-04..2027-03.
        assertEquals(3, academicYearTestCount(dates, 4, today))
        // From January it is simply calendar 2026.
        assertEquals(3, academicYearTestCount(dates, 1, today)) // 2026-03-31, 2026-04-01, 2026-10-01
        // From October the year runs Oct 2026..Sep 2027.
        assertEquals(3, academicYearTestCount(dates, 10, today)) // 2026-10-01, 2027-03-31, 2027-04-01
    }

    @Test
    fun yearsSpanFourEitherSide() {
        assertEquals((2022..2030).toList(), newRegisterYears(today))
    }

    @Test
    fun defaultClassKeepsAValidPreferenceElseFirst() {
        assertEquals(2L, defaultClassId(listOf(c7a, c7b), 2))
        assertEquals(1L, defaultClassId(listOf(c7a, c7b), 99))
        assertEquals(1L, defaultClassId(listOf(c7a, c7b), null))
        assertNull(defaultClassId(emptyList(), 1))
    }

    @Test
    fun contentCombinesEverythingForTheTab() {
        val inputs = RegistersInputs(
            settings = null,
            classes = listOf(c7a),
            registerItems = emptyList(),
            enrollmentRows = emptyList(),
            studentCount = 5,
            testDates = listOf(today),
            homeworkCount = 2,
        )
        val content = buildRegistersContent(inputs, today)
        assertFalse(content.hasRegisters)
        assertEquals(1, content.todayEntries.size)
        assertNull(content.todayEntries[0].register)
        assertEquals(RegistersStats(students = 5, classes = 1, tests = 0, homework = 2), content.stats)
        assertTrue(content.monthGroups.isEmpty())
    }

    @Test
    fun registerWhoseClassIsGoneIsListedAsClass() {
        val items = listOf(
            RegisterListItem(register(1, 2026, 10, 1), c7a),
            RegisterListItem(register(2, 2026, 10, 99), null),
        )
        val group = groupRegistersByMonth(items, emptyMap(), today).single()
        assertEquals(listOf("Class", "Class 7 · A"), group.registers.map { it.label })
    }
}

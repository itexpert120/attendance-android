package com.itexpert120.attendanceManager.ui.features.students

import com.itexpert120.attendanceManager.data.repo.PhotoChange
import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.date
import com.itexpert120.attendanceManager.domain.enrollment
import com.itexpert120.attendanceManager.domain.row
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Student
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentsLogicTest {
    private fun group(id: Long, className: String, section: String) = ClassGroup(id, className, section, EPOCH)

    private fun student(id: Long, name: String, admission: String = "A$id") =
        Student(id = id, admissionNumber = admission, name = name, createdAt = EPOCH)

    private val rows = listOf(
        row(enrollment(1, "2026-04-01", rollNumber = "1"), "Ayaan S/O Awaan"),
        row(enrollment(2, "2026-04-01", rollNumber = "2"), "Sara Khan"),
        row(enrollment(3, "2026-04-01", struckOffOn = "2026-08-03", rollNumber = "3"), "Bilal Ahmed"),
        row(enrollment(4, "2026-04-01", rollNumber = "10"), "Hira Noor"),
    )

    @Test
    fun effectiveClassKeepsTheSelectionWhileItExists() {
        val classes = listOf(group(1, "7", "A"), group(2, "7", "B"))
        assertEquals(2L, effectiveClassId(classes, 2))
    }

    @Test
    fun effectiveClassFallsBackToTheFirstClass() {
        val classes = listOf(group(1, "7", "A"), group(2, "7", "B"))
        assertEquals(1L, effectiveClassId(classes, null))
        assertEquals(1L, effectiveClassId(classes, 99))
        assertNull(effectiveClassId(emptyList(), 1))
    }

    @Test
    fun searchMatchesNameAdmissionAndRollIgnoringCase() {
        assertEquals(listOf(2L), rows.filter { it.matchesQuery("  SARA ") }.map { it.enrollment.id })
        assertEquals(listOf(4L), rows.filter { it.matchesQuery("a4") }.map { it.enrollment.id })
        // "1" matches roll 1 and roll 10, plus admission numbers A1 (the substring "1").
        assertEquals(listOf(1L, 4L), rows.filter { it.matchesQuery("1") }.map { it.enrollment.id })
        assertEquals(4, rows.count { it.matchesQuery("") })
    }

    @Test
    fun listingSplitsOnRollFromStruckOffAndCountsBoth() {
        val listing = buildListing(rows, query = "", showStruckOff = true)
        assertEquals(listOf(1L, 2L, 4L), listing.onRoll.map { it.enrollment.id })
        assertEquals(listOf(3L), listing.struckOff.map { it.enrollment.id })
        assertEquals(3, listing.totalOnRoll)
        assertEquals(1, listing.totalStruckOff)
    }

    @Test
    fun hidingStruckOffKeepsTheirTotalForTheFilterChip() {
        val listing = buildListing(rows, query = "", showStruckOff = false)
        assertTrue(listing.struckOff.isEmpty())
        assertEquals(1, listing.totalStruckOff)
    }

    @Test
    fun searchNarrowsTheListButNotTheTotals() {
        val listing = buildListing(rows, query = "bilal", showStruckOff = true)
        assertTrue(listing.onRoll.isEmpty())
        assertEquals(listOf(3L), listing.struckOff.map { it.enrollment.id })
        assertEquals(3, listing.totalOnRoll)
        assertFalse(listing.isEmpty)
        assertTrue(buildListing(rows, "nobody", true).isEmpty)
    }

    @Test
    fun initialsUseTheFirstTwoNameWordsButSkipParentageMarkers() {
        assertEquals("SK", studentInitials("sara khan"))
        assertEquals("A", studentInitials("Ayaan S/O Awaan"))
        assertEquals("A", studentInitials("  Ayaan  "))
        assertEquals("?", studentInitials("   "))
    }

    @Test
    fun nextRollNumberFollowsTheHighestNumericRoll() {
        assertEquals("11", suggestNextRollNumber(rows))
        assertEquals("", suggestNextRollNumber(emptyList()))
        assertEquals("", suggestNextRollNumber(listOf(row(enrollment(1, "2026-04-01", rollNumber = "A")))))
    }

    @Test
    fun duplicateCopiesStudentsOnTheRollOnTheChosenDate() {
        // Student 3 was struck off on 3 Aug 2026 (inclusive).
        assertEquals(4, countCopiedByDuplicate(rows, date("2026-08-03")))
        assertEquals(3, countCopiedByDuplicate(rows, date("2026-08-04")))
        assertEquals(0, countCopiedByDuplicate(rows, date("2026-03-31")))
    }

    @Test
    fun existingStudentPickerSkipsStudentsAlreadyInTheClass() {
        val all = listOf(student(1, "Ayaan"), student(5, "Zoya"), student(6, "Amna", admission = "X-6"))
        assertEquals(listOf("Amna", "Zoya"), studentsAvailableToAdd(all, rows, "").map { it.name })
        assertEquals(listOf("Amna"), studentsAvailableToAdd(all, rows, "x-6").map { it.name })
    }

    @Test
    fun strikeOffNeverPrecedesAdmission() {
        val admittedLater = row(enrollment(9, "2026-09-01"))
        assertEquals(date("2026-09-01"), strikeOffDate(admittedLater, date("2026-08-20")))
        assertEquals(date("2026-09-15"), strikeOffDate(admittedLater, date("2026-09-15")))
    }

    @Test
    fun resavingARowChangesOnlyTheStruckOffDate() {
        val input = studentInputFromRow(rows[2], null)
        assertNull(input.struckOffOn)
        assertEquals(3L, input.studentId)
        assertEquals(3L, input.enrollmentId)
        assertEquals("3", input.rollNumber)
        assertEquals(PhotoChange.Keep, input.photo)
    }

    @Test
    fun badgeAndPhoneTextsAreFormattedForDevices() {
        assertEquals("Struck off Aug 3, 2026", struckOffBadgeText(date("2026-08-03")))
        assertEquals("923001234567", phoneDigits("+92 300 1234567"))
        assertEquals("tel:+923001234567", telUriText("+92 300 1234567"))
        assertEquals("smsto:+923001234567", smsUriText("+923001234567"))
        assertEquals("https://wa.me/923001234567", whatsAppUriText("+923001234567"))
    }

    @Test
    fun formValidationReportsEachMissingField() {
        val errors = validateStudentForm("  ", "", "", date("2026-04-01"), null)
        assertEquals("Student name is required.", errors.name)
        assertEquals("Admission number is required.", errors.admissionNumber)
        assertEquals("Roll number is required.", errors.rollNumber)
        assertTrue(errors.hasErrors)
        assertFalse(validateStudentForm("Sara", "12", "3", date("2026-04-01"), null).hasErrors)
    }

    @Test
    fun formValidationRejectsStrikeOffBeforeAdmission() {
        val errors = validateStudentForm("Sara", "12", "3", date("2026-04-10"), date("2026-04-09"))
        assertEquals("Struck-off date cannot be before admission date.", errors.struckOffOn)
        assertNull(validateStudentForm("Sara", "12", "3", date("2026-04-10"), date("2026-04-10")).struckOffOn)
    }

    @Test
    fun repositoryMessagesLandUnderTheirField() {
        assertEquals("Admission number must be unique.", StudentFormErrors.fromRepositoryMessage("Admission number must be unique.").admissionNumber)
        assertEquals(
            "Roll number is already active in this class.",
            StudentFormErrors.fromRepositoryMessage("Roll number is already active in this class.").rollNumber,
        )
        assertEquals(
            "Enter a valid phone number, for example 0300 1234567 or +92 300 1234567.",
            StudentFormErrors.fromRepositoryMessage("Enter a valid phone number, for example 0300 1234567 or +92 300 1234567.").phone,
        )
        assertEquals(
            "Struck-off date cannot be before admission date.",
            StudentFormErrors.fromRepositoryMessage("Struck-off date cannot be before admission date.").struckOffOn,
        )
        assertEquals("Student name is required.", StudentFormErrors.fromRepositoryMessage("Student name is required.").name)
        assertEquals("Something else", StudentFormErrors.fromRepositoryMessage("Something else").general)
    }

    @Test
    fun classLabelsMatchTheWebApp() {
        val g = group(1, "7", "A")
        assertEquals("Class 7 · A", classLabel(g))
        assertEquals("7 · A", classChipLabel(g))
    }

    @Test
    fun newStudentFormStartsOnTheGivenAdmissionDate() {
        val form = StudentFormState.forNew(date("2026-05-01"), suggestedRoll = "4")
        assertEquals(date("2026-05-01"), form.admittedOn)
        assertEquals("4", form.rollNumber)
        assertNull(form.struckOffOn)
        assertNull(form.dateOfBirth)
    }

    @Test
    fun newStudentStruckOffBeforeAdmissionIsRejected() {
        val errors = validateStudentForm("Ayaan", "101", "1", date("2026-05-01"), date("2026-04-30"))
        assertEquals("Struck-off date cannot be before admission date.", errors.struckOffOn)
        assertNull(validateStudentForm("Ayaan", "101", "1", date("2026-05-01"), date("2026-05-01")).struckOffOn)
    }
}

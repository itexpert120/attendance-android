package com.itexpert120.attendanceManager.data.repo

import com.itexpert120.attendanceManager.domain.EPOCH
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.domain.attendanceReport
import com.itexpert120.attendanceManager.domain.enrollment
import com.itexpert120.attendanceManager.domain.mark
import com.itexpert120.attendanceManager.domain.register
import com.itexpert120.attendanceManager.domain.row
import com.itexpert120.attendanceManager.model.AttendanceStatus.ABSENT
import com.itexpert120.attendanceManager.model.AttendanceStatus.PRESENT
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsenceMessagesTest {
    private val august = register(id = 1, year = 2026, month = 8)
    private val group = ClassGroup(id = 1, className = "7", section = "B", createdAt = EPOCH)
    private val settings = SchoolSettings(
        schoolName = "Green Valley",
        academicYearStartMonth = 4,
        classInchargeName = "Ms Khan",
        updatedAt = EPOCH,
    )

    private val ali = row(enrollment(1, "2026-04-01", rollNumber = "12"), "Ali")
    private val sara = row(enrollment(2, "2026-04-01", rollNumber = "13"), "Sara")
    private val omar = row(enrollment(3, "2026-04-01", rollNumber = "14"), "Omar")

    // Tuesday 11 August 2026.
    private val marks = listOf(
        mark(1, 1, 11, Session.FIRST, ABSENT),
        mark(1, 1, 11, Session.SECOND, ABSENT),
        mark(1, 2, 11, Session.SECOND, ABSENT),
        mark(1, 2, 11, Session.FIRST, PRESENT),
        mark(1, 3, 11, Session.FIRST, PRESENT),
        mark(1, 3, 11, Session.SECOND, PRESENT),
    )

    private fun daily() = attendanceReport(listOf(ali, sara, omar), marks, emptySet(), august, ReportPeriodType.DAILY, 11)

    @Test
    fun `labels the absent timings`() {
        assertEquals("both timings", absenceSessionLabel(listOf(Session.FIRST, Session.SECOND)))
        assertEquals("the first timing", absenceSessionLabel(listOf(Session.FIRST)))
        assertEquals("the second timing", absenceSessionLabel(listOf(Session.SECOND)))
    }

    @Test
    fun `only absent students become recipients, in roll order`() {
        val recipients = absenceMessageRecipients(daily(), group, settings)

        assertEquals(listOf("Ali", "Sara"), recipients.map { it.student.row.student.name })
    }

    @Test
    fun `fills the context from the report, class and settings`() {
        val ctx = absenceMessageRecipients(daily(), group, settings)[1].context

        assertEquals("Sara", ctx.student)
        assertEquals("Tuesday, August 11, 2026", ctx.date)
        assertEquals("the second timing", ctx.session)
        assertEquals("Green Valley", ctx.school)
        assertEquals("Class 7, Section B", ctx.className)
        assertEquals("13", ctx.roll)
        assertEquals("Ms Khan, the class incharge", ctx.incharge)
    }

    @Test
    fun `uses the default message and formats it`() {
        val message = absenceMessageRecipients(daily(), group, settings)[0].message

        assertEquals(
            "Dear Parent/Guardian, Ali was marked absent for both timings on Tuesday, August 11, 2026 at Green Valley. " +
                "Please contact Ms Khan, the class incharge, if this is unexpected.",
            message,
        )
    }

    @Test
    fun `honours a custom template`() {
        val custom = settings.copy(absenceMessageTemplate = "{student} (roll {roll}, {class}) absent {session}.")

        assertEquals(
            "Sara (roll 13, Class 7, Section B) absent the second timing.",
            absenceMessageRecipients(daily(), group, custom)[1].message,
        )
    }

    @Test
    fun `falls back when settings or the class are missing`() {
        val ctx = absenceMessageRecipients(daily(), null, null).first().context

        assertEquals("school", ctx.school)
        assertEquals("the class", ctx.className)
        assertEquals("the class incharge", ctx.incharge)
        assertTrue(absenceMessageRecipients(daily(), group, settings.copy(classInchargeName = "  ")).first().context.incharge == "the class incharge")
    }

    @Test
    fun `nobody absent means no recipients`() {
        val report = attendanceReport(listOf(omar), marks, emptySet(), august, ReportPeriodType.DAILY, 11)

        assertTrue(absenceMessageRecipients(report, group, settings).isEmpty())
    }
}

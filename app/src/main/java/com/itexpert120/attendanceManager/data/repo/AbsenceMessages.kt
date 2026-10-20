package com.itexpert120.attendanceManager.data.repo

import com.itexpert120.attendanceManager.domain.AbsenceMessageContext
import com.itexpert120.attendanceManager.domain.AttendanceReport
import com.itexpert120.attendanceManager.domain.AttendanceReportStudent
import com.itexpert120.attendanceManager.domain.formatAbsenceMessage
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.SchoolSettings

/*
 * Builds the parent-message text for the Reports tab (see ReportsPanel.messageText in the PWA). Pure so
 * the SMS / WhatsApp / call buttons only have to open an intent with the finished text.
 */

/** A student who was absent in the report period, with the ready-to-send message. */
data class AbsenceMessageRecipient(
    val student: AttendanceReportStudent,
    val context: AbsenceMessageContext,
    /** [context] formatted with the school's template (or the default message). */
    val message: String,
)

/**
 * What the `{session}` token says. Attendance is taken once a day, so an absence always covers the
 * whole day: "... was marked absent for the day on ...".
 */
const val ABSENCE_SESSION_LABEL = "the day"

fun absenceMessageContext(
    student: AttendanceReportStudent,
    report: AttendanceReport,
    classGroup: ClassGroup?,
    settings: SchoolSettings?,
): AbsenceMessageContext {
    val incharge = settings?.classInchargeName?.trim().orEmpty()
    return AbsenceMessageContext(
        student = student.row.student.name,
        date = report.period.label,
        session = ABSENCE_SESSION_LABEL,
        school = settings?.schoolName ?: "school",
        className = classGroup?.let { "Class ${it.className}, Section ${it.section}" } ?: "the class",
        roll = student.row.enrollment.rollNumber,
        incharge = if (incharge.isNotEmpty()) "$incharge, the class incharge" else "the class incharge",
    )
}

fun absenceMessage(
    student: AttendanceReportStudent,
    report: AttendanceReport,
    classGroup: ClassGroup?,
    settings: SchoolSettings?,
): String = formatAbsenceMessage(
    settings?.absenceMessageTemplate,
    absenceMessageContext(student, report, classGroup, settings),
)

/**
 * One recipient per student who has at least one absence in [report], in roll order. The UI offers
 * messaging for daily reports.
 */
fun absenceMessageRecipients(
    report: AttendanceReport,
    classGroup: ClassGroup?,
    settings: SchoolSettings?,
): List<AbsenceMessageRecipient> =
    report.students.filter { it.absent > 0 }.map { student ->
        val context = absenceMessageContext(student, report, classGroup, settings)
        AbsenceMessageRecipient(student, context, formatAbsenceMessage(settings?.absenceMessageTemplate, context))
    }

package com.itexpert120.attendanceManager.export

import android.graphics.Bitmap
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.AttendanceReport
import com.itexpert120.attendanceManager.domain.AttendanceReportStudent
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.ReportPeriodType
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.ui.features.registers.REPORT_TIMING_NOTE
import com.itexpert120.attendanceManager.ui.features.registers.formatRate
import com.itexpert120.attendanceManager.ui.features.registers.formatSessionsAsDays
import com.itexpert120.attendanceManager.ui.features.registers.formatRatePrecise
import com.itexpert120.attendanceManager.ui.features.registers.reportAbsentees
import com.itexpert120.attendanceManager.ui.features.registers.reportPeriodTypeLabel
import com.itexpert120.attendanceManager.ui.features.registers.reportRate
import com.itexpert120.attendanceManager.ui.features.registers.studentRate

/**
 * The Reports tab's two printouts, A4 portrait: the daily / weekly / monthly attendance report (the PWA's
 * `PrintReport.svelte`) and the absentee-only list with photos (`PrintAbsenteeList.svelte`). Both take the
 * [AttendanceReport] the tab is showing, so the PDF always matches the screen.
 */
object AttendanceReportPdf {
    const val REPORT_LABEL = "Attendance Report"
    const val ABSENTEE_LABEL = "Absentee List"

    fun reportTitle(report: AttendanceReport): String = "${reportPeriodTypeLabel(report.type)} attendance report"

    fun reportFileName(data: RegisterData, report: AttendanceReport): String =
        "${reportPeriodTypeLabel(report.type)} report ${classPart(data)} ${report.period.label}"

    fun absenteeFileName(data: RegisterData, report: AttendanceReport): String =
        "Absentees ${classPart(data)} ${report.period.label}"

    // region Attendance report

    suspend fun buildReport(data: RegisterData, report: AttendanceReport, photoStore: PhotoStore?): PdfDoc {
        val logo = photoStore?.let { RegisterPdf.loadBitmap(it, data.settings?.logoFile, 256) }
        return buildReport(data, report, logo)
    }

    fun buildReport(data: RegisterData, report: AttendanceReport, logo: Bitmap?): PdfDoc {
        val school = RegisterPdf.schoolName(data)
        val doc = PdfDoc(PageOrientation.PORTRAIT, footerLeft = school, footerRight = REPORT_LABEL)
        doc.header(
            schoolName = school,
            eyebrow = reportTitle(report),
            title = "Attendance report",
            subtitle = "${classLine(data)} · ${report.period.label} · Each timing = 0.5",
            logo = logo,
        )
        val rate = reportRate(report)
        doc.stats(
            listOf(
                "Present" to formatSessionsAsDays(report.present),
                "Absent" to formatSessionsAsDays(report.absent),
                "Leave" to formatSessionsAsDays(report.leave),
                "Unmarked" to formatSessionsAsDays(report.unmarked),
                "Attendance" to (rate?.let { "${formatRatePrecise(it)}%" } ?: "—"),
            ),
        )
        doc.text(
            REPORT_TIMING_NOTE,
            PdfTextStyle(7.5f, color = PdfColors.Muted),
        )

        if (report.type == ReportPeriodType.DAILY) {
            val day = report.period.startDay
            val absentees = reportAbsentees(report)
            doc.sectionTitle("Absentees (${absentees.size})")
            when {
                data.isHoliday(day) -> doc.text(
                    data.holidayTitle(day)?.let { "Holiday: $it. No attendance is taken." } ?: "Weekend. No attendance is taken.",
                    PdfTextStyle(9f, color = PdfColors.Muted),
                )
                absentees.isEmpty() -> doc.text("No students were marked absent.", PdfTextStyle(9f, color = PdfColors.Muted))
                else -> doc.table(
                    PdfTable(
                        columns = listOf(
                            PdfColumn("Roll", width = 48f),
                            PdfColumn("Name with parentage", weight = 1f),
                            PdfColumn("Absent", width = 110f),
                        ),
                        rows = absentees.map { student ->
                            PdfRow(
                                listOf(
                                    PdfCell(student.row.enrollment.rollNumber, bold = true),
                                    PdfCell(student.row.student.name),
                                    PdfCell(dailyTimingLabel(student, short = true)),
                                ),
                            )
                        },
                        fontSize = 9f,
                    ),
                )
            }
        }

        doc.sectionTitle("Students")
        val columns = listOf(
            PdfColumn("Roll", width = 44f),
            PdfColumn("Name with parentage", weight = 1f),
            PdfColumn("Present", width = 52f, align = PdfAlign.CENTER),
            PdfColumn("Absent", width = 52f, align = PdfAlign.CENTER),
            PdfColumn("Leave", width = 52f, align = PdfAlign.CENTER),
            PdfColumn("Unmarked", width = 58f, align = PdfAlign.CENTER),
            PdfColumn("Attendance", width = 64f, align = PdfAlign.CENTER),
        )
        val rows = report.students.map { student ->
            PdfRow(
                listOf(
                    PdfCell(student.row.enrollment.rollNumber, bold = true),
                    PdfCell(student.row.student.name, bold = true),
                    PdfCell(formatSessionsAsDays(student.present)),
                    PdfCell(formatSessionsAsDays(student.absent), fill = if (student.absent > 0) PdfColors.AbsentFill else null),
                    PdfCell(formatSessionsAsDays(student.leave)),
                    PdfCell(formatSessionsAsDays(student.unmarked)),
                    PdfCell(formatRate(studentRate(student)), bold = true),
                ),
            )
        }
        val totals = if (report.students.isEmpty()) null else PdfRow(
            listOf(
                PdfCell(""),
                PdfCell("Total"),
                PdfCell(formatSessionsAsDays(report.present)),
                PdfCell(formatSessionsAsDays(report.absent)),
                PdfCell(formatSessionsAsDays(report.leave)),
                PdfCell(formatSessionsAsDays(report.unmarked)),
                PdfCell(formatRate(rate)),
            ),
        )
        doc.table(PdfTable(columns, rows, fontSize = 9f, totals = totals))
        if (report.students.isEmpty()) {
            doc.text("No students are on the roll for this month.", PdfTextStyle(9f, color = PdfColors.Muted))
        }

        doc.signatures(listOf("Class incharge signature" to RegisterPdf.inchargeName(data)))
        return doc
    }

    // endregion

    // region Absentee list

    suspend fun buildAbsentees(data: RegisterData, report: AttendanceReport, photoStore: PhotoStore?): PdfDoc {
        val logo = photoStore?.let { RegisterPdf.loadBitmap(it, data.settings?.logoFile, 256) }
        val photos = if (photoStore == null) emptyMap() else reportAbsentees(report)
            .mapNotNull { student ->
                RegisterPdf.loadBitmap(photoStore, student.row.student.photoFile, 120)?.let { student.row.enrollment.id to it }
            }
            .toMap()
        return buildAbsentees(data, report, logo, photos)
    }

    /** [photos] maps enrollment ids to already-decoded, small student photos. */
    fun buildAbsentees(data: RegisterData, report: AttendanceReport, logo: Bitmap?, photos: Map<Long, Bitmap>): PdfDoc {
        val school = RegisterPdf.schoolName(data)
        val absentees = reportAbsentees(report)
        val doc = PdfDoc(PageOrientation.PORTRAIT, footerLeft = school, footerRight = ABSENTEE_LABEL)
        doc.header(
            schoolName = school,
            eyebrow = "Absentee-only list",
            title = "Absentee list",
            subtitle = "${classLine(data)} · ${report.period.label}",
            logo = logo,
        )
        doc.stats(
            listOf(
                "Absent students" to absentees.size.toString(),
                "Absent days" to formatSessionsAsDays(absentees.sumOf { it.absent }),
            ),
        )

        if (absentees.isEmpty()) {
            doc.spacer(16f)
            doc.text("No students were marked absent", PdfTextStyle(11f, bold = true), PdfAlign.CENTER)
            doc.text(
                "There are no explicit absence marks for ${report.period.label}.",
                PdfTextStyle(9f, color = PdfColors.Muted),
                PdfAlign.CENTER,
            )
        } else {
            val daily = report.type == ReportPeriodType.DAILY
            doc.table(
                PdfTable(
                    columns = listOf(
                        PdfColumn("Photo", width = 46f, align = PdfAlign.CENTER),
                        PdfColumn("Roll no.", width = 56f),
                        PdfColumn("Name", weight = 1f),
                        PdfColumn("Date of absence", width = 150f),
                        PdfColumn(if (daily) "Timing" else "Days absent", width = 84f),
                    ),
                    rows = absentees.map { student ->
                        val photo = photos[student.row.enrollment.id]
                        PdfRow(
                            listOf(
                                if (photo != null) PdfCell("", image = photo, imageSize = 30f) else PdfCell("—", color = PdfColors.Muted),
                                PdfCell(student.row.enrollment.rollNumber, bold = true),
                                PdfCell(student.row.student.name, bold = true),
                                PdfCell(report.period.label),
                                PdfCell(if (daily) dailyTimingLabel(student, short = false) else formatSessionsAsDays(student.absent)),
                            ),
                        )
                    },
                    fontSize = 9f,
                ),
            )
        }

        doc.signatures(listOf("Class incharge signature" to RegisterPdf.inchargeName(data)))
        return doc
    }

    // endregion

    /** "Both timings" / "First timing" / "Second timing" (or "Both" / "First" / "Second"). */
    private fun dailyTimingLabel(student: AttendanceReportStudent, short: Boolean): String {
        val word = when {
            student.absentSessions.size >= 2 -> "Both"
            student.absentSessions.firstOrNull() == Session.FIRST -> "First"
            else -> "Second"
        }
        return when {
            short -> word
            word == "Both" -> "Both timings"
            else -> "$word timing"
        }
    }

    private fun classLine(data: RegisterData) = "Class ${data.classGroup.className} · Section ${data.classGroup.section}"

    private fun classPart(data: RegisterData) = "${data.classGroup.className}-${data.classGroup.section}"
}

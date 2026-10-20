package com.itexpert120.attendanceManager.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.FeeField
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.dateOf
import com.itexpert120.attendanceManager.domain.isActiveOn
import com.itexpert120.attendanceManager.domain.minorToDisplay
import com.itexpert120.attendanceManager.domain.monthName
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.ui.features.registers.shortLabel
import com.itexpert120.attendanceManager.ui.features.registers.summaryDaysLabel
import com.itexpert120.attendanceManager.ui.features.registers.summaryFigures

/**
 * The official monthly register, A4 landscape like the paper register (the PWA's `PrintRegister.svelte`):
 *  1. attendance pages, days 1–16 and 17–end, one column per day, with each student's month total, brought
 *     forward and total, and a row with the number of students present each day;
 *  2. the fees and remarks page;
 *  3. the monthly close: student movement, working days, installment collection and the incharge's signature.
 * Every figure comes from [RegisterData] and the register logic, so the PDF matches the Summary and Fees tabs.
 */
object RegisterPdf {
    const val DOCUMENT_LABEL = "Monthly Attendance Register"

    /** Days per attendance page; the second page takes the rest of the month, as on the paper register. */
    private const val FIRST_PAGE_DAYS = 16

    /** One column per day; wide enough for a clear letter now that a day has a single mark. */
    private const val DAY_WIDTH = 22f
    private const val TOTAL_WIDTH = 34f
    /** B-Form numbers are 13 digits (15 with dashes), so this column is wider than the old admission number. */
    private const val B_FORM_WIDTH = 72f
    private const val ROLL_WIDTH = 28f

    /** Dark red of the PWA's holiday notes. */
    private const val HolidayInk = 0xFF7F1D1D.toInt()

    const val TITLE = "Attendance register"

    fun fileName(data: RegisterData): String =
        "Register ${data.classGroup.className}-${data.classGroup.section} ${monthName(data.register.month)} ${data.register.year}"

    /** Loads the school logo from [photoStore] (when there is one) and renders the register. */
    suspend fun build(data: RegisterData, photoStore: PhotoStore?): PdfDoc {
        val logo = photoStore?.let { loadBitmap(it, data.settings?.logoFile, 256) }
        return build(data, logo)
    }

    fun build(data: RegisterData, logo: Bitmap?): PdfDoc {
        val school = schoolName(data)
        val doc = PdfDoc(PageOrientation.LANDSCAPE, footerLeft = school, footerRight = DOCUMENT_LABEL)
        val days = data.days.toList()
        val chunks = listOf(days.take(FIRST_PAGE_DAYS), days.drop(FIRST_PAGE_DAYS)).filter { it.isNotEmpty() }
        chunks.forEachIndexed { index, chunk ->
            if (index > 0) doc.newPage()
            attendancePage(doc, data, logo, chunk, index, chunks.size)
        }
        doc.newPage()
        feesPage(doc, data, logo)
        doc.newPage()
        summaryPage(doc, data, logo)
        return doc
    }

    // region Attendance

    private fun attendancePage(doc: PdfDoc, data: RegisterData, logo: Bitmap?, chunk: List<Int>, index: Int, pages: Int) {
        val month = monthName(data.register.month)
        val year = data.register.year
        doc.header(
            schoolName = schoolName(data),
            eyebrow = "Official monthly record",
            title = "Students Attendance Register",
            subtitle = "$month $year · Attendance page ${index + 1} of $pages",
            logo = logo,
        )
        doc.keyValues(
            listOf(
                "Class" to data.classGroup.className.ifBlank { "—" },
                "Section" to data.classGroup.section.ifBlank { "—" },
                "Month" to month,
                "Year" to year.toString(),
            ),
        )
        val last = index == pages - 1
        doc.table(attendanceTable(data, chunk, last))
        if (data.attendanceRows.isEmpty()) {
            doc.text("No students are on the roll for this month.", PdfTextStyle(9f, color = PdfColors.Muted))
        }
        doc.text(
            "P = Present · A = Absent · L = Leave · H = Holiday or weekend · – = Not on the roll     " +
                "Column totals = students present that day · Month, B/F and Total = present days",
            PdfTextStyle(7f, color = PdfColors.Muted),
            spacingAfter = 2f,
        )
        val reasons = chunk.mapNotNull { day -> data.holidayTitle(day)?.let { "$day: $it" } }
        if (reasons.isNotEmpty()) {
            doc.text("Holiday reasons · ${reasons.joinToString(" · ")}", PdfTextStyle(7f, bold = true, color = HolidayInk))
        }
    }

    internal fun attendanceTable(data: RegisterData, chunk: List<Int>, last: Boolean): PdfTable {
        val register = data.register
        val columns = buildList {
            add(PdfColumn("B-Form", width = B_FORM_WIDTH, align = PdfAlign.CENTER))
            add(PdfColumn("Roll", width = ROLL_WIDTH, align = PdfAlign.CENTER))
            add(PdfColumn("Name with parentage", weight = 1f))
            chunk.forEach { day -> add(PdfColumn("$day", width = DAY_WIDTH, align = PdfAlign.CENTER)) }
            if (last) {
                add(PdfColumn("Month", width = TOTAL_WIDTH, align = PdfAlign.CENTER))
                add(PdfColumn("B/F", width = TOTAL_WIDTH, align = PdfAlign.CENTER))
                add(PdfColumn("Total", width = TOTAL_WIDTH, align = PdfAlign.CENTER))
            }
        }
        fun dayFill(day: Int) = if (data.isHoliday(day)) PdfColors.HolidayFill else null
        val header = listOf(
            buildList {
                add(PdfHeaderCell("B-Form"))
                add(PdfHeaderCell("Roll"))
                add(PdfHeaderCell("Name with parentage"))
                chunk.forEach { day -> add(PdfHeaderCell(day.toString(), fill = dayFill(day))) }
                if (last) {
                    add(PdfHeaderCell("Month"))
                    add(PdfHeaderCell("B/F"))
                    add(PdfHeaderCell("Total"))
                }
            },
        )

        val rows = data.attendanceRows.map { row ->
            val id = row.enrollment.id
            val cells = buildList {
                add(PdfCell(row.student.admissionNumber))
                add(PdfCell(row.enrollment.rollNumber))
                add(PdfCell(row.student.name, bold = true))
                chunk.forEach { day -> add(markCell(data, row, day)) }
                if (last) {
                    val current = data.presentSessionsOf(id)
                    val brought = data.broughtForwardOf(id)
                    add(PdfCell(summaryDaysLabel(current), bold = true))
                    add(PdfCell(summaryDaysLabel(brought), bold = true))
                    add(PdfCell(summaryDaysLabel(current + brought), bold = true))
                }
            }
            PdfRow(cells)
        }

        val totals = if (data.attendanceRows.isEmpty()) null else PdfRow(
            buildList {
                add(PdfCell(""))
                add(PdfCell(""))
                add(PdfCell("Column totals"))
                chunk.forEach { day ->
                    add(
                        if (data.isHoliday(day)) PdfCell("H", fill = PdfColors.HolidayFill, color = PdfColors.Muted)
                        else PdfCell(data.presentOn(day).toString()),
                    )
                }
                if (last) {
                    add(PdfCell(summaryDaysLabel(data.visiblePresentTotal)))
                    add(PdfCell(summaryDaysLabel(data.visibleBroughtForwardTotal)))
                    add(PdfCell(summaryDaysLabel(data.visiblePresentTotal + data.visibleBroughtForwardTotal)))
                }
            },
        )

        return PdfTable(
            columns = columns,
            rows = rows,
            fontSize = 8f,
            cellPadding = 2f,
            zebra = false,
            totals = totals,
            headerCells = header,
        )
    }

    private fun markCell(data: RegisterData, row: EnrollmentRow, day: Int): PdfCell {
        if (data.isHoliday(day)) return PdfCell("H", fill = PdfColors.HolidayFill, color = PdfColors.Muted)
        if (!row.enrollment.isActiveOn(data.register.dateOf(day))) return PdfCell("–", color = PdfColors.Muted)
        return when (data.statusOf(row.enrollment.id, day)) {
            AttendanceStatus.PRESENT -> PdfCell("P", fill = PdfColors.PresentFill)
            AttendanceStatus.ABSENT -> PdfCell("A", bold = true, fill = PdfColors.AbsentFill)
            AttendanceStatus.LEAVE -> PdfCell("L", fill = PdfColors.LeaveFill)
            null -> PdfCell("")
        }
    }


    // endregion

    // region Fees

    private fun feesPage(doc: PdfDoc, data: RegisterData, logo: Bitmap?) {
        doc.header(
            schoolName = schoolName(data),
            eyebrow = "Financial record",
            title = "Fees & Remarks",
            subtitle = classMonthLine(data) + currencyNote(data),
            logo = logo,
        )
        val columns = buildList {
            add(PdfColumn("B-Form", width = B_FORM_WIDTH, align = PdfAlign.CENTER))
            add(PdfColumn("Roll", width = ROLL_WIDTH, align = PdfAlign.CENTER))
            add(PdfColumn("Name with parentage", weight = 1.4f))
            FeeField.entries.forEach { add(PdfColumn(it.shortLabel, width = 54f, align = PdfAlign.END)) }
            add(PdfColumn("Total", width = 60f, align = PdfAlign.END))
            add(PdfColumn("Remarks", weight = 1.6f))
        }
        val rows = data.monthRows.map { row ->
            val fees = data.feesOf(row.enrollment.id)
            PdfRow(
                buildList {
                    add(PdfCell(row.student.admissionNumber))
                    add(PdfCell(row.enrollment.rollNumber))
                    add(PdfCell(row.student.name, bold = true))
                    addAll(feeCells(fees))
                    add(PdfCell(data.remarkOf(row.enrollment.id)))
                },
            )
        }
        val totals = PdfRow(
            buildList {
                add(PdfCell(""))
                add(PdfCell(""))
                add(PdfCell("Totals"))
                addAll(feeCells(data.feeMonthTotals))
                add(PdfCell(""))
            },
        )
        doc.table(PdfTable(columns, rows, fontSize = 8f, cellPadding = 3f, totals = totals))
        if (data.monthRows.isEmpty()) {
            doc.text("No students are on the roll for this month.", PdfTextStyle(9f, color = PdfColors.Muted))
        }
    }

    /** The six fee amounts followed by their total (bold). */
    private fun feeCells(amounts: FeeAmounts): List<PdfCell> =
        FeeField.entries.map { PdfCell(minorToDisplay(it.valueIn(amounts))) } + PdfCell(minorToDisplay(amounts.total), bold = true)

    // endregion

    // region Summary

    private fun summaryPage(doc: PdfDoc, data: RegisterData, logo: Bitmap?) {
        val figures = summaryFigures(data)
        doc.header(
            schoolName = schoolName(data),
            eyebrow = "Monthly close",
            title = "Monthly Summary",
            subtitle = classMonthLine(data),
            logo = logo,
        )
        fun label(text: String) = PdfCell(text, bold = true, fill = PdfColors.HeaderFill)
        fun value(text: String) = PdfCell(text, bold = true)
        val movementRows = listOf(
            PdfRow(listOf(label("Students at beginning"), value(figures.beginning.toString()), label("Students at end"), value(figures.end.toString()))),
            PdfRow(listOf(label("Admitted during month"), value(figures.admitted.toString()), label("Struck off during month"), value(figures.struckOff.toString()))),
            PdfRow(listOf(label("Working days"), value(figures.workingDays), label("Attendance in month"), value(figures.monthAttendanceDays))),
            PdfRow(listOf(label("Academic year attendance"), value(figures.academicYearDays), label("Average attendance"), value(figures.averageAttendance))),
        )
        doc.table(
            PdfTable(
                columns = listOf(
                    PdfColumn("", weight = 2f),
                    PdfColumn("", weight = 1f, align = PdfAlign.CENTER),
                    PdfColumn("", weight = 2f),
                    PdfColumn("", weight = 1f, align = PdfAlign.CENTER),
                ),
                rows = movementRows,
                fontSize = 9.5f,
                cellPadding = 6f,
                headerRows = emptyList(),
                zebra = false,
            ),
        )

        doc.sectionTitle("Installment collection" + currencyNote(data))
        val columns = buildList {
            add(PdfColumn("Installment", width = 58f, align = PdfAlign.CENTER))
            add(PdfColumn("Students", width = 48f, align = PdfAlign.CENTER))
            add(PdfColumn("At rate of", width = 56f, align = PdfAlign.END))
            FeeField.entries.forEach { add(PdfColumn(it.shortLabel, width = 50f, align = PdfAlign.END)) }
            add(PdfColumn("Total", width = 58f, align = PdfAlign.END))
            add(PdfColumn("Receiver / signature", weight = 1f))
        }
        val rows = InstallmentNumber.entries.map { installment ->
            val meta = data.installmentMeta[installment]
            val amounts = data.installmentTotals[installment] ?: FeeAmounts.EMPTY
            PdfRow(
                buildList {
                    add(PdfCell(installment.shortLabel, bold = true, fill = PdfColors.HeaderFill))
                    add(PdfCell((data.installmentStudentCounts[installment] ?: 0).toString()))
                    add(PdfCell(minorToDisplay(meta?.rate ?: 0L)))
                    addAll(feeCells(amounts))
                    // Blank lines leave room for the receiver's handwritten signature.
                    add(PdfCell("${meta?.receiverName.orEmpty()}\n \n "))
                },
            )
        }
        val totals = PdfRow(
            buildList {
                add(PdfCell("Total"))
                add(PdfCell(data.totalInstallmentStudents.toString()))
                add(PdfCell(minorToDisplay(data.totalInstallmentRate)))
                addAll(feeCells(data.installmentGrandTotals))
                add(PdfCell(""))
            },
        )
        doc.table(PdfTable(columns, rows, fontSize = 8f, cellPadding = 3f, zebra = false, totals = totals))

        doc.signatures(listOf("Class incharge signature" to inchargeName(data)))
    }

    // endregion

    // region Shared

    internal fun schoolName(data: RegisterData): String = data.settings?.schoolName?.takeIf { it.isNotBlank() } ?: "School"

    internal fun inchargeName(data: RegisterData): String =
        data.settings?.classInchargeName?.takeIf { it.isNotBlank() } ?: "Name and signature"

    private fun classMonthLine(data: RegisterData): String =
        "Class ${data.classGroup.className}, Section ${data.classGroup.section} · ${monthName(data.register.month)} ${data.register.year}"

    private fun currencyNote(data: RegisterData): String {
        val label = data.settings?.currencyLabel?.trim().orEmpty()
        return if (label.isEmpty()) "" else " · Amounts in $label"
    }

    /**
     * Reads a stored picture (the school logo, a student photo) and decodes it no larger than [maxPx] on its
     * longer side, so a full-resolution camera photo never lands in the PDF or exhausts memory. Null when the
     * file is missing or not an image.
     */
    suspend fun loadBitmap(photoStore: PhotoStore, fileName: String?, maxPx: Int): Bitmap? {
        if (fileName.isNullOrBlank()) return null
        val bytes = runCatching { photoStore.read(fileName) }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= maxPx) return decoded
        val scale = maxPx.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    // endregion
}

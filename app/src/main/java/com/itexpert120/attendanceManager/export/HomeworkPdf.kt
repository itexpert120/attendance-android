package com.itexpert120.attendanceManager.export

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.HomeworkReportWithItems
import com.itexpert120.attendanceManager.data.repo.HomeworkRepository
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.longDateLabel
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Subject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

/**
 * The Daily Homework Report, A4 portrait (the PWA's `PrintHomeworkReport.svelte`): class, date and incharge,
 * then either the typed homework per Subject or the photographed diary page scaled to fit, the note for
 * parents and signature lines for the parent and the class incharge.
 */
object HomeworkPdf {
    const val DOCUMENT_LABEL = "Daily Homework Report"
    const val TITLE = "Homework report"

    /** The longest side the diary photo is decoded at; enough for print without exhausting memory. */
    private const val PHOTO_MAX_PX = 1800

    /** The PWA caps the photo at 170 mm; in points. */
    private const val PHOTO_MAX_HEIGHT = 482f

    /** Light green of the PWA's parent note. */
    private const val NoteFill = 0xFFF0FDF4.toInt()

    fun fileName(group: ClassGroup?, date: LocalDate?): String =
        listOfNotNull("Homework", group?.let { "${it.className}-${it.section}" }, date?.toString()).joinToString(" ")

    /** Loads the saved report [reportId] and everything it shows; fails when the report no longer exists. */
    suspend fun load(
        reportId: Long,
        homework: HomeworkRepository,
        classes: ClassRepository,
        subjects: SubjectRepository,
        settings: SettingsRepository,
        photoStore: PhotoStore?,
    ): PdfDoc {
        val saved = homework.getReport(reportId) ?: error("This homework report has been deleted.")
        return build(
            saved,
            classes.getClass(saved.report.classGroupId),
            subjects.observe().first(),
            settings.getSettings(),
            photoStore,
        )
    }

    suspend fun build(
        saved: HomeworkReportWithItems,
        group: ClassGroup?,
        subjects: List<Subject>,
        settings: SchoolSettings?,
        photoStore: PhotoStore?,
    ): PdfDoc {
        val logo = photoStore?.let { RegisterPdf.loadBitmap(it, settings?.logoFile, 256) }
        val photo = photoStore?.let { RegisterPdf.loadBitmap(it, saved.report.photoFile, PHOTO_MAX_PX) }
        return build(saved, group, subjects, settings, logo, photo)
    }

    /** [photo] is the decoded diary photo; null for a typed report (or when the file is missing). */
    fun build(
        saved: HomeworkReportWithItems,
        group: ClassGroup?,
        subjects: List<Subject>,
        settings: SchoolSettings?,
        logo: Bitmap?,
        photo: Bitmap?,
    ): PdfDoc {
        val report = saved.report
        val school = settings?.schoolName?.takeIf { it.isNotBlank() } ?: "School"
        val doc = PdfDoc(PageOrientation.PORTRAIT, footerLeft = school, footerRight = DOCUMENT_LABEL)
        doc.header(
            schoolName = school,
            eyebrow = "Daily student diary",
            title = TITLE,
            subtitle = "Learning for today, prepared for home",
            logo = logo,
        )
        doc.keyValues(
            listOf(
                "Class" to (group?.className?.ifBlank { null } ?: "—"),
                "Section" to (group?.section?.ifBlank { null } ?: "—"),
                "Date" to longDateLabel(report.date),
                "Incharge" to report.inchargeName.ifBlank { "—" },
            ),
        )

        when {
            photo != null -> {
                doc.sectionTitle("Photographed diary")
                diaryPhoto(doc, photo)
            }
            report.photoFile != null -> doc.text("The diary photo could not be loaded.", PdfTextStyle(9f, color = PdfColors.Muted))
            else -> {
                val names = subjects.associate { it.id to it.name }
                val items = saved.items.sortedBy { it.position }
                doc.spacer(4f)
                doc.table(
                    PdfTable(
                        columns = listOf(PdfColumn("Subject", weight = 0.28f), PdfColumn("Homework / Note", weight = 0.72f)),
                        rows = items.mapIndexed { index, item ->
                            PdfRow(
                                listOf(
                                    PdfCell("${index + 1}.  ${names[item.subjectId] ?: "Subject"}", bold = true),
                                    PdfCell(item.details),
                                ),
                            )
                        },
                        fontSize = 9.5f,
                        cellPadding = 6f,
                        zebra = false,
                    ),
                )
                if (items.isEmpty()) doc.text("No homework was written for this day.", PdfTextStyle(9f, color = PdfColors.Muted))
            }
        }

        // The note for parents, as a tinted box.
        doc.spacer(4f)
        doc.ensureSpace(48f)
        doc.text("NOTE FOR PARENTS", PdfTextStyle(8f, bold = true, color = PdfColors.Accent), spacingAfter = 3f)
        doc.table(
            PdfTable(
                columns = listOf(PdfColumn("", weight = 1f)),
                rows = listOf(PdfRow(listOf(PdfCell(report.parentNote.ifBlank { "—" })), fill = NoteFill)),
                fontSize = 9.5f,
                cellPadding = 8f,
                zebra = false,
                headerCells = emptyList(),
            ),
        )

        doc.signatures(
            listOf(
                "Parent / guardian signature" to null,
                "Class incharge signature" to report.inchargeName.ifBlank { null },
            ),
        )
        return doc
    }

    /** Draws [photo] as wide as the page allows, at most [PHOTO_MAX_HEIGHT] tall, centred, with a thin border. */
    private fun diaryPhoto(doc: PdfDoc, photo: Bitmap) {
        val scale = minOf(doc.contentWidth / photo.width, PHOTO_MAX_HEIGHT / photo.height)
        val width = photo.width * scale
        val height = photo.height * scale
        doc.ensureSpace(height)
        val x = doc.left + (doc.contentWidth - width) / 2
        val top = doc.y
        val rect = RectF(x, top, x + width, top + height)
        doc.canvas.drawBitmap(photo, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        doc.canvas.drawRoundRect(
            rect,
            4f,
            4f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.6f
                color = PdfColors.Rule
            },
        )
        doc.spacer(height + 10f)
    }
}

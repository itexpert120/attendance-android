package com.itexpert120.attendanceManager.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.OutputStream

/** A4 in PostScript points (1/72 inch), which is what [PdfDocument] measures pages in. */
enum class PageOrientation(val width: Int, val height: Int) {
    PORTRAIT(595, 842),
    LANDSCAPE(842, 595),
}

enum class PdfAlign { START, CENTER, END }

data class PdfTextStyle(
    val size: Float = 10f,
    val bold: Boolean = false,
    val color: Int = PdfColors.Ink,
)

/** Print colours: black ink on white paper whatever the app theme is, with light tints for status cells. */
object PdfColors {
    const val Ink = 0xFF1B1B1F.toInt()
    const val Muted = 0xFF5F6168.toInt()
    const val Rule = 0xFFBFC1C8.toInt()
    const val HeaderFill = 0xFFEDEEF2.toInt()
    const val ZebraFill = 0xFFF7F7F9.toInt()
    const val Accent = 0xFF2E6B3F.toInt()
    const val PresentFill = 0xFFDDF2E1.toInt()
    const val AbsentFill = 0xFFF9DEDC.toInt()
    const val LeaveFill = 0xFFFBEFD0.toInt()
    const val HolidayFill = 0xFFE4E5EA.toInt()
}

data class PdfColumn(
    val header: String,
    val weight: Float = 1f,
    val align: PdfAlign = PdfAlign.START,
    /** A fixed width in points; when set, [weight] is ignored. */
    val width: Float? = null,
)

data class PdfCell(
    val text: String,
    val bold: Boolean = false,
    val fill: Int? = null,
    val align: PdfAlign? = null,
    val color: Int? = null,
    /** A picture (a student photo) drawn instead of [text], fitted into a square of at most [imageSize] points. */
    val image: Bitmap? = null,
    val imageSize: Float = 28f,
)

/**
 * A header cell that may span several columns ([span]) and several header rows ([rowSpan]), like
 * `colspan` / `rowspan` in the PWA's print tables. Columns already covered by a row-spanning cell above
 * are skipped automatically when the next header row is laid out.
 */
data class PdfHeaderCell(
    val text: String,
    val span: Int = 1,
    val rowSpan: Int = 1,
    val fill: Int? = null,
    /** Defaults to the column's alignment for single-column cells and to centred for spanning ones. */
    val align: PdfAlign? = null,
    val color: Int? = null,
)

data class PdfRow(val cells: List<PdfCell>, val fill: Int? = null) {
    constructor(vararg texts: String) : this(texts.map { PdfCell(it) })
}

data class PdfTable(
    val columns: List<PdfColumn>,
    val rows: List<PdfRow>,
    val fontSize: Float = 9f,
    val cellPadding: Float = 4f,
    /** Rows of column headers; more than one lets a register show day numbers above weekday letters. */
    val headerRows: List<List<String>>? = null,
    val zebra: Boolean = true,
    /** Optional footer row (totals) drawn bold at the end of the table. */
    val totals: PdfRow? = null,
    /** Spanning header cells; when set they replace [headerRows]. An empty list draws no header at all. */
    val headerCells: List<List<PdfHeaderCell>>? = null,
)

/**
 * A small flowing-layout writer over [PdfDocument]: blocks are appended top to bottom, pages break
 * automatically and tables repeat their header on each page. Every page gets a footer with the school name,
 * the document label and the page number. Not thread-safe; build one document on one coroutine.
 */
class PdfDoc(
    val orientation: PageOrientation,
    private val footerLeft: String,
    private val footerRight: String,
    private val margin: Float = if (orientation == PageOrientation.LANDSCAPE) 28f else 36f,
) {
    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private val footerHeight = 18f

    /** The vertical cursor on the current page. */
    var y: Float = 0f
        private set

    val left: Float get() = margin
    val contentWidth: Float get() = orientation.width - 2 * margin
    private val bottom: Float get() = orientation.height - margin - footerHeight

    val canvas: Canvas
        get() {
            if (page == null) newPage()
            return page!!.canvas
        }

    val pageCount: Int get() = pageNumber

    fun newPage() {
        finishPage()
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(orientation.width, orientation.height, pageNumber).create())
        y = margin
    }

    /** Starts a new page unless [height] more points fit on this one. */
    fun ensureSpace(height: Float) {
        if (page == null) newPage()
        if (y + height > bottom && y > margin + 0.5f) newPage()
    }

    fun spacer(height: Float) {
        if (page == null) newPage()
        y = (y + height).coerceAtMost(bottom)
    }

    fun text(
        text: String,
        style: PdfTextStyle = PdfTextStyle(),
        align: PdfAlign = PdfAlign.START,
        spacingAfter: Float = 4f,
    ) {
        if (text.isEmpty()) return
        val layout = layout(text, style, contentWidth, align)
        // Long paragraphs flow over pages line by line.
        var line = 0
        while (line < layout.lineCount) {
            ensureSpace((layout.getLineBottom(line) - layout.getLineTop(line)).toFloat())
            val available = bottom - y
            var last = line
            while (last + 1 < layout.lineCount &&
                layout.getLineBottom(last + 1) - layout.getLineTop(line) <= available
            ) last++
            val top = layout.getLineTop(line).toFloat()
            val height = layout.getLineBottom(last) - top
            canvas.save()
            canvas.clipRect(left, y, left + contentWidth, y + height)
            canvas.translate(left, y - top)
            layout.draw(canvas)
            canvas.restore()
            y += height
            line = last + 1
        }
        y += spacingAfter
    }

    /** The document banner: optional logo, a small eyebrow, the title and the school line, with a rule. */
    fun header(schoolName: String, eyebrow: String, title: String, subtitle: String? = null, logo: Bitmap? = null) {
        ensureSpace(64f)
        val logoSize = 44f
        var textLeft = left
        if (logo != null) {
            val scale = minOf(logoSize / logo.width, logoSize / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            canvas.drawBitmap(logo, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            textLeft = left + logoSize + 12f
        }
        val width = contentWidth - (textLeft - left)
        val startY = y
        var cursor = y
        cursor += drawLayout(eyebrow.uppercase(), PdfTextStyle(8f, bold = true, color = PdfColors.Accent), textLeft, cursor, width)
        cursor += 1f
        cursor += drawLayout(title, PdfTextStyle(16f, bold = true), textLeft, cursor, width)
        cursor += 1f
        cursor += drawLayout(listOfNotNull(schoolName, subtitle).joinToString(" · "), PdfTextStyle(9f, color = PdfColors.Muted), textLeft, cursor, width)
        y = maxOf(cursor, if (logo != null) startY + logoSize else cursor) + 8f
        canvas.drawLine(left, y, left + contentWidth, y, rulePaint(1f, PdfColors.Ink))
        y += 10f
    }

    /** Label/value pairs in a grid of [columns], like the PWA's `PrintKeyValue`. */
    fun keyValues(items: List<Pair<String, String>>, columns: Int = 4) {
        if (items.isEmpty()) return
        val cellWidth = contentWidth / columns
        items.chunked(columns).forEach { rowItems ->
            val heights = rowItems.map { (key, value) ->
                layout(key, PdfTextStyle(7.5f, color = PdfColors.Muted), cellWidth - 8f).height +
                    layout(value, PdfTextStyle(10f, bold = true), cellWidth - 8f).height
            }
            val rowHeight = (heights.maxOrNull() ?: 0).toFloat() + 6f
            ensureSpace(rowHeight)
            rowItems.forEachIndexed { index, (key, value) ->
                val x = left + index * cellWidth
                val keyHeight = drawLayout(key, PdfTextStyle(7.5f, color = PdfColors.Muted), x, y, cellWidth - 8f)
                drawLayout(value, PdfTextStyle(10f, bold = true), x, y + keyHeight, cellWidth - 8f)
            }
            y += rowHeight
        }
        y += 4f
    }

    /** A row of big numbers with labels underneath, in boxes, like the PWA's `PrintStats`. */
    fun stats(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val gap = 6f
        val boxWidth = (contentWidth - gap * (items.size - 1)) / items.size
        val height = 40f
        ensureSpace(height)
        items.forEachIndexed { index, (label, value) ->
            val x = left + index * (boxWidth + gap)
            val box = RectF(x, y, x + boxWidth, y + height)
            canvas.drawRoundRect(box, 4f, 4f, fillPaint(PdfColors.HeaderFill))
            val valueHeight = drawLayout(value, PdfTextStyle(13f, bold = true), x + 6f, y + 5f, boxWidth - 12f)
            drawLayout(label, PdfTextStyle(7.5f, color = PdfColors.Muted), x + 6f, y + 5f + valueHeight, boxWidth - 12f)
        }
        y += height + 8f
    }

    fun sectionTitle(text: String) {
        ensureSpace(30f)
        y += 4f
        text(text, PdfTextStyle(11f, bold = true), spacingAfter = 4f)
    }

    fun table(table: PdfTable) {
        val widths = columnWidths(table.columns)
        val headerRows = table.headerRows ?: listOf(table.columns.map { it.header })
        val headerStyle = PdfTextStyle(table.fontSize, bold = true)
        val grid = table.headerCells?.let { HeaderGrid(it, widths, table, headerStyle) }

        fun drawHeader() {
            if (grid != null) {
                grid.draw()
                return
            }
            headerRows.forEach { texts ->
                val cells = texts.map { PdfCell(it, bold = true, fill = PdfColors.HeaderFill) }
                val h = rowHeight(cells, widths, table, headerStyle)
                ensureSpace(h)
                drawRow(cells, widths, table, h, null, headerStyle)
            }
        }

        if (page == null) newPage()
        // Keep the header with at least one body row.
        val firstRowHeight = table.rows.firstOrNull()?.let { rowHeight(it.cells, widths, table, PdfTextStyle(table.fontSize)) } ?: 0f
        val headerHeight = grid?.height ?: headerRows.sumOf { texts ->
            rowHeight(texts.map { PdfCell(it, bold = true) }, widths, table, headerStyle).toDouble()
        }.toFloat()
        ensureSpace(headerHeight + firstRowHeight)
        drawHeader()
        val all = table.rows.mapIndexed { i, row -> row to (if (table.zebra && i % 2 == 1) PdfColors.ZebraFill else null) } +
            listOfNotNull(table.totals?.let { totals -> totals.copy(cells = totals.cells.map { it.copy(bold = true) }) to PdfColors.HeaderFill })
        all.forEach { (row, zebra) ->
            val style = PdfTextStyle(table.fontSize)
            val h = rowHeight(row.cells, widths, table, style)
            if (y + h > bottom) {
                newPage()
                drawHeader()
            }
            drawRow(row.cells, widths, table, h, row.fill ?: zebra, style)
        }
        y += 8f
    }

    /** Signature lines on the right, like the PWA's `PrintSignature`. */
    fun signatures(signers: List<Pair<String, String?>>) {
        if (signers.isEmpty()) return
        val width = 150f
        val gap = 40f
        ensureSpace(70f)
        y += 36f
        var x = left + contentWidth - signers.size * width - (signers.size - 1) * gap
        signers.forEach { (label, name) ->
            canvas.drawLine(x, y, x + width, y, rulePaint(0.8f, PdfColors.Ink))
            val h = drawLayout(label, PdfTextStyle(8.5f, bold = true), x, y + 3f, width, PdfAlign.CENTER)
            if (!name.isNullOrBlank()) drawLayout(name, PdfTextStyle(8f, color = PdfColors.Muted), x, y + 3f + h, width, PdfAlign.CENTER)
            x += width + gap
        }
        y += 30f
    }

    fun writeTo(out: OutputStream) {
        if (page == null) newPage()
        finishPage()
        document.writeTo(out)
    }

    fun close() = document.close()

    // ---- internals ----

    private fun finishPage() {
        val current = page ?: return
        val c = current.canvas
        val footerY = orientation.height - margin - footerHeight + 6f
        c.drawLine(left, footerY, left + contentWidth, footerY, rulePaint(0.5f, PdfColors.Rule))
        val style = PdfTextStyle(7.5f, color = PdfColors.Muted)
        drawLayoutOn(c, footerLeft, style, left, footerY + 4f, contentWidth / 2, PdfAlign.START)
        drawLayoutOn(c, "$footerRight · Page $pageNumber", style, left + contentWidth / 2, footerY + 4f, contentWidth / 2, PdfAlign.END)
        document.finishPage(current)
        page = null
    }

    private fun columnWidths(columns: List<PdfColumn>): FloatArray {
        val fixed = columns.sumOf { (it.width ?: 0f).toDouble() }.toFloat()
        val weights = columns.filter { it.width == null }.sumOf { it.weight.toDouble() }.toFloat()
        val flexible = (contentWidth - fixed).coerceAtLeast(0f)
        return FloatArray(columns.size) { i ->
            columns[i].width ?: if (weights == 0f) 0f else flexible * columns[i].weight / weights
        }
    }

    /**
     * Places spanning header cells on the column grid: each cell takes the next free column of its row,
     * skipping columns still covered by a row-spanning cell from a row above.
     */
    private inner class HeaderGrid(
        rows: List<List<PdfHeaderCell>>,
        private val widths: FloatArray,
        private val table: PdfTable,
        private val style: PdfTextStyle,
    ) {
        private inner class Placed(val cell: PdfHeaderCell, val row: Int, val col: Int, val span: Int, val rowSpan: Int)

        private val placed = ArrayList<Placed>()
        private val starts = FloatArray(widths.size + 1).also { s -> widths.forEachIndexed { i, w -> s[i + 1] = s[i] + w } }
        private val rowTops: FloatArray

        val height: Float get() = rowTops.last()

        init {
            val columns = widths.size
            val covered = IntArray(columns)
            rows.forEachIndexed { r, cells ->
                var col = 0
                for (cell in cells) {
                    while (col < columns && covered[col] > 0) col++
                    if (col >= columns) break
                    val span = cell.span.coerceIn(1, columns - col)
                    val rowSpan = cell.rowSpan.coerceIn(1, rows.size - r)
                    placed += Placed(cell, r, col, span, rowSpan)
                    for (c in col until col + span) covered[c] = rowSpan
                    col += span
                }
                for (c in 0 until columns) if (covered[c] > 0) covered[c]--
            }
            val minHeight = layout("0", style, 100f).height + 2 * table.cellPadding * 0.6f
            val heights = FloatArray(rows.size) { minHeight }
            placed.filter { it.rowSpan == 1 }.forEach { p -> heights[p.row] = maxOf(heights[p.row], cellHeight(p)) }
            // A row-spanning cell taller than the rows it covers grows the last of them.
            placed.filter { it.rowSpan > 1 }.forEach { p ->
                val covering = (p.row until p.row + p.rowSpan).sumOf { heights[it].toDouble() }.toFloat()
                val needed = cellHeight(p)
                if (needed > covering) heights[p.row + p.rowSpan - 1] += needed - covering
            }
            rowTops = FloatArray(rows.size + 1).also { t -> heights.forEachIndexed { i, h -> t[i + 1] = t[i] + h } }
        }

        private fun innerWidth(p: Placed) = (starts[p.col + p.span] - starts[p.col] - 2 * table.cellPadding).coerceAtLeast(1f)

        private fun cellHeight(p: Placed) = layout(p.cell.text, style, innerWidth(p)).height + 2 * table.cellPadding * 0.6f

        fun draw() {
            if (placed.isEmpty()) return
            ensureSpace(height)
            val border = rulePaint(0.4f, PdfColors.Rule)
            placed.forEach { p ->
                val x = left + starts[p.col]
                val top = y + rowTops[p.row]
                val rect = RectF(x, top, left + starts[p.col + p.span], y + rowTops[p.row + p.rowSpan])
                canvas.drawRect(rect, fillPaint(p.cell.fill ?: PdfColors.HeaderFill))
                canvas.drawRect(rect, border)
                val align = p.cell.align
                    ?: if (p.span == 1) table.columns.getOrNull(p.col)?.align ?: PdfAlign.START else PdfAlign.CENTER
                val textStyle = style.copy(color = p.cell.color ?: style.color)
                val innerW = innerWidth(p)
                val textH = layout(p.cell.text, textStyle, innerW).height
                drawLayout(p.cell.text, textStyle, x + table.cellPadding, top + (rect.height() - textH) / 2, innerW, align)
            }
            y += height
        }
    }

    private fun imageSide(cell: PdfCell, innerW: Float): Float = minOf(cell.imageSize, innerW)

    private fun rowHeight(cells: List<PdfCell>, widths: FloatArray, table: PdfTable, base: PdfTextStyle): Float {
        var max = 0f
        cells.forEachIndexed { i, cell ->
            if (i >= widths.size) return@forEachIndexed
            val w = (widths[i] - 2 * table.cellPadding).coerceAtLeast(1f)
            val h = if (cell.image != null) imageSide(cell, w)
            else layout(cell.text, base.copy(bold = cell.bold || base.bold), w).height.toFloat()
            max = maxOf(max, h)
        }
        return max + 2 * table.cellPadding * 0.6f
    }

    private fun drawRow(cells: List<PdfCell>, widths: FloatArray, table: PdfTable, height: Float, rowFill: Int?, base: PdfTextStyle) {
        var x = left
        val border = rulePaint(0.4f, PdfColors.Rule)
        cells.forEachIndexed { i, cell ->
            if (i >= widths.size) return@forEachIndexed
            val w = widths[i]
            val rect = RectF(x, y, x + w, y + height)
            (cell.fill ?: rowFill)?.let { canvas.drawRect(rect, fillPaint(it)) }
            canvas.drawRect(rect, border)
            val innerW = (w - 2 * table.cellPadding).coerceAtLeast(1f)
            val image = cell.image
            if (image != null) {
                val side = imageSide(cell, innerW)
                // Centre-crop to a square, like the PWA's object-cover thumbnails.
                val crop = minOf(image.width, image.height)
                val src = android.graphics.Rect((image.width - crop) / 2, (image.height - crop) / 2, (image.width + crop) / 2, (image.height + crop) / 2)
                val dx = x + (w - side) / 2
                val dy = y + (height - side) / 2
                canvas.drawBitmap(image, src, RectF(dx, dy, dx + side, dy + side), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                val align = cell.align ?: table.columns.getOrNull(i)?.align ?: PdfAlign.START
                val style = base.copy(bold = cell.bold || base.bold, color = cell.color ?: base.color)
                val textH = layout(cell.text, style, innerW).height
                drawLayout(cell.text, style, x + table.cellPadding, y + (height - textH) / 2, innerW, align)
            }
            x += w
        }
        y += height
    }

    private fun drawLayout(text: String, style: PdfTextStyle, x: Float, top: Float, width: Float, align: PdfAlign = PdfAlign.START): Float =
        drawLayoutOn(canvas, text, style, x, top, width, align)

    private fun drawLayoutOn(c: Canvas, text: String, style: PdfTextStyle, x: Float, top: Float, width: Float, align: PdfAlign): Float {
        if (text.isEmpty()) return 0f
        val layout = layout(text, style, width, align)
        c.save()
        c.translate(x, top)
        layout.draw(c)
        c.restore()
        return layout.height.toFloat()
    }

    private val paints = HashMap<PdfTextStyle, TextPaint>()

    private fun paint(style: PdfTextStyle): TextPaint = paints.getOrPut(style) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = style.size
            color = style.color
            typeface = if (style.bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        }
    }

    private fun layout(text: String, style: PdfTextStyle, width: Float, align: PdfAlign = PdfAlign.START): StaticLayout {
        val alignment = when (align) {
            PdfAlign.START -> Layout.Alignment.ALIGN_NORMAL
            PdfAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            PdfAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint(style), width.toInt().coerceAtLeast(1))
            .setAlignment(alignment)
            .setIncludePad(false)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(if (width < 40f) 2 else Int.MAX_VALUE)
            .build()
    }

    private fun rulePaint(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = width
        this.color = color
        style = Paint.Style.STROKE
    }

    private fun fillPaint(color: Int) = Paint().apply {
        this.color = color
        style = Paint.Style.FILL
    }

    companion object {
        /** Opaque white, for callers drawing their own content on [canvas]. */
        const val Paper = Color.WHITE
    }
}

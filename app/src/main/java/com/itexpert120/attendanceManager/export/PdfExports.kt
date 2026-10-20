package com.itexpert120.attendanceManager.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Where generated PDFs live and what can be done with one: print through the system print service, share
 * through the share sheet, or copy to a document the user picked with the Storage Access Framework.
 * Files go to `cache/exports/`, which `res/xml/file_paths.xml` exposes to [FileProvider].
 */
object PdfExports {
    private const val DIR = "exports"

    fun exportsDir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /** Renders [doc] to `cache/exports/[fileName]` and closes it. */
    suspend fun write(context: Context, fileName: String, doc: PdfDoc): File = withContext(Dispatchers.IO) {
        val dir = exportsDir(context)
        // Old exports are only useful until shared; keep the folder from growing forever.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val file = File(dir, safeFileName(fileName))
        try {
            FileOutputStream(file).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        file
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Opens the share sheet. Returns false when nothing can receive a PDF. */
    fun share(context: Context, file: File, title: String): Boolean {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title, uri)
        val chooser = Intent.createChooser(send, title)
        if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Hands the file to the system print dialog (which also offers "Save as PDF"). Needs an Activity. */
    fun print(context: Context, file: File, jobName: String, orientation: PageOrientation): Boolean {
        val activity = context.findActivity() ?: return false
        val manager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        val attributes = PrintAttributes.Builder()
            .setMediaSize(
                if (orientation == PageOrientation.LANDSCAPE) PrintAttributes.MediaSize.ISO_A4.asLandscape()
                else PrintAttributes.MediaSize.ISO_A4.asPortrait(),
            )
            .build()
        manager.print(jobName, FilePrintAdapter(file, jobName), attributes)
        return true
    }

    /** Copies the PDF into a document created through `ActivityResultContracts.CreateDocument`. */
    suspend fun copyTo(context: Context, file: File, target: Uri) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(target, "w") ?: error("Could not open the chosen file.")
        out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    }

    fun safeFileName(name: String): String {
        val base = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-").trim().ifEmpty { "export" }
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }
}

/** Streams an already-generated PDF to the print framework; the page count is left to the framework. */
private class FilePrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(PdfExports.safeFileName(name))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
            }
            if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled()
            else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

package com.itexpert120.attendanceManager.ui.components

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.export.PageOrientation
import com.itexpert120.attendanceManager.export.PdfDoc
import com.itexpert120.attendanceManager.export.PdfExports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One PDF to produce. [render] runs off the main thread and returns a filled [PdfDoc]; it may load data
 * from repositories. [fileName] is the suggested name for Save and the share subject.
 */
class PdfRequest(
    val title: String,
    val fileName: String,
    val render: suspend (Context) -> PdfDoc,
)

private sealed interface ExportPhase {
    data object Working : ExportPhase
    data class Ready(val file: File, val pages: Int, val orientation: PageOrientation) : ExportPhase
    data class Failed(val message: String) : ExportPhase
}

/** Holds the request the export sheet is showing; screens call [open] from an "Export" action. */
@Stable
class PdfExportState internal constructor() {
    internal var request by mutableStateOf<PdfRequest?>(null)

    fun open(request: PdfRequest) {
        this.request = request
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberPdfExportState(): PdfExportState = remember { PdfExportState() }

/**
 * The export sheet: generates the PDF, then offers Print (system print dialog), Share (share sheet) and
 * Save (Storage Access Framework). Place it once in a screen; it shows itself when [state] has a request.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PdfExportSheet(state: PdfExportState) {
    val request = state.request ?: return
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var phase by remember(request) { mutableStateOf<ExportPhase>(ExportPhase.Working) }
    var notice by remember(request) { mutableStateOf<String?>(null) }

    LaunchedEffect(request) {
        phase = try {
            val doc = withContext(Dispatchers.Default) { request.render(context) }
            val pages = doc.pageCount.coerceAtLeast(1)
            val orientation = doc.orientation
            val file = PdfExports.write(context, request.fileName, doc)
            ExportPhase.Ready(file, pages, orientation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExportPhase.Failed(e.message ?: "The PDF could not be created.")
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val ready = phase as? ExportPhase.Ready
        if (uri != null && ready != null) {
            scope.launch {
                notice = try {
                    PdfExports.copyTo(context, ready.file, uri)
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    "Saved."
                } catch (e: Exception) {
                    "Could not save: ${e.message ?: "unknown error"}"
                }
            }
        }
    }

    AppSheet(
        title = request.title,
        onDismiss = state::dismiss,
        description = when (val p = phase) {
            ExportPhase.Working -> "Preparing the PDF…"
            is ExportPhase.Ready -> "PDF ready · ${p.pages} ${if (p.pages == 1) "page" else "pages"} · A4 ${if (p.orientation == PageOrientation.LANDSCAPE) "landscape" else "portrait"}"
            is ExportPhase.Failed -> null
        },
        footer = { TextButton(onClick = state::dismiss) { Text("Done") } },
    ) {
        when (val p = phase) {
            ExportPhase.Working -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            is ExportPhase.Failed -> ErrorBanner(p.message)
            is ExportPhase.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListGroup {
                    item {
                        ListRow(
                            label = "Print",
                            detail = "Send to a printer or save as PDF from the print dialog",
                            icon = Icons.Rounded.Print,
                            onClick = {
                                if (!PdfExports.print(context, p.file, request.title, p.orientation)) notice = "Printing is not available on this device."
                            },
                        )
                    }
                    item {
                        ListRow(
                            label = "Share",
                            detail = "WhatsApp, email, Drive and other apps",
                            icon = Icons.Rounded.Share,
                            onClick = {
                                if (!PdfExports.share(context, p.file, request.title)) notice = "No app can receive a PDF."
                            },
                        )
                    }
                    item {
                        ListRow(
                            label = "Save to device",
                            detail = "Pick a folder in Files or Drive",
                            icon = Icons.Rounded.SaveAlt,
                            onClick = { saveLauncher.launch(PdfExports.safeFileName(request.fileName)) },
                        )
                    }
                }
                notice?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

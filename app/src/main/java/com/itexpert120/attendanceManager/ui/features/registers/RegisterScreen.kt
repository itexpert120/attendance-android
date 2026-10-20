package com.itexpert120.attendanceManager.ui.features.registers

import kotlinx.datetime.LocalDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.firstDate
import com.itexpert120.attendanceManager.domain.monthLabel
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.DelayedLoading
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.PdfExportSheet
import com.itexpert120.attendanceManager.ui.components.PdfRequest
import com.itexpert120.attendanceManager.ui.components.rememberPdfExportState
import com.itexpert120.attendanceManager.export.RegisterPdf
import kotlinx.coroutines.flow.map

private enum class RegisterSection(val label: String) {
    ATTENDANCE("Attendance"),
    FEES("Fees"),
    SUMMARY("Summary"),
    REPORTS("Reports"),
}

private sealed interface RegisterState {
    data object Loading : RegisterState
    data object Missing : RegisterState
    data class Ready(val data: RegisterData) : RegisterState
}

/**
 * Hosts one monthly register. It owns the live [RegisterData] and passes it to the four sections, each of
 * which is its own composable in this package so they can be built and changed independently.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RegisterScreen(
    registerId: Long,
    onBack: () -> Unit,
    /** Opens Students on this register's class; the date is the 1st of the register's month (new admissions). */
    onOpenStudents: (classGroupId: Long, defaultAdmittedOn: LocalDate?) -> Unit = { _, _ -> },
) {
    val container = LocalAppContainer.current
    val state by remember(registerId) {
        container.registers.observeRegister(registerId)
            .map<RegisterData?, RegisterState> { it?.let(RegisterState::Ready) ?: RegisterState.Missing }
    }.collectAsState(initial = RegisterState.Loading)
    var sectionIndex by rememberSaveable { mutableIntStateOf(0) }

    val ready = state as? RegisterState.Ready
    val title = ready?.data?.classGroup?.let { group ->
        if (group.section.isBlank()) group.className else "${group.className} · ${group.section}"
    } ?: ""
    val subtitle = ready?.data?.register?.let { monthLabel(it.month, it.year) }

    // One export sheet for the whole screen: the register (top bar, Fees, Summary) and the Reports printouts.
    val pdfExport = rememberPdfExportState()
    val openStudents: (RegisterData) -> Unit = { data ->
        onOpenStudents(data.register.classGroupId, data.register.firstDate)
    }
    val exportRegister: (RegisterData) -> Unit = { data ->
        pdfExport.open(PdfRequest(RegisterPdf.TITLE, RegisterPdf.fileName(data)) { RegisterPdf.build(data, container.photoStore) })
    }

    AppScreen(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        large = false,
        actions = {
            if (ready != null) {
                IconButton(onClick = { openStudents(ready.data) }) {
                    Icon(Icons.Rounded.Groups, contentDescription = "Students")
                }
                IconButton(onClick = { exportRegister(ready.data) }) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Export register PDF")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val tabs: @Composable () -> Unit = {
                RegisterSection.entries.forEachIndexed { index, section ->
                    Tab(
                        selected = index == sectionIndex,
                        onClick = { sectionIndex = index },
                        text = { Text(section.label, maxLines = 1, softWrap = false) },
                    )
                }
            }
            // Four fixed tabs fit at the default font size; with a large font they scroll instead of breaking words.
            if (LocalDensity.current.fontScale > 1.15f) {
                PrimaryScrollableTabRow(selectedTabIndex = sectionIndex, edgePadding = 0.dp, tabs = tabs)
            } else {
                PrimaryTabRow(selectedTabIndex = sectionIndex, tabs = tabs)
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (val current = state) {
                    RegisterState.Loading -> DelayedLoading()
                    RegisterState.Missing -> EmptyState(Icons.Rounded.ErrorOutline, "Register not found", text = "It may have been deleted.")
                    is RegisterState.Ready -> when (RegisterSection.entries[sectionIndex]) {
                        RegisterSection.ATTENDANCE -> AttendanceSection(
                            current.data,
                            Modifier.fillMaxSize(),
                            onOpenStudents = { openStudents(current.data) },
                        )
                        RegisterSection.FEES -> FeesSection(current.data, Modifier.fillMaxSize(), onExport = { exportRegister(current.data) })
                        RegisterSection.SUMMARY -> SummarySection(current.data, Modifier.fillMaxSize(), onExport = { exportRegister(current.data) })
                        RegisterSection.REPORTS -> ReportsSection(current.data, Modifier.fillMaxSize(), pdfExport = pdfExport)
                    }
                }
            }
        }
    }
    PdfExportSheet(pdfExport)
}

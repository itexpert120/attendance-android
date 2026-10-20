package com.itexpert120.attendanceManager.ui.features.registers

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.FeeField
import com.itexpert120.attendanceManager.domain.minorToDisplay
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.PageList
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme

/**
 * The monthly summary the printed register ends with, as cards: attendance, student movement, one card per
 * installment plus the all-installments total, and the signature boxes. As in the PWA's SummaryPanel, each
 * installment's rate and receiver can be edited here too (tap the card), with the same sheet as the Fees tab.
 *
 * [onExport] adds an "Export summary" button when non-null.
 */
@Composable
fun SummarySection(data: RegisterData, modifier: Modifier = Modifier, onExport: (() -> Unit)? = null) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { FeesViewModel(container.registers) }
    val ui by viewModel.state.collectAsState()
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is FeesEvent.InstallmentSaved) {
                editing = null
                snackbar.showSnackbar("Saved ${event.installment.longLabel}")
            }
        }
    }

    Box(modifier) {
        SummaryContent(
            data = data,
            modifier = Modifier.fillMaxSize(),
            onExport = onExport,
            onEditInstallment = {
                viewModel.clearError()
                editing = it.number
            },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }

    editing?.let(InstallmentNumber::fromNumber)?.let { installment ->
        InstallmentMetaSheet(
            installment = installment,
            data = data,
            saving = ui.saving,
            error = ui.error,
            onDismiss = {
                editing = null
                viewModel.clearError()
            },
            onSave = { form -> viewModel.saveInstallment(data.register.id, installment, form) },
        )
    }
}

/** The stateless summary, so previews can render it without a database. */
@Composable
internal fun SummaryContent(
    data: RegisterData,
    modifier: Modifier = Modifier,
    onExport: (() -> Unit)? = null,
    onEditInstallment: ((InstallmentNumber) -> Unit)? = null,
) {
    val figures = remember(data) { summaryFigures(data) }
    val currency = data.feeCurrency()

    PageList(padding = PaddingValues(0.dp), modifier = modifier) {
        item(key = "attendance") { AttendanceHero(figures) }
        item(key = "attendance-details") { AttendanceDetails(figures, hasSettings = data.settings != null) }
        item(key = "movement") { MovementGrid(figures) }
        item(key = "installments-header") {
            SectionHeader(
                "Installment collection",
                if (onEditInstallment != null) "Rates, receivers and totals for the month. Tap an installment to edit its rate and receiver." else "Rates, receivers and totals for the month.",
            )
        }
        InstallmentNumber.entries.forEach { installment ->
            item(key = "installment-${installment.number}") {
                InstallmentSummaryCard(data, installment, currency, onEdit = onEditInstallment?.let { edit -> { edit(installment) } })
            }
        }
        item(key = "all-installments") { AllInstallmentsCard(data, currency) }
        item(key = "sign-off") { SignOffCard(data) }
        if (onExport != null) {
            item(key = "export") { FeesExportButton(onExport, "Export summary") }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttendanceHero(figures: SummaryFigures) {
    AppCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Attendance this month", style = MaterialTheme.typography.labelLarge)
                Text(figures.monthAttendanceDays, style = MaterialTheme.typography.displaySmall)
                Text("days present, all students", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Average attendance", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
                Text(figures.averageAttendance, style = MaterialTheme.typography.headlineMedium)
                Text("per working day", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AttendanceDetails(figures: SummaryFigures, hasSettings: Boolean) {
    GroupCard(header = "Working days and totals") {
        SummaryRow("Working days", figures.workingDays, "Weekends and declared holidays are not counted")
        SummaryRow("Total timings", figures.workingTimings.toString(), "Two timings per working day")
        SummaryRow("Brought forward", figures.broughtForwardDays, "Present days in earlier months of this academic year")
        SummaryRow("Academic year to date", figures.academicYearDays, "Brought forward plus this month")
        if (!hasSettings) {
            Text(
                "Save the school settings to include brought-forward attendance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun MovementGrid(figures: SummaryFigures) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Student movement")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MovementTile(Icons.Rounded.Groups, "At beginning", figures.beginning, Modifier.weight(1f))
            MovementTile(Icons.Rounded.School, "At end", figures.end, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MovementTile(Icons.Rounded.PersonAdd, "Admitted", figures.admitted, Modifier.weight(1f))
            MovementTile(Icons.Rounded.PersonRemove, "Struck off", figures.struckOff, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MovementTile(icon: ImageVector, label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InstallmentSummaryCard(
    data: RegisterData,
    installment: InstallmentNumber,
    currency: String,
    onEdit: (() -> Unit)?,
) {
    val meta = data.installmentMeta[installment]
    val rate = meta?.rate ?: 0
    val receiver = meta?.receiverName.orEmpty()
    val amounts = data.installmentTotals[installment] ?: FeeAmounts.EMPTY
    val students = data.installmentStudentCounts[installment] ?: 0
    val editable = if (onEdit != null) {
        Modifier.clickable(onClickLabel = "Edit rate and receiver", role = Role.Button, onClick = onEdit)
    } else {
        Modifier
    }
    AppCard(Modifier.clip(RoundedCornerShape(20.dp)).then(editable)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(installment.longLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    feeStudentCountLabel(students),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Collected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(feeMoney(amounts.total, currency), style = MaterialTheme.typography.titleMedium)
            }
        }
        SummaryRow("At rate of", if (rate > 0) feeMoney(rate, currency) else "Not set")
        SummaryRow("Receiver", receiver.ifBlank { "Not set" })
        if (onEdit != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit rate and receiver")
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        FeeBreakdown(amounts)
    }
}

@Composable
private fun AllInstallmentsCard(data: RegisterData, currency: String) {
    AppCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text("All installments", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${data.totalInstallmentStudents} student entries",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(feeMoney(data.installmentGrandTotals.total, currency), style = MaterialTheme.typography.titleMedium)
            }
        }
        SummaryRow("Total of the rates", feeMoney(data.totalInstallmentRate, currency))
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        FeeBreakdown(data.installmentGrandTotals)
    }
}

/** The PWA's two signature boxes: the receiver's and the class incharge's. */
@Composable
private fun SignOffCard(data: RegisterData) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Signatures")
        SignatureBox("Receiver\u2019s signature", "Sign after verifying installment totals")
        SignatureBox(
            "Class incharge\u2019s signature",
            data.settings?.classInchargeName?.ifBlank { null } ?: "Name and signature",
        )
    }
}

@Composable
private fun SignatureBox(title: String, caption: String) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(40.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface)
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The six fee categories as a two-column grid of label over amount. */
@Composable
private fun FeeBreakdown(amounts: FeeAmounts, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FeeField.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { field ->
                    Column(Modifier.weight(1f)) {
                        Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(minorToDisplay(field.valueIn(amounts)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** A titled card whose [content] is a stack of [SummaryRow]s. */
@Composable
private fun GroupCard(header: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(header)
        AppCard { content() }
    }
}

/** A label on the left (wrapping, so large fonts are safe) and its value on the right. */
@Composable
private fun SummaryRow(label: String, value: String, supporting: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End, modifier = Modifier.weight(0.6f, fill = false))
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 1400)
@Composable
private fun SummarySectionPreview() {
    AttendanceTheme(dynamicColor = false) {
        SummaryContent(SamplePreviewData.register(), onExport = {}, onEditInstallment = {})
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 1400, fontScale = 1.6f)
@Composable
private fun SummarySectionDarkLargeFontPreview() {
    AttendanceTheme(dynamicColor = false, darkTheme = true) {
        SummaryContent(SamplePreviewData.register())
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 900)
@Composable
private fun SummaryEmptyPreview() {
    AttendanceTheme(dynamicColor = false) {
        SummaryContent(SamplePreviewData.register(withStudents = false))
    }
}

package com.itexpert120.attendanceManager.ui.features.registers

import androidx.compose.foundation.layout.navigationBarsPadding
import com.itexpert120.attendanceManager.ui.components.navigationBarBottom
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.FeeField
import com.itexpert120.attendanceManager.domain.minorToDisplay
import com.itexpert120.attendanceManager.domain.shortDayMonthLabel
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.BadgeTone
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.ListGroup
import com.itexpert120.attendanceManager.ui.components.PageMaxWidth
import com.itexpert120.attendanceManager.ui.components.StatusBadge
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The Fees tab: month totals, the three installments' rate and receiver, and one card per student on the roll
 * (struck-off students included). Tapping a card opens that student's fee sheet.
 *
 * [onExport] is the extension point for the later "Export PDF / Print" action; while it is null no export
 * button is shown.
 */
@Composable
fun FeesSection(data: RegisterData, modifier: Modifier = Modifier, onExport: (() -> Unit)? = null) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { FeesViewModel(container.registers) }
    val ui by viewModel.state.collectAsState()
    FeesContent(
        data = data,
        ui = ui,
        events = viewModel.events,
        onSaveStudent = { card, form -> viewModel.saveStudent(data.register.id, card.enrollmentId, card.row.student.name, form) },
        onSaveInstallment = { installment, form -> viewModel.saveInstallment(data.register.id, installment, form) },
        onClearError = viewModel::clearError,
        modifier = modifier,
        onExport = onExport,
    )
}

/** Stateless Fees UI, so it can be previewed without a database. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FeesContent(
    data: RegisterData,
    ui: FeesUiState,
    events: Flow<FeesEvent>,
    onSaveStudent: (FeeCard, FeeForm) -> Unit,
    onSaveInstallment: (InstallmentNumber, InstallmentMetaForm) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
    onExport: (() -> Unit)? = null,
) {
    val currency = data.feeCurrency()
    val cards = remember(data) { feeCards(data) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FeeFilter.ALL) }
    var editingStudent by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingInstallment by rememberSaveable { mutableStateOf<Int?>(null) }
    val visible = remember(cards, query, filter) { cards.filtered(query, filter) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is FeesEvent.StudentSaved -> {
                    editingStudent = null
                    snackbar.showSnackbar("Saved fees for ${event.studentName}")
                }
                is FeesEvent.InstallmentSaved -> {
                    editingInstallment = null
                    snackbar.showSnackbar("Saved ${event.installment.longLabel}")
                }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = PageMaxWidth).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp + navigationBarBottom()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "totals") { MonthTotalsCard(data, cards, currency) }
            item(key = "installments") {
                InstallmentsGroup(data, currency, onOpen = {
                    onClearError()
                    editingInstallment = it.number
                })
            }
            if (onExport != null) {
                item(key = "export") { FeesExportButton(onExport, "Export fee register") }
            }
            item(key = "search") {
                SearchAndFilters(
                    query = query,
                    onQueryChange = { query = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    cards = cards,
                )
            }
            when {
                cards.isEmpty() -> item(key = "empty") {
                    EmptyState(Icons.Rounded.Payments, "No students on the roll", text = "Students enrolled in this class for the month appear here.")
                }
                visible.isEmpty() -> item(key = "no-match") {
                    EmptyState(Icons.Rounded.SearchOff, "No matching students", text = "Try a different name, roll number or filter.")
                }
                else -> items(visible, key = { it.enrollmentId }) { card ->
                    FeeCardItem(card, currency, onClick = {
                        onClearError()
                        editingStudent = card.enrollmentId
                    })
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }

    val studentCard = editingStudent?.let { id -> cards.firstOrNull { it.enrollmentId == id } }
    if (studentCard != null) {
        StudentFeeSheet(
            card = studentCard,
            data = data,
            saving = ui.saving,
            error = ui.error,
            onDismiss = {
                editingStudent = null
                onClearError()
            },
            onSave = { form -> onSaveStudent(studentCard, form) },
        )
    }
    val installment = editingInstallment?.let(InstallmentNumber::fromNumber)
    if (installment != null) {
        InstallmentMetaSheet(
            installment = installment,
            data = data,
            saving = ui.saving,
            error = ui.error,
            onDismiss = {
                editingInstallment = null
                onClearError()
            },
            onSave = { form -> onSaveInstallment(installment, form) },
        )
    }
}

/** A compact export button at the end of the list; never stretched across the screen. */
@Composable
internal fun FeesExportButton(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onClick) {
            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
            Text(label)
        }
    }
}

@Composable
private fun MonthTotalsCard(data: RegisterData, cards: List<FeeCard>, currency: String) {
    val paid = cards.count { it.overall == FeeStatus.PAID }
    val partial = cards.count { it.overall == FeeStatus.PARTIAL }
    val none = cards.count { it.overall == FeeStatus.NONE }
    AppCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Month total", style = MaterialTheme.typography.labelLarge)
            Text(feeMoney(data.feeMonthTotals.total, currency), style = MaterialTheme.typography.headlineMedium)
            Text(
                "Across ${feeStudentCountLabel(cards.size)} on the roll",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (cards.isNotEmpty()) {
                Text(
                    "$paid paid · $partial partial · $none with no fees",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FeeCategoryGrid(data.feeMonthTotals, Modifier.padding(top = 12.dp))
        }
    }
}

/** The six fee categories in three columns, label over amount (the PWA's per-category totals). */
@Composable
internal fun FeeCategoryGrid(amounts: FeeAmounts, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeeField.entries.chunked(3).forEach { rowFields ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowFields.forEach { field ->
                    Column(
                        Modifier.weight(1f).semantics(mergeDescendants = true) {
                            contentDescription = "${field.label}: ${minorToDisplay(field.valueIn(amounts))}"
                        },
                    ) {
                        Text(field.shortLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Text(
                            minorToDisplay(field.valueIn(amounts)),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallmentsGroup(data: RegisterData, currency: String, onOpen: (InstallmentNumber) -> Unit) {
    ListGroup(
        header = "Installments",
        footer = "Tap an installment to set its rate per student and receiver.",
    ) {
        items(InstallmentNumber.entries) { installment ->
            val meta = data.installmentMeta[installment]
            val rate = meta?.rate ?: 0
            val receiver = meta?.receiverName.orEmpty()
            val details = buildList {
                add(feeStudentCountLabel(data.installmentStudentCounts[installment] ?: 0))
                add(if (rate > 0) "Rate ${feeMoney(rate, currency)}" else "No rate set")
                add(if (receiver.isNotBlank()) "Receiver $receiver" else "No receiver")
            }.joinToString(" · ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Edit ${installment.longLabel}", role = Role.Button) { onOpen(installment) }
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(installment.longLabel, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("Collected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        feeMoney(data.installmentTotals[installment]?.total ?: 0, currency),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchAndFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: FeeFilter,
    onFilterChange: (FeeFilter) -> Unit,
    cards: List<FeeCard>,
) {
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search students") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (query.isEmpty()) null else ({
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
            }),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.moveFocus(FocusDirection.Exit) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeeFilter.entries.forEach { option ->
                val count = cards.count { option.accepts(it.overall) }
                FilterChip(
                    selected = option == filter,
                    onClick = { onFilterChange(option) },
                    label = { Text("${option.label} ($count)") },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeeCardItem(card: FeeCard, currency: String, onClick: () -> Unit) {
    val student = card.row.student
    val summary = buildString {
        append("${student.name}, roll ${card.row.enrollment.rollNumber}, ")
        append(if (card.total > 0) "total ${feeMoney(card.total, currency)}, " else "no fees recorded, ")
        append(card.overall.label())
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = summary },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RollBadge(card.row.enrollment.rollNumber)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(student.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Admission ${student.admissionNumber}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    card.row.enrollment.struckOffOn?.let {
                        StatusBadge("Struck off ${shortDayMonthLabel(it)}", tone = BadgeTone.DANGER, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (card.total > 0) feeMoney(card.total, currency) else "—",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    StatusBadge(card.overall.label(), tone = card.overall.tone())
                }
            }
            feeCategoriesLine(card.categories)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InstallmentNumber.entries.forEach { installment ->
                    val status = card.statuses.getValue(installment)
                    StatusBadge(
                        "${installment.shortLabel} · ${status.shortWord()}",
                        tone = status.tone(),
                    )
                }
            }
            if (card.remark.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Notes,
                        contentDescription = "Remark",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        card.remark,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RollBadge(roll: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(roll, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private fun FeeStatus.shortWord(): String = when (this) {
    FeeStatus.PAID -> "Paid"
    FeeStatus.PARTIAL -> "Partial"
    FeeStatus.NONE -> "None"
}

@Preview(showBackground = true, widthDp = 380, heightDp = 900)
@Composable
private fun FeesSectionPreview() {
    AttendanceTheme(dynamicColor = false) {
        FeesContent(
            data = SamplePreviewData.register(),
            ui = FeesUiState(),
            events = emptyFlow(),
            onSaveStudent = { _, _ -> },
            onSaveInstallment = { _, _ -> },
            onClearError = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 700, fontScale = 1.6f)
@Composable
private fun FeesSectionLargeFontPreview() {
    AttendanceTheme(dynamicColor = false) {
        FeesContent(
            data = SamplePreviewData.register(),
            ui = FeesUiState(),
            events = emptyFlow(),
            onSaveStudent = { _, _ -> },
            onSaveInstallment = { _, _ -> },
            onClearError = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 380, heightDp = 500)
@Composable
private fun FeesEmptyPreview() {
    AttendanceTheme(dynamicColor = false, darkTheme = true) {
        FeesContent(
            data = SamplePreviewData.register(withStudents = false),
            ui = FeesUiState(),
            events = emptyFlow(),
            onSaveStudent = { _, _ -> },
            onSaveInstallment = { _, _ -> },
            onClearError = {},
        )
    }
}

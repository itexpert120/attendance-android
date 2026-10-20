package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.export.TestPdf
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.components.ListGroup
import com.itexpert120.attendanceManager.ui.components.ListRow
import com.itexpert120.attendanceManager.ui.components.PdfExportSheet
import com.itexpert120.attendanceManager.ui.components.PdfRequest
import com.itexpert120.attendanceManager.ui.components.rememberPdfExportState
import com.itexpert120.attendanceManager.ui.components.SegmentedChoice
import kotlinx.coroutines.launch

/** How results are typed: the compact list (default) or one big field per student. */
enum class EntryMode(val label: String) {
    LIST("List"),
    ONE_BY_ONE("One by one"),
}

/** Which bottom sheet of the Test detail screen is open; saved so a sheet survives rotation. */
private enum class DetailSheet { NONE, ACTIONS, EDIT, ROSTER, DELETE }

/** Lazy items above the first student row (summary, entry mode, hint); used to scroll a row into view. */
private const val LEADING_ITEMS = 3

/** One Test: details, roster and rapid result entry. */
@Composable
fun TestDetailScreen(testId: Long, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: TestDetailViewModel = viewModel(key = "test-$testId") {
        TestDetailViewModel(testId, container.tests, container.subjects, container.classes, container.students)
    }
    val state by viewModel.state.collectAsState()
    val rowErrors by viewModel.rowErrors.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val latestOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    // The Test is gone (deleted here or elsewhere, or never existed): leave exactly once.
    val notFound = state is TestDetailState.NotFound
    LaunchedEffect(notFound) { if (notFound) latestOnBack() }

    // The result sheet PDF is a snapshot of the roster on screen when Export is tapped.
    val pdfExport = rememberPdfExportState()
    val exportPdf = (state as? TestDetailState.Ready)?.data?.let { data ->
        {
            pdfExport.open(
                PdfRequest(TestPdf.testTitle(data.test), TestPdf.testFileName(data.test, data.group)) {
                    TestPdf.buildTest(data.test, data.subject, data.group, data.rows, container.settings.getSettings(), container.photoStore)
                },
            )
        }
    }

    TestDetailContent(state, rowErrors, viewModel, snackbar, onBack, onExportPdf = exportPdf)
    PdfExportSheet(pdfExport)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestDetailContent(
    state: TestDetailState,
    rowErrors: Map<Long, String>,
    actions: TestDetailActions,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onExportPdf: (() -> Unit)? = null,
) {
    val data = (state as? TestDetailState.Ready)?.data
    val rows = data?.rows.orEmpty()
    val statuses = remember(rows) { rows.map { it.result } }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    var entryMode by rememberSaveable { mutableStateOf(EntryMode.LIST) }
    var focusTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var currentId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sheet by rememberSaveable { mutableStateOf(DetailSheet.NONE) }
    val imeVisible = WindowInsets.isImeVisible

    fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    /** Moves the keyboard to the student at [index], scrolling the row in first when it isn't on screen. */
    fun focusRow(index: Int) {
        val id = rows[index].enrollment.id
        focusTargetId = id
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) {
            scope.launch { listState.animateScrollToItem(LEADING_ITEMS + index) }
        }
    }

    AppScreen(
        title = data?.test?.name ?: "Test",
        subtitle = data?.let { "${it.subject?.name ?: "Subject"} · ${classLabel(it.group)}" },
        onBack = onBack,
        actions = {
            if (data != null) {
                IconButton(onClick = { sheet = DetailSheet.EDIT }) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit test")
                }
                IconButton(onClick = { sheet = DetailSheet.ACTIONS }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Test actions")
                }
            }
        },
        floatingActionButton = {
            val pending = data != null && entryMode == EntryMode.LIST && data.summary.notEnteredCount > 0
            if (pending && !imeVisible) {
                ExtendedFloatingActionButton(
                    onClick = { nextPendingIndex(statuses)?.let(::focusRow) },
                    icon = { Icon(Icons.Rounded.EditNote, contentDescription = null) },
                    text = { Text("Next not entered") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (data == null) {
            TestLoading(padding)
            return@AppScreen
        }
        val totalMarks = data.test.totalMarks

        TestPage(padding, state = listState) {
            item(key = "summary") { TestSummaryCard(data) }
            if (rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.Groups,
                        title = "No students on this test",
                        text = "Add students of the class to the roster, then enter their results here.",
                        action = { Button(onClick = { sheet = DetailSheet.ROSTER }) { Text("Correct roster") } },
                    )
                }
                return@TestPage
            }
            item(key = "mode") {
                SegmentedChoice(
                    options = EntryMode.entries,
                    selected = entryMode,
                    optionLabel = { it.label },
                    onSelect = {
                        // Clearing focus saves whatever is typed before the field leaves the screen.
                        focusManager.clearFocus()
                        entryMode = it
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item(key = "hint") {
                Text(
                    "Blank is Not entered and 0 is a recorded result. Tap A to mark a student absent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            when (entryMode) {
                EntryMode.LIST -> itemsIndexed(rows, key = { _, row -> row.enrollment.id }) { index, row ->
                    val next = nextEntryIndex(statuses, index)
                    EntryRow(
                        row = row,
                        totalMarks = totalMarks,
                        hasNext = next != null,
                        requestFocus = focusTargetId == row.enrollment.id,
                        onFocusHandled = { focusTargetId = null },
                        onWrite = { write -> actions.saveResult(row.enrollment.id, write.input) },
                        onAdvance = { if (next != null) focusRow(next) else focusManager.clearFocus() },
                        serverError = rowErrors[row.enrollment.id],
                        modifier = Modifier.animateItem(),
                    )
                }
                EntryMode.ONE_BY_ONE -> item(key = "one-by-one") {
                    val shownId = rows.firstOrNull { it.enrollment.id == currentId }?.enrollment?.id
                        ?: rows[nextPendingIndex(statuses) ?: 0].enrollment.id
                    // Pin the student shown first, so saving their result doesn't jump to the next pending one.
                    LaunchedEffect(shownId) { if (currentId != shownId) currentId = shownId }
                    FocusEntry(
                        rows = rows,
                        totalMarks = totalMarks,
                        currentId = shownId,
                        onCurrentChange = { currentId = it },
                        onWrite = { write -> actions.saveResult(shownId, write.input) },
                        rowErrors = rowErrors,
                    )
                }
            }
            item(key = "footer") {
                Text(
                    "Results save automatically on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        val close = { sheet = DetailSheet.NONE }
        when (sheet) {
            DetailSheet.NONE -> Unit
            DetailSheet.ACTIONS -> TestActionsSheet(
                data = data,
                onDismiss = close,
                onEdit = { sheet = DetailSheet.EDIT },
                onRoster = { sheet = DetailSheet.ROSTER },
                onRefresh = {
                    close()
                    scope.launch {
                        when (val result = actions.refreshRoster()) {
                            is Attempt.Ok -> snackbar.showSnackbar("Roster refreshed from current enrollments")
                            is Attempt.Failed -> snackbar.showSnackbar(result.message)
                        }
                    }
                },
                onDelete = { sheet = DetailSheet.DELETE },
                onExportPdf = onExportPdf?.let { export ->
                    {
                        close()
                        export()
                    }
                },
            )
            DetailSheet.EDIT -> TestEditSheet(
                data = data,
                actions = actions,
                onDismiss = close,
                onSaved = {
                    close()
                    showMessage("Test updated")
                },
            )
            DetailSheet.ROSTER -> TestRosterSheet(data = data, actions = actions, onDismiss = close, onMessage = ::showMessage)
            DetailSheet.DELETE -> DeleteTestSheet(data = data, actions = actions, onDismiss = close)
        }
    }
}

/** Date, Total Marks and progress ("18 / 24 entered"), then the class average, highest and lowest marks. */
@Composable
private fun TestSummaryCard(data: TestDetailData) {
    val colors = MaterialTheme.colorScheme
    val test = data.test
    val summary = data.summary
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${shortDateLabel(test.date)} · ${formatMarks(test.totalMarks)} marks",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TestProgressBadge(summary.progress)
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text("${data.enteredCount}", style = MaterialTheme.typography.displaySmall)
                Text(
                    " / ${data.rows.size} entered",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            LinearProgressIndicator(
                progress = { progressFraction(data.enteredCount, data.rows.size) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "Average",
                    value = averagePercent(summary.average, test.totalMarks)?.let(::formatPercent) ?: "–",
                    detail = summary.average?.let { "${formatMarkValue(it)} marks" },
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Highest",
                    value = summary.highest?.let(::formatMarkValue) ?: "–",
                    detail = averagePercent(summary.highest, test.totalMarks)?.let(::formatPercent),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Lowest",
                    value = summary.lowest?.let(::formatMarkValue) ?: "–",
                    detail = averagePercent(summary.lowest, test.totalMarks)?.let(::formatPercent),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "${summary.numericCount} with marks · ${summary.absentCount} absent · ${summary.notEnteredCount} not entered",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, detail: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainer,
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
            Text(
                detail ?: " ",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The overflow menu as a sheet: export the result sheet, edit, correct or refresh the roster, delete. */
@Composable
private fun TestActionsSheet(
    data: TestDetailData,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRoster: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onExportPdf: (() -> Unit)? = null,
) {
    AppSheet(title = data.test.name, onDismiss = onDismiss) {
        ListGroup(muted = true) {
            if (onExportPdf != null) {
                item {
                    ListRow(
                        label = "Export PDF",
                        detail = "Result sheet to print or share",
                        icon = Icons.Rounded.PictureAsPdf,
                        onClick = onExportPdf,
                    )
                }
            }
            item {
                ListRow(
                    label = "Edit test",
                    detail = "Name, subject, date and total marks",
                    icon = Icons.Rounded.Edit,
                    onClick = onEdit,
                )
            }
            item {
                ListRow(
                    label = "Correct roster",
                    detail = "Add or remove students",
                    icon = Icons.Rounded.Groups,
                    onClick = onRoster,
                )
            }
            item {
                ListRow(
                    label = "Refresh roster",
                    detail = if (data.hasResults) "Locked after result entry begins" else "Rebuild from current enrollments",
                    icon = Icons.Rounded.Refresh,
                    enabled = !data.hasResults,
                    onClick = onRefresh,
                )
            }
            item {
                ListRow(
                    label = "Delete test",
                    detail = "Removes the test and its results",
                    icon = Icons.Rounded.Delete,
                    destructive = true,
                    onClick = onDelete,
                )
            }
        }
    }
}

/** Confirms deleting the Test and every recorded result; the screen leaves once the Test is gone. */
@Composable
private fun DeleteTestSheet(data: TestDetailData, actions: TestDetailActions, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val recorded = data.enteredCount

    AppSheet(
        title = "Delete test?",
        description = "“${data.test.name}” and its $recorded recorded result${if (recorded == 1) "" else "s"} " +
            "will be deleted. This cannot be undone.",
        onDismiss = onDismiss,
        footer = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                enabled = !deleting,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    error = null
                    deleting = true
                    scope.launch {
                        val result = actions.deleteTest()
                        if (result is Attempt.Failed) {
                            error = result.message
                            deleting = false
                        }
                    }
                },
            ) { Text("Delete test") }
        },
    ) {
        error?.let { ErrorBanner(it) }
    }
}

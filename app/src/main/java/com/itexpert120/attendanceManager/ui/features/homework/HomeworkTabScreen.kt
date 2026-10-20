package com.itexpert120.attendanceManager.ui.features.homework

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.repo.HomeworkReportWithItems
import com.itexpert120.attendanceManager.domain.dateLabel
import com.itexpert120.attendanceManager.export.HomeworkPdf
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.HomeworkItem
import com.itexpert120.attendanceManager.model.HomeworkReport
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.DelayedLoading
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.PageMaxWidth
import com.itexpert120.attendanceManager.ui.components.PdfExportSheet
import com.itexpert120.attendanceManager.ui.components.PdfRequest
import com.itexpert120.attendanceManager.ui.components.rememberPdfExportState
import com.itexpert120.attendanceManager.ui.components.StoredPhoto
import com.itexpert120.attendanceManager.ui.navigation.HomeworkTab
import com.itexpert120.attendanceManager.ui.navigation.TabReselectEffect
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private const val ALL_CLASSES = -1L
private const val ANY_DATE = Long.MIN_VALUE

/**
 * The Homework tab: saved Daily Homework Reports grouped by date, newest first, with a date filter chip and
 * class filter chips when the school has more than one class. Tapping a report opens it in the editor; the
 * FAB starts a new one; the app bar manages the shared Subjects.
 */
@Composable
fun HomeworkTabScreen(onNewReport: () -> Unit, onEditReport: (reportId: Long) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { HomeworkTabViewModel(container.homework, container.classes, container.subjects) }
    val data by viewModel.data.collectAsStateWithLifecycle()
    val pdfExport = rememberPdfExportState()
    HomeworkTabContent(
        data = data,
        subjectActions = viewModel,
        onNewReport = onNewReport,
        onEditReport = onEditReport,
        onExportReport = { item ->
            val group = data?.classes?.firstOrNull { it.id == item.classGroupId }
            pdfExport.open(
                PdfRequest(HomeworkPdf.TITLE, HomeworkPdf.fileName(group, item.date)) {
                    HomeworkPdf.load(item.id, container.homework, container.classes, container.subjects, container.settings, container.photoStore)
                },
            )
        },
    )
    PdfExportSheet(pdfExport)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeworkTabContent(
    data: HomeworkTabData?,
    onNewReport: () -> Unit,
    onEditReport: (reportId: Long) -> Unit,
    onExportReport: ((HomeworkCardItem) -> Unit)? = null,
    subjectActions: HomeworkSubjectActions? = null,
) {
    var selectedClass by rememberSaveable { mutableLongStateOf(ALL_CLASSES) }
    var dateDays by rememberSaveable { mutableLongStateOf(ANY_DATE) }
    var subjectsOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val today = remember { homeworkToday() }
    val dateFilter = dateDays.takeIf { it != ANY_DATE }?.let(LocalDate::fromEpochDays)
    TabReselectEffect(HomeworkTab) { listState.animateScrollToItem(0) }

    AppScreen(
        title = "Homework",
        subtitle = "Daily diaries for parents",
        actions = {
            if (data != null && subjectActions != null) {
                IconButton(onClick = { subjectsOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = "Manage subjects")
                }
            }
        },
        floatingActionButton = {
            if (data != null && data.classes.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onNewReport,
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New report") },
                )
            }
        },
    ) { padding ->
        if (data == null) {
            DelayedLoading(Modifier.padding(padding))
            return@AppScreen
        }
        val filter = effectiveClassFilter(selectedClass.takeIf { it != ALL_CLASSES }, data.classes)
        val groups = remember(data, filter, today, dateFilter) {
            buildHomeworkGroups(data.reports, data.classes, data.subjects, filter, today, dateFilter)
        }
        val onDate = remember(data.reports, dateFilter) { reportsOnDate(data.reports, dateFilter) }
        val counts = remember(onDate) { onDate.groupingBy { it.report.classGroupId }.eachCount() }

        HomeworkList(padding, listState) {
            if (data.classes.isNotEmpty() && data.reports.isNotEmpty()) {
                item(key = "chips") {
                    FilterChips(
                        classes = data.classes,
                        selected = filter,
                        counts = counts,
                        total = onDate.size,
                        date = dateFilter,
                        onSelect = { selectedClass = it ?: ALL_CLASSES },
                        onDateChange = { dateDays = it?.toEpochDays() ?: ANY_DATE },
                    )
                }
            }
            when {
                data.classes.isEmpty() -> item(key = "no-classes") {
                    EmptyState(
                        icon = Icons.Rounded.Groups,
                        title = "No classes yet",
                        text = "Add a class in Students, then write its daily homework here.",
                    )
                }
                data.reports.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                        title = "No homework reports",
                        text = "Write today's diary once and share it with every parent as a PDF.",
                        action = { Button(onClick = onNewReport) { Text("New report") } },
                    )
                }
                groups.isEmpty() -> item(key = "empty-filter") {
                    EmptyState(
                        icon = Icons.Rounded.FilterAlt,
                        title = "No homework reports",
                        text = "Clear the filters or create today’s diary.",
                        action = {
                            TextButton(
                                onClick = {
                                    selectedClass = ALL_CLASSES
                                    dateDays = ANY_DATE
                                },
                            ) { Text("Clear filters") }
                        },
                    )
                }
                else -> groups.forEach { group ->
                    item(key = "date-${group.date}") {
                        Text(
                            group.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
                                .semantics { heading() },
                        )
                    }
                    items(group.reports, key = { it.id }) { report ->
                        HomeworkReportCard(
                            report,
                            onClick = { onEditReport(report.id) },
                            onExport = onExportReport?.let { export -> { export(report) } },
                        )
                    }
                }
            }
        }

        if (subjectsOpen && subjectActions != null) {
            HomeworkSubjectsSheet(
                subjects = data.subjects,
                reportCounts = remember(data.reports) { homeworkReportCountBySubject(data.reports) },
                actions = subjectActions,
                onDismiss = { subjectsOpen = false },
            )
        }
    }
}

@Composable
private fun HomeworkList(
    padding: PaddingValues,
    state: LazyListState,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxSize(),
            state = state,
            // Room at the bottom for the floating action button.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * The date chip (opens the date picker, which can also clear it) and, with more than one class, the class
 * chips: the web app's Date and Class Group filters.
 */
@Composable
private fun FilterChips(
    classes: List<ClassGroup>,
    selected: Long?,
    counts: Map<Long, Int>,
    total: Int,
    date: LocalDate?,
    onSelect: (Long?) -> Unit,
    onDateChange: (LocalDate?) -> Unit,
) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        item(key = "date") {
            FilterChip(
                selected = date != null,
                onClick = { pickingDate = true },
                label = { Text(date?.let(::dateLabel) ?: "Date") },
                leadingIcon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.semantics {
                    contentDescription = date?.let { "Date filter: ${dateLabel(it)}" } ?: "Filter by date"
                },
            )
        }
        if (classes.size > 1) {
            item(key = "all") {
                FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All · $total") })
            }
            items(classes, key = { it.id }) { group ->
                FilterChip(
                    selected = selected == group.id,
                    onClick = { onSelect(if (selected == group.id) null else group.id) },
                    label = { Text("${group.className} · ${group.section} · ${counts[group.id] ?: 0}") },
                )
            }
        }
    }
    if (pickingDate) {
        DateFilterDialog(
            date = date,
            onPick = {
                pickingDate = false
                onDateChange(it)
            },
            onDismiss = { pickingDate = false },
        )
    }
}

/** The Material date picker for the date filter; "Clear" removes the filter (null). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterDialog(date: LocalDate?, onPick: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    // The picker works in UTC milliseconds at midnight; convert through UTC to avoid off-by-one days.
    val state = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis == null) onDismiss() else onPick(Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date)
                },
            ) { Text("OK") }
        },
        dismissButton = {
            if (date != null) TextButton(onClick = { onPick(null) }) { Text("Clear") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) { DatePicker(state = state) }
}

/** One saved report: class, the subjects it covers (or that it is a diary photo), photo count and incharge, and a PDF action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeworkReportCard(item: HomeworkCardItem, onClick: () -> Unit, modifier: Modifier = Modifier, onExport: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val thumbShape = RoundedCornerShape(16.dp)
            val typedIcon: @Composable () -> Unit = {
                Box(
                    Modifier.size(56.dp).clip(thumbShape).background(colors.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (item.photoFile != null) Icons.Rounded.PhotoCamera else Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = colors.onSecondaryContainer,
                    )
                }
            }
            if (item.photoFile != null) {
                StoredPhoto(
                    fileName = item.photoFile,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(thumbShape).background(colors.surfaceContainerHigh),
                    maxEdgePx = 256,
                    placeholder = typedIcon,
                )
            } else {
                typedIcon()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.classLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    item.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.photoCount > 0) {
                        MetaLabel(Icons.Rounded.PhotoCamera, if (item.photoCount == 1) "1 photo" else "${item.photoCount} photos")
                    } else {
                        MetaLabel(Icons.Rounded.EditNote, pluralSubjects(item.subjectCount))
                    }
                    if (item.inchargeName.isNotBlank()) {
                        Text(
                            item.inchargeName,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (onExport != null) {
                IconButton(onClick = onExport, modifier = Modifier.align(Alignment.CenterVertically)) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Share ${item.classLabel} homework as PDF", tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MetaLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Previews ----------------------------------------------------------------------------------------

private val PreviewInstant = Instant.fromEpochMilliseconds(0)

private fun previewTabData(): HomeworkTabData {
    val today = homeworkToday()
    val classes = listOf(ClassGroup(1, "7", "A", PreviewInstant), ClassGroup(2, "8", "B", PreviewInstant))
    val subjects = listOf(
        Subject(1, "Mathematics", "mathematics", null, PreviewInstant, PreviewInstant),
        Subject(2, "Urdu", "urdu", null, PreviewInstant, PreviewInstant),
        Subject(3, "English", "english", null, PreviewInstant, PreviewInstant),
    )
    fun report(id: Long, classId: Long, date: LocalDate, subjectIds: List<Long>) = HomeworkReportWithItems(
        HomeworkReport(id, classId, date, "Ms. Sana", DEFAULT_PARENT_NOTE, null, PreviewInstant, PreviewInstant),
        subjectIds.mapIndexed { index, subject -> HomeworkItem(id * 10 + index, id, subject, "Exercise 4.2", index) },
    )
    return HomeworkTabData(
        reports = listOf(
            report(1, 1, today, listOf(1, 2, 3)),
            report(2, 2, today, listOf(3)),
            report(3, 1, LocalDate.fromEpochDays(today.toEpochDays() - 1), listOf(1, 2)),
        ),
        classes = classes,
        subjects = subjects,
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeworkTabPreview() {
    AttendanceTheme(dynamicColor = false) {
        HomeworkTabContent(previewTabData(), onNewReport = {}, onEditReport = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeworkTabEmptyPreview() {
    AttendanceTheme(darkTheme = true, dynamicColor = false) {
        HomeworkTabContent(previewTabData().copy(reports = emptyList()), onNewReport = {}, onEditReport = {})
    }
}

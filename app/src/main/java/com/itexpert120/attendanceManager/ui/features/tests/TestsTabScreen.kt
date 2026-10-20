package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.repo.TestInput
import com.itexpert120.attendanceManager.data.repo.TestWithEntries
import com.itexpert120.attendanceManager.domain.TestProgress
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.navigation.TabReselectEffect
import com.itexpert120.attendanceManager.ui.navigation.TestsTab
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * The Tests tab: every Test with its Test Progress, filtered by Subject chips and a filter sheet. Tapping a
 * Test opens its detail ([onOpenTest]); the app bar leads to the Subject and Student reports ([onOpenReports]).
 */
@Composable
fun TestsTabScreen(onOpenTest: (testId: Long) -> Unit, onOpenReports: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { TestsTabViewModel(container.tests, container.subjects, container.classes, container.settings) }
    val data by viewModel.data.collectAsState()
    TestsTabContent(
        data = data,
        actions = viewModel,
        onOpenTest = onOpenTest,
        onOpenReports = onOpenReports,
    )
}

private val TestsFilterSaver: Saver<TestsFilter, Any> = listSaver(
    save = {
        listOf(
            it.query,
            it.classGroupId ?: -1L,
            it.subjectId ?: -1L,
            it.progress?.ordinal ?: -1,
            it.yearChoice,
            it.from?.toEpochDays() ?: Long.MIN_VALUE,
            it.to?.toEpochDays() ?: Long.MIN_VALUE,
        )
    },
    restore = {
        TestsFilter(
            query = it[0] as String,
            classGroupId = (it[1] as Long).takeIf { id -> id >= 0 },
            subjectId = (it[2] as Long).takeIf { id -> id >= 0 },
            progress = (it[3] as Int).takeIf { index -> index >= 0 }?.let { index -> TestProgress.entries[index] },
            yearChoice = it[4] as Int,
            from = (it[5] as Long).takeIf { days -> days != Long.MIN_VALUE }?.let(LocalDate::fromEpochDays),
            to = (it[6] as Long).takeIf { days -> days != Long.MIN_VALUE }?.let(LocalDate::fromEpochDays),
        )
    },
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TestsTabContent(
    data: TestsTabData?,
    actions: TestsTabActions,
    onOpenTest: (testId: Long) -> Unit,
    onOpenReports: () -> Unit,
) {
    var filter by rememberSaveable(stateSaver = TestsFilterSaver) { mutableStateOf(TestsFilter()) }
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var subjectsOpen by rememberSaveable { mutableStateOf(false) }
    val today = remember { today() }
    val listState = rememberLazyListState()
    TabReselectEffect(TestsTab) { listState.animateScrollToItem(0) }

    AppScreen(
        title = "Tests",
        subtitle = "Results across every subject",
        actions = {
            IconButton(onClick = { subjectsOpen = true }) {
                Icon(Icons.Rounded.MenuBook, contentDescription = "Manage subjects")
            }
            IconButton(onClick = onOpenReports) {
                Icon(Icons.Rounded.Assessment, contentDescription = "Test reports")
            }
        },
        floatingActionButton = {
            if (data != null) {
                ExtendedFloatingActionButton(
                    onClick = { createOpen = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New test") },
                )
            }
        },
    ) { padding ->
        if (data == null) {
            TestLoading(padding)
            return@AppScreen
        }
        val subjectNames = remember(data.subjects) { data.subjects.associate { it.id to it.name } }
        val groups = remember(data.classes) { data.classes.associateBy { it.id } }
        val visible by remember(data, filter) {
            derivedStateOf { filterTests(data.tests, filter, today, data.academicYearStartMonth, subjectNames) }
        }
        val forSubjectCounts = remember(data, filter) {
            filterTests(data.tests, filter, today, data.academicYearStartMonth, subjectNames, ignoreSubject = true)
        }
        val listItems = remember(visible, subjectNames, groups) {
            visible.map { buildTestListItem(it, subjectNames[it.test.subjectId], groups[it.test.classGroupId]) }
        }

        TestPage(padding, state = listState) {
            item(key = "search") {
                SearchField(filter.query, onChange = { filter = filter.copy(query = it) })
            }
            item(key = "chips") {
                FilterChips(
                    filter = filter,
                    subjects = data.activeSubjects,
                    classLabel = filter.classGroupId?.let { classLabel(groups[it]) },
                    counts = forSubjectCounts.groupingBy { it.test.subjectId }.eachCount(),
                    total = forSubjectCounts.size,
                    onFilterChange = { filter = it },
                    onOpenFilters = { filtersOpen = true },
                )
            }
            if (listItems.isEmpty()) {
                item(key = "empty") {
                    if (data.tests.isEmpty()) {
                        EmptyState(
                            icon = Icons.Rounded.Quiz,
                            title = "No tests yet",
                            text = "Create a test, then enter each student's marks as fast as you can type.",
                            action = { Button(onClick = { createOpen = true }) { Text("New test") } },
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Rounded.FilterAlt,
                            title = "No tests match",
                            text = "Change the filters or search to see more tests.",
                            action = { TextButton(onClick = { filter = TestsFilter() }) { Text("Clear filters") } },
                        )
                    }
                }
            } else {
                item(key = "count") {
                    Text(
                        testsListHeader(listItems.size, testsRangeLabel(filter, today, data.academicYearStartMonth)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                items(listItems, key = { it.id }) { row ->
                    TestCard(row, onClick = { onOpenTest(row.id) })
                }
                item(key = "order") {
                    Text(
                        "Newest Test date first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }
        }

        if (filtersOpen) {
            TestsFilterSheet(
                filter = filter,
                onFilterChange = { filter = it },
                classes = data.classes,
                subjects = data.subjects,
                academicYears = academicYearChoices(today, data.academicYearStartMonth, data.tests.map { it.test.date }),
                academicYearStartMonth = data.academicYearStartMonth,
                today = today,
                matchCount = visible.size,
                onDismiss = { filtersOpen = false },
            )
        }
        if (createOpen) {
            NewTestSheet(
                subjects = data.activeSubjects,
                classes = data.classes,
                defaultClassId = filter.classGroupId,
                actions = actions,
                onDismiss = { createOpen = false },
                onCreated = {
                    createOpen = false
                    onOpenTest(it)
                },
            )
        }
        if (subjectsOpen) {
            SubjectsSheet(
                subjects = data.subjects,
                testCounts = testCountBySubject(data.tests),
                actions = actions,
                onDismiss = { subjectsOpen = false },
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text("Search tests") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
private fun FilterChips(
    filter: TestsFilter,
    subjects: List<Subject>,
    classLabel: String?,
    counts: Map<Long, Int>,
    total: Int,
    onFilterChange: (TestsFilter) -> Unit,
    onOpenFilters: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        item(key = "filters") {
            FilterChip(
                selected = filter.activeCount > 0,
                onClick = onOpenFilters,
                label = { Text(if (filter.activeCount > 0) "Filters · ${filter.activeCount}" else "Filters") },
                leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (classLabel != null) {
            item(key = "class") {
                FilterChip(
                    selected = true,
                    onClick = { onFilterChange(filter.copy(classGroupId = null)) },
                    label = { Text(classLabel) },
                    trailingIcon = {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear class filter", modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
        item(key = "all") {
            FilterChip(
                selected = filter.subjectId == null,
                onClick = { onFilterChange(filter.copy(subjectId = null)) },
                label = { Text("All · $total") },
            )
        }
        items(subjects, key = { "subject-${it.id}" }) { subject ->
            FilterChip(
                selected = filter.subjectId == subject.id,
                onClick = {
                    onFilterChange(filter.copy(subjectId = if (filter.subjectId == subject.id) null else subject.id))
                },
                label = { Text("${subject.name} · ${counts[subject.id] ?: 0}") },
            )
        }
    }
}

/** One Test in the list: name, where and when, progress, Total Marks and class average. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestCard(item: TestListItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerLowest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(colors.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Quiz, contentDescription = null, tint = colors.onTertiaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progressFraction(item.entered, item.total) },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TestProgressBadge(item.progress)
                Text(
                    "${item.entered} / ${item.total} entered",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                Text(item.totalMarksLabel, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                item.averageLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

// ---- Previews ----------------------------------------------------------------------------------------

private val PreviewInstant = Instant.fromEpochMilliseconds(0)

private object NoOpTestsActions : TestsTabActions {
    override suspend fun createTest(input: TestInput) = Attempt.Failed("Preview")

    override suspend fun addSubject(name: String) = Attempt.Failed("Preview")

    override suspend fun renameSubject(subjectId: Long, name: String) = Attempt.Failed("Preview")

    override suspend fun setSubjectArchived(subjectId: Long, archived: Boolean) = Attempt.Failed("Preview")
}

private fun previewData(): TestsTabData {
    val maths = Subject(1, "Mathematics", "mathematics", null, PreviewInstant, PreviewInstant)
    val urdu = Subject(2, "Urdu", "urdu", null, PreviewInstant, PreviewInstant)
    val group = ClassGroup(1, "7", "A", PreviewInstant)
    fun test(id: Long, name: String, subject: Long, date: String) =
        TestRecord(id, name, name.lowercase(), subject, 1, LocalDate.parse(date), 4000, PreviewInstant, PreviewInstant)
    fun entry(testId: Long, enrollment: Long, marks: Int?) = TestRosterEntry(
        testId,
        enrollment,
        if (marks == null) TestResultStatus.NOT_ENTERED else TestResultStatus.MARKS,
        marks,
        null,
        PreviewInstant,
    )
    return TestsTabData(
        tests = listOf(
            TestWithEntries(test(1, "Weekly Test 3", 1, "2026-09-28"), listOf(entry(1, 1, 3500), entry(1, 2, 2800), entry(1, 3, null))),
            TestWithEntries(test(2, "Midterm", 2, "2026-09-20"), listOf(entry(2, 1, 3000), entry(2, 2, 3600))),
            TestWithEntries(test(3, "Pop quiz", 1, "2026-09-10"), listOf(entry(3, 1, null), entry(3, 2, null))),
        ),
        subjects = listOf(maths, urdu),
        classes = listOf(group),
        academicYearStartMonth = 4,
    )
}

@Preview(showBackground = true)
@Composable
private fun TestsTabPreview() {
    AttendanceTheme(dynamicColor = false) {
        TestsTabContent(previewData(), NoOpTestsActions, onOpenTest = {}, onOpenReports = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun TestsTabDarkPreview() {
    AttendanceTheme(darkTheme = true, dynamicColor = false) {
        TestsTabContent(previewData(), NoOpTestsActions, onOpenTest = {}, onOpenReports = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun TestsTabEmptyPreview() {
    AttendanceTheme(dynamicColor = false) {
        TestsTabContent(previewData().copy(tests = emptyList()), NoOpTestsActions, onOpenTest = {}, onOpenReports = {})
    }
}

package com.itexpert120.attendanceManager.ui.features.registers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.DelayedLoading
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.ListGroup
import com.itexpert120.attendanceManager.ui.components.ListRow
import com.itexpert120.attendanceManager.ui.components.PageList
import com.itexpert120.attendanceManager.ui.navigation.RegistersTab
import com.itexpert120.attendanceManager.ui.navigation.TabReselectEffect
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlin.time.Instant

/**
 * The Registers tab: what to do today (take attendance, or create this month's register), shortcuts to the
 * other areas, and every register grouped by month.
 */
@Composable
fun RegistersTabScreen(
    onOpenRegister: (registerId: Long) -> Unit,
    onOpenStudents: () -> Unit,
    onOpenTests: () -> Unit,
    onOpenHomework: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: RegistersViewModel = viewModel {
        RegistersViewModel(
            settings = container.settings,
            classes = container.classes,
            registers = container.registers,
            students = container.students,
            tests = container.tests,
            homework = container.homework,
            enrollmentRows = container.database.enrollments().observeAllRows(),
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()

    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Failures of the one-tap "Create this month's register" have no form to show them in, so they get a snackbar.
    LaunchedEffect(action.error, sheetOpen) {
        val error = action.error
        if (error != null && !sheetOpen) {
            // Clear only after the snackbar is gone: changing the effect's key would cancel it immediately.
            snackbar.showSnackbar(error)
            viewModel.clearError()
        }
    }

    val ready = state as? RegistersUiState.Ready
    RegistersTabContent(
        state = state,
        busy = action.busy,
        snackbar = snackbar,
        onNewRegister = { sheetOpen = true },
        onTakeAttendance = onOpenRegister,
        onCreateThisMonth = { classGroupId ->
            ready?.content?.today?.let { today ->
                viewModel.createRegister(classGroupId, today.year, today.month.number, onOpenRegister)
            }
        },
        onOpenStudents = onOpenStudents,
        onOpenTests = onOpenTests,
        onOpenHomework = onOpenHomework,
        onOpenRegister = onOpenRegister,
    )

    if (sheetOpen && ready != null) {
        val dismiss = {
            sheetOpen = false
            viewModel.clearError()
        }
        RegistersNewRegisterSheet(
            classes = ready.content.classes,
            today = ready.content.today,
            initialClassId = defaultClassId(ready.content.classes, preferred = null),
            action = action,
            onCreateRegister = { classGroupId, year, month ->
                viewModel.createRegister(classGroupId, year, month) { registerId ->
                    sheetOpen = false
                    onOpenRegister(registerId)
                }
            },
            onAddClass = viewModel::addClass,
            onClearError = viewModel::clearError,
            onDismiss = dismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun RegistersTabContent(
    state: RegistersUiState,
    busy: Boolean,
    snackbar: SnackbarHostState,
    onNewRegister: () -> Unit,
    onTakeAttendance: (registerId: Long) -> Unit,
    onCreateThisMonth: (classGroupId: Long) -> Unit,
    onOpenStudents: () -> Unit,
    onOpenTests: () -> Unit,
    onOpenHomework: () -> Unit,
    onOpenRegister: (registerId: Long) -> Unit,
) {
    val content = (state as? RegistersUiState.Ready)?.content
    val listState = rememberLazyListState()
    TabReselectEffect(RegistersTab) { listState.animateScrollToItem(0) }
    val fabExpanded by remember { derivedStateOf { !listState.canScrollBackward } }

    AppScreen(
        title = "Registers",
        subtitle = content?.schoolName,
        actions = {
            IconButton(onClick = onOpenStudents) {
                Icon(Icons.Rounded.Groups, contentDescription = "Students and classes")
            }
        },
        floatingActionButton = {
            if (content != null) {
                ExtendedFloatingActionButton(
                    onClick = onNewRegister,
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New register") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (content == null) {
            DelayedLoading(Modifier.fillMaxSize().padding(padding))
        } else {
            PageList(padding, state = listState) {
                item(key = "today") {
                    RegistersTodayCard(
                        today = content.today,
                        schoolName = content.schoolName,
                        logoFile = content.logoFile,
                        entries = content.todayEntries,
                        busy = busy,
                        onTakeAttendance = onTakeAttendance,
                        onCreateThisMonth = onCreateThisMonth,
                        onOpenStudents = onOpenStudents,
                    )
                }
                item(key = "stats") {
                    RegistersStatsCard(
                        stats = content.stats,
                        onOpenStudents = onOpenStudents,
                        onOpenTests = onOpenTests,
                        onOpenHomework = onOpenHomework,
                    )
                }
                if (!content.hasRegisters) {
                    item(key = "empty") {
                        AppCard {
                            EmptyState(
                                icon = Icons.Rounded.CalendarMonth,
                                title = "No registers yet",
                                text = "Add your students, then create the first monthly register.",
                                action = {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedButton(onClick = onOpenStudents) {
                                            Icon(Icons.Rounded.Groups, contentDescription = null)
                                            Text("Add students", modifier = Modifier.padding(start = 8.dp))
                                        }
                                        Button(onClick = onNewRegister) {
                                            Icon(Icons.Rounded.Add, contentDescription = null)
                                            Text("New register", modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }
                                },
                            )
                        }
                    }
                } else {
                    items(content.monthGroups, key = { it.key }) { group ->
                        ListGroup(header = if (group.isCurrent) "${group.title} · This month" else group.title) {
                            items(group.registers) { summary ->
                                ListRow(
                                    label = summary.label,
                                    detail = studentCountLabel(summary.studentCount),
                                    icon = Icons.Rounded.CalendarMonth,
                                    chevron = true,
                                    onClick = { onOpenRegister(summary.id) },
                                )
                            }
                        }
                    }
                    item(key = "footer") {
                        Text(
                            "Registers, marks and fees are saved on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

// region Previews

private val PreviewInstant = Instant.fromEpochMilliseconds(0)
private val PreviewToday = LocalDate(2026, 10, 7)
private val PreviewClasses = listOf(
    ClassGroup(1, "7", "A", PreviewInstant),
    ClassGroup(2, "7", "B", PreviewInstant),
)

private fun previewContent(withRegisters: Boolean): RegistersContent {
    fun register(id: Long, classId: Long, month: Int) = Register(id, classId, 2026, month, PreviewInstant)
    val october = listOf(RegisterSummary(register(1, 1, 10), PreviewClasses[0], 32))
    val september = PreviewClasses.mapIndexed { i, c -> RegisterSummary(register(10L + i, c.id, 9), c, 30 + i) }
    return RegistersContent(
        schoolName = "Crescent Public School",
        logoFile = null,
        today = PreviewToday,
        classes = PreviewClasses,
        todayEntries = listOf(
            TodayEntry(PreviewClasses[0], october.first().register),
            TodayEntry(PreviewClasses[1], null),
        ),
        monthGroups = if (withRegisters) {
            listOf(
                RegisterMonthGroup(2026, 10, true, october),
                RegisterMonthGroup(2026, 9, false, september),
            )
        } else {
            emptyList()
        },
        stats = RegistersStats(students = 62, classes = 2, tests = 4, homework = 11),
    )
}

@Composable
private fun PreviewHost(state: RegistersUiState) {
    AttendanceTheme(dynamicColor = false) {
        RegistersTabContent(
            state = state,
            busy = false,
            snackbar = remember { SnackbarHostState() },
            onNewRegister = {},
            onTakeAttendance = {},
            onCreateThisMonth = {},
            onOpenStudents = {},
            onOpenTests = {},
            onOpenHomework = {},
            onOpenRegister = {},
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RegistersTabPreview() = PreviewHost(RegistersUiState.Ready(previewContent(withRegisters = true)))

@Preview(showBackground = true, showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RegistersTabDarkPreview() = PreviewHost(RegistersUiState.Ready(previewContent(withRegisters = true)))

@Preview(showBackground = true)
@Composable
private fun RegistersNewRegisterSheetPreview() {
    AttendanceTheme(dynamicColor = false) {
        RegistersNewRegisterSheet(
            classes = PreviewClasses,
            today = PreviewToday,
            initialClassId = 1,
            action = RegistersAction(error = "That class and section already exists."),
            onCreateRegister = { _, _, _ -> },
            onAddClass = { _, _, _ -> },
            onClearError = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun RegistersTabEmptyPreview() = PreviewHost(RegistersUiState.Ready(previewContent(withRegisters = false)))

// endregion

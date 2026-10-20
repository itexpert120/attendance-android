package com.itexpert120.attendanceManager.ui.features.registers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.ui.components.SegmentedChoice
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/** Breakpoint (dp) from which the month overview becomes the full register grid. */
private const val WIDE_WINDOW_DP = 840

/**
 * Day-first attendance marking for one register: a date strip, the student list for the selected day with
 * two tap-to-cycle timing buttons per student, a counts and bulk-actions bar, holidays, and a Month mode
 * with a per-student heat strip.
 */
@Composable
fun AttendanceSection(data: RegisterData, modifier: Modifier = Modifier, onOpenStudents: (() -> Unit)? = null) {
    val container = LocalAppContainer.current
    val registerId = data.register.id
    val viewModel: AttendanceViewModel = viewModel(key = "attendance-$registerId") {
        AttendanceViewModel(container.registers)
    }
    val today by viewModel.today.collectAsState()
    var monthMode by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable(registerId) { mutableIntStateOf(defaultSelectedDay(data.register, today)) }
    var showHolidaySheet by rememberSaveable { mutableStateOf(false) }
    var openStudentId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest { message ->
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = if (message.onUndo != null) "Undo" else null,
                withDismissAction = false,
                duration = if (message.onUndo != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) message.onUndo?.invoke()
        }
    }

    val day = selectedDay.coerceIn(data.days)
    val latestData by rememberUpdatedState(data)
    val actions = remember(viewModel, day, registerId) {
        AttendanceRowActions(
            onSetMark = { enrollmentId, session, status -> viewModel.setMark(registerId, enrollmentId, day, session, status) },
            onSwipeAbsent = { enrollmentId, name -> viewModel.markAbsent(latestData, enrollmentId, name, day) },
            onBlocked = viewModel::post,
        )
    }

    AttendanceContent(
        data = data,
        today = today,
        monthMode = monthMode,
        onMonthModeChange = { monthMode = it },
        selectedDay = day,
        onSelectDay = { selectedDay = it },
        snackbar = snackbar,
        actions = actions,
        onBulk = { sessions, status -> viewModel.bulk(latestData, day, sessions, status) },
        onOpenStudents = onOpenStudents,
        onFillBlanks = { viewModel.fillBlanksPresent(latestData, day) },
        onDeclareHoliday = { showHolidaySheet = true },
        onRemoveHoliday = { viewModel.removeHoliday(latestData, day) },
        onOpenStudent = { openStudentId = it },
        modifier = modifier,
    )

    if (showHolidaySheet) {
        AttendanceHolidaySheet(
            date = dayInfo(data, day, today).date,
            hasMarks = data.hasMarksOn(day),
            onDismiss = { showHolidaySheet = false },
            onConfirm = { reason ->
                showHolidaySheet = false
                viewModel.declareHoliday(latestData, day, reason)
            },
        )
    }
    openStudentId?.let { id ->
        val row = data.attendanceRows.firstOrNull { it.enrollment.id == id }
        if (row != null) AttendanceStudentMonthSheet(data, row, onDismiss = { openStudentId = null })
    }
}

/** The stateless screen, kept separate so previews can show it without a database. */
@Composable
internal fun AttendanceContent(
    data: RegisterData,
    today: LocalDate,
    monthMode: Boolean,
    onMonthModeChange: (Boolean) -> Unit,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    snackbar: SnackbarHostState,
    actions: AttendanceRowActions,
    onBulk: (List<Session>, AttendanceStatus?) -> Unit,
    onOpenStudents: (() -> Unit)?,
    onFillBlanks: () -> Unit,
    onDeclareHoliday: () -> Unit,
    onRemoveHoliday: () -> Unit,
    onOpenStudent: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = remember(data, selectedDay, today) { dayInfo(data, selectedDay, today) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_WINDOW_DP.dp
        Column(Modifier.fillMaxSize()) {
            SegmentedChoice(
                options = listOf(false, true),
                selected = monthMode,
                optionLabel = { if (it) "Month" else "Day" },
                onSelect = onMonthModeChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (monthMode) {
                    AttendanceMonthPane(data, wide, onOpenStudent, onOpenStudents, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        AttendanceDayPane(
                            data = data,
                            today = today,
                            info = info,
                            selectedDay = selectedDay,
                            onSelectDay = onSelectDay,
                            actions = actions,
                            onRemoveHoliday = onRemoveHoliday,
                            onOpenStudents = onOpenStudents,
                            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                        )
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            }
            // Shown on every markable day, even without students, so a holiday never depends on the roster.
            if (!monthMode && info.canMark) {
                val counts = remember(data, selectedDay) {
                    if (activeRowsOn(data, selectedDay).isEmpty()) null else dayCounts(data, selectedDay)
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    AttendanceBottomBar(
                        counts = counts,
                        canDeclareHoliday = info.canToggleHoliday,
                        onBulk = { sessions, status -> onBulk(sessions, status) },
                        onFillBlanks = onFillBlanks,
                        onClear = { onBulk(it, null) },
                        onDeclareHoliday = onDeclareHoliday,
                        modifier = Modifier.widthIn(max = 720.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttendanceDayPane(
    data: RegisterData,
    today: LocalDate,
    info: DayInfo,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    actions: AttendanceRowActions,
    onRemoveHoliday: () -> Unit,
    onOpenStudents: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val strip = remember(data, today) { buildDateStrip(data, today) }
    val stripState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedDay - 3).coerceAtLeast(0))
    LaunchedEffect(selectedDay) { stripState.animateScrollToItem((selectedDay - 3).coerceAtLeast(0)) }
    val todayDay = if (data.register.year == today.year && data.register.month == today.month.number) today.day else null
    val explanation = dayExplanation(info)
    val showStudents = info.kind != DayKind.HOLIDAY && info.kind != DayKind.WEEKEND

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "strip") {
            AttendanceDateStrip(strip, selectedDay, onSelectDay, stripState, Modifier.padding(bottom = 8.dp))
        }
        item(key = "header") {
            AttendanceDayHeader(
                info = info,
                isToday = info.date == today,
                onRemoveHoliday = onRemoveHoliday,
                onJumpToToday = if (todayDay != null && todayDay != selectedDay) ({ onSelectDay(todayDay) }) else null,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (nobodyOnRoll(data, info)) {
            item(key = "nobody-on-roll") {
                AttendanceNobodyOnRollBanner(
                    date = info.date,
                    onEditStudents = onOpenStudents,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
        if (showStudents) {
            if (data.attendanceRows.isEmpty()) {
                item(key = "empty") { AttendanceNoStudents(onOpenStudents) }
            } else {
                items(data.attendanceRows, key = { it.enrollment.id }) { row ->
                    val id = row.enrollment.id
                    val onRoll = isOnRoll(row, data.register, selectedDay)
                    StudentAttendanceRow(
                        enrollmentId = id,
                        roll = row.enrollment.rollNumber,
                        name = row.student.name,
                        photoFile = row.student.photoFile,
                        first = data.statusOf(id, selectedDay, Session.FIRST),
                        second = data.statusOf(id, selectedDay, Session.SECOND),
                        editable = info.canMark && onRoll,
                        blockedMessage = if (onRoll) explanation.orEmpty() else "${row.student.name} was not on the roll on this date.",
                        subtitle = dayRowSubtitle(data, row, selectedDay),
                        actions = actions,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

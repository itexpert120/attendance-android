package com.itexpert120.attendanceManager.ui.features.registers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.itexpert120.attendanceManager.domain.isActiveOn
import kotlinx.coroutines.flow.flatMapLatest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.HomeworkRepository
import com.itexpert120.attendanceManager.data.repo.RegisterListItem
import com.itexpert120.attendanceManager.data.repo.RegisterRepository
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.data.repo.StudentRepository
import com.itexpert120.attendanceManager.data.repo.TestRepository
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.SchoolSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

sealed interface RegistersUiState {
    data object Loading : RegistersUiState
    data class Ready(val content: RegistersContent) : RegistersUiState
}

/** Progress and failure of the last create-register / add-class action, shown inline in the sheet or as a snackbar. */
data class RegistersAction(val busy: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class RegistersViewModel(
    settings: SettingsRepository,
    private val classes: ClassRepository,
    private val registers: RegisterRepository,
    students: StudentRepository,
    tests: TestRepository,
    homework: HomeworkRepository,
    /** Enrollments of every class, used to count the students of each register. */
    enrollmentRows: Flow<List<EnrollmentRow>>,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private class Base(
        val settings: SchoolSettings?,
        val classes: List<ClassGroup>,
        val items: List<RegisterListItem>,
        val rows: List<EnrollmentRow>,
    )

    private class Counts(val students: Int, val testDates: List<LocalDate>, val homework: Int)

    /** Re-reads the date every minute so the tab rolls over at midnight while the app stays open. */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(clock.now().toLocalDateTime(timeZone).date)
            delay(CLOCK_POLL_MILLIS)
        }
    }.distinctUntilChanged()

    val state: StateFlow<RegistersUiState> = combine(
        combine(
            settings.observeSettings(),
            classes.observeClasses(),
            registers.observeRegisterListItems(),
            enrollmentRows,
        ) { s, c, i, r -> Base(s, c, i, r) },
        combine(
            students.observeStudents(),
            tests.observeTests(),
            homework.observeReports(),
        ) { s, t, h -> Counts(s.size, t.map { it.date }, h.size) },
        today.flatMapLatest { day -> registers.observeMarkedOn(day).map { day to it } },
    ) { base, counts, (today, marked) ->
        val inputs = RegistersInputs(
            settings = base.settings,
            classes = base.classes,
            registerItems = base.items,
            enrollmentRows = base.rows,
            studentCount = counts.students,
            testDates = counts.testDates,
            homeworkCount = counts.homework,
        )
        val content = buildRegistersContent(inputs, today)
        val rowsByClass = base.rows.groupBy { it.enrollment.classGroupId }
        val entries = content.todayEntries.map { entry ->
            entry.copy(
                marked = entry.register?.let { marked[it.id] } ?: 0,
                onRoll = rowsByClass[entry.classGroup.id].orEmpty().count { it.enrollment.isActiveOn(today) },
            )
        }
        RegistersUiState.Ready(content.copy(todayEntries = entries)) as RegistersUiState
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RegistersUiState.Loading)

    private val _action = MutableStateFlow(RegistersAction())
    val action: StateFlow<RegistersAction> = _action.asStateFlow()

    fun clearError() {
        _action.update { it.copy(error = null) }
    }

    /** Creates (or finds) the class's register for the month, then reports its id through [onCreated]. */
    fun createRegister(classGroupId: Long, year: Int, month: Int, onCreated: (registerId: Long) -> Unit) {
        runAction("Could not create the register.") {
            onCreated(registers.createRegister(classGroupId, year, month).id)
        }
    }

    /** Adds a class and reports its id through [onAdded] so the form can select it. */
    fun addClass(className: String, section: String, onAdded: (classGroupId: Long) -> Unit) {
        runAction("Could not add the class.") {
            onAdded(classes.addClass(className, section).id)
        }
    }

    private fun runAction(fallbackMessage: String, block: suspend () -> Unit) {
        if (_action.value.busy) return
        _action.value = RegistersAction(busy = true)
        viewModelScope.launch {
            val failure = try {
                block()
                null
            } catch (e: ValidationException) {
                e.message ?: fallbackMessage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fallbackMessage
            }
            _action.value = RegistersAction(busy = false, error = failure)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val CLOCK_POLL_MILLIS = 60_000L
    }
}

package com.itexpert120.attendanceManager.ui.features.students

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.files.PickedPhoto
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.SavedStudent
import com.itexpert120.attendanceManager.data.repo.StudentInput
import com.itexpert120.attendanceManager.data.repo.StudentRepository
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Everything the Students screen draws, derived from the repositories and the saved search state. */
data class StudentsUiState(
    val loading: Boolean = true,
    val classes: List<ClassGroup> = emptyList(),
    val selectedClass: ClassGroup? = null,
    /** Every enrollment of the selected class (struck-off ones too), in roll order. */
    val rows: List<EnrollmentRow> = emptyList(),
    val query: String = "",
    val showStruckOff: Boolean = true,
    val listing: StudentListing = StudentListing.EMPTY,
)

/**
 * State holder of the Students screen. The selected class, the search text and the struck-off filter live in
 * a [SavedStateHandle], so they survive rotation and process death; the repository work that must not be
 * cut short by a rotation runs in [NonCancellable].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudentsViewModel(
    private val students: StudentRepository,
    private val classes: ClassRepository,
    private val handle: SavedStateHandle,
    initialClassGroupId: Long?,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : ViewModel() {

    private val classList: StateFlow<List<ClassGroup>?> =
        classes.observeClasses().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val selectedId: StateFlow<Long> = handle.getStateFlow(KEY_CLASS, initialClassGroupId ?: NO_CLASS)
    private val queryFlow: StateFlow<String> = handle.getStateFlow(KEY_QUERY, "")
    private val showStruckOffFlow: StateFlow<Boolean> = handle.getStateFlow(KEY_SHOW_STRUCK_OFF, true)

    private val effectiveId = combine(classList, selectedId) { list, selected ->
        list?.let { effectiveClassId(it, selected.takeIf { id -> id != NO_CLASS }) }
    }.distinctUntilChanged()

    /** null until the first emission of the selected class's roll. */
    private val rowsFlow: kotlinx.coroutines.flow.Flow<List<EnrollmentRow>?> = effectiveId.flatMapLatest { id ->
        if (id == null) {
            flowOf<List<EnrollmentRow>?>(emptyList())
        } else {
            students.observeEnrollmentRows(id).map<List<EnrollmentRow>, List<EnrollmentRow>?> { it }.onStart { emit(null) }
        }
    }

    val state: StateFlow<StudentsUiState> = combine(classList, effectiveId, rowsFlow, queryFlow, showStruckOffFlow) {
            list, id, rows, query, showStruckOff ->
        if (list == null || rows == null) {
            StudentsUiState(loading = true, query = query, showStruckOff = showStruckOff)
        } else {
            StudentsUiState(
                loading = false,
                classes = list,
                selectedClass = list.firstOrNull { it.id == id },
                rows = rows,
                query = query,
                showStruckOff = showStruckOff,
                listing = buildListing(rows, query, showStruckOff),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudentsUiState())

    /** Every student of the school; collect it only while the "add existing student" sheet is open. */
    val allStudents: StateFlow<List<Student>> =
        students.observeStudents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A photo picked for the open student form; the form itself only keeps whether it was replaced. */
    private val _pendingPhoto = MutableStateFlow<PickedPhoto?>(null)
    val pendingPhoto: StateFlow<PickedPhoto?> = _pendingPhoto

    fun today(): LocalDate = today.invoke()

    fun selectClass(id: Long) {
        handle[KEY_CLASS] = id
    }

    fun setQuery(query: String) {
        handle[KEY_QUERY] = query
    }

    fun setShowStruckOff(show: Boolean) {
        handle[KEY_SHOW_STRUCK_OFF] = show
    }

    fun setPendingPhoto(photo: PickedPhoto?) {
        _pendingPhoto.value = photo
    }

    suspend fun saveStudent(input: StudentInput): Result<SavedStudent> =
        guarded("Could not save the student.") { students.saveStudent(input) }

    suspend fun addClass(className: String, section: String): Result<ClassGroup> =
        guarded("Could not add the class.") { classes.addClass(className, section) }.onSuccess { selectClass(it.id) }

    suspend fun duplicateClass(
        sourceClassGroupId: Long,
        className: String,
        section: String,
        admittedOn: LocalDate,
    ): Result<ClassGroup> =
        guarded("Could not duplicate the class.") {
            classes.duplicateClass(sourceClassGroupId, className, section, admittedOn)
        }.onSuccess { selectClass(it.id) }

    /** Runs [block] to completion even if the screen is rotated meanwhile; user-fixable problems come back as failures. */
    private suspend fun <T> guarded(fallback: String, block: suspend () -> T): Result<T> = withContext(NonCancellable) {
        try {
            Result.success(block())
        } catch (e: ValidationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ValidationException(fallback))
        }
    }

    private companion object {
        const val KEY_CLASS = "classId"
        const val KEY_QUERY = "query"
        const val KEY_SHOW_STRUCK_OFF = "showStruckOff"
        const val NO_CLASS = -1L
    }
}

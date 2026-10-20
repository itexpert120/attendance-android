package com.itexpert120.attendanceManager.ui.features.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.StudentRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.data.repo.TestInput
import com.itexpert120.attendanceManager.data.repo.TestRepository
import com.itexpert120.attendanceManager.data.repo.TestResultInput
import com.itexpert120.attendanceManager.data.repo.TestRosterRow
import com.itexpert120.attendanceManager.domain.TestSummary
import com.itexpert120.attendanceManager.domain.testSummary
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Test detail screen draws once the Test has loaded. */
data class TestDetailData(
    val test: TestRecord,
    val subject: Subject?,
    val group: ClassGroup?,
    val rows: List<TestRosterRow>,
    val subjects: List<Subject>,
    val classes: List<ClassGroup>,
    /** Every enrollment of the Test's class, for the roster sheet's "add a student" list. */
    val classRows: List<EnrollmentRow>,
) {
    val summary: TestSummary = testSummary(rows.map { it.entry })

    /** True once any result is entered, which locks the class, date and roster refresh. */
    val hasResults: Boolean get() = rows.any { it.result != TestResultStatus.NOT_ENTERED }

    /** The highest recorded mark in hundredths, the floor for Total Marks; 0 when there are none. */
    val highestMarks: Int get() = rows.maxOfOrNull { if (it.result == TestResultStatus.MARKS) it.marks ?: 0 else 0 } ?: 0

    val enteredCount: Int get() = rows.size - summary.notEnteredCount
}

sealed interface TestDetailState {
    data object Loading : TestDetailState

    /** The Test does not exist (any more); the screen leaves. */
    data object NotFound : TestDetailState

    data class Ready(val data: TestDetailData) : TestDetailState
}

/** The commands of the Test detail screen; the ViewModel implements them, previews use a no-op. */
interface TestDetailActions {
    /** Saves one student's result (null clears it). Runs on the ViewModel scope so a write survives leaving the screen. */
    fun saveResult(enrollmentId: Long, input: TestResultInput?)

    suspend fun updateTest(input: TestInput): Attempt<TestRecord>

    suspend fun refreshRoster(): Attempt<Unit>

    suspend fun addToRoster(enrollmentId: Long): Attempt<Unit>

    suspend fun removeFromRoster(enrollmentId: Long): Attempt<Unit>

    suspend fun deleteTest(): Attempt<Unit>
}

@OptIn(ExperimentalCoroutinesApi::class)
class TestDetailViewModel(
    private val testId: Long,
    private val tests: TestRepository,
    subjects: SubjectRepository,
    classes: ClassRepository,
    students: StudentRepository,
) : ViewModel(), TestDetailActions {
    private val errors = Channel<String>(Channel.BUFFERED)

    /** One-off messages (failures that have no row to show them on), shown as snackbars. */
    val messages: Flow<String> = errors.receiveAsFlow()

    private val rowErrorState = MutableStateFlow<Map<Long, String>>(emptyMap())

    /**
     * The repository's message for each enrollment whose last result save failed, shown inline on that
     * student's row; a later successful save for the row clears it.
     */
    val rowErrors: StateFlow<Map<Long, String>> = rowErrorState.asStateFlow()

    private val classRows: Flow<List<EnrollmentRow>> = tests.observeTest(testId)
        .map { it?.classGroupId }
        .distinctUntilChanged()
        .flatMapLatest { classId -> if (classId == null) flowOf(emptyList()) else students.observeEnrollmentRows(classId) }

    val state: StateFlow<TestDetailState> = combine(
        tests.observeTest(testId),
        tests.observeRoster(testId),
        subjects.observe(),
        classes.observeClasses(),
        classRows,
    ) { test, rows, subjectList, classList, classEnrollments ->
        if (test == null) {
            TestDetailState.NotFound
        } else {
            TestDetailState.Ready(
                TestDetailData(
                    test = test,
                    subject = subjectList.firstOrNull { it.id == test.subjectId },
                    group = classList.firstOrNull { it.id == test.classGroupId },
                    rows = rows,
                    subjects = subjectList,
                    classes = classList,
                    classRows = classEnrollments,
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TestDetailState.Loading)

    override fun saveResult(enrollmentId: Long, input: TestResultInput?) {
        viewModelScope.launch {
            val result = attempt("Could not save this result.") { tests.setTestResult(testId, enrollmentId, input) }
            rowErrorState.update { current ->
                if (result is Attempt.Failed) current + (enrollmentId to result.message) else current - enrollmentId
            }
        }
    }

    override suspend fun updateTest(input: TestInput): Attempt<TestRecord> =
        attempt("Could not update this Test.") { tests.updateTest(testId, input) }

    override suspend fun refreshRoster(): Attempt<Unit> =
        attempt("Could not refresh the Test Roster.") { tests.refreshTestRoster(testId) }

    override suspend fun addToRoster(enrollmentId: Long): Attempt<Unit> =
        attempt("Could not add this student.") { tests.addTestRosterMember(testId, enrollmentId) }

    override suspend fun removeFromRoster(enrollmentId: Long): Attempt<Unit> =
        attempt("Could not remove this student.") { tests.removeTestRosterMember(testId, enrollmentId) }

    override suspend fun deleteTest(): Attempt<Unit> =
        attempt("Could not delete this Test.") { tests.deleteTest(testId) }
}

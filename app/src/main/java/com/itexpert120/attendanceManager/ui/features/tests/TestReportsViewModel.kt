package com.itexpert120.attendanceManager.ui.features.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.StudentRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.data.repo.TestReport
import com.itexpert120.attendanceManager.data.repo.TestReportFilter
import com.itexpert120.attendanceManager.data.repo.TestRepository
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.Subject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Subjects (archived included, a report can cover them) and Class Groups the pickers offer. */
data class ReportLookups(val subjects: List<Subject>, val classes: List<ClassGroup>)

/** The Student Report picker's students for one class; [classGroupId] tells a stale list from a fresh one. */
data class StudentOptions(val classGroupId: Long, val students: List<Student>)

sealed interface ReportState {
    data object Loading : ReportState

    /** The request has no report yet (a Student Report without a student). */
    data object Incomplete : ReportState

    data class Ready(val report: TestReport) : ReportState
}

/**
 * Subject and Student Reports. The screen owns the request (saved with `rememberSaveable`) and pushes the
 * resolved filter here with [select]; the report re-computes whenever Tests or results change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestReportsViewModel(
    tests: TestRepository,
    subjects: SubjectRepository,
    classes: ClassRepository,
    students: StudentRepository,
) : ViewModel() {
    private val filter = MutableStateFlow<TestReportFilter?>(null)
    private val studentClass = MutableStateFlow<Long?>(null)
    private val selected = MutableStateFlow(false)

    val lookups: StateFlow<ReportLookups?> = combine(subjects.observe(), classes.observeClasses(), ::ReportLookups)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val studentOptions: StateFlow<StudentOptions?> = studentClass
        .flatMapLatest { classId ->
            if (classId == null) {
                flowOf(null)
            } else {
                students.observeEnrollmentRows(classId).map { rows -> StudentOptions(classId, studentChoices(rows.map { it.student })) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val report: StateFlow<ReportState> = combine(filter, selected) { current, chosen -> current to chosen }
        .flatMapLatest { (current, chosen) ->
            when {
                !chosen -> flowOf(ReportState.Loading)
                current == null -> flowOf(ReportState.Incomplete)
                else -> tests.observeReport(current).map { ReportState.Ready(it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportState.Loading)

    /** Shows the report for [filter] (null: incomplete); [studentClassId] loads that class's students for the picker. */
    fun select(filter: TestReportFilter?, studentClassId: Long?) {
        this.filter.value = filter
        studentClass.value = studentClassId
        selected.value = true
    }
}

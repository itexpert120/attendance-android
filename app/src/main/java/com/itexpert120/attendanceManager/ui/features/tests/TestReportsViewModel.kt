package com.itexpert120.attendanceManager.ui.features.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.data.repo.SubjectTeacherRepository
import com.itexpert120.attendanceManager.data.repo.TestRepository
import com.itexpert120.attendanceManager.domain.AssessmentData
import com.itexpert120.attendanceManager.domain.DateRange
import com.itexpert120.attendanceManager.domain.sortedClasses
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.SubjectTeacher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** The Subjects (archived included, a report can cover them), Class Groups and teacher assignments. */
data class ReportLookups(val subjects: List<Subject>, val classes: List<ClassGroup>, val teachers: List<SubjectTeacher>)

/**
 * The three test reports. The screen owns the request (saved with `rememberSaveable`) and pushes its date range
 * here with [select]; [data] re-computes whenever Tests, results or assignments change, and the screen derives
 * the chosen report from it with the pure functions in `domain/TestAssessments.kt`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestReportsViewModel(
    tests: TestRepository,
    subjects: SubjectRepository,
    classes: ClassRepository,
    private val teachers: SubjectTeacherRepository,
) : ViewModel() {
    private val range = MutableStateFlow<DateRange?>(null)

    val lookups: StateFlow<ReportLookups?> =
        combine(subjects.observe(), classes.observeClasses(), teachers.observe()) { s, c, t -> ReportLookups(s, c.sortedClasses(), t) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The data for the selected range; a value for an older range may linger briefly, so check its `range`. */
    val data: StateFlow<AssessmentData?> = range
        .flatMapLatest { current -> if (current == null) flowOf(null) else tests.observeAssessmentData(current) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(range: DateRange) {
        this.range.value = range
    }

    /** Saves the changed teacher names of one class; returns null on success or the message to show. */
    suspend fun saveTeachers(classGroupId: Long, names: Map<Long, String>): String? {
        names.forEach { (subjectId, name) ->
            val outcome = attempt("Couldn't save the teacher.") { teachers.setTeacher(classGroupId, subjectId, name) }
            if (outcome is Attempt.Failed) return outcome.message
        }
        return null
    }
}

package com.itexpert120.attendanceManager.ui.features.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.data.repo.TestInput
import com.itexpert120.attendanceManager.data.repo.TestRepository
import com.itexpert120.attendanceManager.data.repo.TestWithEntries
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Everything the Tests tab renders; `null` data means the first emission has not arrived yet. */
data class TestsTabData(
    val tests: List<TestWithEntries>,
    val subjects: List<Subject>,
    val classes: List<ClassGroup>,
    val academicYearStartMonth: Int,
) {
    val activeSubjects: List<Subject> get() = subjects.filterNot { it.isArchived }
}

/** The commands the Tests tab's sheets run; the ViewModel implements them, previews use a no-op. */
interface TestsTabActions {
    suspend fun createTest(input: TestInput): Attempt<TestRecord>

    suspend fun addSubject(name: String): Attempt<Subject>

    suspend fun renameSubject(subjectId: Long, name: String): Attempt<Subject>

    suspend fun setSubjectArchived(subjectId: Long, archived: Boolean): Attempt<Unit>
}

class TestsTabViewModel(
    private val tests: TestRepository,
    private val subjects: SubjectRepository,
    classes: ClassRepository,
    settings: SettingsRepository,
) : ViewModel(), TestsTabActions {
    val data: StateFlow<TestsTabData?> = combine(
        tests.observeTestsWithEntries(),
        subjects.observe(),
        classes.observeClasses(),
        settings.observeSettings(),
    ) { testList, subjectList, classList, school ->
        TestsTabData(testList, subjectList, classList, school?.academicYearStartMonth ?: 1)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override suspend fun createTest(input: TestInput): Attempt<TestRecord> =
        attempt("Could not create this Test.") { tests.createTest(input) }

    override suspend fun addSubject(name: String): Attempt<Subject> =
        attempt("Could not add this Subject.") { subjects.saveSubject(name) }

    override suspend fun renameSubject(subjectId: Long, name: String): Attempt<Subject> =
        attempt("Could not rename this Subject.") { subjects.renameSubject(subjectId, name) }

    override suspend fun setSubjectArchived(subjectId: Long, archived: Boolean): Attempt<Unit> =
        attempt("Could not update this Subject.") {
            if (archived) subjects.archiveSubject(subjectId) else subjects.restoreSubject(subjectId)
        }
}

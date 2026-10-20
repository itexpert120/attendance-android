package com.itexpert120.attendanceManager.ui.features.homework

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.HomeworkReportWithItems
import com.itexpert120.attendanceManager.data.repo.HomeworkRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Everything the Homework tab renders; `null` means the first emission has not arrived yet. */
data class HomeworkTabData(
    val reports: List<HomeworkReportWithItems>,
    val classes: List<ClassGroup>,
    val subjects: List<Subject>,
)

/** The Subject catalogue commands of the Homework tab and editor (the web app's "Manage Subjects"). */
interface HomeworkSubjectActions {
    suspend fun addSubject(name: String): HomeworkResult<Subject>

    suspend fun renameSubject(subjectId: Long, name: String): HomeworkResult<Subject>

    suspend fun setSubjectArchived(subjectId: Long, archived: Boolean): HomeworkResult<Unit>
}

/** The repository side of [HomeworkSubjectActions], shared by the tab's and the editor's ViewModels. */
internal class HomeworkSubjectCommands(private val subjects: SubjectRepository) {
    suspend fun add(name: String): HomeworkResult<Subject> =
        homeworkAttempt("Could not create this Subject.") { subjects.saveSubject(name) }

    suspend fun rename(subjectId: Long, name: String): HomeworkResult<Subject> =
        homeworkAttempt("Could not rename this Subject.") { subjects.renameSubject(subjectId, name) }

    suspend fun setArchived(subjectId: Long, archived: Boolean): HomeworkResult<Unit> =
        homeworkAttempt("Could not update this Subject.") {
            if (archived) subjects.archiveSubject(subjectId) else subjects.restoreSubject(subjectId)
        }
}

class HomeworkTabViewModel(
    homework: HomeworkRepository,
    classes: ClassRepository,
    subjects: SubjectRepository,
) : ViewModel(), HomeworkSubjectActions {
    private val commands = HomeworkSubjectCommands(subjects)

    val data: StateFlow<HomeworkTabData?> = combine(
        homework.observeReports(),
        classes.observeClasses(),
        subjects.observe(),
    ) { reports, classList, subjectList ->
        HomeworkTabData(reports, classList, subjectList)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    override suspend fun addSubject(name: String): HomeworkResult<Subject> = commands.add(name)

    override suspend fun renameSubject(subjectId: Long, name: String): HomeworkResult<Subject> =
        commands.rename(subjectId, name)

    override suspend fun setSubjectArchived(subjectId: Long, archived: Boolean): HomeworkResult<Unit> =
        commands.setArchived(subjectId, archived)
}

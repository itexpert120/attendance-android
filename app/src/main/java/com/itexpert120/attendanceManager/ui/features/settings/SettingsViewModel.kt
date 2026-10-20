package com.itexpert120.attendanceManager.ui.features.settings

import com.itexpert120.attendanceManager.data.db.ClassUsage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.data.repo.StudentRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.ui.theme.ThemePreference
import com.itexpert120.attendanceManager.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the Settings tab shows; null in [SettingsViewModel.data] until the first emission. */
data class SettingsTabData(
    val school: SchoolSettings?,
    val classes: List<ClassGroup>,
    val subjects: List<Subject>,
    /** Every student of the school, on roll or not (the PWA's `students.length`). */
    val studentCount: Int = 0,
) {
    val activeSubjectCount: Int get() = subjects.count { !it.isArchived }
}

/** The commands the Classes and Subjects sheets run. Each returns null on success or the message to show inline. */
interface CatalogActions {
    suspend fun addClass(className: String, section: String): String?

    suspend fun renameClass(classGroupId: Long, className: String, section: String): String?

    /** What deleting the class would remove, or null if it can't be read. */
    suspend fun classUsage(classGroupId: Long): ClassUsage?

    suspend fun deleteClass(classGroupId: Long): String?

    suspend fun addSubject(name: String): String?

    suspend fun renameSubject(subjectId: Long, name: String): String?

    suspend fun setSubjectArchived(subjectId: Long, archived: Boolean): String?
}

class SettingsViewModel(
    settings: SettingsRepository,
    private val classes: ClassRepository,
    students: StudentRepository,
    private val subjects: SubjectRepository,
    private val theme: ThemePreferenceStore,
) : ViewModel(), CatalogActions {
    val data: StateFlow<SettingsTabData?> = combine(
        settings.observeSettings(),
        classes.observeClasses(),
        subjects.observe(),
        students.observeStudents().map { it.size }.distinctUntilChanged(),
    ) { school, classList, subjectList, studentCount -> SettingsTabData(school, classList, subjectList, studentCount) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val themePreference: StateFlow<ThemePreference> = theme.preference
    val dynamicColor: StateFlow<Boolean> = theme.dynamicColor

    fun setTheme(preference: ThemePreference) = theme.set(preference)

    fun setDynamicColor(enabled: Boolean) = theme.setDynamicColor(enabled)

    override suspend fun addClass(className: String, section: String): String? =
        failureOf("Could not add the class.") { classes.addClass(className, section) }

    override suspend fun renameClass(classGroupId: Long, className: String, section: String): String? =
        failureOf("Could not rename the class.") { classes.renameClass(classGroupId, className, section) }

    override suspend fun classUsage(classGroupId: Long): ClassUsage? =
        runCatching { classes.classUsage(classGroupId) }.getOrNull()

    override suspend fun deleteClass(classGroupId: Long): String? =
        failureOf("Could not delete the class.") { classes.deleteClass(classGroupId) }

    override suspend fun addSubject(name: String): String? =
        failureOf("Could not add this Subject.") { subjects.saveSubject(name) }

    override suspend fun renameSubject(subjectId: Long, name: String): String? =
        failureOf("Could not rename this Subject.") { subjects.renameSubject(subjectId, name) }

    override suspend fun setSubjectArchived(subjectId: Long, archived: Boolean): String? =
        failureOf("Could not update this Subject.") {
            if (archived) subjects.archiveSubject(subjectId) else subjects.restoreSubject(subjectId)
        }
}

/** Runs a repository command; a [ValidationException] keeps the repository's own text, anything else becomes [fallback]. */
internal suspend fun failureOf(fallback: String, block: suspend () -> Unit): String? = try {
    block()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (invalid: ValidationException) {
    invalid.message ?: fallback
} catch (_: Exception) {
    fallback
}

package com.itexpert120.attendanceManager.ui.features.homework

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.files.PickedPhoto
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.DiaryPhotoInput
import com.itexpert120.attendanceManager.data.repo.HomeworkReportWithItems
import com.itexpert120.attendanceManager.data.repo.HomeworkRepository
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

/** The diary photo of the draft. A newly picked photo lives only in memory (it is too big for saved state). */
sealed interface DraftPhoto {
    data object None : DraftPhoto

    /** The photo the saved report already has. */
    data class Existing(val fileName: String) : DraftPhoto

    class New(val photo: PickedPhoto) : DraftPhoto
}

/** An item taken off the draft, kept so the Snackbar's Undo can put it back in place. */
data class RemovedHomeworkItem(val item: HomeworkDraftItem, val index: Int, val subjectName: String?)

/** Everything the editor draws once the draft is ready. */
data class HomeworkEditorForm(
    val draft: HomeworkDraft,
    val photo: DraftPhoto,
    val classes: List<ClassGroup>,
    /** Every Subject, active first (archived ones only matter to items that already use them). */
    val subjects: List<Subject>,
    val isNew: Boolean,
    val saving: Boolean,
    /** True after a failed save, so missing fields light up (and keep updating as they are fixed). */
    val showErrors: Boolean,
    val error: HomeworkFormError?,
    /** Increases with each failed save, so the form scrolls to the error even when the message repeats. */
    val errorSerial: Int,
    /** Unsaved changes, for the "Discard changes?" question on Back. */
    val dirty: Boolean,
) {
    val activeSubjects: List<Subject> get() = subjects.filterNot { it.isArchived }

    val fieldErrors: HomeworkFieldErrors = if (showErrors) homeworkFieldErrors(draft) else HomeworkFieldErrors()

    val subjectNames: Map<Long, String> get() = subjects.associate { it.id to it.name }
}

sealed interface HomeworkEditorState {
    data object Loading : HomeworkEditorState

    /** The report does not exist (any more). */
    data object NotFound : HomeworkEditorState

    /** Saved or deleted; the screen leaves. */
    data object Closed : HomeworkEditorState

    /**
     * Saved with "Save & PDF": the screen opens the PDF of report [reportId] and leaves once that sheet is
     * closed (the web app printed, then went back).
     */
    data class SavedForPdf(val reportId: Long, val group: ClassGroup?, val date: LocalDate) : HomeworkEditorState

    data class Ready(val form: HomeworkEditorForm) : HomeworkEditorState
}

/** The editor's commands; the ViewModel implements them, previews use a no-op. */
interface HomeworkEditorActions : HomeworkSubjectActions {
    fun updateDraft(transform: (HomeworkDraft) -> HomeworkDraft)

    fun setPhoto(photo: PickedPhoto)

    /** Takes the photo off the draft and returns it for Undo. */
    fun removePhoto(): DraftPhoto

    fun restorePhoto(photo: DraftPhoto)

    fun removeItem(key: Long): RemovedHomeworkItem?

    fun restoreItem(removed: RemovedHomeworkItem)

    /**
     * Saves on the ViewModel scope (a rotation can't cut it short); the state turns [HomeworkEditorState.Closed],
     * or [HomeworkEditorState.SavedForPdf] when [thenPdf] (the web app's "Save & PDF").
     */
    fun save(thenPdf: Boolean = false)

    fun delete()

    /** Adds a Subject from the Subjects manager and, in typed mode, a homework row for it (the web app's "Add & use"). */
    suspend fun addAndUseSubject(name: String): HomeworkResult<Subject>
}

private enum class Phase { LOADING, NOT_FOUND, READY, CLOSED, SAVED_FOR_PDF }

private data class Status(
    val phase: Phase,
    /** The report saved by "Save & PDF", for [Phase.SAVED_FOR_PDF]. */
    val saved: HomeworkReportWithItems? = null,
    val saving: Boolean = false,
    val showErrors: Boolean = false,
    val error: HomeworkFormError? = null,
    val errorSerial: Int = 0,
)

/**
 * State holder of the homework editor. The draft (all text, the class, date, mode and items) is written to the
 * [SavedStateHandle] on every change, so it survives rotation and process death; a freshly picked photo
 * survives rotation only.
 */
class HomeworkEditorViewModel(
    private val reportId: Long?,
    private val homework: HomeworkRepository,
    private val subjectRepository: SubjectRepository,
    private val classRepository: ClassRepository,
    private val settings: SettingsRepository,
    private val handle: SavedStateHandle,
) : ViewModel(), HomeworkEditorActions {

    private val draft = MutableStateFlow(handle.get<String>(KEY_DRAFT)?.let(::decodeDraft))
    private val baseline = MutableStateFlow(handle.get<String>(KEY_BASELINE)?.let(::decodeDraft))
    private val photo = MutableStateFlow<DraftPhoto>(
        handle.get<String>(KEY_PHOTO_FILE)?.let { DraftPhoto.Existing(it) } ?: DraftPhoto.None,
    )
    private val baselinePhoto: String? get() = handle[KEY_BASELINE_PHOTO]

    private val status = MutableStateFlow(
        Status(
            phase = if (draft.value != null) Phase.READY else Phase.LOADING,
            showErrors = handle[KEY_SHOW_ERRORS] ?: false,
            error = handle.get<String>(KEY_ERROR)?.let { message ->
                draft.value?.let { HomeworkFormError(message, homeworkErrorTarget(message, it)) }
            },
        ),
    )

    val state: StateFlow<HomeworkEditorState> = combine(
        combine(draft, baseline, photo, ::Triple),
        status,
        classRepository.observeClasses(),
        subjectRepository.observe(),
    ) { (current, base, picture), st, classes, subjects ->
        when {
            st.phase == Phase.CLOSED -> HomeworkEditorState.Closed
            st.phase == Phase.SAVED_FOR_PDF && st.saved != null -> st.saved.report.let { report ->
                HomeworkEditorState.SavedForPdf(report.id, classes.firstOrNull { it.id == report.classGroupId }, report.date)
            }
            st.phase == Phase.NOT_FOUND -> HomeworkEditorState.NotFound
            current == null -> HomeworkEditorState.Loading
            else -> HomeworkEditorState.Ready(
                HomeworkEditorForm(
                    draft = current,
                    photo = picture,
                    classes = classes,
                    subjects = subjects,
                    isNew = reportId == null,
                    saving = st.saving,
                    showErrors = st.showErrors,
                    error = st.error,
                    errorSerial = st.errorSerial,
                    dirty = current != base || (current.mode == HomeworkEntryMode.PHOTO && photoChanged(picture)),
                ),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeworkEditorState.Loading)

    init {
        if (draft.value == null) load()
    }

    private fun load() {
        viewModelScope.launch {
            val start = if (reportId == null) {
                val classes = classRepository.observeClasses().first()
                val active = subjectRepository.observe().first().filterNot { it.isArchived }
                val incharge = settings.getSettings()?.classInchargeName.orEmpty()
                newHomeworkDraft(classes, active, incharge, homeworkToday())
            } else {
                val report = homework.getReport(reportId)
                if (report == null) {
                    status.update { it.copy(phase = Phase.NOT_FOUND) }
                    return@launch
                }
                report.report.photoFile?.let { setPhotoState(DraftPhoto.Existing(it)) }
                handle[KEY_BASELINE_PHOTO] = report.report.photoFile
                homeworkDraftFrom(report)
            }
            baseline.value = start
            handle[KEY_BASELINE] = encodeDraft(start)
            setDraft(start)
            status.update { it.copy(phase = Phase.READY) }
        }
    }

    private fun photoChanged(current: DraftPhoto): Boolean = when (current) {
        DraftPhoto.None -> baselinePhoto != null
        is DraftPhoto.Existing -> current.fileName != baselinePhoto
        is DraftPhoto.New -> true
    }

    private fun setDraft(value: HomeworkDraft) {
        draft.value = value
        handle[KEY_DRAFT] = encodeDraft(value)
    }

    private fun setPhotoState(value: DraftPhoto) {
        photo.value = value
        handle[KEY_PHOTO_FILE] = (value as? DraftPhoto.Existing)?.fileName
    }

    override fun updateDraft(transform: (HomeworkDraft) -> HomeworkDraft) {
        draft.value?.let { setDraft(transform(it)) }
    }

    override fun setPhoto(photo: PickedPhoto) {
        setPhotoState(DraftPhoto.New(photo))
    }

    override fun removePhoto(): DraftPhoto {
        val previous = photo.value
        setPhotoState(DraftPhoto.None)
        return previous
    }

    override fun restorePhoto(photo: DraftPhoto) {
        if (this.photo.value == DraftPhoto.None) setPhotoState(photo)
    }

    override fun removeItem(key: Long): RemovedHomeworkItem? {
        val current = draft.value ?: return null
        val index = current.items.indexOfFirst { it.key == key }
        if (index < 0) return null
        val item = current.items[index]
        val name = item.subjectId?.let { id -> (state.value as? HomeworkEditorState.Ready)?.form?.subjectNames?.get(id) }
        setDraft(current.withoutItem(key))
        return RemovedHomeworkItem(item, index, name)
    }

    override fun restoreItem(removed: RemovedHomeworkItem) {
        updateDraft { it.withItemRestored(removed.item, removed.index) }
    }

    override fun save(thenPdf: Boolean) {
        val current = draft.value ?: return
        if (status.value.saving) return
        status.update { it.copy(saving = true) }
        val photoInput = when (val picture = photo.value) {
            DraftPhoto.None -> null
            is DraftPhoto.Existing -> DiaryPhotoInput.KeepExisting
            is DraftPhoto.New -> DiaryPhotoInput.New(picture.photo.bytes, picture.photo.mimeType)
        }
        viewModelScope.launch {
            val input = current.toInput(photoInput)
            val result = withContext(NonCancellable) {
                homeworkAttempt("Could not save this Daily Homework Report.") {
                    if (reportId == null) homework.createReport(input) else homework.updateReport(reportId, input)
                }
            }
            when (result) {
                is HomeworkResult.Ok -> status.update {
                    if (thenPdf) {
                        it.copy(phase = Phase.SAVED_FOR_PDF, saved = result.value, saving = false)
                    } else {
                        it.copy(phase = Phase.CLOSED, saving = false)
                    }
                }
                is HomeworkResult.Failed -> fail(result.message, current)
            }
        }
    }

    override fun delete() {
        val id = reportId ?: return
        if (status.value.saving) return
        status.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = withContext(NonCancellable) {
                homeworkAttempt("Could not delete this Daily Homework Report.") { homework.deleteReport(id) }
            }
            when (result) {
                is HomeworkResult.Ok -> status.update { it.copy(phase = Phase.CLOSED, saving = false) }
                is HomeworkResult.Failed -> {
                    val message = result.message
                    status.update {
                        it.copy(
                            saving = false,
                            error = HomeworkFormError(message, HomeworkErrorTarget.GENERAL),
                            errorSerial = it.errorSerial + 1,
                        )
                    }
                }
            }
        }
    }

    private fun fail(message: String, submitted: HomeworkDraft) {
        handle[KEY_SHOW_ERRORS] = true
        handle[KEY_ERROR] = message
        status.update {
            it.copy(
                saving = false,
                showErrors = true,
                error = HomeworkFormError(message, homeworkErrorTarget(message, submitted)),
                errorSerial = it.errorSerial + 1,
            )
        }
    }

    private val subjectCommands = HomeworkSubjectCommands(subjectRepository)

    /** Reports per Subject, for the Subjects manager's "Active · 2 homework reports". */
    val subjectReportCounts: StateFlow<Map<Long, Int>> = homework.observeReports()
        .map(::homeworkReportCountBySubject)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    override suspend fun addSubject(name: String): HomeworkResult<Subject> = subjectCommands.add(name)

    override suspend fun addAndUseSubject(name: String): HomeworkResult<Subject> {
        val result = subjectCommands.add(name)
        if (result is HomeworkResult.Ok) {
            val id = result.value.id
            updateDraft { current ->
                if (current.mode == HomeworkEntryMode.TYPED && !current.usesSubject(id)) current.withItemAdded(id) else current
            }
        }
        return result
    }

    override suspend fun renameSubject(subjectId: Long, name: String): HomeworkResult<Subject> =
        subjectCommands.rename(subjectId, name)

    override suspend fun setSubjectArchived(subjectId: Long, archived: Boolean): HomeworkResult<Unit> {
        // As the web app: a Subject on the open draft stays active until its row is removed.
        if (archived && draft.value?.usesSubject(subjectId) == true) {
            return HomeworkResult.Failed("Remove this Subject from the report before archiving it.")
        }
        return subjectCommands.setArchived(subjectId, archived)
    }

    private companion object {
        const val KEY_DRAFT = "homework.draft"
        const val KEY_BASELINE = "homework.baseline"
        const val KEY_PHOTO_FILE = "homework.photoFile"
        const val KEY_BASELINE_PHOTO = "homework.baselinePhoto"
        const val KEY_SHOW_ERRORS = "homework.showErrors"
        const val KEY_ERROR = "homework.error"

        val json = Json { ignoreUnknownKeys = true }

        fun encodeDraft(draft: HomeworkDraft): String = json.encodeToString(HomeworkDraft.serializer(), draft)

        fun decodeDraft(text: String): HomeworkDraft? =
            runCatching { json.decodeFromString(HomeworkDraft.serializer(), text) }.getOrNull()
    }
}

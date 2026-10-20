package com.itexpert120.attendanceManager.ui.features.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.files.PickedPhoto
import com.itexpert120.attendanceManager.data.repo.PhotoChange
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.domain.DEFAULT_ABSENCE_MESSAGE_TEMPLATE
import com.itexpert120.attendanceManager.domain.ValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SchoolSettingsUiState(
    /** False until the stored settings have been read into [form]. */
    val loaded: Boolean = false,
    val original: SchoolSettingsForm = SchoolSettingsForm(),
    val form: SchoolSettingsForm = SchoolSettingsForm(),
    /** The logo currently stored, shown while [logo] is [PhotoChange.Keep]. */
    val storedLogoFile: String? = null,
    val logo: PhotoChange = PhotoChange.Keep,
    val saving: Boolean = false,
    /** Inline under the School name field. */
    val schoolNameError: String? = null,
    /** Any other repository message, shown in a banner above the Save bar. */
    val error: String? = null,
    /** Set once the save has committed; the screen then goes back. */
    val saved: Boolean = false,
) {
    val dirty: Boolean get() = loaded && (form != original || logo != PhotoChange.Keep)

    /** The picked replacement, if any, for the preview. */
    val pickedLogo: PickedPhoto? get() = (logo as? PhotoChange.Replace)?.let { PickedPhoto(it.bytes, it.mimeType) }

    val hasLogo: Boolean get() = when (logo) {
        PhotoChange.Keep -> storedLogoFile != null
        PhotoChange.Remove -> false
        is PhotoChange.Replace -> true
    }
}

/**
 * Holds the School settings form so it survives rotation; the text fields are also kept in [savedState] so
 * they survive process death (a picked logo is too large for the saved-state bundle and is re-picked).
 */
class SchoolSettingsViewModel(
    private val settings: SettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(SchoolSettingsUiState())
    val state: StateFlow<SchoolSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val stored = runCatching { settings.getSettings() }.getOrNull()
            val original = stored?.toForm() ?: SchoolSettingsForm()
            val form = restoreForm() ?: original
            val removed = savedState.get<Boolean>(KEY_LOGO_REMOVED) == true
            _state.update {
                it.copy(
                    loaded = true,
                    original = original,
                    form = form,
                    storedLogoFile = stored?.logoFile,
                    logo = if (removed && stored?.logoFile != null) PhotoChange.Remove else PhotoChange.Keep,
                    error = if (stored == null) "Complete the school setup first." else null,
                )
            }
        }
    }

    fun setSchoolName(value: String) = edit(clearsNameError = true) { copy(schoolName = value) }
    fun setStartMonth(month: Int) = edit { copy(academicYearStartMonth = month) }
    fun setCurrency(value: String) = edit { copy(currencyLabel = value) }
    fun setIncharge(value: String) = edit { copy(classInchargeName = value) }
    fun setTemplate(value: String) = edit { copy(template = value.take(TEMPLATE_MAX_LENGTH)) }
    fun resetTemplate() = setTemplate(DEFAULT_ABSENCE_MESSAGE_TEMPLATE)

    fun setLogo(photo: PickedPhoto) {
        savedState[KEY_LOGO_REMOVED] = false
        _state.update { it.copy(logo = PhotoChange.Replace(photo.bytes, photo.mimeType), error = null) }
    }

    fun removeLogo() {
        _state.update { current ->
            val change = if (current.storedLogoFile != null) PhotoChange.Remove else PhotoChange.Keep
            savedState[KEY_LOGO_REMOVED] = change == PhotoChange.Remove
            current.copy(logo = change, error = null)
        }
    }

    fun save() {
        val snapshot = _state.value
        if (!snapshot.loaded || snapshot.saving) return
        val form = snapshot.form
        form.schoolNameError?.let { message ->
            _state.update { it.copy(schoolNameError = message) }
            return
        }
        _state.update { it.copy(saving = true, error = null, schoolNameError = null) }
        viewModelScope.launch {
            try {
                settings.saveSettings(
                    schoolName = form.schoolName,
                    academicYearStartMonth = form.academicYearStartMonth,
                    currencyLabel = form.currencyLabel,
                    classInchargeName = form.classInchargeName,
                    absenceMessageTemplate = templateForSave(form.template),
                    logo = snapshot.logo,
                )
                clearSavedForm()
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ValidationException) {
                val message = e.message ?: GENERIC_FAILURE
                _state.update {
                    if (message == SCHOOL_NAME_REQUIRED) {
                        it.copy(saving = false, schoolNameError = message)
                    } else {
                        it.copy(saving = false, error = message)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = GENERIC_FAILURE) }
            }
        }
    }

    private fun edit(clearsNameError: Boolean = false, transform: SchoolSettingsForm.() -> SchoolSettingsForm) {
        _state.update { current ->
            if (!current.loaded || current.saving) return@update current
            val form = current.form.transform()
            persist(form)
            current.copy(
                form = form,
                error = null,
                schoolNameError = if (clearsNameError) null else current.schoolNameError,
            )
        }
    }

    private fun persist(form: SchoolSettingsForm) {
        savedState[KEY_NAME] = form.schoolName
        savedState[KEY_MONTH] = form.academicYearStartMonth
        savedState[KEY_CURRENCY] = form.currencyLabel
        savedState[KEY_INCHARGE] = form.classInchargeName
        savedState[KEY_TEMPLATE] = form.template
    }

    private fun restoreForm(): SchoolSettingsForm? {
        val name = savedState.get<String>(KEY_NAME) ?: return null
        return SchoolSettingsForm(
            schoolName = name,
            academicYearStartMonth = savedState.get<Int>(KEY_MONTH) ?: 4,
            currencyLabel = savedState.get<String>(KEY_CURRENCY).orEmpty(),
            classInchargeName = savedState.get<String>(KEY_INCHARGE).orEmpty(),
            template = savedState.get<String>(KEY_TEMPLATE).orEmpty(),
        )
    }

    private fun clearSavedForm() {
        listOf(KEY_NAME, KEY_MONTH, KEY_CURRENCY, KEY_INCHARGE, KEY_TEMPLATE, KEY_LOGO_REMOVED).forEach {
            savedState.remove<Any>(it)
        }
    }

    private companion object {
        const val GENERIC_FAILURE = "Could not save the school settings. Please try again."
        const val KEY_NAME = "school.name"
        const val KEY_MONTH = "school.month"
        const val KEY_CURRENCY = "school.currency"
        const val KEY_INCHARGE = "school.incharge"
        const val KEY_TEMPLATE = "school.template"
        const val KEY_LOGO_REMOVED = "school.logoRemoved"
    }
}

package com.itexpert120.attendanceManager.ui.features.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.files.PickedPhoto
import com.itexpert120.attendanceManager.data.repo.PhotoChange
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.domain.ValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the whole onboarding form so it survives rotation (the picked logo is far too big for a saved-state
 * bundle). On success nothing needs to be reported: `AttendanceApp` swaps to the tabs when settings appear.
 */
class SetupViewModel(private val settings: SettingsRepository) : ViewModel() {
    private val _form = MutableStateFlow(SetupForm())
    val form: StateFlow<SetupForm> = _form.asStateFlow()

    /** Applies an edit to a field; a stale inline error is dropped because the user is fixing it. */
    private fun edit(transform: SetupForm.() -> SetupForm) = _form.update { it.transform().copy(error = null) }

    fun setSchoolName(value: String) = edit { copy(schoolName = value) }
    fun setClassName(value: String) = edit { copy(className = value) }
    fun setSection(value: String) = edit { copy(section = value) }
    fun setAcademicYearStartMonth(month: Int) = edit { copy(academicYearStartMonth = month) }
    fun setCurrency(value: String) = edit { copy(currencyLabel = value) }
    fun setIncharge(value: String) = edit { copy(classInchargeName = value) }
    fun setLogo(photo: PickedPhoto?) = edit { copy(logo = photo) }

    fun next() = _form.update { if (it.saving) it else it.advance() }
    fun back() = _form.update { if (it.saving) it else it.retreat() }
    fun goTo(step: SetupStep) = _form.update { if (it.saving) it else it.jumpTo(step) }

    fun finish() {
        val snapshot = _form.value
        if (snapshot.saving) return
        _form.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                settings.completeSetup(
                    schoolName = snapshot.schoolName,
                    className = snapshot.className,
                    section = snapshot.section,
                    academicYearStartMonth = snapshot.academicYearStartMonth,
                    currencyLabel = snapshot.currencyLabel,
                    classInchargeName = snapshot.classInchargeName,
                    logo = snapshot.logo?.let { PhotoChange.Replace(it.bytes, it.mimeType) } ?: PhotoChange.Keep,
                )
                // Stay in "saving" until the app replaces this screen, so a second tap can't save twice.
            } catch (e: ValidationException) {
                fail(e.message ?: GENERIC_FAILURE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(GENERIC_FAILURE)
            }
        }
    }

    private fun fail(message: String) = _form.update { it.copy(saving = false, error = message) }

    private companion object {
        const val GENERIC_FAILURE = "Could not save your school. Please try again."
    }
}

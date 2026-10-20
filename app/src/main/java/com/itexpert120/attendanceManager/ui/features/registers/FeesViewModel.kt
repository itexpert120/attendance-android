package com.itexpert120.attendanceManager.ui.features.registers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.repo.RegisterRepository
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.InstallmentNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Transient state of the Fees section's sheets: a save in flight and its inline error. */
data class FeesUiState(val saving: Boolean = false, val error: String? = null)

/** One-off results the screen turns into a closed sheet and a snackbar. */
sealed interface FeesEvent {
    data class StudentSaved(val studentName: String) : FeesEvent
    data class InstallmentSaved(val installment: InstallmentNumber) : FeesEvent
}

/**
 * Saves fee entries, remarks and installment metadata. The register's data itself arrives from the host
 * screen as an immutable snapshot, so this ViewModel only owns the saving state and its outcome.
 */
class FeesViewModel(private val registers: RegisterRepository) : ViewModel() {
    private val saving = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val eventChannel = Channel<FeesEvent>(Channel.BUFFERED)

    val state: StateFlow<FeesUiState> = combine(saving, error, ::FeesUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeesUiState())

    val events: Flow<FeesEvent> = eventChannel.receiveAsFlow()

    fun clearError() {
        error.value = null
    }

    /** Writes all three installments and the remark of one student. */
    fun saveStudent(registerId: Long, enrollmentId: Long, studentName: String, form: FeeForm) {
        launchSave(FeesEvent.StudentSaved(studentName)) {
            registers.saveFeeEntries(registerId, enrollmentId, form.toInstallmentFees())
            registers.saveRemark(registerId, enrollmentId, form.remark)
        }
    }

    fun saveInstallment(registerId: Long, installment: InstallmentNumber, form: InstallmentMetaForm) {
        launchSave(FeesEvent.InstallmentSaved(installment)) {
            registers.saveInstallmentMeta(registerId, installment, form.rateMinor, form.receiver)
        }
    }

    private fun launchSave(success: FeesEvent, block: suspend () -> Unit) {
        if (saving.value) return
        saving.value = true
        error.value = null
        viewModelScope.launch {
            try {
                block()
                eventChannel.send(success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: ValidationException) {
                error.value = invalid.message ?: GENERIC_ERROR
            } catch (_: Exception) {
                error.value = GENERIC_ERROR
            } finally {
                saving.value = false
            }
        }
    }

    private companion object {
        const val GENERIC_ERROR = "Could not save. Please try again."
    }
}

package com.itexpert120.attendanceManager.ui.features.registers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Which parent of which report has been messaged: a period (for example "daily-14") and an enrollment. */
internal data class SentKey(val period: String, val enrollmentId: Long)

/**
 * Holds what must outlive the message sheet while the register screen is open: the "messaged" ticks, so
 * closing and reopening the sheet (or rotating) does not lose track of who the teacher already contacted.
 */
internal class ReportsViewModel(
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    val today: StateFlow<LocalDate> = attendanceTodayFlow(clock, timeZone)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock.now().toLocalDateTime(timeZone).date)

    private val _sent = MutableStateFlow<Set<SentKey>>(emptySet())
    val sent: StateFlow<Set<SentKey>> = _sent.asStateFlow()

    fun setSent(key: SentKey, sent: Boolean) {
        _sent.update { if (sent) it + key else it - key }
    }
}

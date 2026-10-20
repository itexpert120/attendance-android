package com.itexpert120.attendanceManager.ui.features.registers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.data.repo.RegisterRepository
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** A snackbar request; [onUndo] non-null shows an Undo action. */
internal class AttendanceMessage(val text: String, val onUndo: (() -> Unit)? = null)

/** The device's current date, re-read every minute so a screen left open rolls over at midnight. */
internal fun attendanceTodayFlow(clock: Clock, timeZone: TimeZone, pollMillis: Long = 60_000L): Flow<LocalDate> = flow {
    while (true) {
        emit(clock.now().toLocalDateTime(timeZone).date)
        delay(pollMillis)
    }
}.distinctUntilChanged()

/**
 * Writes attendance edits for one register. The UI prevents every invalid tap (the repository silently
 * ignores them), so this class only has to run the writes off the main thread, remember what to restore
 * for Undo, and report failures as messages.
 */
internal class AttendanceViewModel(
    private val registers: RegisterRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    val today: StateFlow<LocalDate> = attendanceTodayFlow(clock, timeZone)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock.now().toLocalDateTime(timeZone).date)

    private val messageChannel = Channel<AttendanceMessage>(Channel.BUFFERED)
    val messages: Flow<AttendanceMessage> = messageChannel.receiveAsFlow()

    fun post(text: String) {
        messageChannel.trySend(AttendanceMessage(text))
    }

    fun setMark(registerId: Long, enrollmentId: Long, day: Int, session: Session, status: AttendanceStatus?) {
        launchWrite { registers.setMark(registerId, enrollmentId, day, session, status) }
    }

    /** Swipe gesture: absent for both timings, with Undo. */
    fun markAbsent(data: RegisterData, enrollmentId: Long, name: String, day: Int) {
        val registerId = data.register.id
        val before = snapshotMarks(data, day, Session.entries).filter { it.enrollmentId == enrollmentId }
        if (before.all { it.previous == AttendanceStatus.ABSENT }) {
            post("$name is already absent")
            return
        }
        launchWrite {
            Session.entries.forEach { registers.setMark(registerId, enrollmentId, day, it, AttendanceStatus.ABSENT) }
            messageChannel.trySend(AttendanceMessage("$name marked absent") { restore(registerId, day, before) })
        }
    }

    /** Sets (or with a null [status] clears) every student's mark for the given timings, with Undo. */
    fun bulk(data: RegisterData, day: Int, sessions: List<Session>, status: AttendanceStatus?) {
        val registerId = data.register.id
        val before = snapshotMarks(data, day, sessions)
        if (before.isEmpty()) return
        val changed = before.count { it.previous != status }
        if (changed == 0) {
            post(if (status == null) "Nothing to clear" else "Everyone is already marked ${bulkStatusWord(status)}")
            return
        }
        val timings = timingsText(sessions)
        launchWrite {
            sessions.forEach { registers.bulkSetMarks(registerId, day, it, status) }
            val text = if (status == null) "Cleared $timings" else "All ${bulkStatusWord(status)} for $timings"
            messageChannel.trySend(AttendanceMessage(text) { restore(registerId, day, before) })
        }
    }

    /** Marks only the blank cells as present, leaving absences and leaves untouched. */
    fun fillBlanksPresent(data: RegisterData, day: Int) {
        val registerId = data.register.id
        val blanks = blankCells(data, day)
        if (blanks.isEmpty()) {
            post("No blank marks left")
            return
        }
        launchWrite {
            blanks.forEach { registers.setMark(registerId, it.enrollmentId, day, it.session, AttendanceStatus.PRESENT) }
            messageChannel.trySend(
                AttendanceMessage("${blanks.size} blank marks set to present") { restore(registerId, day, blanks) },
            )
        }
    }

    fun declareHoliday(data: RegisterData, day: Int, reason: String) {
        val registerId = data.register.id
        val hadMarks = data.hasMarksOn(day)
        launchWrite {
            registers.toggleHoliday(registerId, day, reason)
            // Deleted marks are not restored, so Undo is only honest when there was nothing to lose.
            val undo: (() -> Unit)? = if (hadMarks) null else ({ toggleHoliday(registerId, day, "") })
            messageChannel.trySend(AttendanceMessage("Day declared a holiday", undo))
        }
    }

    fun removeHoliday(data: RegisterData, day: Int) {
        val registerId = data.register.id
        val title = data.holidayTitle(day).orEmpty()
        launchWrite {
            registers.toggleHoliday(registerId, day)
            messageChannel.trySend(AttendanceMessage("Holiday removed") { toggleHoliday(registerId, day, title) })
        }
    }

    private fun toggleHoliday(registerId: Long, day: Int, reason: String) {
        launchWrite { registers.toggleHoliday(registerId, day, reason) }
    }

    private fun restore(registerId: Long, day: Int, changes: List<MarkChange>) {
        launchWrite {
            changes.forEach { registers.setMark(registerId, it.enrollmentId, day, it.session, it.previous) }
        }
    }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ValidationException) {
                post(e.message ?: "Could not save attendance")
            } catch (e: Exception) {
                post("Could not save attendance")
            }
        }
    }

    private fun bulkStatusWord(status: AttendanceStatus): String = when (status) {
        AttendanceStatus.PRESENT -> "present"
        AttendanceStatus.ABSENT -> "absent"
        AttendanceStatus.LEAVE -> "on leave"
    }

    private fun timingsText(sessions: List<Session>): String = when {
        sessions.size >= 2 -> "both timings"
        sessions.first() == Session.FIRST -> "the first timing"
        else -> "the second timing"
    }
}

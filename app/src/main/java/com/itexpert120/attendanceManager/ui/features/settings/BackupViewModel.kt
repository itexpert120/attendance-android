package com.itexpert120.attendanceManager.ui.features.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itexpert120.attendanceManager.data.backup.AppBackup
import com.itexpert120.attendanceManager.data.backup.AppBackupSummary
import com.itexpert120.attendanceManager.data.backup.BackupSummary
import com.itexpert120.attendanceManager.data.backup.BackupTables
import com.itexpert120.attendanceManager.data.backup.PwaBackupImporter
import com.itexpert120.attendanceManager.data.backup.PwaTables
import com.itexpert120.attendanceManager.domain.ValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.time.Clock

/** Where the Backup & restore flow is; drives the progress dialog and the confirmation sheets. */
internal sealed interface BackupPhase {
    data object Idle : BackupPhase

    data class Working(val message: String) : BackupPhase

    /** A native backup was read; waiting for "Restore". */
    data class ConfirmRestore(val uri: Uri, val summary: AppBackupSummary) : BackupPhase

    /** A web-app backup was read; waiting for "Import". */
    data class ConfirmWebImport(val uri: Uri, val summary: BackupSummary) : BackupPhase
}

/** A snackbar to show once; [success] also plays the confirm haptic. */
internal data class BackupMessage(val text: String, val success: Boolean)

/**
 * Runs backup, restore and web-app import off the main thread. Lives in a ViewModel so a rotation in the
 * middle of a restore neither cancels it nor loses the confirmation.
 */
internal class BackupViewModel(
    private val contentResolver: ContentResolver,
    private val appBackup: AppBackup,
    private val webImporter: PwaBackupImporter,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val _phase = MutableStateFlow<BackupPhase>(BackupPhase.Idle)
    val phase: StateFlow<BackupPhase> = _phase.asStateFlow()

    private val _messages = Channel<BackupMessage>(Channel.BUFFERED)
    val messages: Flow<BackupMessage> = _messages.receiveAsFlow()

    /** "attendance-backup-2026-10-07.zip" for today. */
    fun suggestedFileName(): String = AppBackup.suggestedFileName(clock.todayIn(TimeZone.currentSystemDefault()))

    fun backUp(uri: Uri) = launchWork("Backing up…") {
        val summary = try {
            withContext(Dispatchers.IO) {
                val output = contentResolver.openOutputStream(uri, "w") ?: throw FileNotFoundException()
                output.use { appBackup.export(it) }
            }
        } catch (e: Throwable) {
            // Don't leave a half-written backup behind where it could be mistaken for a good one.
            withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } }
            throw e
        }
        val students = summary.counts[BackupTables.STUDENTS] ?: 0
        done("Backup saved · ${plural(students, "student")}, ${plural(summary.photoCount, "photo")}")
    }

    fun inspectRestore(uri: Uri) = launchWork("Reading the backup…") {
        val summary = read(uri) { appBackup.inspect(it) }
        _phase.value = BackupPhase.ConfirmRestore(uri, summary)
    }

    fun confirmRestore() {
        val confirm = _phase.value as? BackupPhase.ConfirmRestore ?: return
        launchWork("Restoring…") {
            val result = read(confirm.uri) { appBackup.restore(it) }
            val students = result.counts[BackupTables.STUDENTS] ?: 0
            val missing = if (result.missingPhotos > 0) " (${plural(result.missingPhotos, "photo")} missing from the file)" else ""
            done("Restored ${result.schoolName ?: "the backup"} · ${plural(students, "student")}$missing")
        }
    }

    fun inspectWebImport(uri: Uri) = launchWork("Reading the web app backup…") {
        val summary = read(uri) { webImporter.inspect(it) }
        _phase.value = BackupPhase.ConfirmWebImport(uri, summary)
    }

    fun confirmWebImport() {
        val confirm = _phase.value as? BackupPhase.ConfirmWebImport ?: return
        launchWork("Importing…") {
            val result = read(confirm.uri) { webImporter.import(it) }
            val students = result.counts[PwaTables.STUDENTS] ?: 0
            val skipped = if (result.warnings.isNotEmpty()) " · ${plural(result.warnings.size, "problem")} fixed or skipped" else ""
            done("Imported ${result.schoolName ?: "the web app backup"} · ${plural(students, "student")}$skipped")
        }
    }

    /** Closes a confirmation; ignored while work is running, which can't be interrupted safely. */
    fun dismiss() {
        if (_phase.value !is BackupPhase.Working) _phase.value = BackupPhase.Idle
    }

    private suspend fun <T> read(uri: Uri, block: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val input = contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
        input.use { block(it) }
    }

    private fun done(text: String) {
        _phase.value = BackupPhase.Idle
        _messages.trySend(BackupMessage(text, success = true))
    }

    private fun launchWork(progress: String, block: suspend () -> Unit) {
        if (_phase.value is BackupPhase.Working) return
        _phase.value = BackupPhase.Working(progress)
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _phase.value = BackupPhase.Idle
                _messages.trySend(BackupMessage(messageFor(e), success = false))
            }
        }
    }

    private fun messageFor(e: Throwable): String = when (e) {
        is ValidationException -> e.message ?: "This file can't be used."
        is FileNotFoundException, is SecurityException -> "The file could not be opened. Pick it again."
        is IOException -> "The file could not be read or written${e.message?.let { ": $it" }.orEmpty()}"
        is OutOfMemoryError -> "This file is too large to open on this phone."
        else -> "Something went wrong${e.message?.let { ": $it" }.orEmpty()}"
    }

    private fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"
}

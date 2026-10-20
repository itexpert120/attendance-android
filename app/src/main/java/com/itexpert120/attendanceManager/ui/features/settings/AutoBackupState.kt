package com.itexpert120.attendanceManager.ui.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.backup.auto.AutoBackupStatus
import com.itexpert120.attendanceManager.data.backup.auto.autoBackupDetail

/** What the "Automatic backups" rows of Settings show, plus their two commands. */
@Immutable
internal data class AutoBackupUi(
    val enabled: Boolean,
    /** "Daily · keeps last 5 · Last: 20 Oct, 14:05", the latest error, or "Backing up…". */
    val detail: String,
    val failed: Boolean,
    val backingUpNow: Boolean,
    val setEnabled: (Boolean) -> Unit,
    val backUpNow: () -> Unit,
)

/** The row's detail line while a "Back up now" is queued or running, otherwise [autoBackupDetail]. */
internal fun autoBackupRowDetail(status: AutoBackupStatus, backingUpNow: Boolean): String =
    if (backingUpNow) "Backing up…" else autoBackupDetail(status)

/**
 * Reads the automatic backup setting and its latest result. Both live outside the school database (a
 * per-device preference and WorkManager), so they are observed straight from the app container.
 */
@Composable
internal fun rememberAutoBackupUi(): AutoBackupUi {
    val container = LocalAppContainer.current
    val preferences = container.autoBackupPreferences
    val scheduler = container.autoBackupScheduler
    val status by preferences.status.collectAsStateWithLifecycle()
    val runningFlow = remember(scheduler) { scheduler.backingUpNow }
    val running by runningFlow.collectAsStateWithLifecycle(initialValue = false)
    return remember(status, running, preferences, scheduler) {
        AutoBackupUi(
            enabled = status.enabled,
            detail = autoBackupRowDetail(status, running),
            failed = status.enabled && status.lastError != null && !running,
            backingUpNow = running,
            setEnabled = { enabled ->
                preferences.setEnabled(enabled)
                scheduler.apply(enabled)
            },
            backUpNow = scheduler::backUpNow,
        )
    }
}

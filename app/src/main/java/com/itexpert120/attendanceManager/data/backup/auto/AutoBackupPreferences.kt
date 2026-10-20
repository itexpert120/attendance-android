package com.itexpert120.attendanceManager.data.backup.auto

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Per-device automatic backup settings and the outcome of the latest run. Kept in SharedPreferences (not
 * the school database) because they describe this phone, not the school's data.
 */
class AutoBackupPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    /** Keeps a strong reference: SharedPreferences only holds listeners weakly. */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = read() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Updates when the setting changes or a backup (run by WorkManager) records its result. */
    val status: StateFlow<AutoBackupStatus> = state.asStateFlow()

    val enabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        state.value = read()
    }

    fun recordSuccess(at: Instant) {
        prefs.edit {
            putLong(KEY_LAST_SUCCESS, at.toEpochMilliseconds())
            remove(KEY_LAST_ERROR)
        }
        state.value = read()
    }

    fun recordFailure(message: String) {
        prefs.edit { putString(KEY_LAST_ERROR, message) }
        state.value = read()
    }

    private fun read(): AutoBackupStatus {
        val millis = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        return AutoBackupStatus(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            lastSuccess = millis.takeIf { it > 0 }
                ?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) },
            lastError = prefs.getString(KEY_LAST_ERROR, null),
        )
    }

    private companion object {
        const val PREFS_NAME = "auto_backup"
        const val KEY_ENABLED = "autoBackupEnabled"
        const val KEY_LAST_SUCCESS = "lastAutoBackupAt"
        const val KEY_LAST_ERROR = "lastAutoBackupError"
    }
}

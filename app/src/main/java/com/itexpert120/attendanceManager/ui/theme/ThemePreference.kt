package com.itexpert120.attendanceManager.ui.theme

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Follows the device by default; Settings can force light or dark (the PWA's per-device theme choice). */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** A per-device preference, so it lives in SharedPreferences rather than the school database or its backups. */
class ThemePreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val preference: StateFlow<ThemePreference> = state.asStateFlow()

    private val dynamic = MutableStateFlow(prefs.getBoolean(DYNAMIC_KEY, true))

    /** Material You colour from the wallpaper (Android 12+); off means the register-green brand scheme. */
    val dynamicColor: StateFlow<Boolean> = dynamic.asStateFlow()

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(DYNAMIC_KEY, enabled) }
        dynamic.value = enabled
    }

    fun set(preference: ThemePreference) {
        prefs.edit { putString(KEY, preference.name) }
        state.value = preference
    }

    private fun read(): ThemePreference =
        ThemePreference.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: ThemePreference.SYSTEM

    private companion object {
        const val KEY = "theme"
        const val DYNAMIC_KEY = "dynamic_color"
    }
}

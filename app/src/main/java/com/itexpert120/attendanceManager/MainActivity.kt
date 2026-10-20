package com.itexpert120.attendanceManager

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.itexpert120.attendanceManager.ui.AttendanceApp
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import com.itexpert120.attendanceManager.ui.theme.ThemePreference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AttendanceApplication).container
        setContent {
            val preference by container.themePreference.preference.collectAsState()
            val dynamicColor by container.themePreference.dynamicColor.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (preference) {
                ThemePreference.SYSTEM -> systemDark
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            // Edge-to-edge with system bar icons that follow the app's own theme choice, not just the device's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                AttendanceTheme(darkTheme = dark, dynamicColor = dynamicColor) { AttendanceApp() }
            }
        }
    }
}

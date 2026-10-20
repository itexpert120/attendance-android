package com.itexpert120.attendanceManager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.itexpert120.attendanceManager.model.AttendanceStatus

/**
 * Fixed semantic colours for attendance. They deliberately ignore Material You: "present" must read as green
 * and "absent" as red on every wallpaper. Never rely on colour alone; always pair with the status letter or icon.
 */
data class StatusColors(val container: Color, val content: Color)

object AttendanceColors {
    private val presentLight = StatusColors(Color(0xFFDCFCE7), Color(0xFF14532D))
    private val presentDark = StatusColors(Color(0xFF14532D), Color(0xFFBBF7D0))
    private val absentLight = StatusColors(Color(0xFFFEE2E2), Color(0xFF7F1D1D))
    private val absentDark = StatusColors(Color(0xFF7F1D1D), Color(0xFFFEE2E2))
    private val leaveLight = StatusColors(Color(0xFFFEF3C7), Color(0xFF78350F))
    private val leaveDark = StatusColors(Color(0xFF713F12), Color(0xFFFEF3C7))

    @Composable
    fun of(status: AttendanceStatus, dark: Boolean = isSystemInDarkTheme()): StatusColors = when (status) {
        AttendanceStatus.PRESENT -> if (dark) presentDark else presentLight
        AttendanceStatus.ABSENT -> if (dark) absentDark else absentLight
        AttendanceStatus.LEAVE -> if (dark) leaveDark else leaveLight
    }
}

package com.itexpert120.attendanceManager.ui.features.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTabDataTest {
    @Test
    fun studentCountDefaultsToZero() {
        assertEquals(0, SettingsTabData(school = null, classes = emptyList(), subjects = emptyList()).studentCount)
    }

    @Test
    fun studentCountIsKept() {
        assertEquals(42, SettingsTabData(null, emptyList(), emptyList(), studentCount = 42).studentCount)
    }
}

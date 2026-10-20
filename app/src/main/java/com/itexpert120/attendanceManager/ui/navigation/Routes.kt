package com.itexpert120.attendanceManager.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Screens are routes. Short tasks (create, edit one item, filters, menus) are bottom sheets inside a
 * screen, not routes, so the system back gesture closes the sheet before it leaves the screen.
 */

/** The four tabs; the PWA's Registers / Tests / Homework / Settings. */
sealed interface TabRoute : NavKey

@Serializable data object RegistersTab : TabRoute
@Serializable data object TestsTab : TabRoute
@Serializable data object HomeworkTab : TabRoute
@Serializable data object SettingsTab : TabRoute

/** A monthly register with its attendance, fees, summary and reports sections. */
@Serializable data class RegisterRoute(val registerId: Long) : NavKey

/** Students and classes, optionally opened on one class. */
@Serializable
data class StudentsRoute(
    val classGroupId: Long? = null,
    /** ISO date a new student's "Admitted on" defaults to (the 1st of the register's month when opened from one). */
    val defaultAdmittedOn: String? = null,
) : NavKey

@Serializable data object TestReportsRoute : NavKey

@Serializable data class TestRoute(val testId: Long) : NavKey

/** The homework diary editor: `reportId == null` creates a new report. */
@Serializable data class HomeworkEditorRoute(val reportId: Long? = null) : NavKey

@Serializable data object SchoolSettingsRoute : NavKey

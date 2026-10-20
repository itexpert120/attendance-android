package com.itexpert120.attendanceManager.ui.navigation

import kotlinx.datetime.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.itexpert120.attendanceManager.ui.features.homework.HomeworkEditorScreen
import com.itexpert120.attendanceManager.ui.features.homework.HomeworkTabScreen
import com.itexpert120.attendanceManager.ui.features.registers.RegisterScreen
import com.itexpert120.attendanceManager.ui.features.registers.RegistersTabScreen
import com.itexpert120.attendanceManager.ui.features.settings.SchoolSettingsScreen
import com.itexpert120.attendanceManager.ui.features.settings.SettingsTabScreen
import com.itexpert120.attendanceManager.ui.features.students.StudentsScreen
import com.itexpert120.attendanceManager.ui.features.tests.TestDetailScreen
import com.itexpert120.attendanceManager.ui.features.tests.TestReportsScreen
import com.itexpert120.attendanceManager.ui.features.tests.TestsTabScreen

private data class TabItem(val route: TabRoute, val label: String, val icon: ImageVector)

private val Tabs = listOf(
    TabItem(RegistersTab, "Registers", Icons.Rounded.CalendarMonth),
    TabItem(TestsTab, "Tests", Icons.Rounded.Quiz),
    TabItem(HomeworkTab, "Homework", Icons.Rounded.MenuBook),
    TabItem(SettingsTab, "Settings", Icons.Rounded.Settings),
)

/**
 * The app's navigation: a bottom bar on the four tab roots and pushed screens above them. As in the PWA,
 * Registers is the bottom of the stack and the other tabs replace each other on top of it, so Back from any
 * tab returns to Registers before leaving the app.
 */
@Composable
fun AppNavHost() {
    val backStack = rememberNavBackStack(RegistersTab)
    val top = backStack.lastOrNull()
    val reselect = remember { MutableSharedFlow<TabRoute>(extraBufferCapacity = 1) }
    val haptics = LocalHapticFeedback.current

    val selectTab: (TabRoute) -> Unit = { tab ->
        if (tab == backStack.lastOrNull()) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            reselect.tryEmit(tab)
        } else {
            backStack.selectTab(tab)
        }
    }

    CompositionLocalProvider(LocalTabReselect provides reselect) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            transitionSpec = { AppTransitions.push() },
            popTransitionSpec = { AppTransitions.pop() },
            predictivePopTransitionSpec = { AppTransitions.pop() },
            entryProvider = entryProvider {
                entry<RegistersTab>(metadata = AppTransitions.tabMetadata) {
                    TabFrame(RegistersTab, onSelect = selectTab) {
                        RegistersTabScreen(
                            onOpenRegister = { backStack.add(RegisterRoute(it)) },
                            onOpenStudents = { backStack.add(StudentsRoute()) },
                            onOpenTests = { backStack.selectTab(TestsTab) },
                            onOpenHomework = { backStack.selectTab(HomeworkTab) },
                        )
                    }
                }
                entry<TestsTab>(metadata = AppTransitions.tabMetadata) {
                    TabFrame(TestsTab, onSelect = selectTab) {
                        TestsTabScreen(
                            onOpenTest = { backStack.add(TestRoute(it)) },
                            onOpenReports = { backStack.add(TestReportsRoute) },
                        )
                    }
                }
                entry<HomeworkTab>(metadata = AppTransitions.tabMetadata) {
                    TabFrame(HomeworkTab, onSelect = selectTab) {
                        HomeworkTabScreen(
                            onNewReport = { backStack.add(HomeworkEditorRoute()) },
                            onEditReport = { backStack.add(HomeworkEditorRoute(it)) },
                        )
                    }
                }
                entry<SettingsTab>(metadata = AppTransitions.tabMetadata) {
                    TabFrame(SettingsTab, onSelect = selectTab) {
                        SettingsTabScreen(
                            onOpenSchoolSettings = { backStack.add(SchoolSettingsRoute) },
                            onOpenStudents = { backStack.add(StudentsRoute()) },
                        )
                    }
                }

                entry<RegisterRoute> {
                    RegisterScreen(
                        it.registerId,
                        onBack = backStack::pop,
                        onOpenStudents = { classGroupId, defaultAdmittedOn ->
                            backStack.add(StudentsRoute(classGroupId, defaultAdmittedOn?.toString()))
                        },
                    )
                }
                entry<StudentsRoute> {
                    StudentsScreen(
                        it.classGroupId,
                        onBack = backStack::pop,
                        defaultAdmittedOn = it.defaultAdmittedOn?.let(LocalDate::parse),
                    )
                }
                entry<TestReportsRoute> { TestReportsScreen(onBack = backStack::pop, onOpenTest = { backStack.add(TestRoute(it)) }) }
                entry<TestRoute> { TestDetailScreen(it.testId, onBack = backStack::pop) }
                entry<HomeworkEditorRoute> { HomeworkEditorScreen(it.reportId, onBack = backStack::pop) }
                entry<SchoolSettingsRoute> { SchoolSettingsScreen(onBack = backStack::pop) }
            },
        )
    }
}

/**
 * A tab root with the bottom bar (a rail on tablets). Each tab entry draws its own bar, so a pushed screen
 * slides in over it like a new Android screen instead of the bar vanishing and the tab re-laying out.
 */
@Composable
private fun TabFrame(selected: TabRoute, onSelect: (TabRoute) -> Unit, content: @Composable () -> Unit) {
    NavigationSuiteScaffold(
        layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()),
        navigationSuiteItems = {
            Tabs.forEach { tab ->
                item(
                    selected = tab.route == selected,
                    onClick = { onSelect(tab.route) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.label) },
                )
            }
        },
        content = content,
    )
}

private fun NavBackStack<NavKey>.pop() {
    if (size > 1) removeLastOrNull()
}

private fun NavBackStack<NavKey>.selectTab(tab: TabRoute) {
    val current = lastOrNull() ?: return
    when {
        current == tab -> Unit
        tab == RegistersTab -> while (size > 1) removeLastOrNull()
        current == RegistersTab -> add(tab)
        current is TabRoute -> this[lastIndex] = tab
        // A pushed screen (e.g. a Test) was on top: go to the tab's root above Registers.
        else -> {
            while (size > 1) removeLastOrNull()
            add(tab)
        }
    }
}

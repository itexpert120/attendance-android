package com.itexpert120.attendanceManager.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter

/** Emits a tab when the user taps the tab that is already selected (the PWA scrolled that screen to the top). */
val LocalTabReselect = staticCompositionLocalOf<SharedFlow<TabRoute>> { MutableSharedFlow() }

/**
 * Runs [onReselect] (typically `listState.animateScrollToItem(0)`) when [tab] is tapped while it is already
 * showing. Place it in the tab's root screen.
 */
@Composable
fun TabReselectEffect(tab: TabRoute, onReselect: suspend () -> Unit) {
    val flow = LocalTabReselect.current
    val latest = rememberUpdatedState(onReselect)
    LaunchedEffect(flow, tab) {
        flow.filter { it == tab }.collect { latest.value() }
    }
}

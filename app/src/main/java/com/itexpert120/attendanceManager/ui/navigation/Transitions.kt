package com.itexpert120.attendanceManager.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.ui.NavDisplay

/**
 * Screen transitions, the way Android pushes screens: the new screen slides in from the end edge on top
 * while the one below shifts a little the other way (parallax), and Back reverses it. No cross-fades.
 * Tabs switch instantly, as bottom navigation does on Android.
 */
internal object AppTransitions {
    private const val DURATION_MS = 380

    /** Material's "emphasized" easing: quick start, long gentle settle. */
    private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val spec = tween<IntOffset>(DURATION_MS, easing = Emphasized)

    /** How far the screen underneath moves, as a fraction of the width. */
    private const val PARALLAX = 0.25f

    fun push(): ContentTransform =
        slideInHorizontally(spec) { it } togetherWith
            slideOutHorizontally(spec) { -(it * PARALLAX).toInt() }

    /** NavDisplay keeps the leaving screen on top while the one underneath slides back into place. */
    fun pop(): ContentTransform =
        slideInHorizontally(spec) { -(it * PARALLAX).toInt() } togetherWith slideOutHorizontally(spec) { it }

    private fun none(): ContentTransform = EnterTransition.None togetherWith ExitTransition.None

    /** Entry metadata for the tab roots: switching tabs (or Back from a tab to Registers) is instant. */
    val tabMetadata: Map<String, Any> =
        NavDisplay.transitionSpec { none() } +
            NavDisplay.popTransitionSpec { none() } +
            NavDisplay.predictivePopTransitionSpec { _ -> none() }
}

package com.itexpert120.attendanceManager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A bottom sheet for short tasks (create, edit one item, filters, menus) that doesn't deserve its own screen
 * (the PWA's `Modal`). The system back gesture dismisses it. [footer] holds the action buttons, typically a
 * [TextButton] to cancel and a filled `Button` to confirm.
 *
 * Edge to edge: the scrim and the sheet's surface run behind the system bars; the sheet handles the bottom
 * insets itself (see [SheetBody]) so long forms stay usable with the keyboard open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SheetMotion {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            // Only the top and sides: the default also pads the bottom (navigation bar + keyboard), which stops
            // the content above the navigation bar instead of letting it run behind it.
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) },
            modifier = modifier,
        ) {
            SheetContentMotion { SheetBody(title, description, footer, content) }
        }
    }
}

private val LocalAppMotionScheme = staticCompositionLocalOf<MotionScheme?> { null }

/**
 * Wrap a `ModalBottomSheet` call in this. The app's expressive springs overshoot, and a tall sheet that
 * overshoots the top visibly wobbles while the sheet squashes its content to hide the gap; sheets settle with
 * the standard (non-bouncy) motion instead. Inside the sheet, use [SheetContentMotion] to get the app's
 * motion back for the content.
 */
@Composable
fun SheetMotion(content: @Composable () -> Unit) {
    val appMotion = MaterialTheme.motionScheme
    CompositionLocalProvider(LocalAppMotionScheme provides appMotion) {
        MaterialTheme(motionScheme = MotionScheme.standard(), content = content)
    }
}

/** Restores the app's motion scheme for a sheet's content; see [SheetMotion]. */
@Composable
fun SheetContentMotion(content: @Composable () -> Unit) {
    MaterialTheme(motionScheme = LocalAppMotionScheme.current ?: MaterialTheme.motionScheme, content = content)
}

/**
 * Title, scrolling content and footer, with the bottom insets handled here:
 * - the keyboard lifts the whole body ([imePadding]): the footer buttons stay above it, and the scroll area
 *   shrinks while keeping the focused field in view;
 * - with a footer, the footer's background fills the navigation bar area and its buttons sit above the bar;
 * - without one, the content scrolls behind the navigation bar and ends with room to clear it.
 * Insets already consumed by [imePadding] are not added again, so nothing is padded twice.
 */
@Composable
private fun SheetBody(
    title: String,
    description: String?,
    footer: (@Composable RowScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = if (footer != null) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            if (footer == null) {
                // Inside the scroll, so the last item can be scrolled clear of the navigation bar.
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
        if (footer != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = footer,
            )
        }
    }
}

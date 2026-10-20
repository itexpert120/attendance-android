package com.itexpert120.attendanceManager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A bottom sheet for short tasks (create, edit one item, filters, menus) that doesn't deserve its own screen
 * (the PWA's `Modal`). The system back gesture dismisses it. [footer] holds the action buttons, typically a
 * [TextButton] to cancel and a filled `Button` to confirm.
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
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            if (footer != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
                    content = footer,
                )
            } else {
                Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {}
            }
        }
}

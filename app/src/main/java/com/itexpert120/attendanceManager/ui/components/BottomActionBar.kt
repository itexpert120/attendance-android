package com.itexpert120.attendanceManager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The bottom action area of a screen or step-by-step flow, the way Android's own setup and editors do it:
 * an optional low-emphasis action on the left ([secondary], e.g. a Back or Delete `TextButton`) and the
 * primary action as a compact filled `Button` on the right ([primary]). Buttons keep their natural width;
 * never stretch them across the screen. Put it in `AppScreen(bottomBar = …)`.
 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    secondary: (@Composable RowScope.() -> Unit)? = null,
    primary: @Composable RowScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (secondary != null) secondary()
            Spacer(Modifier.weight(1f))
            primary()
        }
    }
}

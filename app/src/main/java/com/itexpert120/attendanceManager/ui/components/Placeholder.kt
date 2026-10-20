package com.itexpert120.attendanceManager.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.runtime.Composable

/** Temporary body for a screen that hasn't been ported yet; each feature replaces its own. */
@Composable
fun PlaceholderScreen(title: String, onBack: (() -> Unit)? = null) {
    AppScreen(title = title, onBack = onBack) { padding ->
        PageList(padding) {
            item { EmptyState(Icons.Rounded.Construction, "Coming soon", text = "$title hasn't been ported from the web app yet.") }
        }
    }
}

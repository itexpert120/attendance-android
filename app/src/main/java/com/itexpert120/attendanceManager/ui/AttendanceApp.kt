package com.itexpert120.attendanceManager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.ui.components.DelayedLoading
import com.itexpert120.attendanceManager.ui.features.setup.SetupScreen
import com.itexpert120.attendanceManager.ui.navigation.AppNavHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private sealed interface SchoolState {
    data object Loading : SchoolState
    data class Loaded(val settings: SchoolSettings?) : SchoolState
    data class Failed(val message: String) : SchoolState
}

/** The PWA's fallback when the database error carries no text (`app-state.svelte.ts`). */
private const val OPEN_FAILURE = "Could not open the offline database."

/** Opens on first-run setup until a school exists, then on the tabs; a database that can't open gets its own screen. */
@Composable
fun AttendanceApp() {
    val container = LocalAppContainer.current
    // Bumping this re-subscribes to the settings, which retries opening the database.
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val state by remember(container, attempt) {
        container.settings.observeSettings()
            .map<SchoolSettings?, SchoolState> { SchoolState.Loaded(it) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(SchoolState.Failed(error.message?.takeIf { it.isNotBlank() } ?: OPEN_FAILURE))
            }
    }.collectAsState(initial = SchoolState.Loading)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = state) {
            SchoolState.Loading -> DelayedLoading()
            is SchoolState.Failed -> DatabaseErrorScreen(current.message, onRetry = { attempt++ })
            is SchoolState.Loaded -> if (current.settings == null) SetupScreen() else AppNavHost()
        }
    }
}

/** The PWA's "The offline database could not open" card, with the error text and a retry. */
@Composable
private fun DatabaseErrorScreen(message: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(colors.surfaceContainer).safeDrawingPadding().padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = colors.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.widthIn(max = 448.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = colors.error, modifier = Modifier.size(24.dp))
                Text(
                    "The offline database could not open",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
        }
    }
}

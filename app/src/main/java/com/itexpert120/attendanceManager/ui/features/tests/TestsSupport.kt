package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.foundation.layout.Arrangement
import com.itexpert120.attendanceManager.ui.components.navigationBarBottom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.domain.TestProgress
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.ui.components.BadgeTone
import com.itexpert120.attendanceManager.ui.components.DelayedLoading
import com.itexpert120.attendanceManager.ui.components.DropdownField
import com.itexpert120.attendanceManager.ui.components.PageMaxWidth
import com.itexpert120.attendanceManager.ui.components.StatusBadge
import kotlinx.coroutines.CancellationException

/** The outcome of a repository command: its value, or the message to show inline. */
sealed interface Attempt<out T> {
    data class Ok<T>(val value: T) : Attempt<T>

    data class Failed(val message: String) : Attempt<Nothing>
}

/**
 * Runs a repository command and turns a [ValidationException] into [Attempt.Failed] with the repository's
 * own text. Anything unexpected becomes [fallback] so a bug never crashes a form.
 */
suspend fun <T> attempt(fallback: String, block: suspend () -> T): Attempt<T> = try {
    Attempt.Ok(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (invalid: ValidationException) {
    Attempt.Failed(invalid.message ?: fallback)
} catch (_: Exception) {
    Attempt.Failed(fallback)
}

fun progressTone(progress: TestProgress): BadgeTone = when (progress) {
    TestProgress.NOT_STARTED -> BadgeTone.NEUTRAL
    TestProgress.IN_PROGRESS -> BadgeTone.WARNING
    TestProgress.COMPLETE -> BadgeTone.SUCCESS
}

@Composable
fun TestProgressBadge(progress: TestProgress, modifier: Modifier = Modifier) {
    StatusBadge(progressLabel(progress), modifier, progressTone(progress))
}

/** One option of a [ChoiceField]; [value] null usually means "all". */
data class Choice<T>(val value: T, val label: String)

/** A dropdown over [choices] that selects by value, so "All" can be a real, displayable option. */
@Composable
fun <T> ChoiceField(
    label: String,
    choices: List<Choice<T>>,
    value: T,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    DropdownField(
        label = label,
        options = choices,
        selected = choices.firstOrNull { it.value == value },
        optionLabel = { it.label },
        onSelect = { onChange(it.value) },
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * A centred, width-limited scrolling page for the Test screens. Unlike the kit's `PageList` it spaces rows
 * tightly (8dp, cards in a list), and [imePadding] keeps the focused row above the keyboard.
 */
@Composable
fun TestPage(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    bottomPadding: androidx.compose.ui.unit.Dp = 96.dp,
    spacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: LazyListScope.() -> Unit,
) {
    Box(
        modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = PageMaxWidth).fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + navigationBarBottom()),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/** The Test screens' loading state; blank at first so a fast local load doesn't flash a spinner. */
@Composable
fun TestLoading(padding: PaddingValues = PaddingValues(), modifier: Modifier = Modifier) {
    DelayedLoading(modifier.padding(padding))
}

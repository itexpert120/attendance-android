package com.itexpert120.attendanceManager.ui.features.tests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.data.repo.TestRosterRow
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import com.itexpert120.attendanceManager.ui.theme.AttendanceColors

/** The text of one marks field. [focused] outlives the student shown (the one-by-one mode reuses the field). */
@Stable
class MarksFieldState internal constructor(initial: String, private val focus: MutableState<Boolean>) {
    var text by mutableStateOf(initial)
    var focused: Boolean
        get() = focus.value
        set(value) {
            focus.value = value
        }
}

/**
 * The field state for [entry]: starts from the stored result, restarts when a different student is shown, and
 * follows the stored result while the field isn't being edited (so a saved "35.00" settles as "35").
 */
@Composable
fun rememberMarksFieldState(entry: TestRosterEntry): MarksFieldState {
    val focus = remember { mutableStateOf(false) }
    val state = remember(entry.enrollmentId) { MarksFieldState(fieldTextFor(entry), focus) }
    val stored = fieldTextFor(entry)
    LaunchedEffect(entry.enrollmentId, stored, entry.result) {
        if (!state.focused || entry.result == TestResultStatus.ABSENT) state.text = stored
    }
    return state
}

/**
 * A numeric marks field built for rapid entry: decimal keypad, [imeAction] Next or Done, and the result is
 * written when the field loses focus, when Next/Done is pressed or when the field leaves the screen. An
 * invalid value (above Total Marks, or malformed) is never written and shows the error state.
 * [onAdvance] is called after a valid Next/Done so the screen can move on.
 */
@Composable
fun MarksField(
    state: MarksFieldState,
    entry: TestRosterEntry,
    studentName: String,
    totalMarks: Int,
    imeAction: ImeAction,
    focusRequester: FocusRequester,
    onWrite: (Write) -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val absent = entry.result == TestResultStatus.ABSENT
    val invalid = evaluateMarksField(state.text, totalMarks) is FieldOutcome.Invalid

    val commit by rememberUpdatedState<() -> Unit> {
        writeFor(evaluateMarksField(state.text, totalMarks), entry)?.let(onWrite)
    }
    DisposableEffect(Unit) { onDispose { commit() } }

    val advance = {
        if (evaluateMarksField(state.text, totalMarks) is FieldOutcome.Invalid) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
        } else {
            commit()
            onAdvance()
        }
    }

    val shape = RoundedCornerShape(if (large) 24.dp else 16.dp)
    val borderColor = when {
        invalid -> colors.error
        state.focused -> colors.primary
        else -> Color.Transparent
    }
    val textStyle = (if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge)
        .copy(color = if (absent) colors.onSurfaceVariant else colors.onSurface, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
    Box(
        modifier
            .heightIn(min = if (large) 88.dp else 52.dp)
            .widthIn(min = if (large) 160.dp else 88.dp)
            .clip(shape)
            .background(if (absent) colors.surfaceContainer else colors.surfaceContainerHigh)
            .border(if (invalid || state.focused) 2.dp else 0.dp, borderColor, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = state.text,
            onValueChange = { state.text = sanitizeMarksInput(it) },
            enabled = !absent,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { advance() }, onDone = { advance() }),
            modifier = Modifier
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    if (state.focused && !focus.isFocused) commit()
                    state.focused = focus.isFocused
                }
                .semantics { contentDescription = "Marks for $studentName, out of ${formatMarks(totalMarks)}" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (state.text.isEmpty()) {
                        Text(
                            if (absent) "Absent" else "–",
                            style = textStyle.copy(fontWeight = FontWeight.Normal),
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** The quick "A" toggle of a row: a 52dp target in the fixed absent colours, with a letter so colour isn't the only cue. */
@Composable
fun AbsentToggle(absent: Boolean, studentName: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val status = AttendanceColors.of(AttendanceStatus.ABSENT)
    FilledIconToggleButton(
        checked = absent,
        onCheckedChange = {
            haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onToggle()
        },
        colors = IconButtonDefaults.filledIconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = status.container,
            checkedContentColor = status.content,
        ),
        modifier = modifier.size(52.dp).semantics { contentDescription = "Absent: $studentName" },
    ) {
        Text("A", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** The roll number in a pill, wide enough for "12A" without clipping. */
@Composable
fun RollBadge(roll: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Box(
        modifier
            .heightIn(min = if (large) 56.dp else 40.dp)
            .widthIn(min = if (large) 56.dp else 40.dp)
            .clip(RoundedCornerShape(if (large) 28.dp else 20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            roll,
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
        )
    }
}

/** One student of the compact entry list: roll, name and status, the marks field and the Absent toggle. */
@Composable
fun EntryRow(
    row: TestRosterRow,
    totalMarks: Int,
    hasNext: Boolean,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onWrite: (Write) -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
    serverError: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val state = rememberMarksFieldState(row.entry)
    val outcome = evaluateMarksField(state.text, totalMarks)

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focusRequester.requestFocus() }
            onFocusHandled()
        }
    }

    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLowest, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RollBadge(row.enrollment.rollNumber)
            Column(Modifier.weight(1f)) {
                Text(
                    row.student.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val error = (outcome as? FieldOutcome.Invalid)?.message ?: serverError
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                } else {
                    Text(
                        entryRowDetail(row.entry, totalMarks, row.student.admissionNumber),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MarksField(
                state = state,
                entry = row.entry,
                studentName = row.student.name,
                totalMarks = totalMarks,
                imeAction = if (hasNext) ImeAction.Next else ImeAction.Done,
                focusRequester = focusRequester,
                onWrite = onWrite,
                onAdvance = onAdvance,
                // A fixed width leaves the name room for one line; marks never need more than "100.25".
                modifier = Modifier.width(96.dp),
            )
            AbsentToggle(
                absent = row.result == TestResultStatus.ABSENT,
                studentName = row.student.name,
                onToggle = { onWrite(toggleAbsent(row.entry)) },
            )
        }
    }
}

/**
 * One-student-at-a-time entry: a big marks field on a card, Absent, and Previous / Next. The keyboard's Next
 * saves and moves to the following student who isn't Absent; the field is focused automatically.
 */
@Composable
fun FocusEntry(
    rows: List<TestRosterRow>,
    totalMarks: Int,
    currentId: Long,
    onCurrentChange: (Long) -> Unit,
    onWrite: (Write) -> Unit,
    modifier: Modifier = Modifier,
    rowErrors: Map<Long, String> = emptyMap(),
) {
    if (rows.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val index = rows.indexOfFirst { it.enrollment.id == currentId }.coerceAtLeast(0)
    val row = rows[index]
    val statuses = rows.map { it.result }
    val next = nextEntryIndex(statuses, index)
    val pending = nextPendingIndex(statuses, index)
    val focusRequester = remember { FocusRequester() }
    val state = rememberMarksFieldState(row.entry)
    val outcome = evaluateMarksField(state.text, totalMarks)
    val absent = row.result == TestResultStatus.ABSENT

    LaunchedEffect(row.enrollment.id, absent) {
        if (!absent) runCatching { focusRequester.requestFocus() }
    }

    fun goTo(target: Int) {
        // Clearing focus first commits whatever is typed for this student (a button tap doesn't blur the field).
        focusManager.clearFocus()
        onCurrentChange(rows[target].enrollment.id)
    }

    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLowest, modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Student ${index + 1} of ${rows.size}",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            RollBadge(row.enrollment.rollNumber, large = true)
            Text(row.student.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                "B-Form ${row.student.admissionNumber}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            MarksField(
                state = state,
                entry = row.entry,
                studentName = row.student.name,
                totalMarks = totalMarks,
                imeAction = if (next != null) ImeAction.Next else ImeAction.Done,
                focusRequester = focusRequester,
                onWrite = onWrite,
                onAdvance = { if (next != null) onCurrentChange(rows[next].enrollment.id) else focusManager.clearFocus() },
                large = true,
            )
            val error = (outcome as? FieldOutcome.Invalid)?.message ?: rowErrors[row.enrollment.id]
            Text(
                error ?: "out of ${formatMarks(totalMarks)} · ${entryStatusText(row.entry, totalMarks)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) colors.error else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            val absentToggleText = if (absent) "Absent (tap to undo)" else "Mark absent"
            if (absent) {
                FilledTonalButton(
                    onClick = { onWrite(toggleAbsent(row.entry)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(absentToggleText) }
            } else {
                OutlinedButton(
                    onClick = { onWrite(toggleAbsent(row.entry)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(absentToggleText) }
            }
            // Previous at the left, Next at the right, each at its natural width.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { goTo(index - 1) },
                    enabled = index > 0,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    Text("Previous", modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = { goTo(next ?: index) },
                    enabled = next != null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Next", modifier = Modifier.padding(end = 8.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                }
            }
            if (pending != null && pending != index) {
                TextButton(onClick = { goTo(pending) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Jump to next not entered")
                }
            }
        }
    }
}

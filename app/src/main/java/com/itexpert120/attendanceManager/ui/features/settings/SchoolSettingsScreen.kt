package com.itexpert120.attendanceManager.ui.features.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.files.PickedPhoto
import com.itexpert120.attendanceManager.data.repo.PhotoChange
import com.itexpert120.attendanceManager.domain.AbsenceMessageToken
import com.itexpert120.attendanceManager.domain.DEFAULT_ABSENCE_MESSAGE_TEMPLATE
import com.itexpert120.attendanceManager.domain.dateLabel
import com.itexpert120.attendanceManager.domain.monthName
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.BottomActionBar
import com.itexpert120.attendanceManager.ui.components.DelayedLoading
import com.itexpert120.attendanceManager.ui.components.DropdownField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.components.PageMaxWidth
import com.itexpert120.attendanceManager.ui.components.StoredPhoto
import com.itexpert120.attendanceManager.ui.components.rememberPhotoPicker
import com.itexpert120.attendanceManager.ui.features.setup.SetupTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.math.max
import kotlin.time.Clock

/** School name, academic year, currency, incharge, logo and the absence message template. */
@Composable
fun SchoolSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { SchoolSettingsViewModel(container.settings, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val photos = rememberPhotoPicker(
        keepTransparency = true,
        onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        onPicked = viewModel::setLogo,
    )
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    val requestBack: () -> Unit = { if (state.dirty && !state.saving) confirmDiscard = true else onBack() }
    BackHandler(enabled = state.dirty && !state.saving && !state.saved) { confirmDiscard = true }

    AppScreen(
        title = "School settings",
        subtitle = "Details printed on registers and reports",
        onBack = requestBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar(modifier = Modifier.imePadding()) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.dirty && !state.saving,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (state.saving) "Saving…" else "Save changes") }
            }
        },
    ) { padding ->
        if (!state.loaded) {
            DelayedLoading(Modifier.padding(padding))
        } else {
            SchoolSettingsForm(
                padding = padding,
                state = state,
                onSchoolName = viewModel::setSchoolName,
                onStartMonth = viewModel::setStartMonth,
                onCurrency = viewModel::setCurrency,
                onIncharge = viewModel::setIncharge,
                onTemplate = viewModel::setTemplate,
                onResetTemplate = viewModel::resetTemplate,
                onChooseLogo = photos.pickFromLibrary,
                onTakeLogo = photos.takePhoto,
                onRemoveLogo = viewModel::removeLogo,
                logoBusy = photos.isProcessing,
            )
        }
    }

    if (confirmDiscard) {
        AppSheet(
            title = "Discard changes?",
            description = "Your changes to the school settings haven't been saved.",
            onDismiss = { confirmDiscard = false },
            footer = {
                TextButton(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Keep editing")
                }
                Button(
                    onClick = {
                        confirmDiscard = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Discard") }
            },
        ) {}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchoolSettingsForm(
    padding: PaddingValues,
    state: SchoolSettingsUiState,
    onSchoolName: (String) -> Unit,
    onStartMonth: (Int) -> Unit,
    onCurrency: (String) -> Unit,
    onIncharge: (String) -> Unit,
    onTemplate: (String) -> Unit,
    onResetTemplate: () -> Unit,
    onChooseLogo: () -> Unit,
    onTakeLogo: () -> Unit,
    onRemoveLogo: () -> Unit,
    logoBusy: Boolean,
) {
    val form = state.form
    Box(
        Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = PageMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LogoPicker(
                        state = state,
                        onChoose = onChooseLogo,
                        onTake = onTakeLogo,
                        onRemove = onRemoveLogo,
                        busy = logoBusy,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SetupTextField(
                        value = form.schoolName,
                        onValueChange = onSchoolName,
                        label = "School name",
                        isError = state.schoolNameError != null,
                        supportingText = state.schoolNameError,
                    )
                    DropdownField(
                        label = "Academic year starts",
                        options = (1..12).toList(),
                        selected = form.academicYearStartMonth,
                        optionLabel = ::monthName,
                        onSelect = onStartMonth,
                    )
                    SetupTextField(
                        value = form.currencyLabel,
                        onValueChange = onCurrency,
                        label = "Currency label",
                        placeholder = "Rs.",
                        supportingText = "Shown before fee amounts",
                        capitalization = KeyboardCapitalization.None,
                    )
                    SetupTextField(
                        value = form.classInchargeName,
                        onValueChange = onIncharge,
                        label = "Class incharge name",
                        placeholder = "e.g. Ms. Sana Ali",
                        supportingText = "Named in absence messages to parents",
                        imeAction = ImeAction.Done,
                    )
                }
            }

            AbsenceMessageSection(
                form = form,
                onTemplate = onTemplate,
                onReset = onResetTemplate,
            )

            state.error?.let { ErrorBanner(it) }
            Spacer(Modifier.heightIn(min = 8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogoPicker(
    state: SchoolSettingsUiState,
    onChoose: () -> Unit,
    onTake: () -> Unit,
    onRemove: () -> Unit,
    busy: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogoPreview(state, Modifier.size(72.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("School logo", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(
                "Printed on registers and reports.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onChoose, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (state.hasLogo) "Change" else "Choose")
        }
        FilledTonalButton(onClick = onTake, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Camera")
        }
        if (state.hasLogo) {
            TextButton(onClick = onRemove, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
        }
    }
}

@Composable
private fun LogoPreview(state: SchoolSettingsUiState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shaped = modifier.clip(CircleShape).background(colors.surfaceContainerHigh)
    val placeholder: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.School, contentDescription = "No school logo", tint = colors.onSecondaryContainer)
        }
    }
    when (state.logo) {
        is PhotoChange.Replace -> PickedLogoImage(state.pickedLogo, shaped, placeholder)
        PhotoChange.Remove -> Box(shaped) { placeholder() }
        PhotoChange.Keep -> StoredPhoto(
            fileName = state.storedLogoFile,
            contentDescription = "School logo",
            contentScale = ContentScale.Crop,
            maxEdgePx = 256,
            modifier = shaped,
            placeholder = placeholder,
        )
    }
}

/** A freshly picked logo, decoded off the main thread and downsampled for the 72dp preview. */
@Composable
private fun PickedLogoImage(photo: PickedPhoto?, modifier: Modifier, placeholder: @Composable () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, photo) {
        value = photo?.let { picked -> withContext(Dispatchers.Default) { decodePreview(picked.bytes) } }
    }
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription = "New school logo", modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier) { placeholder() }
    }
}

private fun decodePreview(bytes: ByteArray, maxEdge: Int = 256): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbsenceMessageSection(form: SchoolSettingsForm, onTemplate: (String) -> Unit, onReset: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    // The field keeps its own selection so a placeholder chip can be inserted at the cursor.
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(form.template, TextRange(form.template.length)))
    }
    // This value is the source of truth for the text while editing; the ViewModel only mirrors it, so a
    // lagging state emission can never overwrite a keystroke.
    val today = remember { dateLabel(Clock.System.todayIn(TimeZone.currentSystemDefault())) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Absence message",
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    value = TextFieldValue(DEFAULT_ABSENCE_MESSAGE_TEMPLATE, TextRange(DEFAULT_ABSENCE_MESSAGE_TEMPLATE.length))
                    onReset()
                },
                enabled = !form.isDefaultTemplate, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reset")
            }
        }
        AppCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Prefilled when you message a parent by SMS or WhatsApp from the absentee list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { next ->
                        val limited = if (next.text.length > TEMPLATE_MAX_LENGTH) {
                            next.copy(text = next.text.take(TEMPLATE_MAX_LENGTH))
                        } else {
                            next
                        }
                        value = limited
                        onTemplate(limited.text)
                    },
                    label = { Text("Message template") },
                    supportingText = {
                        Text("${value.text.length} / $TEMPLATE_MAX_LENGTH · An empty message uses the default.")
                    },
                    minLines = 4,
                    maxLines = 10,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Tap to insert at the cursor",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AbsenceMessageToken.entries.forEach { token ->
                        AssistChip(
                            onClick = {
                                val inserted = insertToken(value.text, value.selection.start, value.selection.end, token)
                                if (inserted != null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                    value = TextFieldValue(inserted.text, TextRange(inserted.cursor))
                                    onTemplate(inserted.text)
                                } else {
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                }
                            },
                            label = { Text(token.chipLabel) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                            },
                        )
                    }
                }
                Surface(
                    color = colors.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Preview", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Text(
                            previewMessage(form, today),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}

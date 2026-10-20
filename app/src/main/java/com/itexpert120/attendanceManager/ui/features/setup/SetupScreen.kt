package com.itexpert120.attendanceManager.ui.features.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.ui.features.settings.rememberBackupActions
import com.itexpert120.attendanceManager.data.files.PickedPhoto
import com.itexpert120.attendanceManager.domain.monthName
import com.itexpert120.attendanceManager.ui.components.BottomActionBar
import com.itexpert120.attendanceManager.ui.components.DropdownField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.components.ListGroup
import com.itexpert120.attendanceManager.ui.components.ListRow
import com.itexpert120.attendanceManager.ui.components.rememberPhotoPicker
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every action the onboarding screens can trigger; defaults are no-ops so previews stay trivial. */
internal class SetupCallbacks(
    val onSchoolName: (String) -> Unit = {},
    val onClassName: (String) -> Unit = {},
    val onSection: (String) -> Unit = {},
    val onStartMonth: (Int) -> Unit = {},
    val onCurrency: (String) -> Unit = {},
    val onIncharge: (String) -> Unit = {},
    val onChooseLogo: () -> Unit = {},
    val onTakeLogo: () -> Unit = {},
    val onRemoveLogo: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onEdit: (SetupStep) -> Unit = {},
    val onFinish: () -> Unit = {},
    /** Import an old web-app `.json` backup instead of setting up from scratch. */
    val onImportWebBackup: () -> Unit = {},
    /** Restore this app's own `.zip` backup, for example on a new phone. */
    val onRestoreBackup: () -> Unit = {},
)

/**
 * First-run onboarding: welcome, school, first class, preferences, then a review before saving. The app swaps to
 * the tabs by itself once the settings exist, so nothing is reported back.
 */
@Composable
fun SetupScreen() {
    val container = LocalAppContainer.current
    val viewModel: SetupViewModel = viewModel { SetupViewModel(container.settings) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Importing or restoring writes the school settings, so the app leaves setup by itself afterwards.
    val backup = rememberBackupActions(snackbarHostState, emptyApp = true)
    val photos = rememberPhotoPicker(
        keepTransparency = true,
        onError = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        onPicked = viewModel::setLogo,
    )

    SetupContent(
        form = form,
        logoBusy = photos.isProcessing,
        snackbarHostState = snackbarHostState,
        callbacks = SetupCallbacks(
            onSchoolName = viewModel::setSchoolName,
            onClassName = viewModel::setClassName,
            onSection = viewModel::setSection,
            onStartMonth = viewModel::setAcademicYearStartMonth,
            onCurrency = viewModel::setCurrency,
            onIncharge = viewModel::setIncharge,
            onChooseLogo = photos.pickFromLibrary,
            onTakeLogo = photos.takePhoto,
            onRemoveLogo = { viewModel.setLogo(null) },
            onNext = viewModel::next,
            onBack = viewModel::back,
            onEdit = viewModel::goTo,
            onFinish = viewModel::finish,
            onImportWebBackup = backup.importWebBackup,
            onRestoreBackup = backup.restore,
        ),
    )
}

@Composable
internal fun SetupContent(
    form: SetupForm,
    callbacks: SetupCallbacks,
    logoBusy: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    BackHandler(enabled = form.step != SetupStep.WELCOME && !form.saving, onBack = callbacks.onBack)

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { SetupBottomBar(form, callbacks) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (form.step != SetupStep.WELCOME) SetupProgress(form.step)
            AnimatedContent(
                targetState = form.step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally { fullWidth -> fullWidth / 6 * direction } + fadeIn()) togetherWith
                        (slideOutHorizontally { fullWidth -> -fullWidth / 6 * direction } + fadeOut())
                },
                label = "setup step",
            ) { step ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (step) {
                            SetupStep.WELCOME -> WelcomePage(callbacks)
                            SetupStep.SCHOOL -> SchoolPage(form, callbacks, logoBusy)
                            SetupStep.CLASS -> ClassPage(form, callbacks)
                            SetupStep.PREFERENCES -> PreferencesPage(form, callbacks)
                            SetupStep.REVIEW -> ReviewPage(form, callbacks)
                        }
                    }
                }
            }
        }
    }
}

// region Chrome

/** A compact progress line: a thin bar with "2 of 4" beside it. */
@Composable
private fun SetupProgress(step: SetupStep) {
    val progress by animateFloatAsState(step.number / SetupStep.COUNTED.toFloat(), label = "setup progress")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
        Text(
            "${step.number} of ${SetupStep.COUNTED}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Step ${step.number} of ${SetupStep.COUNTED}" },
        )
    }
}

/**
 * The setup-wizard bottom bar: a low-emphasis Back on the left and a compact filled Next / Finish on the right,
 * both within thumb reach. Buttons keep their natural width (see `docs/phone-design.md`).
 */
@Composable
private fun SetupBottomBar(form: SetupForm, callbacks: SetupCallbacks) {
    BottomActionBar(
        secondary = if (form.step == SetupStep.WELCOME) {
            null
        } else {
            { TextButton(onClick = callbacks.onBack, enabled = !form.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") } }
        },
    ) {
        when (form.step) {
            SetupStep.WELCOME -> Button(onClick = callbacks.onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Get started")
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp).size(ButtonDefaults.IconSize),
                )
            }

            SetupStep.REVIEW -> Button(
                onClick = callbacks.onFinish,
                enabled = !form.saving,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (form.saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Text("Saving…", modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text("Finish")
                }
            }

            else -> Button(onClick = callbacks.onNext, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
        }
    }
}

@Composable
private fun PageHeader(title: String, text: String) {
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// endregion

// region Pages

@Composable
private fun WelcomePage(callbacks: SetupCallbacks) {
    val colors = MaterialTheme.colorScheme
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier.size(88.dp).clip(CircleShape).background(colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.School, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(48.dp))
    }
    Text(
        "Your school register, always at hand.",
        style = MaterialTheme.typography.displaySmall,
        color = colors.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        "Attendance, students, fees, tests and homework, all on this phone and working without a connection.",
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    ListGroup {
        item { ListRow(label = "Works offline", detail = "No internet needed", icon = Icons.Rounded.CloudOff) }
        item { ListRow(label = "Stored on this phone", detail = "Every register stays local", icon = Icons.Rounded.PhoneAndroid) }
        item { ListRow(label = "No accounts", detail = "Nothing is uploaded or shared", icon = Icons.Rounded.Lock) }
    }
    ListGroup(header = "Already have your data?") {
        item {
            ListRow(
                label = "Import from the web app",
                detail = "Pick the .json backup saved by the old web app",
                icon = Icons.Rounded.Language,
                chevron = true,
                onClick = callbacks.onImportWebBackup,
            )
        }
        item {
            ListRow(
                label = "Restore a backup",
                detail = "A .zip backup from this app, e.g. from your old phone",
                icon = Icons.Rounded.Restore,
                chevron = true,
                onClick = callbacks.onRestoreBackup,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchoolPage(form: SetupForm, callbacks: SetupCallbacks, logoBusy: Boolean) {
    val colors = MaterialTheme.colorScheme
    PageHeader("Your school", "The name and logo appear on printed registers and reports.")

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHigh)
                .clickable(enabled = !logoBusy, onClickLabel = "Choose a school logo", onClick = callbacks.onChooseLogo),
            contentAlignment = Alignment.Center,
        ) {
            SetupLogoImage(
                logo = form.logo,
                modifier = Modifier.fillMaxSize().background(colors.surfaceContainerLowest),
                placeholder = {
                    Icon(
                        Icons.Rounded.AddPhotoAlternate,
                        contentDescription = "Add a school logo",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                },
            )
        }
        Text("School logo (optional)", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = callbacks.onChooseLogo, enabled = !logoBusy) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Choose photo", modifier = Modifier.padding(start = 8.dp))
            }
            FilledTonalButton(onClick = callbacks.onTakeLogo, enabled = !logoBusy) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Take photo", modifier = Modifier.padding(start = 8.dp))
            }
            if (form.logo != null) TextButton(onClick = callbacks.onRemoveLogo, enabled = !logoBusy) { Text("Remove") }
        }
    }

    SetupTextField(
        value = form.schoolName,
        onValueChange = callbacks.onSchoolName,
        label = "School name",
        placeholder = "e.g. Crescent Public School",
        isError = form.error != null && form.schoolName.isBlank(),
        supportingText = form.error.takeIf { form.schoolName.isBlank() },
        imeAction = ImeAction.Done,
        onDone = callbacks.onNext,
    )
}

@Composable
private fun ClassPage(form: SetupForm, callbacks: SetupCallbacks) {
    PageHeader("Your first class", "Students, registers and tests belong to a class and a section. You can add more classes later.")
    val missing = form.error != null
    SetupTextField(
        value = form.className,
        onValueChange = callbacks.onClassName,
        label = "Class",
        placeholder = "e.g. 7",
        isError = missing && form.className.isBlank(),
        supportingText = form.error.takeIf { missing && form.className.isBlank() },
    )
    SetupTextField(
        value = form.section,
        onValueChange = callbacks.onSection,
        label = "Section",
        placeholder = "e.g. A",
        capitalization = KeyboardCapitalization.Characters,
        isError = missing && form.section.isBlank(),
        supportingText = form.error.takeIf { missing && form.section.isBlank() && form.className.isNotBlank() },
        imeAction = ImeAction.Done,
        onDone = callbacks.onNext,
    )
}

@Composable
private fun PreferencesPage(form: SetupForm, callbacks: SetupCallbacks) {
    PageHeader("A few details", "Used for fees, reports and printouts. You can change all of this later in Settings.")
    DropdownField(
        label = "Academic year starts in",
        options = (1..12).toList(),
        selected = form.academicYearStartMonth,
        optionLabel = ::monthName,
        onSelect = callbacks.onStartMonth,
    )
    SetupTextField(
        value = form.currencyLabel,
        onValueChange = callbacks.onCurrency,
        label = "Currency label",
        placeholder = "Rs.",
        supportingText = "Shown next to fee amounts. Leave empty for Rs.",
        capitalization = KeyboardCapitalization.None,
        keyboardType = KeyboardType.Text,
    )
    SetupTextField(
        value = form.classInchargeName,
        onValueChange = callbacks.onIncharge,
        label = "Class incharge name (optional)",
        placeholder = "e.g. Ms. Sana Ali",
        supportingText = "Printed on registers and homework reports.",
        imeAction = ImeAction.Done,
        onDone = callbacks.onNext,
    )
}

@Composable
private fun ReviewPage(form: SetupForm, callbacks: SetupCallbacks) {
    PageHeader("You're all set", "Check your details, then finish. Tap a row to change it.")
    ListGroup {
        item {
            ListRow(
                label = form.schoolName.trim(),
                detail = "School",
                leading = {
                    SetupLogoImage(
                        logo = form.logo,
                        modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        placeholder = {
                            Icon(
                                Icons.Rounded.School,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(8.dp),
                            )
                        },
                    )
                },
                chevron = true,
                onClick = { callbacks.onEdit(SetupStep.SCHOOL) },
            )
        }
        item {
            ListRow(
                label = form.classSummary(),
                detail = "First class",
                icon = Icons.Rounded.Groups,
                chevron = true,
                onClick = { callbacks.onEdit(SetupStep.CLASS) },
            )
        }
        item {
            ListRow(
                label = academicYearSummary(form.academicYearStartMonth),
                detail = "Academic year",
                icon = Icons.Rounded.CalendarMonth,
                chevron = true,
                onClick = { callbacks.onEdit(SetupStep.PREFERENCES) },
            )
        }
        item {
            ListRow(
                label = form.effectiveCurrency(),
                detail = "Currency label",
                icon = Icons.Rounded.Payments,
                chevron = true,
                onClick = { callbacks.onEdit(SetupStep.PREFERENCES) },
            )
        }
        if (form.classInchargeName.isNotBlank()) {
            item {
                ListRow(
                    label = form.classInchargeName.trim(),
                    detail = "Class incharge",
                    icon = Icons.Rounded.Person,
                    chevron = true,
                    onClick = { callbacks.onEdit(SetupStep.PREFERENCES) },
                )
            }
        }
    }
    form.error?.let { ErrorBanner(it, Modifier.animateContentSize()) }
}

// endregion

/** Shows the picked logo (decoded off the main thread and downsampled), or [placeholder] while there is none. */
@Composable
private fun SetupLogoImage(logo: PickedPhoto?, modifier: Modifier, placeholder: @Composable () -> Unit) {
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, logo) {
        value = logo?.let { picked -> withContext(Dispatchers.Default) { decodeLogoPreview(picked.bytes) } }
    }
    val image = bitmap
    if (image != null) {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = "School logo",
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { placeholder() }
    }
}

private fun decodeLogoPreview(bytes: ByteArray, maxEdge: Int = 320): ImageBitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

// region Previews

@Composable
private fun PreviewStep(form: SetupForm) {
    AttendanceTheme(dynamicColor = false) { SetupContent(form, SetupCallbacks()) }
}

private val PreviewForm = SetupForm(
    schoolName = "Crescent Public School",
    className = "7",
    section = "A",
    classInchargeName = "Ms. Sana Ali",
)

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SetupWelcomePreview() = PreviewStep(SetupForm())

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SetupSchoolPreview() = PreviewStep(SetupForm(step = SetupStep.SCHOOL, error = "School name is required."))

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SetupClassPreview() = PreviewStep(PreviewForm.copy(step = SetupStep.CLASS))

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun SetupPreferencesPreview() = PreviewStep(PreviewForm.copy(step = SetupStep.PREFERENCES))

@Preview(showBackground = true, showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SetupReviewDarkPreview() = PreviewStep(PreviewForm.copy(step = SetupStep.REVIEW))

// endregion

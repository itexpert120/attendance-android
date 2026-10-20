package com.itexpert120.attendanceManager.ui.features.homework

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.files.PickedPhoto
import com.itexpert120.attendanceManager.domain.dateLabel
import com.itexpert120.attendanceManager.export.HomeworkPdf
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.BottomActionBar
import com.itexpert120.attendanceManager.ui.components.DelayedLoading
import com.itexpert120.attendanceManager.ui.components.DateField
import com.itexpert120.attendanceManager.ui.components.DropdownField
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.components.PageMaxWidth
import com.itexpert120.attendanceManager.ui.components.PdfExportSheet
import com.itexpert120.attendanceManager.ui.components.PdfRequest
import com.itexpert120.attendanceManager.ui.components.rememberPdfExportState
import com.itexpert120.attendanceManager.ui.components.SegmentedChoice
import com.itexpert120.attendanceManager.ui.components.StoredPhoto
import com.itexpert120.attendanceManager.ui.components.rememberPhotoPicker
import com.itexpert120.attendanceManager.ui.features.setup.SetupTextField
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

private const val NO_ITEM = -1L
private val FieldShape = RoundedCornerShape(12.dp)

/**
 * Create (`reportId == null`) or edit a Daily Homework Report: class, date and incharge, then either typed
 * homework per Subject or one photographed diary page, and the note for parents. Save sits at the bottom;
 * an edited report can be deleted from the app bar.
 */
@Composable
fun HomeworkEditorScreen(reportId: Long?, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        HomeworkEditorViewModel(
            reportId = reportId,
            homework = container.homework,
            subjectRepository = container.subjects,
            classRepository = container.classes,
            settings = container.settings,
            handle = createSavedStateHandle(),
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val subjectReportCounts by viewModel.subjectReportCounts.collectAsStateWithLifecycle()
    val pdfExport = rememberPdfExportState()
    val latestOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(state) {
        when (val current = state) {
            HomeworkEditorState.Closed -> latestOnBack()
            // "Save & PDF": show the saved report's PDF, then leave once its sheet is closed.
            is HomeworkEditorState.SavedForPdf -> {
                pdfExport.open(
                    PdfRequest(HomeworkPdf.TITLE, HomeworkPdf.fileName(current.group, current.date)) {
                        HomeworkPdf.load(
                            current.reportId,
                            container.homework,
                            container.classes,
                            container.subjects,
                            container.settings,
                            container.photoStore,
                        )
                    },
                )
                snapshotFlow { pdfExport.request }.first { it == null }
                latestOnBack()
            }
            else -> Unit
        }
    }
    HomeworkEditorContent(
        state = state,
        actions = viewModel,
        isNew = reportId == null,
        onBack = onBack,
        subjectReportCounts = subjectReportCounts,
    )
    PdfExportSheet(pdfExport)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeworkEditorContent(
    state: HomeworkEditorState,
    actions: HomeworkEditorActions,
    isNew: Boolean,
    onBack: () -> Unit,
    subjectReportCounts: Map<Long, Int> = emptyMap(),
) {
    val form = (state as? HomeworkEditorState.Ready)?.form
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var subjectsOpen by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val requestBack = {
        if (form != null && form.dirty && !form.saving) confirmDiscard = true else onBack()
    }
    BackHandler(enabled = form != null && form.dirty && !form.saving) { confirmDiscard = true }

    AppScreen(
        title = if (isNew) "New homework" else "Edit homework",
        subtitle = "Daily diary for parents",
        onBack = requestBack,
        actions = {
            if (form != null) {
                IconButton(onClick = { subjectsOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = "Manage subjects")
                }
            }
            if (!isNew && form != null) {
                IconButton(onClick = { confirmDelete = true }, enabled = !form.saving) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete report")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (form != null) {
                SaveBar(
                    label = if (isNew) "Save report" else "Save changes",
                    saving = form.saving,
                    onSave = { actions.save() },
                    onSaveAndPdf = { actions.save(thenPdf = true) },
                )
            }
        },
    ) { padding ->
        when (state) {
            HomeworkEditorState.Loading, HomeworkEditorState.Closed -> DelayedLoading(Modifier.padding(padding))
            // Behind the PDF sheet of "Save & PDF"; the screen leaves when that sheet closes.
            is HomeworkEditorState.SavedForPdf -> Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Rounded.Check,
                    title = "Report saved",
                    text = "Print or share the PDF, then you return to Homework.",
                )
            }
            HomeworkEditorState.NotFound -> Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Rounded.EventBusy,
                    title = "Report not found",
                    text = "This homework report has been deleted.",
                    action = { Button(onClick = onBack) { Text("Back to homework") } },
                )
            }
            is HomeworkEditorState.Ready -> HomeworkForm(
                form = state.form,
                actions = actions,
                snackbar = snackbar,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            )
        }
    }

    if (subjectsOpen && form != null) {
        HomeworkSubjectsSheet(
            subjects = form.subjects,
            reportCounts = subjectReportCounts,
            actions = actions,
            onDismiss = { subjectsOpen = false },
            inEditor = true,
            draftSubjectIds = form.draft.items.mapNotNullTo(HashSet()) { it.subjectId },
            onAdd = actions::addAndUseSubject,
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text(if (isNew) "This report hasn't been saved." else "Your changes to this report won't be saved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onBack()
                    },
                ) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (confirmDelete && form != null) {
        val group = form.classes.firstOrNull { it.id == form.draft.classGroupId }
        val on = form.draft.date?.let { " on ${dateLabel(it)}" }.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this report?") },
            text = { Text("The homework report for ${homeworkClassLabel(group)}$on will be deleted. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        actions.delete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HomeworkForm(
    form: HomeworkEditorForm,
    actions: HomeworkEditorActions,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var pickerFor by rememberSaveable { mutableLongStateOf(NO_ITEM) }
    val draft = form.draft
    val errors = form.fieldErrors
    val error = form.error

    val generalRequester = remember { BringIntoViewRequester() }
    val detailsRequester = remember { BringIntoViewRequester() }
    val homeworkRequester = remember { BringIntoViewRequester() }
    val noteRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(form.errorSerial) {
        if (form.errorSerial == 0 || error == null) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        when (error.target) {
            HomeworkErrorTarget.CLASS, HomeworkErrorTarget.DATE, HomeworkErrorTarget.INCHARGE -> detailsRequester
            HomeworkErrorTarget.ITEMS, HomeworkErrorTarget.PHOTO -> homeworkRequester
            HomeworkErrorTarget.NOTE -> noteRequester
            HomeworkErrorTarget.GENERAL -> generalRequester
        }.bringIntoView()
    }

    fun showUndo(message: String, onUndo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, actionLabel = "Undo") == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    // As the web app's diary picker: a 1920px long edge keeps handwriting readable.
    val picker = rememberPhotoPicker(
        maxEdge = 1920,
        onError = {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar("Could not use that image.")
            }
        },
    ) { actions.setPhoto(it) }

    Box(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = PageMaxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (error?.target == HomeworkErrorTarget.GENERAL) {
                ErrorBanner(error.message, Modifier.bringIntoViewRequester(generalRequester))
            }

            // ---- Class, date, incharge
            AppCard(Modifier.bringIntoViewRequester(detailsRequester)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (form.classes.isEmpty()) {
                        ErrorBanner("Add a class in Students before writing homework.")
                    } else {
                        DropdownField(
                            label = "Class & section",
                            options = form.classes,
                            selected = form.classes.firstOrNull { it.id == draft.classGroupId },
                            optionLabel = { "Class ${it.className} · Section ${it.section}" },
                            onSelect = { group -> actions.updateDraft { it.copy(classGroupId = group.id) } },
                        )
                    }
                    if (error?.target == HomeworkErrorTarget.CLASS) ErrorBanner(error.message)
                    DateField(
                        label = "Report date",
                        date = draft.date,
                        onChange = { date -> actions.updateDraft { it.copy(date = date) } },
                        supportingText = if (errors.dateMissing) "Required" else null,
                    )
                    if (error?.target == HomeworkErrorTarget.DATE) ErrorBanner(error.message)
                    SetupTextField(
                        value = draft.inchargeName,
                        onValueChange = { text -> actions.updateDraft { it.copy(inchargeName = text) } },
                        label = "Class incharge",
                        placeholder = "Name shown on the report",
                        isError = errors.inchargeMissing,
                        supportingText = if (errors.inchargeMissing) "Required" else null,
                        imeAction = ImeAction.Done,
                    )
                    if (error?.target == HomeworkErrorTarget.INCHARGE) ErrorBanner(error.message)
                }
            }

            // ---- Homework: typed per Subject, or a diary photo
            Column(
                Modifier.bringIntoViewRequester(homeworkRequester),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader("Homework")
                SegmentedChoice(
                    options = HomeworkEntryMode.entries,
                    selected = draft.mode,
                    optionLabel = { if (it == HomeworkEntryMode.TYPED) "Type subjects" else "Diary photo" },
                    onSelect = { mode ->
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        actions.updateDraft { current ->
                            val switched = current.copy(mode = mode)
                            // Typing with nothing to type into: start with the first free Subject, as the web app did.
                            if (mode == HomeworkEntryMode.TYPED && switched.items.isEmpty()) {
                                switched.firstUnusedSubjectId(form.activeSubjects)?.let(switched::withItemAdded) ?: switched
                            } else {
                                switched
                            }
                        }
                    },
                )
                if (draft.mode == HomeworkEntryMode.TYPED) {
                    draft.items.forEachIndexed { index, item ->
                        androidx.compose.runtime.key(item.key) {
                            HomeworkItemCard(
                                item = item,
                                number = index + 1,
                                count = draft.items.size,
                                subjectName = item.subjectId?.let { form.subjectNames[it] },
                                subjectArchived = form.subjects.firstOrNull { it.id == item.subjectId }?.isArchived == true,
                                subjectError = errors.itemSubject[item.key],
                                detailsError = errors.itemDetails[item.key],
                                onPickSubject = { pickerFor = item.key },
                                onDetailsChange = { text ->
                                    actions.updateDraft { it.withItem(item.key) { entry -> entry.copy(details = text) } }
                                },
                                onMove = { direction -> actions.updateDraft { it.withItemMoved(item.key, direction) } },
                                onRemove = {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                    actions.removeItem(item.key)?.let { removed ->
                                        showUndo("Removed ${removed.subjectName ?: "homework item"}") { actions.restoreItem(removed) }
                                    }
                                },
                            )
                        }
                    }
                    if (draft.items.isEmpty()) {
                        Text(
                            "No subjects on this report yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val subject = draft.firstUnusedSubjectId(form.activeSubjects)
                            val next = draft.withItemAdded(subject)
                            actions.updateDraft { it.withItemAdded(subject) }
                            // Nothing left to pre-fill: let the teacher pick or create a Subject straight away.
                            if (subject == null) pickerFor = next.items.last().key
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (draft.items.isEmpty()) "Add a subject" else "Add another subject")
                    }
                } else {
                    DiaryPhotoCard(
                        photo = form.photo,
                        processing = picker.isProcessing,
                        onCamera = picker.takePhoto,
                        onGallery = picker.pickFromLibrary,
                        onRemove = {
                            val previous = actions.removePhoto()
                            if (previous != DraftPhoto.None) showUndo("Photo removed") { actions.restorePhoto(previous) }
                        },
                    )
                }
                if (error?.target == HomeworkErrorTarget.ITEMS || error?.target == HomeworkErrorTarget.PHOTO) {
                    ErrorBanner(error.message)
                }
            }

            // ---- Note for parents
            Column(
                Modifier.bringIntoViewRequester(noteRequester),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader("Note for parents")
                OutlinedTextField(
                    value = draft.parentNote,
                    onValueChange = { text -> actions.updateDraft { it.copy(parentNote = text.take(600)) } },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    shape = FieldShape,
                    isError = errors.noteMissing,
                    supportingText = { Text(if (errors.noteMissing) "Required" else "Printed at the end of the report.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                if (error?.target == HomeworkErrorTarget.NOTE) ErrorBanner(error.message)
            }
            Spacer(Modifier.size(8.dp))
        }
    }

    val pickingItem = draft.items.firstOrNull { it.key == pickerFor }
    if (pickingItem != null) {
        SubjectPickerSheet(
            choices = subjectChoicesFor(pickingItem, draft, form.subjects),
            selectedId = pickingItem.subjectId,
            onSelect = { subjectId ->
                actions.updateDraft { it.withItem(pickingItem.key) { entry -> entry.copy(subjectId = subjectId) } }
                pickerFor = NO_ITEM
            },
            onAddSubject = actions::addSubject,
            onDismiss = { pickerFor = NO_ITEM },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
    )
}

/** One Subject's homework: the Subject (opens the picker), the assignment, and reorder / remove. */
@Composable
private fun HomeworkItemCard(
    item: HomeworkDraftItem,
    number: Int,
    count: Int,
    subjectName: String?,
    subjectArchived: Boolean,
    subjectError: String?,
    detailsError: String?,
    onPickSubject: () -> Unit,
    onDetailsChange: (String) -> Unit,
    onMove: (direction: Int) -> Unit,
    onRemove: () -> Unit,
) {
    AppCard {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = subjectName?.let { if (subjectArchived) "$it (archived)" else it }.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Subject $number") },
                        placeholder = { Text("Choose a subject") },
                        isError = subjectError != null,
                        supportingText = subjectError?.let { { Text(it) } },
                        trailingIcon = {
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                            )
                        },
                        shape = FieldShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // The read-only field would swallow the tap; this overlay opens the picker instead.
                    Box(
                        Modifier
                            .matchParentSize()
                            .padding(top = 8.dp)
                            .clip(FieldShape)
                            .clickable(role = Role.DropdownList, onClickLabel = "Choose subject", onClick = onPickSubject),
                    )
                }
                OutlinedTextField(
                    value = item.details,
                    onValueChange = { onDetailsChange(it.take(1200)) },
                    label = { Text("Homework") },
                    placeholder = { Text("What should students complete? One instruction per line.") },
                    minLines = 3,
                    isError = detailsError != null,
                    supportingText = detailsError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = FieldShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (count > 1) {
                    IconButton(onClick = { onMove(-1) }, enabled = number > 1) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move ${subjectName ?: "item"} up")
                    }
                    IconButton(onClick = { onMove(1) }, enabled = number < count) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move ${subjectName ?: "item"} down")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Remove")
                }
            }
        }
    }
}

/** The diary photo: a big camera target when empty, otherwise the page with Retake / Gallery / Remove. */
@Composable
private fun DiaryPhotoCard(
    photo: DraftPhoto,
    processing: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val previewModifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceContainerHigh)
            when (photo) {
                DraftPhoto.None -> Surface(
                    onClick = onCamera,
                    enabled = !processing,
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surfaceContainerHigh,
                    contentColor = colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                ) {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(40.dp))
                        Text(
                            if (processing) "Preparing photo…" else "Photograph the diary",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                        Text("One photo instead of typing each subject", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is DraftPhoto.Existing -> StoredPhoto(
                    fileName = photo.fileName,
                    contentDescription = "Homework diary photo",
                    modifier = previewModifier,
                    contentScale = ContentScale.Fit,
                    maxEdgePx = 1280,
                )
                is DraftPhoto.New -> PickedPhotoPreview(photo.photo, previewModifier)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onCamera, enabled = !processing, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (photo == DraftPhoto.None) "Camera" else "Retake")
                }
                OutlinedButton(onClick = onGallery, enabled = !processing, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Gallery")
                }
                if (photo != DraftPhoto.None) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onRemove, enabled = !processing) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remove photo")
                    }
                }
            }
        }
    }
}

@Composable
private fun PickedPhotoPreview(photo: PickedPhoto, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, photo) {
        value = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)?.asImageBitmap()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription = "Homework diary photo", modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Box(modifier)
    }
}

/** Save at the bottom right as a compact button; "Save & PDF" (the web app's second action) at the bottom left. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SaveBar(label: String, saving: Boolean, onSave: () -> Unit, onSaveAndPdf: () -> Unit) {
    BottomActionBar(
        secondary = {
            TextButton(onClick = onSaveAndPdf, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Save & PDF")
            }
        },
    ) {
        Button(onClick = onSave, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) {
            if (saving) {
                LoadingIndicator(Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text("Saving\u2026")
            } else {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(label)
            }
        }
    }
}

// ---- Previews ----------------------------------------------------------------------------------------

private val PreviewInstant = Instant.fromEpochMilliseconds(0)

private object NoOpEditorActions : HomeworkEditorActions {
    override fun updateDraft(transform: (HomeworkDraft) -> HomeworkDraft) = Unit

    override fun setPhoto(photo: PickedPhoto) = Unit

    override fun removePhoto(): DraftPhoto = DraftPhoto.None

    override fun restorePhoto(photo: DraftPhoto) = Unit

    override fun removeItem(key: Long): RemovedHomeworkItem? = null

    override fun restoreItem(removed: RemovedHomeworkItem) = Unit

    override fun save(thenPdf: Boolean) = Unit

    override fun delete() = Unit

    override suspend fun addSubject(name: String): HomeworkResult<Subject> = HomeworkResult.Failed("Preview")

    override suspend fun addAndUseSubject(name: String): HomeworkResult<Subject> = HomeworkResult.Failed("Preview")

    override suspend fun renameSubject(subjectId: Long, name: String): HomeworkResult<Subject> = HomeworkResult.Failed("Preview")

    override suspend fun setSubjectArchived(subjectId: Long, archived: Boolean): HomeworkResult<Unit> =
        HomeworkResult.Failed("Preview")
}

private fun previewForm(): HomeworkEditorForm {
    val classes = listOf(ClassGroup(1, "7", "A", PreviewInstant))
    val subjects = listOf(
        Subject(1, "Mathematics", "mathematics", null, PreviewInstant, PreviewInstant),
        Subject(2, "Urdu", "urdu", null, PreviewInstant, PreviewInstant),
    )
    val draft = newHomeworkDraft(classes, subjects, "Ms. Sana", homeworkToday())
        .withItem(1) { it.copy(details = "Exercise 4.2, questions 1 to 5") }
        .withItemAdded(null)
    return HomeworkEditorForm(
        draft = draft,
        photo = DraftPhoto.None,
        classes = classes,
        subjects = subjects,
        isNew = true,
        saving = false,
        showErrors = true,
        error = HomeworkFormError("Every Homework Item needs a Subject and assignment details.", HomeworkErrorTarget.ITEMS),
        errorSerial = 0,
        dirty = true,
    )
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun HomeworkEditorPreview() {
    AttendanceTheme(dynamicColor = false) {
        HomeworkEditorContent(HomeworkEditorState.Ready(previewForm()), NoOpEditorActions, isNew = true, onBack = {})
    }
}

package com.itexpert120.attendanceManager.ui.features.students

import androidx.compose.foundation.layout.Arrangement
import com.itexpert120.attendanceManager.ui.components.navigationBarBottom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.DelayedLoading
import com.itexpert120.attendanceManager.ui.components.EmptyState
import com.itexpert120.attendanceManager.ui.components.PageMaxWidth
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

private enum class StudentsSheet { None, Details, Form, NewClass, Duplicate, Existing }

private const val NO_ENROLLMENT = -1L

/**
 * Students and classes. Pick a class with the chips, search its roll, tap a student for details and to
 * call / message the parent, add students with the button at the bottom, and manage classes from the
 * top-bar menu. Sheets, not screens, hold every form so Back always closes the topmost thing.
 */
@Composable
fun StudentsScreen(
    initialClassGroupId: Long?,
    onBack: () -> Unit,
    /** Default "Admitted on" for a new student; today when null. */
    defaultAdmittedOn: LocalDate? = null,
) {
    val container = LocalAppContainer.current
    val vm: StudentsViewModel = viewModel {
        StudentsViewModel(container.students, container.classes, createSavedStateHandle(), initialClassGroupId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val pendingPhoto by vm.pendingPhoto.collectAsStateWithLifecycle()

    var sheet by rememberSaveable { mutableStateOf(StudentsSheet.None) }
    var sheetEnrollmentId by rememberSaveable { mutableLongStateOf(NO_ENROLLMENT) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val phones = container.phoneNormalizer

    fun closeSheet() {
        sheet = StudentsSheet.None
        sheetEnrollmentId = NO_ENROLLMENT
        vm.setPendingPhoto(null)
    }

    fun say(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun contact(action: ContactAction, row: EnrollmentRow) {
        if (row.student.phone.isBlank()) {
            say("${row.student.name} has no phone number saved.")
            return
        }
        if (launchContact(context, action, row.student.phone)) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        } else {
            say(
                when (action) {
                    ContactAction.CALL -> "No phone app available."
                    ContactAction.SMS -> "No messaging app available."
                    ContactAction.WHATSAPP -> "WhatsApp isn't available on this device."
                },
            )
        }
    }

    /** Changes only the struck-off date of [row], with an Undo that puts the previous date back. */
    fun setStruckOff(row: EnrollmentRow, struckOffOn: LocalDate?, message: String) {
        closeSheet()
        scope.launch {
            val saved = vm.saveStudent(studentInputFromRow(row, struckOffOn))
            saved.onFailure { snackbar.showSnackbar(it.message ?: "Could not save the student.") }
            if (saved.isSuccess) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) {
                    vm.saveStudent(studentInputFromRow(row, row.enrollment.struckOffOn))
                }
            }
        }
    }

    StudentsContent(
        state = state,
        snackbarHostState = snackbar,
        phoneDisplay = phones::display,
        onBack = onBack,
        onSelectClass = vm::selectClass,
        onQueryChange = vm::setQuery,
        onShowStruckOffChange = vm::setShowStruckOff,
        onOpenStudent = { row ->
            sheetEnrollmentId = row.enrollment.id
            sheet = StudentsSheet.Details
        },
        onContact = ::contact,
        onAddStudent = {
            vm.setPendingPhoto(null)
            sheetEnrollmentId = NO_ENROLLMENT
            sheet = StudentsSheet.Form
        },
        onAddExisting = {
            sheetEnrollmentId = NO_ENROLLMENT
            sheet = StudentsSheet.Existing
        },
        onNewClass = { sheet = StudentsSheet.NewClass },
        onDuplicateClass = { sheet = StudentsSheet.Duplicate },
    )

    if (state.loading) return
    val group = state.selectedClass
    val row = state.rows.firstOrNull { it.enrollment.id == sheetEnrollmentId }

    when (sheet) {
        StudentsSheet.None -> Unit

        StudentsSheet.Details -> if (row != null) {
            StudentDetailSheet(
                row = row,
                phoneDisplay = remember(row.student.phone) { phones.display(row.student.phone) },
                onContact = { action -> contact(action, row) },
                onEdit = {
                    vm.setPendingPhoto(null)
                    sheet = StudentsSheet.Form
                },
                onStrikeOff = { setStruckOff(row, strikeOffDate(row, vm.today()), "${row.student.name} struck off") },
                onRestore = { setStruckOff(row, null, "${row.student.name} is back on the roll") },
                onDismiss = ::closeSheet,
            )
        } else {
            LaunchedEffect(Unit) { closeSheet() }
        }

        StudentsSheet.Form -> if (group != null) {
            // An edit whose row no longer exists (for example after a restore) falls back to a closed sheet.
            if (sheetEnrollmentId != NO_ENROLLMENT && row == null) {
                LaunchedEffect(Unit) { closeSheet() }
            } else {
                StudentFormSheet(
                    editing = row,
                    classGroupId = group.id,
                    classText = classLabel(group),
                    today = vm.today(),
                    newAdmittedOn = defaultAdmittedOn ?: vm.today(),
                    suggestedRoll = suggestNextRollNumber(state.rows),
                    phoneNormalizer = phones,
                    pendingPhoto = pendingPhoto,
                    onPhotoPicked = vm::setPendingPhoto,
                    onSave = vm::saveStudent,
                    onSaved = { name ->
                        val edited = row != null
                        closeSheet()
                        say(if (edited) "Saved $name" else "Added $name")
                    },
                    onDismiss = ::closeSheet,
                    onChooseExisting = {
                        sheet = StudentsSheet.Existing
                    },
                )
            }
        }

        StudentsSheet.NewClass -> NewClassSheet(
            onCreate = vm::addClass,
            onCreated = { created ->
                closeSheet()
                say("Added ${classLabel(created)}")
            },
            onDismiss = ::closeSheet,
        )

        StudentsSheet.Duplicate -> if (group != null) {
            DuplicateClassSheet(
                source = group,
                sourceRows = state.rows,
                today = vm.today(),
                onDuplicate = { className, section, startsOn ->
                    vm.duplicateClass(group.id, className, section, startsOn)
                },
                onDuplicated = { created ->
                    closeSheet()
                    say("Created ${classLabel(created)}")
                },
                onDismiss = ::closeSheet,
            )
        }

        StudentsSheet.Existing -> if (group != null) {
            val all by vm.allStudents.collectAsStateWithLifecycle()
            StudentExistingSheet(
                classText = classLabel(group),
                classGroupId = group.id,
                allStudents = all,
                classRows = state.rows,
                // Same default as a new student: the register's first day when opened from a register.
                defaultAdmittedOn = defaultAdmittedOn ?: vm.today(),
                suggestedRoll = suggestNextRollNumber(state.rows),
                onAdd = vm::saveStudent,
                onAdded = { name ->
                    closeSheet()
                    say("Added $name to ${classLabel(group)}")
                },
                onDismiss = ::closeSheet,
            )
        }
    }
}

/** The screen without its ViewModel and sheets, so it can be previewed and reasoned about on its own. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StudentsContent(
    state: StudentsUiState,
    snackbarHostState: SnackbarHostState,
    phoneDisplay: (String) -> String,
    onBack: () -> Unit,
    onSelectClass: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onShowStruckOffChange: (Boolean) -> Unit,
    onOpenStudent: (EnrollmentRow) -> Unit,
    onContact: (ContactAction, EnrollmentRow) -> Unit,
    onAddStudent: () -> Unit,
    onAddExisting: () -> Unit,
    onNewClass: () -> Unit,
    onDuplicateClass: () -> Unit,
) {
    val listState = rememberLazyListState()
    val group = state.selectedClass
    var menuOpen by remember { mutableStateOf(false) }

    AppScreen(
        title = "Students",
        subtitle = headerSubtitle(state),
        onBack = onBack,
        large = false,
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Class options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New class") },
                        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        onClick = { menuOpen = false; onNewClass() },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate this class") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        enabled = group != null && state.rows.isNotEmpty(),
                        onClick = { menuOpen = false; onDuplicateClass() },
                    )
                    DropdownMenuItem(
                        text = { Text("Add existing student") },
                        leadingIcon = { Icon(Icons.Rounded.PersonSearch, contentDescription = null) },
                        enabled = group != null,
                        onClick = { menuOpen = false; onAddExisting() },
                    )
                }
            }
        },
        floatingActionButton = {
            if (group != null) {
                ExtendedFloatingActionButton(
                    onClick = onAddStudent,
                    expanded = !listState.canScrollBackward,
                    icon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
                    text = { Text("Add student") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = PageMaxWidth).fillMaxSize()) {
                when {
                    state.loading -> DelayedLoading()

                    group == null -> EmptyState(
                        icon = Icons.Rounded.School,
                        title = "Create your first class",
                        text = "Classes hold students, registers and tests. Add a class and section to begin.",
                        action = { Button(onClick = onNewClass) { Text("New class") } },
                    )

                    else -> {
                        ClassChips(state.classes, group.id, onSelectClass, onNewClass)
                        StudentSearchField(
                            query = state.query,
                            onQueryChange = onQueryChange,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        if (state.listing.totalStruckOff > 0) {
                            FilterChip(
                                selected = state.showStruckOff,
                                onClick = { onShowStruckOffChange(!state.showStruckOff) },
                                label = { Text("Struck off · ${state.listing.totalStruckOff}") },
                                leadingIcon = if (state.showStruckOff) {
                                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else {
                                    null
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        StudentList(
                            state = state,
                            listState = listState,
                            phoneDisplay = phoneDisplay,
                            onOpenStudent = onOpenStudent,
                            onContact = onContact,
                            onAddStudent = onAddStudent,
                            onAddExisting = onAddExisting,
                            onClearSearch = { onQueryChange("") },
                            onShowStruckOff = { onShowStruckOffChange(true) },
                        )
                    }
                }
            }
        }
    }
}

/** "Class 7 · A · 32 on roll" (plus the struck-off count), or a hint when there are no classes. */
internal fun headerSubtitle(state: StudentsUiState): String {
    if (state.loading) return ""
    val group = state.selectedClass ?: return "No classes yet"
    val struckOff = state.listing.totalStruckOff
    val counts = "${state.listing.totalOnRoll} on roll" + if (struckOff > 0) ", $struckOff struck off" else ""
    return "${classLabel(group)} · $counts"
}

@Composable
private fun ClassChips(
    classes: List<ClassGroup>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onNewClass: () -> Unit,
) {
    val rowState = rememberLazyListState()
    // Keep the selected chip in view after rotation or after a class was just created.
    LaunchedEffect(selectedId, classes.size) {
        val index = classes.indexOfFirst { it.id == selectedId }
        if (index >= 0) rowState.animateScrollToItem(index)
    }
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(classes.size, key = { classes[it].id }) { index ->
            val item = classes[index]
            FilterChip(
                selected = item.id == selectedId,
                onClick = { onSelect(item.id) },
                label = { Text(classChipLabel(item)) },
            )
        }
        item(key = "new-class") {
            AssistChip(
                onClick = onNewClass,
                label = { Text("New class") },
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/**
 * A search-bar styled input (pill, tonal container, leading search icon, clear button). The full
 * [androidx.compose.material3.SearchBar] has a 360dp minimum width and an expanding results overlay; filtering a
 * list in place only needs the field, so this keeps the look without the overlay.
 */
@Composable
private fun StudentSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text("Search name, roll or B-Form no.", maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainerHigh,
            unfocusedContainerColor = colors.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Search students" },
    )
}

@Composable
private fun StudentList(
    state: StudentsUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    phoneDisplay: (String) -> String,
    onOpenStudent: (EnrollmentRow) -> Unit,
    onContact: (ContactAction, EnrollmentRow) -> Unit,
    onAddStudent: () -> Unit,
    onAddExisting: () -> Unit,
    onClearSearch: () -> Unit,
    onShowStruckOff: () -> Unit,
) {
    val group = state.selectedClass ?: return
    val listing = state.listing
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // Bottom room so the floating button never covers the last row.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp + navigationBarBottom()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when {
            state.rows.isEmpty() -> item(key = "empty-class") {
                EmptyState(
                    icon = Icons.Rounded.Groups,
                    title = "No students in ${classLabel(group)}",
                    text = "Add students with their B-Form and roll numbers, or enrol students who are already in the school.",
                    action = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(onClick = onAddStudent) { Text("Add student") }
                            TextButton(onClick = onAddExisting) { Text("Add existing student") }
                        }
                    },
                )
            }

            listing.isEmpty && state.query.isNotBlank() -> item(key = "no-results") {
                EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = "No students match “${state.query.trim()}”",
                    text = "Check the spelling, or search by roll number or B-Form number.",
                    action = { TextButton(onClick = onClearSearch) { Text("Clear search") } },
                )
            }

            listing.isEmpty -> item(key = "all-struck-off") {
                EmptyState(
                    icon = Icons.Rounded.Groups,
                    title = "Everyone here is struck off",
                    text = "Struck-off students are hidden. Show them to see or restore them.",
                    action = { Button(onClick = onShowStruckOff) { Text("Show struck-off students") } },
                )
            }

            else -> {
                section("on-roll", "On roll · ${listing.onRoll.size}", listing.onRoll, phoneDisplay, onOpenStudent, onContact)
                section("struck-off", "Struck off · ${listing.struckOff.size}", listing.struckOff, phoneDisplay, onOpenStudent, onContact)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    key: String,
    header: String,
    rows: List<EnrollmentRow>,
    phoneDisplay: (String) -> String,
    onOpenStudent: (EnrollmentRow) -> Unit,
    onContact: (ContactAction, EnrollmentRow) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "header-$key") {
        Text(
            header,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
    }
    itemsIndexed(rows, key = { _, row -> row.enrollment.id }) { index, row ->
        StudentListItem(
            row = row,
            shape = segmentedShape(index, rows.size),
            phoneDisplay = remember(row.student.phone) { phoneDisplay(row.student.phone) },
            onClick = { onOpenStudent(row) },
            onCall = { onContact(ContactAction.CALL, row) },
            onMessage = { onContact(ContactAction.SMS, row) },
        )
    }
}

// ---- Previews -----------------------------------------------------------------------------------------

private val PreviewClass = ClassGroup(1, "7", "A", Instant.fromEpochMilliseconds(0))

private fun previewRow(id: Long, name: String, roll: String, struckOffOn: LocalDate? = null, phone: String = "+923001234567") = EnrollmentRow(
    Enrollment(id, id, 1, roll, LocalDate(2026, 4, 1), struckOffOn),
    Student(id, "10$id", name, phone, null, null, Instant.fromEpochMilliseconds(0)),
)

private fun previewState(query: String = "", rows: List<EnrollmentRow>) = StudentsUiState(
    loading = false,
    classes = listOf(PreviewClass, ClassGroup(2, "7", "B", Instant.fromEpochMilliseconds(0))),
    selectedClass = PreviewClass,
    rows = rows,
    query = query,
    listing = buildListing(rows, query, true),
)

private val PreviewRows = listOf(
    previewRow(1, "Ayaan S/O Awaan", "1"),
    previewRow(2, "Sara Khan", "2", phone = ""),
    previewRow(3, "Bilal Ahmed", "3", struckOffOn = LocalDate(2026, 8, 3)),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun StudentsContentPreview() {
    AttendanceTheme(dynamicColor = false) {
        StudentsContent(previewState(rows = PreviewRows), remember { SnackbarHostState() }, { it }, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, fontScale = 1.5f)
@Composable
private fun StudentsContentDarkPreview() {
    AttendanceTheme(dynamicColor = false, darkTheme = true) {
        StudentsContent(previewState(rows = PreviewRows), remember { SnackbarHostState() }, { it }, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun StudentsEmptyPreview() {
    AttendanceTheme(dynamicColor = false) {
        StudentsContent(previewState(rows = emptyList()), remember { SnackbarHostState() }, { it }, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun StudentsNoResultsPreview() {
    AttendanceTheme(dynamicColor = false) {
        StudentsContent(previewState(query = "zzz", rows = PreviewRows), remember { SnackbarHostState() }, { it }, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun StudentsNoClassesPreview() {
    AttendanceTheme(dynamicColor = false) {
        StudentsContent(StudentsUiState(loading = false), remember { SnackbarHostState() }, { it }, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }
}

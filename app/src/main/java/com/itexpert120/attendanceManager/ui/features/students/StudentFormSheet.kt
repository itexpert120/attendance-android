package com.itexpert120.attendanceManager.ui.features.students

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.data.files.PickedPhoto
import com.itexpert120.attendanceManager.data.repo.PhotoChange
import com.itexpert120.attendanceManager.data.repo.SavedStudent
import com.itexpert120.attendanceManager.data.repo.StudentInput
import com.itexpert120.attendanceManager.domain.PhoneNormalizer
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.ui.components.AppSheet
import com.itexpert120.attendanceManager.ui.components.DateField
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.components.rememberPhotoPicker
import com.itexpert120.attendanceManager.ui.theme.AttendanceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * The student form's fields, kept in a small holder that survives rotation and process death
 * (see [Saver]). The picked photo's bytes are too big for saved state, so they live in the ViewModel and
 * the holder only records what was decided ([photo]).
 */
@Stable
class StudentFormState(
    name: String,
    admissionNumber: String,
    rollNumber: String,
    phone: String,
    dateOfBirth: LocalDate?,
    admittedOn: LocalDate,
    struckOffOn: LocalDate?,
    photo: PhotoEdit,
) {
    var name by mutableStateOf(name)
    var admissionNumber by mutableStateOf(admissionNumber)
    var rollNumber by mutableStateOf(rollNumber)
    var phone by mutableStateOf(phone)
    var dateOfBirth by mutableStateOf(dateOfBirth)
    var admittedOn by mutableStateOf(admittedOn)
    var struckOffOn by mutableStateOf(struckOffOn)
    var photo by mutableStateOf(photo)

    /** Not saved: errors are recomputed by the next save. */
    var errors by mutableStateOf(StudentFormErrors.NONE)
    var saving by mutableStateOf(false)

    companion object {
        /** A blank form; [admittedOn] is the 1st of the register's month when opened from a register, else today. */
        fun forNew(admittedOn: LocalDate, suggestedRoll: String) =
            StudentFormState("", "", suggestedRoll, "", null, admittedOn, null, PhotoEdit.KEEP)

        fun forEdit(row: EnrollmentRow, phoneDisplay: String) = StudentFormState(
            name = row.student.name,
            admissionNumber = row.student.admissionNumber,
            rollNumber = row.enrollment.rollNumber,
            phone = phoneDisplay,
            dateOfBirth = row.student.dateOfBirth,
            admittedOn = row.enrollment.admittedOn,
            struckOffOn = row.enrollment.struckOffOn,
            photo = PhotoEdit.KEEP,
        )

        val Saver: Saver<StudentFormState, Any> = listSaver(
            save = {
                listOf(
                    it.name, it.admissionNumber, it.rollNumber, it.phone,
                    it.dateOfBirth?.toString().orEmpty(), it.admittedOn.toString(),
                    it.struckOffOn?.toString().orEmpty(), it.photo.name,
                )
            },
            restore = {
                StudentFormState(
                    name = it[0],
                    admissionNumber = it[1],
                    rollNumber = it[2],
                    phone = it[3],
                    dateOfBirth = (it[4]).takeIf { s -> s.isNotEmpty() }?.let(LocalDate::parse),
                    admittedOn = LocalDate.parse(it[5]),
                    struckOffOn = (it[6]).takeIf { s -> s.isNotEmpty() }?.let(LocalDate::parse),
                    photo = PhotoEdit.valueOf(it[7]),
                )
            },
        )
    }
}

/**
 * Add or edit a student ([editing] != null) in a bottom sheet. Validation messages from the repository are
 * shown under the field they are about and the sheet stays open until the save succeeds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudentFormSheet(
    editing: EnrollmentRow?,
    classGroupId: Long,
    classText: String,
    today: LocalDate,
    /** "Admitted on" for a new student. */
    newAdmittedOn: LocalDate,
    suggestedRoll: String,
    phoneNormalizer: PhoneNormalizer,
    pendingPhoto: PickedPhoto?,
    onPhotoPicked: (PickedPhoto?) -> Unit,
    onSave: suspend (StudentInput) -> Result<SavedStudent>,
    onSaved: (name: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseExisting: (() -> Unit)? = null,
) {
    val form = rememberSaveable(editing?.enrollment?.id, saver = StudentFormState.Saver) {
        if (editing != null) {
            StudentFormState.forEdit(editing, phoneNormalizer.display(editing.student.phone))
        } else {
            StudentFormState.forNew(newAdmittedOn, suggestedRoll)
        }
    }
    // After process death the form may say "replaced" while the picked bytes are gone: treat that as unchanged.
    val photo = if (form.photo == PhotoEdit.REPLACE && pendingPhoto == null) PhotoEdit.KEEP else form.photo
    val scope = rememberCoroutineScope()
    var photoError by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberPhotoPicker(onError = { photoError = it }) { picked ->
        photoError = null
        onPhotoPicked(picked)
        form.photo = PhotoEdit.REPLACE
    }
    val photoBusy = picker.isProcessing

    fun submit() {
        if (form.saving) return
        val local = validateStudentForm(
            form.name, form.admissionNumber, form.rollNumber, form.admittedOn, form.struckOffOn,
            originalBForm = editing?.student?.admissionNumber,
        )
        if (local.hasErrors) {
            form.errors = local.copy(phone = form.errors.phone)
            return
        }
        val photoChange = when {
            photo == PhotoEdit.REPLACE && pendingPhoto != null -> PhotoChange.Replace(pendingPhoto.bytes, pendingPhoto.mimeType)
            photo == PhotoEdit.REMOVE -> PhotoChange.Remove
            else -> PhotoChange.Keep
        }
        val input = StudentInput(
            classGroupId = classGroupId,
            admissionNumber = form.admissionNumber,
            rollNumber = form.rollNumber,
            name = form.name,
            phone = form.phone,
            admittedOn = form.admittedOn,
            studentId = editing?.student?.id,
            enrollmentId = editing?.enrollment?.id,
            dateOfBirth = form.dateOfBirth,
            struckOffOn = form.struckOffOn,
            photo = photoChange,
        )
        form.errors = StudentFormErrors.NONE
        form.saving = true
        scope.launch {
            val result = onSave(input)
            form.saving = false
            result
                .onSuccess { onSaved(form.name.trim()) }
                .onFailure { form.errors = StudentFormErrors.fromRepositoryMessage(it.message ?: "Could not save the student.") }
        }
    }

    AppSheet(
        title = if (editing != null) "Edit student" else "Add student",
        description = classText,
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            TextButton(onClick = onDismiss, enabled = !form.saving) { Text("Cancel") }
            Button(onClick = ::submit, enabled = !form.saving) {
                Text(
                    when {
                        form.saving -> "Saving…"
                        editing != null -> "Save"
                        else -> "Add student"
                    },
                )
            }
        },
    ) {
        form.errors.general?.let { ErrorBanner(it) }

        if (editing == null && onChooseExisting != null) {
            TextButton(onClick = onChooseExisting, modifier = Modifier.align(Alignment.Start)) {
                Icon(Icons.Rounded.PersonSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Already in the school? Add an existing student", modifier = Modifier.padding(start = 8.dp))
            }
        }

        // Photo: preview, then the three ways to change it.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormPhotoPreview(
                name = form.name,
                edit = photo,
                pending = pendingPhoto,
                storedFile = editing?.student?.photoFile,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = { photoError = null; picker.pickFromLibrary() }, enabled = !photoBusy) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Choose photo", modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { photoError = null; picker.takePhoto() }, enabled = !photoBusy) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Take photo", modifier = Modifier.padding(start = 8.dp))
                }
                val hasPhoto = photo == PhotoEdit.REPLACE || (photo == PhotoEdit.KEEP && editing?.student?.photoFile != null)
                if (hasPhoto) {
                    TextButton(
                        onClick = {
                            photoError = null
                            onPhotoPicked(null)
                            form.photo = PhotoEdit.REMOVE
                        },
                        enabled = !photoBusy,
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Remove", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            photoError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        StudentField(
            value = form.name,
            onValueChange = { form.name = it; form.errors = form.errors.copy(name = null) },
            label = "Name with parentage",
            placeholder = "Ayaan S/O Awaan",
            error = form.errors.name,
            capitalization = KeyboardCapitalization.Words,
        )
        BFormField(
            value = form.admissionNumber,
            onValueChange = { form.admissionNumber = it; form.errors = form.errors.copy(admissionNumber = null) },
            error = form.errors.admissionNumber,
        )
        StudentField(
            value = form.rollNumber,
            onValueChange = { form.rollNumber = it; form.errors = form.errors.copy(rollNumber = null) },
            label = "Roll number",
            error = form.errors.rollNumber,
            keyboardType = KeyboardType.Number,
        )
        StudentField(
            value = form.phone,
            onValueChange = { form.phone = it; form.errors = form.errors.copy(phone = null) },
            label = "Parent's phone number (optional)",
            placeholder = "0300 1234567",
            supportingText = "Used to call and message the parent. Never printed.",
            error = form.errors.phone,
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done,
            onFocusLost = {
                // Show the number the way it will be stored; flag an invalid one right away.
                try {
                    val normalized = phoneNormalizer.normalize(form.phone)
                    form.phone = phoneNormalizer.display(normalized)
                    form.errors = form.errors.copy(phone = null)
                } catch (e: ValidationException) {
                    form.errors = form.errors.copy(phone = e.message)
                }
            },
        )

        OptionalDate(
            label = "Date of birth (optional)",
            date = form.dateOfBirth,
            onChange = { form.dateOfBirth = it },
            clearLabel = "Clear date of birth",
            maxDate = today,
        )
        DateField(
            label = "Admitted on",
            date = form.admittedOn,
            onChange = { form.admittedOn = it; form.errors = form.errors.copy(struckOffOn = null) },
        )
        OptionalDate(
            label = "Struck off on (optional)",
            date = form.struckOffOn,
            onChange = { form.struckOffOn = it; form.errors = form.errors.copy(struckOffOn = null) },
            clearLabel = if (editing != null) "Clear date and put the student back on the roll" else "Clear struck-off date",
            hint = "Leave empty while the student is on the roll.",
            error = form.errors.struckOffOn,
        )
    }
}

/** A date that may be empty: the standard [DateField] plus a button to clear it. */
@Composable
private fun OptionalDate(
    label: String,
    date: LocalDate?,
    onChange: (LocalDate?) -> Unit,
    clearLabel: String,
    hint: String? = null,
    error: String? = null,
    maxDate: LocalDate? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        DateField(
            label = label,
            date = date,
            onChange = onChange,
            supportingText = if (error == null) hint else null,
            maxDate = maxDate,
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
        if (date != null) {
            // The kit's DateField only reports picked dates, so clearing is its own action.
            TextButton(onClick = { onChange(null) }) { Text(clearLabel) }
        }
    }
}

@Composable
private fun FormPhotoPreview(
    name: String,
    edit: PhotoEdit,
    pending: PickedPhoto?,
    storedFile: String?,
) {
    val colors = MaterialTheme.colorScheme
    val preview: Bitmap? by produceState<Bitmap?>(null, pending, edit) {
        value = if (edit == PhotoEdit.REPLACE && pending != null) {
            withContext(Dispatchers.Default) { decodePreview(pending.bytes) }
        } else {
            null
        }
    }
    val image = preview
    if (image != null) {
        Image(
            image.asImageBitmap(),
            contentDescription = "Selected photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(CircleShape).background(colors.secondaryContainer),
        )
    } else {
        StudentAvatar(
            name = name,
            photoFile = if (edit == PhotoEdit.KEEP) storedFile else null,
            size = 96.dp,
            textStyle = MaterialTheme.typography.headlineMedium,
        )
    }
}

private fun decodePreview(bytes: ByteArray, maxEdge: Int = 384): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** An outlined field with the keyboard behaviour forms need: the right keyboard and Next / Done actions. */
@Composable
internal fun StudentField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Next,
    onFocusLost: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = (error ?: supportingText)?.let { { Text(it) } },
        isError = error != null,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { focus.moveFocus(FocusDirection.Down) },
            onDone = {
                focus.clearFocus()
                onDone?.invoke()
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (it.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    onFocusLost?.invoke()
                }
            },
    )
}

/**
 * The B-Form number: a number keypad, with the dashes of `12345-1234567-1` inserted as the digits are typed
 * (the formatted text is what gets stored). An older value with other characters is shown as it is until
 * the user edits it.
 */
@Composable
private fun BFormField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
) {
    val focus = LocalFocusManager.current
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    OutlinedTextField(
        value = field,
        onValueChange = { edited ->
            if (edited.text == field.text) {
                field = edited // only the caret or selection moved
            } else {
                val (text, caret) = formatBFormEdit(edited.text, edited.selection.end)
                field = TextFieldValue(text, TextRange(caret))
                onValueChange(text)
            }
        },
        label = { Text("B-Form number") },
        placeholder = { Text("12345-1234567-1") },
        supportingText = { Text(error ?: "13 digits from the child's NADRA B-Form.") },
        isError = error != null,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(showBackground = true)
@Composable
private fun StudentFieldPreview() {
    AttendanceTheme(dynamicColor = false) {
        Box(Modifier.padding(16.dp)) {
            StudentField("Ayaan", {}, "Name with parentage", error = "Student name is required.")
        }
    }
}

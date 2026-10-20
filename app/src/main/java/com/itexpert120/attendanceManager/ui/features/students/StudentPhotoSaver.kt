package com.itexpert120.attendanceManager.ui.features.students

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.model.Student
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Saves a student's stored photo to the device's gallery; see [rememberStudentPhotoSaver]. */
class StudentPhotoSaver internal constructor(
    private val saveAction: (Student) -> Unit,
    private val busy: State<Boolean>,
) {
    /** True while a photo is being copied; disable the save action meanwhile. */
    val isSaving: Boolean get() = busy.value

    fun save(student: Student) = saveAction(student)
}

private const val GALLERY_FOLDER = "Attendance"

/**
 * Copies a student's photo out of the app's private storage into `Pictures/Attendance`, named after the
 * student and their B-Form number. Android 10+ writes through MediaStore (no permission needed); older
 * versions ask where to save it with the Storage Access Framework. The copy runs off the main thread and the
 * result is reported with a toast, which stays visible above the sheet that started it.
 */
@Composable
fun rememberStudentPhotoSaver(): StudentPhotoSaver {
    val context = LocalContext.current
    val photoFiles = LocalAppContainer.current.photoFiles
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }

    fun copy(savedMessage: String, block: suspend () -> Boolean) {
        busy.value = true
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { block() }
            } catch (_: Exception) {
                false
            }
            busy.value = false
            Toast.makeText(
                context,
                if (ok) savedMessage else "Could not save the photo.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Before Android 10: the user picks the place (and can rename the file) in the system's save dialog.
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val file = pendingFile
        pendingFile = null
        if (uri != null && file != null) copy("Photo saved.") { writeTo(context, uri, file) }
    }
    val latestLauncher by rememberUpdatedState(createDocument)

    return remember(photoFiles) {
        StudentPhotoSaver(
            saveAction = save@{ student ->
                val file = student.photoFile?.let(photoFiles::resolve)
                if (file == null) {
                    Toast.makeText(context, "This student has no photo.", Toast.LENGTH_SHORT).show()
                    return@save
                }
                val displayName = studentPhotoFileName(student.name, student.admissionNumber, file.extension.ifEmpty { "jpg" })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    copy("Photo saved to Pictures/$GALLERY_FOLDER.") { saveToMediaStore(context, file, displayName) }
                } else {
                    pendingFile = file
                    latestLauncher.launch(displayName)
                }
            },
            busy = busy,
        )
    }
}

private fun mimeTypeOf(file: File): String = when (file.extension.lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "image/jpeg"
}

private fun writeTo(context: Context, uri: Uri, file: File): Boolean {
    if (!file.isFile) return false
    val out = context.contentResolver.openOutputStream(uri) ?: return false
    out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    return true
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
private fun saveToMediaStore(context: Context, file: File, displayName: String): Boolean {
    if (!file.isFile) return false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, mimeTypeOf(file))
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: return false
    return try {
        val written = writeTo(context, uri, file)
        if (!written) {
            resolver.delete(uri, null, null)
            return false
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

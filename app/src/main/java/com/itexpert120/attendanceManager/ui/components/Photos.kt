package com.itexpert120.attendanceManager.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.files.PhotoProcessor
import com.itexpert120.attendanceManager.data.files.PickedPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Opens the system Photo Picker or the camera. Both end up in the `onPicked` callback of [rememberPhotoPicker].
 * [isProcessing] is true while a picked image is being decoded and resized; disable the photo buttons then.
 */
class PhotoPickerLauncher internal constructor(
    val pickFromLibrary: () -> Unit,
    val takePhoto: () -> Unit,
    private val processing: State<Boolean>,
) {
    val isProcessing: Boolean get() = processing.value
}

/**
 * Native photo input: the system Photo Picker (no storage permission needed) and the camera via FileProvider
 * (no camera permission needed because the camera app does the capture). The result is already downscaled and
 * upright; pass it to a repository as `PhotoChange.Replace(photo.bytes, photo.mimeType)`.
 *
 * [maxEdge] caps the long edge in pixels (diary pages use 1920 so handwriting stays readable).
 * [keepTransparency] keeps PNG logos transparent. [onError] receives the message to show when an image can't
 * be read; without it the failure would be silent.
 */
@Composable
fun rememberPhotoPicker(
    maxEdge: Int = 1280,
    keepTransparency: Boolean = false,
    onError: (String) -> Unit = {},
    onPicked: (PickedPhoto) -> Unit,
): PhotoPickerLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    val latestError by rememberUpdatedState(onError)
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val processing = remember { mutableStateOf(false) }

    fun process(uri: Uri) {
        processing.value = true
        scope.launch {
            val photo = PhotoProcessor.fromUri(context, uri, maxEdge, keepTransparency)
            processing.value = false
            if (photo != null) latest(photo) else latestError(PhotoProcessor.READ_ERROR)
        }
    }

    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) process(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        cameraUri = null
        if (saved && uri != null) process(uri)
    }
    return remember(library, camera) {
        PhotoPickerLauncher(
            pickFromLibrary = { library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            takePhoto = {
                val uri = PhotoProcessor.newCameraUri(context)
                cameraUri = uri
                camera.launch(uri)
            },
            processing = processing,
        )
    }
}

/** Shows a stored photo by file name (a student photo, school logo or diary page), or [placeholder] when absent. */
@Composable
fun StoredPhoto(
    fileName: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxEdgePx: Int = 512,
    placeholder: @Composable () -> Unit = {},
) {
    val photoStore = LocalAppContainer.current.photoFiles
    val bitmap: State<Bitmap?> = produceState<Bitmap?>(initialValue = null, fileName, maxEdgePx) {
        value = if (fileName == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                photoStore.resolve(fileName)?.takeIf { it.isFile }?.let { file -> decodeSampled(file.path, maxEdgePx) }
            }
        }
    }
    val image = bitmap.value
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription, modifier, contentScale = contentScale)
    } else {
        Box(modifier) { placeholder() }
    }
}

private fun decodeSampled(path: String, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

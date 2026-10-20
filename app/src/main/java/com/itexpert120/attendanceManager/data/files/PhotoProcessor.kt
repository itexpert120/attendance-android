package com.itexpert120.attendanceManager.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

/** A photo ready to hand to a repository as `PhotoChange.Replace(bytes, mimeType)`. */
class PickedPhoto(val bytes: ByteArray, val mimeType: String = "image/jpeg")

/**
 * Turns a picked or captured image into a modest JPEG: sampled at decode time so a 50-megapixel camera photo
 * never has to fit in memory, rotated upright from its EXIF orientation, and capped at [maxEdge] pixels.
 * The web app did the same with a canvas (`image.ts`).
 */
object PhotoProcessor {
    private const val DEFAULT_MAX_EDGE = 1280
    private const val JPEG_QUALITY = 85

    /** The message the web app showed when an image could not be used. */
    const val READ_ERROR = "Could not read that image."

    /**
     * Decodes [uri]; null when it isn't a readable image. With [keepTransparency] a source with an alpha
     * channel (a PNG logo) stays PNG so its transparent parts don't turn black; everything else is JPEG.
     */
    suspend fun fromUri(
        context: Context,
        uri: Uri,
        maxEdge: Int = DEFAULT_MAX_EDGE,
        keepTransparency: Boolean = false,
    ): PickedPhoto? =
        withContext(Dispatchers.IO) {
            runCatching { decode(context, uri, maxEdge, keepTransparency) }.getOrNull()
        }

    private fun decode(context: Context, uri: Uri, maxEdge: Int, keepTransparency: Boolean): PickedPhoto? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val scale = minOf(1f, maxEdge.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        val final = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val output = ByteArrayOutputStream()
        val png = keepTransparency && final.hasAlpha()
        final.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        if (final !== decoded) decoded.recycle()
        final.recycle()
        return PickedPhoto(output.toByteArray(), if (png) "image/png" else "image/jpeg")
    }

    /** A fresh cache file exposed through the app's FileProvider for the camera app to write into. */
    fun newCameraUri(context: Context): Uri {
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}

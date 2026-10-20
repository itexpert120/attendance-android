package com.itexpert120.attendanceManager.data.files

import android.content.Context
import com.itexpert120.attendanceManager.domain.PhotoStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Photos live in the app's private storage (`files/photos`), so they are removed with the app like the database. */
class FilePhotoStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PhotoStore {
    private val directory = File(context.applicationContext.filesDir, "photos").apply { mkdirs() }

    override suspend fun save(bytes: ByteArray, mimeType: String): String = withContext(io) {
        val name = "${UUID.randomUUID()}.${extensionFor(mimeType)}"
        // Write to a temp name first so a crash never leaves a half-written photo under its final name.
        val target = File(directory, name)
        val temp = File(directory, "$name.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(target)) { "Could not store the photo." }
        name
    }

    override suspend fun read(fileName: String): ByteArray? = withContext(io) {
        resolve(fileName)?.takeIf { it.isFile }?.readBytes()
    }

    override suspend fun delete(fileName: String) {
        withContext(io) { resolve(fileName)?.delete() }
    }

    /** The file for [fileName] if it stays inside the photo directory, so a stored name can never escape it. */
    fun resolve(fileName: String): File? =
        File(directory, fileName).takeIf { it.parentFile == directory }

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
}

package com.itexpert120.attendanceManager.data.repo

import com.itexpert120.attendanceManager.domain.PhotoStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What a save should do with a record's photo (student photo, school logo). */
sealed interface PhotoChange {
    /** Leave the stored photo as it is. */
    data object Keep : PhotoChange

    /** Delete the stored photo. */
    data object Remove : PhotoChange

    /** Store [bytes] as the new photo; the previous file is deleted once the save has committed. */
    class Replace(val bytes: ByteArray, val mimeType: String) : PhotoChange
}

/** Writes the replacement photo (if any) ahead of the transaction; returns its file name. */
internal suspend fun PhotoStore.saveReplacement(change: PhotoChange): String? =
    (change as? PhotoChange.Replace)?.let { save(it.bytes, it.mimeType) }

/** The file name the record should point at after this change, given its [current] file and any [saved] replacement. */
internal fun PhotoChange.resolve(current: String?, saved: String?): String? = when (this) {
    PhotoChange.Keep -> current
    PhotoChange.Remove -> null
    is PhotoChange.Replace -> saved
}

/** Deletes a file written for a save that then failed, even when the failure is a cancellation. */
internal suspend fun PhotoStore.discardQuietly(fileName: String?) {
    if (fileName == null) return
    withContext(NonCancellable) { runCatching { delete(fileName) } }
}

/** Deletes the file a committed save has replaced or removed. Failure to clean up never fails the save. */
internal suspend fun PhotoStore.deleteSuperseded(oldFile: String?, newFile: String?) {
    if (oldFile == null || oldFile == newFile) return
    runCatching { delete(oldFile) }
}

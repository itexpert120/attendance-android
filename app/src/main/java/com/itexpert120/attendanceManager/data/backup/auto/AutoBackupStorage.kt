package com.itexpert120.attendanceManager.data.backup.auto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.itexpert120.attendanceManager.data.backup.AppBackup
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * Where automatic backups live. On Android 10+ that is `Download/Attendance backups/` through MediaStore,
 * which needs no permission and survives uninstalling the app. Older versions get the app's external files
 * folder (`Android/data/<app>/files/backups`), which also needs no permission.
 *
 * Every write goes to a pending entry (or a temp file) first and is only published when complete, so a
 * half-written backup never appears in the folder or counts towards rotation.
 */
internal interface AutoBackupStorage {
    /** Writes a new backup called [fileName] with [write]; deletes the partial file if [write] throws. */
    suspend fun write(fileName: String, write: suspend (OutputStream) -> Unit)

    /** Deletes old automatic backups so only the newest [keep] remain. Manual backups are never touched. */
    fun rotate(keep: Int = AUTO_BACKUP_KEEP)

    companion object {
        fun create(context: Context): AutoBackupStorage =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStoreBackupStorage(context.applicationContext)
            } else {
                AppFilesBackupStorage(
                    context.applicationContext.getExternalFilesDir("backups")
                        ?: throw IOException("Shared storage is not available"),
                )
            }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private class MediaStoreBackupStorage(private val context: Context) : AutoBackupStorage {
    private val resolver get() = context.contentResolver
    private val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$AUTO_BACKUP_FOLDER/"

    override suspend fun write(fileName: String, write: suspend (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, AppBackup.MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create $fileName in Downloads")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException(fileName)
            output.use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    override fun rotate(keep: Int) {
        // Without storage permission MediaStore only lists files this app created, which is exactly the set
        // rotation may touch; pending (half-written) entries are excluded by default.
        val ids = mutableMapOf<BackupFileEntry, Long>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf(relativePath, "$AUTO_BACKUP_PREFIX%")
        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                ids[BackupFileEntry(name, cursor.getLong(addedColumn) * 1000)] = cursor.getLong(idColumn)
            }
        }
        autoBackupsToDelete(ids.keys.toList(), keep).forEach { entry ->
            val id = ids.getValue(entry)
            runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
        }
    }
}

private class AppFilesBackupStorage(private val directory: File) : AutoBackupStorage {
    override suspend fun write(fileName: String, write: suspend (OutputStream) -> Unit) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create ${directory.path}")
        val temp = File(directory, "$fileName.partial")
        try {
            temp.outputStream().use { write(it) }
            val target = File(directory, fileName)
            if (!temp.renameTo(target)) throw IOException("Could not save $fileName")
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    override fun rotate(keep: Int) {
        val files = directory.listFiles().orEmpty().filter { it.isFile }
        val byName = files.associateBy { it.name }
        autoBackupsToDelete(files.map { BackupFileEntry(it.name, it.lastModified()) }, keep)
            .forEach { byName[it.name]?.delete() }
        // Leftovers of a run killed mid-write never become backups; an hour old means no run still owns them.
        val staleBefore = System.currentTimeMillis() - 60 * 60 * 1000L
        files.filter { it.name.startsWith(AUTO_BACKUP_PREFIX) && it.name.endsWith(".partial") && it.lastModified() < staleBefore }
            .forEach { it.delete() }
    }
}

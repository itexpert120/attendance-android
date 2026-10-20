package com.itexpert120.attendanceManager.data.backup.auto

import com.itexpert120.attendanceManager.domain.shortMonthName
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import java.util.Locale

/*
 * The pure rules of automatic backups: how the files are named, which ones rotation removes and how the
 * Settings row describes the state. Kept free of Android so they are unit-tested on the JVM.
 */

/** How many automatic backups are kept; older ones are deleted after each successful backup. */
const val AUTO_BACKUP_KEEP = 5

const val AUTO_BACKUP_PREFIX = "attendance-auto-backup-"

/** The folder under Downloads (API 29+) that holds automatic backups. */
const val AUTO_BACKUP_FOLDER = "Attendance backups"

/**
 * Matches only names this app gives its automatic backups. MediaStore may add " (1)" when a name is
 * taken, so that suffix is allowed; manual backups ("attendance-backup-…") never match.
 */
private val AUTO_BACKUP_NAME = Regex("""^attendance-auto-backup-\d{4}-\d{2}-\d{2}-\d{4}( \(\d+\))?\.zip$""")

/** "attendance-auto-backup-2026-10-20-1405.zip" */
fun autoBackupFileName(time: LocalDateTime): String =
    AUTO_BACKUP_PREFIX + String.format(
        Locale.ROOT, "%04d-%02d-%02d-%02d%02d.zip", time.year, time.month.number, time.day, time.hour, time.minute,
    )

fun isAutoBackupName(name: String): Boolean = AUTO_BACKUP_NAME.matches(name)

/** A backup file found on the device; [timestamp] is its creation or modification time in epoch millis. */
data class BackupFileEntry(val name: String, val timestamp: Long)

/**
 * Given every file in the backup folder, returns the automatic backups to delete so that only the [keep]
 * newest remain. Files that aren't automatic backups (manual backups, anything else) are never returned.
 * Newest is decided by [BackupFileEntry.timestamp], then by name (which embeds the time) for ties.
 */
fun autoBackupsToDelete(files: List<BackupFileEntry>, keep: Int = AUTO_BACKUP_KEEP): List<BackupFileEntry> {
    require(keep >= 0) { "keep must not be negative" }
    return files
        .filter { isAutoBackupName(it.name) }
        .sortedWith(compareByDescending<BackupFileEntry> { it.timestamp }.thenByDescending { it.name })
        .drop(keep)
}

/** The outcome of the latest automatic backup attempt, as stored in preferences. */
data class AutoBackupStatus(
    val enabled: Boolean,
    /** Local time of the last successful backup, or null if none has run yet. */
    val lastSuccess: LocalDateTime?,
    /** Set when the latest attempt failed (it is cleared by the next success). */
    val lastError: String?,
)

/** "20 Oct, 14:05" */
fun backupTimeLabel(time: LocalDateTime): String =
    "${time.day} ${shortMonthName(time.month.number)}, " +
        String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)

/**
 * The detail line of the "Automatic backups" row: "Daily · keeps last 5 · Last: 20 Oct, 14:05",
 * or the latest error, or that automatic backups are off.
 */
fun autoBackupDetail(status: AutoBackupStatus): String {
    if (!status.enabled) return "Off · existing backups are kept"
    val last = when {
        status.lastError != null -> "Last attempt failed: ${status.lastError}"
        status.lastSuccess != null -> "Last: ${backupTimeLabel(status.lastSuccess)}"
        else -> "No backup yet"
    }
    return "Daily · keeps last $AUTO_BACKUP_KEEP · $last"
}

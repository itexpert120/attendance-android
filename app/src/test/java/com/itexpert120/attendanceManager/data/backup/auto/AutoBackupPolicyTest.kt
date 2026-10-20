package com.itexpert120.attendanceManager.data.backup.auto

import com.itexpert120.attendanceManager.ui.features.settings.autoBackupRowDetail
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupPolicyTest {
    private fun auto(day: Int, timestamp: Long = day.toLong()) =
        BackupFileEntry("attendance-auto-backup-2026-10-%02d-0300.zip".format(day), timestamp)

    @Test
    fun `names auto backups with date and time`() {
        assertEquals(
            "attendance-auto-backup-2026-10-20-1405.zip",
            autoBackupFileName(LocalDateTime(2026, 10, 20, 14, 5)),
        )
        assertEquals(
            "attendance-auto-backup-2026-01-02-0007.zip",
            autoBackupFileName(LocalDateTime(2026, 1, 2, 0, 7)),
        )
    }

    @Test
    fun `recognises only automatic backup names`() {
        assertTrue(isAutoBackupName("attendance-auto-backup-2026-10-20-1405.zip"))
        assertTrue(isAutoBackupName("attendance-auto-backup-2026-10-20-1405 (1).zip"))
        assertFalse(isAutoBackupName("attendance-backup-2026-10-20.zip"))
        assertFalse(isAutoBackupName("attendance-auto-backup-2026-10-20-1405.zip.partial"))
        assertFalse(isAutoBackupName("attendance-auto-backup-notes.zip"))
        assertFalse(isAutoBackupName("photo.jpg"))
    }

    @Test
    fun `keeps nothing to delete when five or fewer exist`() {
        assertEquals(emptyList<BackupFileEntry>(), autoBackupsToDelete((1..5).map { auto(it) }))
        assertEquals(emptyList<BackupFileEntry>(), autoBackupsToDelete(emptyList()))
    }

    @Test
    fun `deletes the oldest beyond the newest five regardless of input order`() {
        val files = listOf(auto(3), auto(7), auto(1), auto(5), auto(2), auto(6), auto(4))
        assertEquals(listOf(auto(2), auto(1)), autoBackupsToDelete(files))
    }

    @Test
    fun `never deletes manual backups or other files`() {
        val manual = BackupFileEntry("attendance-backup-2026-01-01.zip", 0)
        val other = BackupFileEntry("notes.txt", 0)
        val files = (1..6).map { auto(it) } + manual + other
        assertEquals(listOf(auto(1)), autoBackupsToDelete(files))
    }

    @Test
    fun `uses the name to order backups with the same timestamp`() {
        val files = (1..7).map { auto(it, timestamp = 100) }
        assertEquals(listOf(auto(2, 100), auto(1, 100)), autoBackupsToDelete(files))
    }

    @Test
    fun `honours a custom keep count`() {
        val files = (1..3).map { auto(it) }
        assertEquals(listOf(auto(2), auto(1)), autoBackupsToDelete(files, keep = 1))
        assertEquals(files.reversed(), autoBackupsToDelete(files, keep = 0))
    }

    @Test
    fun `formats the last backup time`() {
        assertEquals("20 Oct, 14:05", backupTimeLabel(LocalDateTime(2026, 10, 20, 14, 5)))
        assertEquals("3 Jan, 09:00", backupTimeLabel(LocalDateTime(2026, 1, 3, 9, 0)))
    }

    @Test
    fun `describes the automatic backup state`() {
        val time = LocalDateTime(2026, 10, 20, 14, 5)
        assertEquals(
            "Daily · keeps last 5 · Last: 20 Oct, 14:05",
            autoBackupDetail(AutoBackupStatus(enabled = true, lastSuccess = time, lastError = null)),
        )
        assertEquals(
            "Daily · keeps last 5 · No backup yet",
            autoBackupDetail(AutoBackupStatus(enabled = true, lastSuccess = null, lastError = null)),
        )
        assertEquals(
            "Daily · keeps last 5 · Last attempt failed: disk full",
            autoBackupDetail(AutoBackupStatus(enabled = true, lastSuccess = time, lastError = "disk full")),
        )
        assertEquals(
            "Off · existing backups are kept",
            autoBackupDetail(AutoBackupStatus(enabled = false, lastSuccess = time, lastError = "disk full")),
        )
    }

    @Test
    fun `shows progress while backing up now`() {
        val status = AutoBackupStatus(enabled = true, lastSuccess = null, lastError = null)
        assertEquals("Backing up…", autoBackupRowDetail(status, backingUpNow = true))
        assertEquals("Daily · keeps last 5 · No backup yet", autoBackupRowDetail(status, backingUpNow = false))
    }
}

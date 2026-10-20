package com.itexpert120.attendanceManager.data.backup.auto

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.itexpert120.attendanceManager.AttendanceApplication
import com.itexpert120.attendanceManager.data.backup.AppBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/** Writes one automatic backup, then deletes the older ones beyond [AUTO_BACKUP_KEEP]. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AttendanceApplication).container
        val preferences = container.autoBackupPreferences
        return try {
            // Before setup (a fresh install, or after "Clear data") there is nothing to keep, and an empty
            // backup would rotate away the good ones, so the run is skipped.
            if (container.settings.getSettings() == null) return Result.success()
            val now = Clock.System.now()
            val storage = AutoBackupStorage.create(applicationContext)
            val name = autoBackupFileName(now.toLocalDateTime(TimeZone.currentSystemDefault()))
            storage.write(name) { output -> AppBackup(container.database, container.photoStore).export(output) }
            preferences.recordSuccess(now)
            runCatching { storage.rotate() }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            preferences.recordFailure(failureMessage(e))
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private fun failureMessage(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "not enough memory"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "could not write the file"
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}

/** Schedules or cancels the daily automatic backup according to [AutoBackupPreferences]. */
class AutoBackupScheduler(context: Context) {
    private val workManager by lazy { WorkManager.getInstance(context.applicationContext) }

    private val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    /** Called at app start and whenever the setting changes. */
    fun apply(enabled: Boolean) {
        if (enabled) schedule() else cancel()
    }

    /** Keeps an existing schedule, so opening the app doesn't push the next backup back by a day. */
    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Stops future automatic backups; the files already written stay. */
    private fun cancel() {
        workManager.cancelUniqueWork(DAILY_WORK)
    }

    /** Runs one automatic backup as soon as possible (it still counts towards the five kept). */
    fun backUpNow() {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>().build()
        workManager.enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** True while a [backUpNow] backup is queued or running. */
    val backingUpNow: Flow<Boolean>
        get() = workManager.getWorkInfosForUniqueWorkFlow(NOW_WORK)
            .map { infos -> infos.any { !it.state.isFinished } }

    private companion object {
        const val DAILY_WORK = "auto-backup-daily"
        const val NOW_WORK = "auto-backup-now"
    }
}

package com.itexpert120.attendanceManager

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.itexpert120.attendanceManager.data.backup.auto.AutoBackupPreferences
import com.itexpert120.attendanceManager.data.backup.auto.AutoBackupScheduler
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.data.files.AndroidPhoneNormalizer
import com.itexpert120.attendanceManager.data.files.FilePhotoStore
import com.itexpert120.attendanceManager.data.repo.ClassRepository
import com.itexpert120.attendanceManager.data.repo.HomeworkRepository
import com.itexpert120.attendanceManager.data.repo.RegisterRepository
import com.itexpert120.attendanceManager.data.repo.SettingsRepository
import com.itexpert120.attendanceManager.data.repo.StudentRepository
import com.itexpert120.attendanceManager.data.repo.SubjectRepository
import com.itexpert120.attendanceManager.data.repo.SubjectTeacherRepository
import com.itexpert120.attendanceManager.data.repo.TestRepository
import com.itexpert120.attendanceManager.domain.PhoneNormalizer
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires the app's long-lived objects by hand; there is only one process-wide instance, owned by
 * [AttendanceApplication]. Screens reach it through [LocalAppContainer] and build their ViewModels from
 * the repositories, so nothing below the UI knows about Android navigation or Compose.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Work that must outlive any one screen, such as startup migrations. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AttendanceDatabase by lazy { AttendanceDatabase.create(appContext) }
    val photoStore: PhotoStore by lazy { FilePhotoStore(appContext) }
    val photoFiles: FilePhotoStore get() = photoStore as FilePhotoStore
    val phoneNormalizer: PhoneNormalizer by lazy { AndroidPhoneNormalizer(appContext) }
    val themePreference: ThemePreferenceStore by lazy { ThemePreferenceStore(appContext) }

    val settings: SettingsRepository by lazy { SettingsRepository(database, photoStore) }
    val classes: ClassRepository by lazy { ClassRepository(database, photoStore = photoStore) }
    val students: StudentRepository by lazy { StudentRepository(database, photoStore, phoneNormalizer) }
    val registers: RegisterRepository by lazy { RegisterRepository(database) }
    val subjects: SubjectRepository by lazy { SubjectRepository(database) }
    val tests: TestRepository by lazy { TestRepository(database) }
    val subjectTeachers: SubjectTeacherRepository by lazy { SubjectTeacherRepository(database) }
    val homework: HomeworkRepository by lazy { HomeworkRepository(database, photoStore) }

    val autoBackupPreferences: AutoBackupPreferences by lazy { AutoBackupPreferences(appContext) }
    val autoBackupScheduler: AutoBackupScheduler by lazy { AutoBackupScheduler(appContext) }

    /** Called once from [AttendanceApplication.onCreate]; keeps the slow first-use work off the main thread. */
    fun startup() {
        applicationScope.launch(Dispatchers.IO) {
            // libphonenumber reads its metadata from assets on first use, which would otherwise hitch a screen.
            (phoneNormalizer as? AndroidPhoneNormalizer)?.warmUp()
            // Older data may hold phone numbers in a non-canonical format (the PWA did the same on load).
            runCatching { students.migratePhoneNumbers() }
            // Automatic backups are on unless the user turned them off; KEEP leaves an existing schedule alone.
            runCatching { autoBackupScheduler.apply(autoBackupPreferences.enabled) }
        }
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer: screens must be shown inside AttendanceApp")
}

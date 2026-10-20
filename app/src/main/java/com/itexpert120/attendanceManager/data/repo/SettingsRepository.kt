package com.itexpert120.attendanceManager.data.repo

import androidx.room3.withWriteTransaction
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.SchoolSettings
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock

/** The single School Settings record, created by first-run setup. */
class SettingsRepository(
    private val db: AttendanceDatabase,
    private val photoStore: PhotoStore,
    private val clock: Clock = Clock.System,
) {
    /** Emits null until setup has been completed. */
    fun observeSettings(): Flow<SchoolSettings?> = db.settings().observe()

    suspend fun getSettings(): SchoolSettings? = db.settings().get()

    /**
     * First-run setup: writes the settings and the first Class Group in one transaction. A blank
     * [currencyLabel] falls back to "Rs.". Returns the created class.
     */
    suspend fun completeSetup(
        schoolName: String,
        className: String,
        section: String,
        academicYearStartMonth: Int,
        currencyLabel: String = DEFAULT_CURRENCY,
        classInchargeName: String = "",
        logo: PhotoChange = PhotoChange.Keep,
    ): ClassGroup {
        val school = schoolName.trim()
        val classLabel = className.trim()
        val sectionLabel = section.trim()
        if (school.isEmpty()) throw ValidationException("School name is required.")
        if (classLabel.isEmpty() || sectionLabel.isEmpty()) throw ValidationException("Class and section are required.")
        requireValidMonth(academicYearStartMonth)

        val savedLogo = photoStore.saveReplacement(logo)
        val committed: Pair<ClassGroup, String?>
        try {
            val now = clock.now()
            committed = db.withWriteTransaction {
                val groups = db.classGroups()
                if (groups.find(classLabel, sectionLabel) != null) {
                    throw ValidationException("That class and section already exists.")
                }
                val previousLogo = db.settings().get()?.logoFile
                db.settings().upsert(
                    SchoolSettings(
                        schoolName = school,
                        academicYearStartMonth = academicYearStartMonth,
                        currencyLabel = currencyLabel.trim().ifEmpty { DEFAULT_CURRENCY },
                        classInchargeName = classInchargeName.trim(),
                        logoFile = logo.resolve(previousLogo, savedLogo),
                        updatedAt = now,
                    ),
                )
                val created = ClassGroup(className = classLabel, section = sectionLabel, createdAt = now)
                created.copy(id = groups.insert(created)) to previousLogo
            }
        } catch (t: Throwable) {
            photoStore.discardQuietly(savedLogo)
            throw t
        }
        photoStore.deleteSuperseded(committed.second, logo.resolve(committed.second, savedLogo))
        return committed.first
    }

    /**
     * Updates the settings after setup. A blank [absenceMessageTemplate] is stored as null, which means
     * "use the default template". The previous logo file is deleted after the save commits when it was
     * replaced or removed.
     */
    suspend fun saveSettings(
        schoolName: String,
        academicYearStartMonth: Int,
        currencyLabel: String,
        classInchargeName: String,
        absenceMessageTemplate: String? = null,
        logo: PhotoChange = PhotoChange.Keep,
    ) {
        val school = schoolName.trim()
        if (school.isEmpty()) throw ValidationException("School name is required.")
        requireValidMonth(academicYearStartMonth)

        val savedLogo = photoStore.saveReplacement(logo)
        val committed: Pair<String?, String?>
        try {
            val now = clock.now()
            committed = db.withWriteTransaction {
                val existing = db.settings().get() ?: throw ValidationException("Complete the school setup first.")
                val updatedLogo = logo.resolve(existing.logoFile, savedLogo)
                db.settings().upsert(
                    existing.copy(
                        schoolName = school,
                        academicYearStartMonth = academicYearStartMonth,
                        currencyLabel = currencyLabel.trim().ifEmpty { DEFAULT_CURRENCY },
                        classInchargeName = classInchargeName.trim(),
                        logoFile = updatedLogo,
                        absenceMessageTemplate = absenceMessageTemplate?.takeIf { it.isNotBlank() },
                        updatedAt = now,
                    ),
                )
                existing.logoFile to updatedLogo
            }
        } catch (t: Throwable) {
            photoStore.discardQuietly(savedLogo)
            throw t
        }
        photoStore.deleteSuperseded(committed.first, committed.second)
    }

    private fun requireValidMonth(month: Int) {
        if (month !in 1..12) throw ValidationException("Choose the month the academic year starts in.")
    }

    companion object {
        const val DEFAULT_CURRENCY = "Rs."
    }
}

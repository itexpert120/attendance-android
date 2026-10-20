package com.itexpert120.attendanceManager.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.support.FakePhotoStore
import com.itexpert120.attendanceManager.support.MutableClock
import com.itexpert120.attendanceManager.support.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private lateinit var db: AttendanceDatabase
    private val photos = FakePhotoStore()
    private val clock = MutableClock()
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = SettingsRepository(db, photos, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun setup(
        logo: PhotoChange = PhotoChange.Keep,
        currency: String = "Rs.",
    ) = repo.completeSetup(
        schoolName = "  Crescent Public School ",
        className = " 7 ",
        section = " A ",
        academicYearStartMonth = 4,
        currencyLabel = currency,
        classInchargeName = " Ms Fatima ",
        logo = logo,
    )

    @Test
    fun settingsAreNullBeforeSetup() = runTest {
        assertNull(repo.observeSettings().first())
    }

    @Test
    fun completeSetupCreatesSettingsAndFirstClassTogether() = runTest {
        val group = setup()

        val settings = repo.observeSettings().first()!!
        assertEquals("Crescent Public School", settings.schoolName)
        assertEquals(4, settings.academicYearStartMonth)
        assertEquals("Ms Fatima", settings.classInchargeName)
        assertNull(settings.logoFile)
        assertNull(settings.absenceMessageTemplate)
        assertEquals(Instant.parse("2026-08-15T09:00:00Z"), settings.updatedAt)
        assertEquals("7", group.className)
        assertEquals("A", group.section)
        assertEquals(listOf(group), db.classGroups().getAll())
    }

    @Test
    fun blankCurrencyFallsBackToRupees() = runTest {
        setup(currency = "   ")

        assertEquals("Rs.", repo.getSettings()!!.currencyLabel)
    }

    @Test
    fun customCurrencyIsKeptTrimmed() = runTest {
        setup(currency = " PKR ")

        assertEquals("PKR", repo.getSettings()!!.currencyLabel)
    }

    @Test
    fun completeSetupStoresTheLogoFile() = runTest {
        setup(logo = PhotoChange.Replace(byteArrayOf(1, 2, 3), "image/png"))

        val logo = repo.getSettings()!!.logoFile
        assertNotNull(logo)
        assertTrue(photos.files.containsKey(logo))
    }

    @Test
    fun failedSetupWritesNothingAndRemovesTheNewLogo() = runTest {
        setup()
        // A second setup that clashes with the existing class must roll back its settings change.
        try {
            repo.completeSetup(
                schoolName = "Other School",
                className = "7",
                section = "a",
                academicYearStartMonth = 1,
                logo = PhotoChange.Replace(byteArrayOf(9), "image/png"),
            )
            fail("expected a ValidationException")
        } catch (e: ValidationException) {
            assertEquals("That class and section already exists.", e.message)
        }

        assertEquals("Crescent Public School", repo.getSettings()!!.schoolName)
        assertTrue(photos.files.isEmpty())
        assertEquals(1, db.classGroups().getAll().size)
    }

    @Test
    fun setupRequiresSchoolClassAndSection() = runTest {
        for ((school, cls, section) in listOf(
            Triple("", "7", "A"),
            Triple("School", " ", "A"),
            Triple("School", "7", ""),
        )) {
            try {
                repo.completeSetup(school, cls, section, academicYearStartMonth = 4)
                fail("expected a ValidationException")
            } catch (_: ValidationException) {
            }
        }
        assertNull(repo.getSettings())
        assertTrue(db.classGroups().getAll().isEmpty())
    }

    @Test
    fun saveSettingsUpdatesFieldsAndBumpsUpdatedAt() = runTest {
        setup()
        clock.now = Instant.parse("2026-09-01T10:00:00Z")

        repo.saveSettings(
            schoolName = " New Name ",
            academicYearStartMonth = 8,
            currencyLabel = "",
            classInchargeName = "Mr Ali",
            absenceMessageTemplate = "Hello {student}",
        )

        val settings = repo.getSettings()!!
        assertEquals("New Name", settings.schoolName)
        assertEquals(8, settings.academicYearStartMonth)
        assertEquals("Rs.", settings.currencyLabel)
        assertEquals("Mr Ali", settings.classInchargeName)
        assertEquals("Hello {student}", settings.absenceMessageTemplate)
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), settings.updatedAt)
    }

    @Test
    fun blankAbsenceTemplateMeansDefault() = runTest {
        setup()
        repo.saveSettings("School", 4, "Rs.", "", absenceMessageTemplate = "custom")
        repo.saveSettings("School", 4, "Rs.", "", absenceMessageTemplate = "  ")

        assertNull(repo.getSettings()!!.absenceMessageTemplate)
    }

    @Test
    fun saveSettingsBeforeSetupIsRejected() = runTest {
        try {
            repo.saveSettings("School", 4, "Rs.", "", logo = PhotoChange.Replace(byteArrayOf(2), "image/png"))
            fail("expected a ValidationException")
        } catch (_: ValidationException) {
        }
        assertNull(repo.getSettings())
        assertTrue(photos.files.isEmpty())
    }

    @Test
    fun replacingTheLogoDeletesTheOldFileAfterSaving() = runTest {
        setup(logo = PhotoChange.Replace(byteArrayOf(1), "image/png"))
        val oldLogo = repo.getSettings()!!.logoFile!!

        repo.saveSettings("School", 4, "Rs.", "", logo = PhotoChange.Replace(byteArrayOf(2), "image/png"))

        val newLogo = repo.getSettings()!!.logoFile!!
        assertTrue(newLogo != oldLogo)
        assertEquals(setOf(newLogo), photos.files.keys)
    }

    @Test
    fun removingTheLogoDeletesTheFile() = runTest {
        setup(logo = PhotoChange.Replace(byteArrayOf(1), "image/png"))

        repo.saveSettings("School", 4, "Rs.", "", logo = PhotoChange.Remove)

        assertNull(repo.getSettings()!!.logoFile)
        assertTrue(photos.files.isEmpty())
    }

    @Test
    fun keepingTheLogoLeavesTheFileInPlace() = runTest {
        setup(logo = PhotoChange.Replace(byteArrayOf(1), "image/png"))
        val logo = repo.getSettings()!!.logoFile

        repo.saveSettings("Renamed", 4, "Rs.", "")

        assertEquals(logo, repo.getSettings()!!.logoFile)
        assertEquals(setOf(logo), photos.files.keys)
    }

    @Test
    fun rejectedSaveDiscardsTheReplacementLogoAndKeepsTheOld() = runTest {
        setup(logo = PhotoChange.Replace(byteArrayOf(1), "image/png"))
        val logo = repo.getSettings()!!.logoFile

        try {
            repo.saveSettings("School", 13, "Rs.", "", logo = PhotoChange.Replace(byteArrayOf(2), "image/png"))
            fail("expected a ValidationException")
        } catch (_: ValidationException) {
        }

        assertEquals(logo, repo.getSettings()!!.logoFile)
        assertEquals(setOf(logo), photos.files.keys)
    }
}

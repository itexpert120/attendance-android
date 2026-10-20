package com.itexpert120.attendanceManager.support

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.PhoneNormalizer
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.ValidationException
import kotlin.time.Clock
import kotlin.time.Instant

/** A fresh in-memory database; call `close()` in `@After`. Foreign keys are enforced like production. */
fun inMemoryDatabase(): AttendanceDatabase =
    Room.inMemoryDatabaseBuilder<AttendanceDatabase>(ApplicationProvider.getApplicationContext<Context>())
        .setDriver(BundledSQLiteDriver())
        .build()

/** Keeps photos in memory so repository tests can assert on what was saved and deleted. */
class FakePhotoStore : PhotoStore {
    val files = linkedMapOf<String, ByteArray>()
    private var counter = 0

    override suspend fun save(bytes: ByteArray, mimeType: String): String {
        val name = "photo-${++counter}.${mimeType.substringAfter('/', "bin")}"
        files[name] = bytes
        return name
    }

    override suspend fun read(fileName: String): ByteArray? = files[fileName]

    override suspend fun delete(fileName: String) {
        files.remove(fileName)
    }
}

/**
 * Accepts digits with an optional leading "+" and returns them unchanged; "invalid" inputs throw.
 * Real normalisation is covered by the libphonenumber-backed implementation's own tests.
 */
class FakePhoneNormalizer : PhoneNormalizer {
    override fun normalize(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (!text.matches(Regex("""\+?\d{7,15}"""))) {
            throw ValidationException("Enter a valid phone number, for example 0300 1234567 or +92 300 1234567.")
        }
        return text
    }

    override fun display(value: String): String = value
}

/** A clock tests can move; defaults to 2026-08-15T09:00:00Z. */
class MutableClock(var now: Instant = Instant.parse("2026-08-15T09:00:00Z")) : Clock {
    override fun now(): Instant = now
}

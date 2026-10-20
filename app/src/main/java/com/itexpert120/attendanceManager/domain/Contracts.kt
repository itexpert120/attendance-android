package com.itexpert120.attendanceManager.domain

/**
 * A business-rule violation whose [message] is shown to the user as-is (the PWA threw plain `Error`s
 * with the same kind of text). Repositories throw this for anything the user can fix: duplicate admission
 * numbers, marks above the Total Marks, editing a locked roster, and so on.
 */
class ValidationException(message: String) : Exception(message)

/** Normalises and formats phone numbers. The Android implementation wraps libphonenumber (default region PK). */
interface PhoneNormalizer {
    /** E.164 form of [input], or "" for blank input. Throws [ValidationException] for an invalid number. */
    fun normalize(input: String): String

    /** International display form; returns [value] unchanged when it can't be parsed. */
    fun display(value: String): String
}

/**
 * Stores user photos (student photos, school logo, homework diary pages) as files and hands back a stable
 * file name to keep in the database, instead of embedding base64 in rows like the PWA did.
 */
interface PhotoStore {
    /** Saves [bytes] and returns the new file name. */
    suspend fun save(bytes: ByteArray, mimeType: String): String

    suspend fun read(fileName: String): ByteArray?

    /** Deleting a name that doesn't exist is a no-op. */
    suspend fun delete(fileName: String)
}

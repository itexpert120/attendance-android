package com.itexpert120.attendanceManager.domain

/**
 * A business-rule violation whose [message] is shown to the user as-is (the PWA threw plain `Error`s
 * with the same kind of text). Repositories throw this for anything the user can fix: duplicate B-Form
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

/**
 * The student's NADRA B-Form (child registration certificate) number, kept in `Student.admissionNumber`:
 * 13 digits shown as `12345-1234567-1`. Numbers saved before this rule (free-text admission numbers) stay
 * valid while they are not changed.
 */
object BFormNumber {
    const val DIGITS = 13

    const val FORMAT_MESSAGE = "B-Form number must have 13 digits (12345-1234567-1)."
    const val REQUIRED_MESSAGE = "B-Form number is required."
    const val DUPLICATE_MESSAGE = "B-Form number must be unique."

    /** The digits of [input], in order. */
    fun digits(input: String): String = input.filter { it in '0'..'9' }

    /**
     * Formats what the user has typed so far: keeps at most 13 digits and inserts the dashes after the 5th and
     * 12th digit ("1234512" -> "12345-12"). Used while typing, so partial input is fine.
     */
    fun formatPartial(input: String): String {
        val d = digits(input).take(DIGITS)
        return buildString {
            d.forEachIndexed { i, c ->
                if (i == 5 || i == 12) append('-')
                append(c)
            }
        }
    }

    /**
     * The dashed form `12345-1234567-1` of [input] when it is exactly 13 digits, written with or without dashes
     * and spaces; null for anything else.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.any { it !in '0'..'9' && it != '-' && it != ' ' }) return null
        val d = digits(trimmed)
        return if (d.length == DIGITS) formatPartial(d) else null
    }

    /** Whether [stored] and [other] are the same number, ignoring dashes, spaces and case. */
    fun sameNumber(stored: String, other: String): Boolean {
        val a = normalize(stored)
        val b = normalize(other)
        return if (a != null && b != null) a == b else stored.trim().equals(other.trim(), ignoreCase = true)
    }
}

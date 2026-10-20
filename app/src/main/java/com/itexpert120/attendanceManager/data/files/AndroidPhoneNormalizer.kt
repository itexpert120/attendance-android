package com.itexpert120.attendanceManager.data.files

import android.content.Context
import com.itexpert120.attendanceManager.domain.PhoneNormalizer
import com.itexpert120.attendanceManager.domain.ValidationException
import io.michaelrocks.libphonenumber.android.NumberParseException
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil

/**
 * libphonenumber-backed [PhoneNormalizer] with Pakistan as the default region, so "0300 1234567" becomes
 * "+923001234567". Only valid numbers are accepted.
 *
 * libphonenumber-android reads its region metadata from assets the first time a number is parsed, which is
 * disk I/O. Construction is cheap and does no I/O (the util is created lazily from the application context);
 * call [warmUp] from a background dispatcher at app start so the first form save doesn't pay for it on the
 * main thread. Keep one instance per process: each [PhoneNumberUtil] instance loads its own metadata.
 */
class AndroidPhoneNormalizer(
    context: Context,
    private val defaultRegion: String = DEFAULT_REGION,
) : PhoneNormalizer {
    private val appContext = context.applicationContext
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.createInstance(appContext) }

    /** Loads the metadata for the default region now. Safe to call from any thread, any number of times. */
    fun warmUp() {
        util.getExampleNumber(defaultRegion)
    }

    override fun normalize(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        try {
            val parsed = util.parse(text, defaultRegion)
            if (util.isValidNumber(parsed)) {
                return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
        } catch (_: NumberParseException) {
            // Use the same friendly validation message for every parser failure.
        }
        throw ValidationException(INVALID_MESSAGE)
    }

    override fun display(value: String): String {
        if (value.isBlank()) return ""
        return try {
            util.format(util.parse(value, defaultRegion), PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
        } catch (_: NumberParseException) {
            value
        }
    }

    companion object {
        const val DEFAULT_REGION = "PK"
        const val INVALID_MESSAGE = "Enter a valid phone number, for example 0300 1234567 or +92 300 1234567."
    }
}

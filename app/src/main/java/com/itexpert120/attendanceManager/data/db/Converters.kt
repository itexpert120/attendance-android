package com.itexpert120.attendanceManager.data.db

import androidx.room3.ColumnTypeConverter
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Dates are stored as ISO-8601 text ("2026-08-03"), which sorts and range-compares correctly in SQL,
 * and timestamps as epoch milliseconds.
 */
class Converters {
    @ColumnTypeConverter
    fun localDateToText(value: LocalDate?): String? = value?.toString()

    @ColumnTypeConverter
    fun textToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @ColumnTypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilliseconds()

    @ColumnTypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)
}

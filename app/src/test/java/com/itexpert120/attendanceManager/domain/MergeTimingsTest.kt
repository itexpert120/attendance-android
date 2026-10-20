package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.AttendanceStatus.ABSENT
import com.itexpert120.attendanceManager.model.AttendanceStatus.LEAVE
import com.itexpert120.attendanceManager.model.AttendanceStatus.PRESENT
import com.itexpert120.attendanceManager.model.Session.FIRST
import com.itexpert120.attendanceManager.model.Session.SECOND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTimingsTest {
    @Test
    fun `present wins, then leave, then absent`() {
        val merged = mergeTimings(
            listOf(
                mark(1, 1, 3, FIRST, ABSENT),
                mark(1, 1, 3, SECOND, PRESENT),
                mark(1, 2, 3, FIRST, ABSENT),
                mark(1, 2, 3, SECOND, LEAVE),
                mark(1, 3, 3, FIRST, ABSENT),
                mark(1, 3, 3, SECOND, ABSENT),
            ),
        ).associate { it.enrollmentId to it.status }
        assertEquals(mapOf(1L to PRESENT, 2L to LEAVE, 3L to ABSENT), merged)
    }

    @Test
    fun `a lone second timing becomes the day's first-timing mark`() {
        val merged = mergeTimings(listOf(mark(1, 1, 4, SECOND, LEAVE)))
        assertEquals(listOf(mark(1, 1, 4, FIRST, LEAVE)), merged)
    }

    @Test
    fun `keeps one mark per register, student and day`() {
        val merged = mergeTimings(
            listOf(
                mark(1, 1, 3, FIRST, PRESENT),
                mark(1, 1, 3, SECOND, ABSENT),
                mark(1, 1, 4, SECOND, ABSENT),
                mark(2, 1, 3, FIRST, LEAVE),
            ),
        )
        assertEquals(3, merged.size)
        assertTrue(merged.all { it.session == FIRST })
        assertEquals(
            setOf(Triple(1L, 3, PRESENT), Triple(1L, 4, ABSENT), Triple(2L, 3, LEAVE)),
            merged.mapTo(HashSet()) { Triple(it.registerId, it.day, it.status) },
        )
    }

    @Test
    fun `first-timing-only data is returned unchanged`() {
        val marks = listOf(mark(1, 1, 3, FIRST, PRESENT), mark(1, 2, 3, FIRST, ABSENT))
        assertSame(marks, mergeTimings(marks))
    }
}

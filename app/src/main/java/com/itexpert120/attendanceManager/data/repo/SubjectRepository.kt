package com.itexpert120.attendanceManager.data.repo

import androidx.room3.withWriteTransaction
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.domain.naturalCompare
import com.itexpert120.attendanceManager.model.Subject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/** How Subject and Test names are compared for duplicates: trimmed and case-insensitive. */
internal fun normalizeCatalogName(value: String): String = value.trim().lowercase()

/** Active Subjects first, then natural order by name ("Maths 2" before "Maths 10"); archived Subjects come last. */
fun List<Subject>.sortedForDisplay(): List<Subject> =
    sortedWith(
        compareBy<Subject> { it.isArchived }
            .thenComparator { a, b -> naturalCompare(a.name, b.name) }
            .thenBy { it.id },
    )

/**
 * The school-wide Subject catalogue. Subjects are archived rather than deleted so historical Tests and
 * Daily Homework Reports keep pointing at them (ADR 0002).
 */
class SubjectRepository(
    private val db: AttendanceDatabase,
    private val clock: Clock = Clock.System,
) {
    /** Every Subject, active ones first. */
    fun observe(): Flow<List<Subject>> = db.subjects().observeAll().map { it.sortedForDisplay() }

    suspend fun get(id: Long): Subject? = db.subjects().getById(id)

    /**
     * Adds a Subject. A name that matches an archived Subject re-activates that Subject (adopting the newly
     * typed spelling) instead of creating a duplicate; a name matching an active one is rejected.
     */
    suspend fun saveSubject(name: String): Subject {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw ValidationException("Subject name is required.")
        val normalized = normalizeCatalogName(trimmed)
        return db.withWriteTransaction {
            val dao = db.subjects()
            val existing = dao.findByNormalizedName(normalized)
            val now = clock.now()
            when {
                existing == null -> {
                    val subject = Subject(
                        name = trimmed,
                        normalizedName = normalized,
                        createdAt = now,
                        updatedAt = now,
                    )
                    subject.copy(id = dao.insert(subject))
                }
                existing.isArchived -> {
                    val restored = existing.copy(name = trimmed, archivedAt = null, updatedAt = now)
                    dao.update(restored)
                    restored
                }
                else -> throw ValidationException("That Subject already exists.")
            }
        }
    }

    suspend fun renameSubject(subjectId: Long, name: String): Subject {
        val trimmed = name.trim()
        return db.withWriteTransaction {
            val dao = db.subjects()
            val subject = dao.getById(subjectId) ?: throw ValidationException("Subject not found.")
            if (trimmed.isEmpty()) throw ValidationException("Subject name is required.")
            val normalized = normalizeCatalogName(trimmed)
            val clash = dao.findByNormalizedName(normalized)
            if (clash != null && clash.id != subjectId) throw ValidationException("That Subject already exists.")
            val updated = subject.copy(name = trimmed, normalizedName = normalized, updatedAt = clock.now())
            dao.update(updated)
            updated
        }
    }

    /** Hides the Subject from pickers; a no-op for an unknown or already archived Subject. */
    suspend fun archiveSubject(subjectId: Long) {
        db.withWriteTransaction {
            val subject = db.subjects().getById(subjectId)
            if (subject != null && !subject.isArchived) {
                val now = clock.now()
                db.subjects().update(subject.copy(archivedAt = now, updatedAt = now))
            }
        }
    }

    /** A no-op for an unknown or already active Subject. */
    suspend fun restoreSubject(subjectId: Long) {
        db.withWriteTransaction {
            val subject = db.subjects().getById(subjectId)
            if (subject != null && subject.isArchived) {
                db.subjects().update(subject.copy(archivedAt = null, updatedAt = clock.now()))
            }
        }
    }
}

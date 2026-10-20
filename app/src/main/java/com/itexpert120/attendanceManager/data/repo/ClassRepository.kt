package com.itexpert120.attendanceManager.data.repo

import androidx.room3.withWriteTransaction
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.data.db.ClassUsage
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.domain.isActiveOn
import com.itexpert120.attendanceManager.domain.naturalCompare
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/** Class Groups (a class name plus a section, e.g. "7" + "A"). */
class ClassRepository(
    private val db: AttendanceDatabase,
    private val clock: Clock = Clock.System,
    /** Deletes homework photos of a deleted class; null in tests that don't care about files. */
    private val photoStore: PhotoStore? = null,
) {
    /** All classes in natural order of "className+section", so "7A" < "7B" < "10A". */
    fun observeClasses(): Flow<List<ClassGroup>> = db.classGroups().observeAll().map { it.sortedNaturally() }

    suspend fun getClass(id: Long): ClassGroup? = db.classGroups().getById(id)

    /** Throws "That class and section already exists." (compared case-insensitively). */
    suspend fun addClass(className: String, section: String): ClassGroup {
        val classLabel = className.trim()
        val sectionLabel = section.trim()
        if (classLabel.isEmpty() || sectionLabel.isEmpty()) throw ValidationException(CLASS_AND_SECTION_REQUIRED)
        return db.withWriteTransaction {
            if (db.classGroups().find(classLabel, sectionLabel) != null) throw ValidationException(ALREADY_EXISTS)
            val group = ClassGroup(className = classLabel, section = sectionLabel, createdAt = clock.now())
            group.copy(id = db.classGroups().insert(group))
        }
    }

    /**
     * Creates a new class and enrols into it every student of [sourceClassGroupId] who is on the roll on
     * [admittedOn] (same roll numbers, admitted on [admittedOn], never struck off). Students struck off
     * before that date are not copied. Returns the new class.
     */
    suspend fun duplicateClass(
        sourceClassGroupId: Long,
        className: String,
        section: String,
        admittedOn: LocalDate,
    ): ClassGroup {
        val classLabel = className.trim()
        val sectionLabel = section.trim()
        if (classLabel.isEmpty() || sectionLabel.isEmpty()) throw ValidationException(CLASS_AND_SECTION_REQUIRED)
        return db.withWriteTransaction {
            val groups = db.classGroups()
            if (groups.find(classLabel, sectionLabel) != null) throw ValidationException(ALREADY_EXISTS)
            groups.getById(sourceClassGroupId) ?: throw ValidationException("Select a class to duplicate.")

            val created = ClassGroup(className = classLabel, section = sectionLabel, createdAt = clock.now())
            val group = created.copy(id = groups.insert(created))
            val copies = db.enrollments().getForClass(sourceClassGroupId)
                .filter { it.isActiveOn(admittedOn) }
                .map { it.copiedInto(group.id, admittedOn) }
            if (copies.isNotEmpty()) db.enrollments().insertAll(copies)
            group
        }
    }

    /** Renames a class (class name and/or section); same rules and messages as [addClass]. */
    suspend fun renameClass(classGroupId: Long, className: String, section: String): ClassGroup {
        val classLabel = className.trim()
        val sectionLabel = section.trim()
        if (classLabel.isEmpty() || sectionLabel.isEmpty()) throw ValidationException(CLASS_AND_SECTION_REQUIRED)
        return db.withWriteTransaction {
            val groups = db.classGroups()
            val current = groups.getById(classGroupId) ?: throw ValidationException(NOT_FOUND)
            val clash = groups.find(classLabel, sectionLabel)
            if (clash != null && clash.id != classGroupId) throw ValidationException(ALREADY_EXISTS)
            val renamed = current.copy(className = classLabel, section = sectionLabel)
            groups.update(renamed)
            renamed
        }
    }

    suspend fun classUsage(classGroupId: Long): ClassUsage = db.classGroups().usage(classGroupId)

    /**
     * Deletes a class with its enrollments, registers (marks, fees, remarks, holidays), Tests and homework.
     * Students themselves are kept, so they can still be enrolled in another class. The school must keep
     * at least one class. Homework photos are removed after the delete commits.
     */
    suspend fun deleteClass(classGroupId: Long) {
        val photos = db.withWriteTransaction {
            val groups = db.classGroups()
            val group = groups.getById(classGroupId) ?: throw ValidationException(NOT_FOUND)
            if (groups.getAll().size <= 1) throw ValidationException("The school needs at least one class.")
            val files = groups.homeworkPhotoFiles(classGroupId)
            groups.delete(group)
            files
        }
        photos.forEach { runCatching { photoStore?.delete(it) } }
    }

    private companion object {
        const val NOT_FOUND = "That class no longer exists."
        const val ALREADY_EXISTS = "That class and section already exists."
        const val CLASS_AND_SECTION_REQUIRED = "Class and section are required."
    }
}

internal fun List<ClassGroup>.sortedNaturally(): List<ClassGroup> =
    sortedWith { a, b -> naturalCompare(a.className + a.section, b.className + b.section) }

/** The enrollment a duplicated class gets: same student and roll number, starting [admittedOn], not struck off. */
internal fun Enrollment.copiedInto(classGroupId: Long, admittedOn: LocalDate) =
    Enrollment(
        studentId = studentId,
        classGroupId = classGroupId,
        rollNumber = rollNumber,
        admittedOn = admittedOn,
        struckOffOn = null,
    )

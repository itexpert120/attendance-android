package com.itexpert120.attendanceManager.data.repo

import androidx.room3.withWriteTransaction
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.SubjectTeacher
import kotlinx.coroutines.flow.Flow

/** Longest teacher name accepted, in characters. */
const val TEACHER_NAME_MAX_LENGTH = 60

/**
 * Validates a typed teacher name: trimmed, inner whitespace collapsed; blank means "no teacher". Throws
 * [ValidationException] when the name is too long.
 */
fun normalizeTeacherName(name: String): String {
    val trimmed = name.trim().replace(Regex("\\s+"), " ")
    if (trimmed.length > TEACHER_NAME_MAX_LENGTH) {
        throw ValidationException("Teacher name must be at most $TEACHER_NAME_MAX_LENGTH characters.")
    }
    return trimmed
}

/** Who teaches each Subject in each Class Group (used by the Teacher Performance report). */
class SubjectTeacherRepository(private val db: AttendanceDatabase) {
    fun observe(): Flow<List<SubjectTeacher>> = db.subjectTeachers().observeAll()

    suspend fun getAll(): List<SubjectTeacher> = db.subjectTeachers().getAll()

    /** Sets the teacher of [subjectId] in [classGroupId]; a blank [teacherName] removes the assignment. */
    suspend fun setTeacher(classGroupId: Long, subjectId: Long, teacherName: String) {
        val name = normalizeTeacherName(teacherName)
        db.withWriteTransaction {
            if (db.classGroups().getById(classGroupId) == null) throw ValidationException("Select a Class Group.")
            if (db.subjects().getById(subjectId) == null) throw ValidationException("Subject not found.")
            if (name.isEmpty()) {
                db.subjectTeachers().delete(classGroupId, subjectId)
            } else {
                db.subjectTeachers().upsert(SubjectTeacher(classGroupId, subjectId, name))
            }
        }
    }
}

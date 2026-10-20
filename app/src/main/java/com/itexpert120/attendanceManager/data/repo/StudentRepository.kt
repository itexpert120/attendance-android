package com.itexpert120.attendanceManager.data.repo

import androidx.room3.withWriteTransaction
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.BFormNumber
import com.itexpert120.attendanceManager.domain.PhoneNormalizer
import com.itexpert120.attendanceManager.domain.PhotoStore
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.domain.sortedByRollNumber
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * The form behind "add student" / "edit student". Leave [studentId] and [enrollmentId] null to create a
 * new student with a first enrollment; pass both (taken from the [EnrollmentRow] being edited) to update.
 * Passing only [studentId] enrols an existing student into [classGroupId] as a new enrollment.
 */
data class StudentInput(
    val classGroupId: Long,
    val admissionNumber: String,
    val rollNumber: String,
    val name: String,
    val phone: String,
    val admittedOn: LocalDate,
    val studentId: Long? = null,
    val enrollmentId: Long? = null,
    val dateOfBirth: LocalDate? = null,
    val struckOffOn: LocalDate? = null,
    val photo: PhotoChange = PhotoChange.Keep,
)

data class SavedStudent(val studentId: Long, val enrollmentId: Long)

/** Students and their enrollments in Class Groups. */
class StudentRepository(
    private val db: AttendanceDatabase,
    private val photoStore: PhotoStore,
    private val phoneNormalizer: PhoneNormalizer,
    private val clock: Clock = Clock.System,
) {
    /** The enrollments of one class (struck-off ones included, see Enrollment.struckOffOn), by roll number. */
    fun observeEnrollmentRows(classGroupId: Long): Flow<List<EnrollmentRow>> =
        db.enrollments().observeRowsForClass(classGroupId).map { it.sortedByRollNumber() }

    /** Every student in the school, in no particular order. */
    fun observeStudents(): Flow<List<Student>> = db.students().observeAll()

    /**
     * Creates or updates a student and one enrollment atomically. Throws [ValidationException] with the
     * PWA's messages: an invalid phone, "B-Form number must be unique.", "Roll number is already active
     * in this class." (another enrollment of the class with that roll number that was not struck off before
     * [StudentInput.admittedOn]) and "Struck-off date cannot be before admission date.".
     *
     * [StudentInput.admissionNumber] is the B-Form number: a new student, or a changed number, must have 13
     * digits (with or without dashes) and is stored as `12345-1234567-1`; an existing student's unchanged
     * number is kept as it is, so records from before the rule can still be edited. Duplicates are found by
     * comparing the normalised numbers.
     *
     * A replaced or removed photo file is deleted only after the transaction commits; a photo saved for a
     * save that fails is deleted again.
     */
    suspend fun saveStudent(input: StudentInput): SavedStudent {
        val typedBForm = input.admissionNumber.trim()
        val rollNumber = input.rollNumber.trim()
        val name = input.name.trim()
        if (name.isEmpty()) throw ValidationException("Student name is required.")
        if (typedBForm.isEmpty()) throw ValidationException(BFormNumber.REQUIRED_MESSAGE)
        if (rollNumber.isEmpty()) throw ValidationException("Roll number is required.")
        require(input.studentId != null || input.enrollmentId == null) {
            "An enrollment can only be edited together with its student."
        }
        val phone = phoneNormalizer.normalize(input.phone)

        val savedPhoto = photoStore.saveReplacement(input.photo)
        val committed: Triple<SavedStudent, String?, String?>
        try {
            val now = clock.now()
            committed = db.withWriteTransaction {
                val students = db.students()
                val enrollments = db.enrollments()

                val existing = input.studentId?.let { students.getById(it) ?: throw ValidationException("Student not found.") }
                val existingEnrollment = input.enrollmentId?.let {
                    enrollments.getById(it)?.takeIf { enrollment -> enrollment.studentId == input.studentId }
                        ?: throw ValidationException("Enrollment not found.")
                }

                val keepsLegacyNumber = existing != null && existing.admissionNumber.trim().equals(typedBForm, ignoreCase = true)
                val admissionNumber = if (keepsLegacyNumber) {
                    existing.admissionNumber
                } else {
                    BFormNumber.normalize(typedBForm) ?: throw ValidationException(BFormNumber.FORMAT_MESSAGE)
                }
                val sameAdmission = students.findByAdmissionNumber(admissionNumber)
                    ?: if (keepsLegacyNumber) null else students.getAll().firstOrNull { BFormNumber.sameNumber(it.admissionNumber, admissionNumber) }
                if (sameAdmission != null && sameAdmission.id != existing?.id) {
                    throw ValidationException(BFormNumber.DUPLICATE_MESSAGE)
                }
                val rollConflicts = enrollments.countRollNumberConflicts(
                    classGroupId = input.classGroupId,
                    rollNumber = rollNumber,
                    excludingEnrollmentId = existingEnrollment?.id ?: 0L,
                    admittedOn = input.admittedOn,
                )
                if (rollConflicts > 0) throw ValidationException("Roll number is already active in this class.")
                if (input.struckOffOn != null && input.struckOffOn < input.admittedOn) {
                    throw ValidationException("Struck-off date cannot be before admission date.")
                }

                val photoFile = input.photo.resolve(existing?.photoFile, savedPhoto)
                val student = Student(
                    id = existing?.id ?: 0L,
                    admissionNumber = admissionNumber,
                    name = name,
                    phone = phone,
                    dateOfBirth = input.dateOfBirth,
                    photoFile = photoFile,
                    createdAt = existing?.createdAt ?: now,
                )
                val studentId = if (existing != null) {
                    students.update(student)
                    existing.id
                } else {
                    students.insert(student)
                }
                val enrollment = Enrollment(
                    id = existingEnrollment?.id ?: 0L,
                    studentId = studentId,
                    classGroupId = input.classGroupId,
                    rollNumber = rollNumber,
                    admittedOn = input.admittedOn,
                    struckOffOn = input.struckOffOn,
                )
                val enrollmentId = if (existingEnrollment != null) {
                    enrollments.update(enrollment)
                    existingEnrollment.id
                } else {
                    enrollments.insert(enrollment)
                }
                Triple(SavedStudent(studentId, enrollmentId), existing?.photoFile, photoFile)
            }
        } catch (t: Throwable) {
            photoStore.discardQuietly(savedPhoto)
            throw t
        }
        photoStore.deleteSuperseded(committed.second, committed.third)
        return committed.first
    }

    /**
     * Rewrites stored phone numbers into E.164 (the PWA did this on every refresh, so numbers typed before
     * the normaliser existed get fixed). Numbers that can't be parsed are left alone. Returns how many changed.
     */
    suspend fun migratePhoneNumbers(): Int = db.withWriteTransaction {
        var changed = 0
        for (student in db.students().getAll()) {
            val normalized = try {
                phoneNormalizer.normalize(student.phone)
            } catch (_: ValidationException) {
                continue
            }
            if (normalized != student.phone) {
                db.students().update(student.copy(phone = normalized))
                changed++
            }
        }
        changed
    }
}

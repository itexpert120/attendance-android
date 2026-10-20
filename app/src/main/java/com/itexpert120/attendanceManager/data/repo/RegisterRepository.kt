package com.itexpert120.attendanceManager.data.repo

import kotlinx.datetime.number
import androidx.room3.withWriteTransaction
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.domain.dateOf
import com.itexpert120.attendanceManager.domain.isActiveOn
import com.itexpert120.attendanceManager.domain.isAttendanceDateEditable
import com.itexpert120.attendanceManager.domain.isHolidayDay
import com.itexpert120.attendanceManager.domain.isInMonth
import com.itexpert120.attendanceManager.domain.isRegisterEarlierInAcademicYear
import com.itexpert120.attendanceManager.domain.isVisibleInAttendance
import com.itexpert120.attendanceManager.domain.sortedByRollNumber
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.Holiday
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.StudentRemark
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** One installment's amounts for a student, as saved from the fee editor. */
data class InstallmentFees(val installment: InstallmentNumber, val amounts: FeeAmounts)

/**
 * Monthly registers: attendance marks, holidays, fees, installment metadata and remarks.
 *
 * Attendance edits follow the PWA's rules and are silently ignored (no exception) when the date is
 * outside the edit window (today and the seven days before it; never the future), is a weekend or declared
 * holiday, or the student was not on the roll that day. [clock] and [timeZone] decide what "today" is.
 */
class RegisterRepository(
    private val db: AttendanceDatabase,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    private fun today(): LocalDate = clock.now().toLocalDateTime(timeZone).date

    /** All registers, newest month first. */
    fun observeRegisters(): Flow<List<Register>> = db.registers().observeAll()

    /**
     * All registers, newest first, each with its class (for the home list). A register whose class is gone is
     * kept with a null class, as the PWA lists it under a plain "Class" label.
     */
    fun observeRegisterListItems(): Flow<List<RegisterListItem>> =
        combine(db.registers().observeAll(), db.classGroups().observeAll()) { registers, groups ->
            val byId = groups.associateBy { it.id }
            registers.map { register -> RegisterListItem(register, byId[register.classGroupId]) }
        }

    suspend fun getRegister(registerId: Long): Register? = db.registers().getById(registerId)

    /** Register id -> number of students with a mark on [date], for registers of [date]'s month. */
    fun observeMarkedOn(date: LocalDate): Flow<Map<Long, Int>> =
        db.attendance().observeMarkedCounts(date.year, date.month.number, date.day)
            .map { counts -> counts.associate { it.registerId to it.marked } }

    /** Returns the class's register for the month, creating it if it does not exist yet. */
    suspend fun createRegister(classGroupId: Long, year: Int, month: Int): Register {
        if (month !in 1..12) throw ValidationException("Select a valid month.")
        return db.withWriteTransaction {
            db.registers().find(classGroupId, year, month)
                ?: run {
                    db.classGroups().getById(classGroupId) ?: throw ValidationException("Select a class.")
                    val created = Register(classGroupId = classGroupId, year = year, month = month, createdAt = clock.now())
                    created.copy(id = db.registers().insert(created))
                }
        }
    }

    /**
     * Live snapshot of one register for every register screen; emits null when the register does not
     * exist (or is deleted), so the UI can show a "not found" state.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRegister(registerId: Long): Flow<RegisterData?> =
        db.registers().observeById(registerId).distinctUntilChanged().flatMapLatest { register ->
            if (register == null) flowOf(null) else observeData(register)
        }

    private fun observeData(register: Register): Flow<RegisterData?> {
        val id = register.id
        val group: Flow<ClassGroup?> = db.classGroups().observeAll()
            .map { groups -> groups.firstOrNull { it.id == register.classGroupId } }
        val core = combine(
            group,
            db.enrollments().observeRowsForClass(register.classGroupId),
            db.attendance().observeForRegister(id),
            db.settings().observe(),
            observeBroughtForward(register),
        ) { group, rows, marks, settings, broughtForward ->
            Core(group, rows, marks, settings, broughtForward)
        }
        val extras = combine(
            db.holidays().observeForRegister(id),
            db.fees().observeEntries(id),
            db.fees().observeMeta(id),
            db.fees().observeRemarks(id),
        ) { holidays, entries, meta, remarks -> Extras(holidays, entries, meta, remarks) }

        return combine(core, extras) { core, extras ->
            // A register whose class was deleted still opens (it is listed too), under a plain "Class" label.
            val group = core.group ?: ClassGroup(
                id = register.classGroupId,
                className = MISSING_CLASS_LABEL,
                section = "",
                createdAt = register.createdAt,
            )
            val monthRows = core.rows.filter { it.enrollment.isInMonth(register) }.sortedByRollNumber()
            RegisterData(
                register = register,
                classGroup = group,
                settings = core.settings,
                monthRows = monthRows,
                attendanceRows = monthRows.filter { it.enrollment.isVisibleInAttendance(register) },
                marks = core.marks,
                holidayTitles = extras.holidays.sortedBy { it.day }.associate { it.day to it.title },
                feeEntries = extras.entries,
                installmentMeta = extras.meta.associateBy { it.installment },
                remarks = extras.remarks.filter { it.text.isNotBlank() }.associate { it.enrollmentId to it.text },
                broughtForwardByEnrollment = core.broughtForward,
            )
        }
    }

    /** Present days per enrollment in earlier registers of the same class and academic year. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeBroughtForward(register: Register): Flow<Map<Long, Int>> =
        combine(db.registers().observeAll(), db.settings().observe()) { registers, settings ->
            if (settings == null) {
                emptyList()
            } else {
                registers
                    .filter { isRegisterEarlierInAcademicYear(it, register, settings.academicYearStartMonth) }
                    .map { it.id }
                    .sorted()
            }
        }.distinctUntilChanged().flatMapLatest { earlierIds ->
            if (earlierIds.isEmpty()) {
                flowOf(emptyMap())
            } else {
                db.attendance().observePresentSessionCounts(earlierIds)
                    .map { counts -> counts.associate { it.enrollmentId to it.sessions } }
            }
        }

    private class Core(
        val group: ClassGroup?,
        val rows: List<EnrollmentRow>,
        val marks: List<AttendanceMark>,
        val settings: SchoolSettings?,
        val broughtForward: Map<Long, Int>,
    )

    private class Extras(
        val holidays: List<Holiday>,
        val entries: List<FeeEntry>,
        val meta: List<InstallmentMeta>,
        val remarks: List<StudentRemark>,
    )

    // region Attendance

    /** Sets (or, with a null [status], clears) one student's mark for [day]; attendance is taken once a day. */
    suspend fun setMark(registerId: Long, enrollmentId: Long, day: Int, status: AttendanceStatus?) {
        val session = Session.FIRST
        db.withWriteTransaction {
            val register = db.registers().getById(registerId) ?: return@withWriteTransaction
            if (!isAttendanceDateEditable(register, day, today())) return@withWriteTransaction
            if (isHolidayDay(register, day, holidayDays(registerId))) return@withWriteTransaction
            val enrollment = db.enrollments().getById(enrollmentId) ?: return@withWriteTransaction
            if (enrollment.classGroupId != register.classGroupId || !enrollment.isActiveOn(register.dateOf(day))) {
                return@withWriteTransaction
            }
            if (status != null) {
                db.attendance().upsert(AttendanceMark(registerId, enrollmentId, day, session, status))
            } else {
                db.attendance().delete(registerId, enrollmentId, day, session)
            }
        }
    }

    /**
     * Marks every student shown in the attendance grid who was on the roll that day, or clears their
     * marks when [status] is null. Students hidden because they were struck off this month are untouched.
     */
    suspend fun bulkSetMarks(registerId: Long, day: Int, status: AttendanceStatus?) {
        val session = Session.FIRST
        db.withWriteTransaction {
            val register = db.registers().getById(registerId) ?: return@withWriteTransaction
            if (!isAttendanceDateEditable(register, day, today())) return@withWriteTransaction
            if (isHolidayDay(register, day, holidayDays(registerId))) return@withWriteTransaction
            val date = register.dateOf(day)
            val enrollmentIds = db.enrollments().getForClass(register.classGroupId)
                .filter { it.isVisibleInAttendance(register) && it.isActiveOn(date) }
                .map { it.id }
            if (enrollmentIds.isEmpty()) return@withWriteTransaction
            if (status != null) {
                db.attendance().upsertAll(enrollmentIds.map { AttendanceMark(registerId, it, day, session, status) })
            } else {
                db.attendance().deleteSession(registerId, day, session, enrollmentIds)
            }
        }
    }

    /**
     * Declares [day] a school holiday (deleting that day's marks) or, if it already is one, removes the
     * declaration. A blank [reason] becomes "School holiday". Marks cleared by a holiday are not restored.
     */
    suspend fun toggleHoliday(registerId: Long, day: Int, reason: String = DEFAULT_HOLIDAY_TITLE) {
        db.withWriteTransaction {
            val register = db.registers().getById(registerId) ?: return@withWriteTransaction
            if (!isAttendanceDateEditable(register, day, today())) return@withWriteTransaction
            val existing = db.holidays().getForRegister(registerId).any { it.day == day }
            if (existing) {
                db.holidays().delete(registerId, day)
            } else {
                db.attendance().deleteDay(registerId, day)
                db.holidays().upsert(Holiday(registerId, day, reason.trim().ifEmpty { DEFAULT_HOLIDAY_TITLE }))
            }
        }
    }

    private suspend fun holidayDays(registerId: Long): Set<Int> =
        db.holidays().getForRegister(registerId).mapTo(HashSet()) { it.day }

    // endregion

    // region Fees and remarks

    /** Saves the given installments' amounts for one student; installments not listed are left alone. */
    suspend fun saveFeeEntries(registerId: Long, enrollmentId: Long, entries: List<InstallmentFees>) {
        if (entries.isEmpty()) return
        db.withWriteTransaction {
            db.registers().getById(registerId) ?: return@withWriteTransaction
            db.enrollments().getById(enrollmentId) ?: return@withWriteTransaction
            db.fees().upsertEntries(entries.map { FeeEntry(registerId, enrollmentId, it.installment, it.amounts) })
        }
    }

    /** [rate] is in minor units. */
    suspend fun saveInstallmentMeta(registerId: Long, installment: InstallmentNumber, rate: Long, receiverName: String) {
        db.withWriteTransaction {
            db.registers().getById(registerId) ?: return@withWriteTransaction
            db.fees().upsertMeta(InstallmentMeta(registerId, installment, rate, receiverName.trim()))
        }
    }

    /** Saves the trimmed remark; blank text deletes it. */
    suspend fun saveRemark(registerId: Long, enrollmentId: Long, text: String) {
        val trimmed = text.trim()
        db.withWriteTransaction {
            if (trimmed.isEmpty()) {
                db.fees().deleteRemark(registerId, enrollmentId)
            } else {
                db.registers().getById(registerId) ?: return@withWriteTransaction
                db.enrollments().getById(enrollmentId) ?: return@withWriteTransaction
                db.fees().upsertRemark(StudentRemark(registerId, enrollmentId, trimmed))
            }
        }
    }

    // endregion

    companion object {
        const val DEFAULT_HOLIDAY_TITLE = "School holiday"

        /** The PWA's label for a register whose class no longer exists. */
        const val MISSING_CLASS_LABEL = "Class"
    }
}

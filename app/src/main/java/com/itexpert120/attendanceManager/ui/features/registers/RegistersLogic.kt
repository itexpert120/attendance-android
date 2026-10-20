package com.itexpert120.attendanceManager.ui.features.registers

import com.itexpert120.attendanceManager.data.repo.RegisterListItem
import com.itexpert120.attendanceManager.domain.academicYearStartYear
import com.itexpert120.attendanceManager.domain.isVisibleInAttendance
import com.itexpert120.attendanceManager.domain.monthLabel
import com.itexpert120.attendanceManager.domain.naturalCompare
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.SchoolSettings
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/*
 * The Registers tab's rules as plain functions of plain data, so they can be unit tested on the JVM. The
 * ViewModel only wires repository flows into [buildRegistersContent]; the screens only draw its result.
 */

/**
 * One row of the registers list: a register, its class (null when the class was deleted) and how many students
 * appear in its attendance table.
 */
data class RegisterSummary(val register: Register, val classGroup: ClassGroup?, val studentCount: Int) {
    val id: Long get() = register.id

    /** "Class 7 · A", or the PWA's plain "Class" when the class no longer exists. */
    val label: String get() = classGroup?.let(::classLabel) ?: "Class"
}

/** All registers of one calendar month, for one section of the list. */
data class RegisterMonthGroup(
    val year: Int,
    val month: Int,
    /** True for the month that contains today. */
    val isCurrent: Boolean,
    val registers: List<RegisterSummary>,
) {
    val key: String get() = "$year-$month"
    val title: String get() = monthLabel(month, year)
}

/** What "today" needs from one class: its register for the current month, or null when it must be created. */
data class TodayEntry(
    val classGroup: ClassGroup,
    val register: Register?,
    /** Students with at least one mark today, and students on the roll today. */
    val marked: Int = 0,
    val onRoll: Int = 0,
)

data class RegistersStats(val students: Int, val classes: Int, val tests: Int, val homework: Int)

/** Everything the Registers tab draws. */
data class RegistersContent(
    val schoolName: String?,
    val logoFile: String?,
    val today: LocalDate,
    val classes: List<ClassGroup>,
    val todayEntries: List<TodayEntry>,
    val monthGroups: List<RegisterMonthGroup>,
    val stats: RegistersStats,
) {
    val hasRegisters: Boolean get() = monthGroups.isNotEmpty()
}

/** "Class 7 · A" */
fun classLabel(group: ClassGroup): String = "Class ${group.className} · ${group.section}"

/** "7 · Section A", for pickers where the word "Class" is already in the field label. */
fun classOptionLabel(group: ClassGroup): String = "${group.className} · Section ${group.section}"

fun studentCountLabel(count: Int): String = if (count == 1) "1 student" else "$count students"

/** One entry per class, in the order given, with its register for [today]'s month when there is one. */
fun buildTodayEntries(classes: List<ClassGroup>, registers: List<Register>, today: LocalDate): List<TodayEntry> {
    val thisMonth = registers.filter { it.year == today.year && it.month == today.month.number }
        .associateBy { it.classGroupId }
    return classes.map { group -> TodayEntry(group, thisMonth[group.id]) }
}

/**
 * The students shown in each register's attendance table (the PWA's `attendanceRowsForRegister`), keyed by
 * register id. [rows] are the enrollments of every class.
 */
fun registerStudentCounts(registers: List<Register>, rows: List<EnrollmentRow>): Map<Long, Int> {
    val byClass = rows.groupBy { it.enrollment.classGroupId }
    return registers.associate { register ->
        register.id to byClass[register.classGroupId].orEmpty().count { it.enrollment.isVisibleInAttendance(register) }
    }
}

/** Registers grouped by month, newest month first, classes in natural order inside a month. */
fun groupRegistersByMonth(
    items: List<RegisterListItem>,
    studentCounts: Map<Long, Int>,
    today: LocalDate,
): List<RegisterMonthGroup> =
    items
        .groupBy { it.register.year to it.register.month }
        .entries
        .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int>, List<RegisterListItem>>> { it.key.first }.thenByDescending { it.key.second })
        .map { (key, group) ->
            RegisterMonthGroup(
                year = key.first,
                month = key.second,
                isCurrent = key.first == today.year && key.second == today.month.number,
                registers = group
                    .sortedWith { a, b ->
                        naturalCompare(a.classGroup.sortKey(), b.classGroup.sortKey())
                    }
                    .map { RegisterSummary(it.register, it.classGroup, studentCounts[it.register.id] ?: 0) },
            )
        }

private fun ClassGroup?.sortKey(): String = this?.let { it.className + it.section } ?: ""

/** Tests dated inside the academic year that contains [today] (the PWA's dashboard stat). */
fun academicYearTestCount(testDates: List<LocalDate>, academicYearStartMonth: Int, today: LocalDate): Int {
    val current = academicYearStartYear(today, academicYearStartMonth)
    return testDates.count { academicYearStartYear(it, academicYearStartMonth) == current }
}

/** Years offered when creating a register: four either side of the current one. */
fun newRegisterYears(today: LocalDate): List<Int> = (today.year - 4..today.year + 4).toList()

/** The class a new-register form starts on: [preferred] when it still exists, else the first class. */
fun defaultClassId(classes: List<ClassGroup>, preferred: Long?): Long? =
    classes.firstOrNull { it.id == preferred }?.id ?: classes.firstOrNull()?.id

/** Raw repository output for the tab; kept as a value so [buildRegistersContent] stays a pure function. */
class RegistersInputs(
    val settings: SchoolSettings?,
    val classes: List<ClassGroup>,
    val registerItems: List<RegisterListItem>,
    val enrollmentRows: List<EnrollmentRow>,
    val studentCount: Int,
    val testDates: List<LocalDate>,
    val homeworkCount: Int,
)

fun buildRegistersContent(inputs: RegistersInputs, today: LocalDate): RegistersContent {
    val counts = registerStudentCounts(inputs.registerItems.map { it.register }, inputs.enrollmentRows)
    return RegistersContent(
        schoolName = inputs.settings?.schoolName,
        logoFile = inputs.settings?.logoFile,
        today = today,
        classes = inputs.classes,
        todayEntries = buildTodayEntries(inputs.classes, inputs.registerItems.map { it.register }, today),
        monthGroups = groupRegistersByMonth(inputs.registerItems, counts, today),
        stats = RegistersStats(
            students = inputs.studentCount,
            classes = inputs.classes.size,
            tests = inputs.settings?.let { academicYearTestCount(inputs.testDates, it.academicYearStartMonth, today) } ?: 0,
            homework = inputs.homeworkCount,
        ),
    )
}

package com.itexpert120.attendanceManager.domain

import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.SubjectTeacher
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry

/*
 * The three assessment reports over a period: a student's report card, teacher performance and a class
 * assessment. Everything here is pure; the repository supplies an [AssessmentData] snapshot.
 *
 * Percentages: a single result is its marks as a percentage of its Test's Total Marks. An average is the mean of
 * those per-result percentages (so tests out of 10 and out of 100 weigh the same), while a student's overall
 * percentage is marks obtained over marks possible, the way a report card adds up.
 */

/** A result at or above this percentage of the Test's Total Marks passes. */
const val PASS_PERCENT = 40.0

/** Everything the assessment reports read: the Tests in [range] with their roster entries, plus lookups. */
data class AssessmentData(
    val range: DateRange,
    /** Tests dated within [range]. */
    val tests: List<TestRecord>,
    /** Roster entries of [tests] only. */
    val entries: List<TestRosterEntry>,
    /** Every enrollment with its student (struck-off ones too: old results still belong to them). */
    val enrollments: List<EnrollmentRow>,
    val subjects: List<Subject>,
    val classes: List<ClassGroup>,
    val teachers: List<SubjectTeacher>,
)

/** Counts and score statistics over a set of Tests' results; percentages are 0–100, null without any marks. */
data class ResultStats(
    val tests: Int,
    val results: Int,
    val absent: Int,
    val notEntered: Int,
    val averagePercent: Double?,
    /** Share of numeric results at or above [PASS_PERCENT], as a percentage. */
    val passRate: Double?,
    val highestPercent: Double?,
    val lowestPercent: Double?,
) {
    /** Results recorded as marks or absent. */
    val entered: Int get() = results + absent
}

fun resultStats(tests: List<TestRecord>, entries: List<TestRosterEntry>): ResultStats {
    val totals = tests.associate { it.id to it.totalMarks }
    val relevant = entries.filter { it.testId in totals }
    val percents = relevant
        .filter { it.result == TestResultStatus.MARKS }
        .map { marksPercentage(it.marks ?: 0, totals.getValue(it.testId)) }
    return ResultStats(
        tests = tests.size,
        results = percents.size,
        absent = relevant.count { it.result == TestResultStatus.ABSENT },
        notEntered = relevant.count { it.result == TestResultStatus.NOT_ENTERED },
        averagePercent = percents.takeIf { it.isNotEmpty() }?.average(),
        passRate = percents.takeIf { it.isNotEmpty() }?.let { list -> list.count(::isPass) * 100.0 / list.size },
        highestPercent = percents.maxOrNull(),
        lowestPercent = percents.minOrNull(),
    )
}

fun isPass(percent: Double): Boolean = percent >= PASS_PERCENT - 1e-9

/** Classes in display order: natural order of class name, then section. */
fun List<ClassGroup>.sortedClasses(): List<ClassGroup> =
    sortedWith { a, b -> naturalCompare(a.className, b.className).takeIf { it != 0 } ?: naturalCompare(a.section, b.section) }

private fun subjectOrder(subjects: List<Subject>): Comparator<Long> {
    val sorted = subjects.sortedWith(
        compareBy<Subject> { it.isArchived }.thenComparator { a, b -> naturalCompare(a.name, b.name) }.thenBy { it.id },
    )
    val index = sorted.withIndex().associate { (i, s) -> s.id to i }
    return compareBy { index[it] ?: Int.MAX_VALUE }
}

// region Student standings

/** One student's totals in one class over the period, with their rank among students who have marks. */
data class StudentStanding(
    val student: Student,
    val rollNumber: String,
    val tests: Int,
    val results: Int,
    val absent: Int,
    val notEntered: Int,
    /** Hundredths. */
    val obtained: Int,
    /** Hundredths: Total Marks of the Tests the student has marks in. */
    val possible: Int,
    /** Competition rank (1, 2, 2, 4); null without any marks. */
    val rank: Int?,
) {
    val percent: Double? get() = if (results == 0 || possible <= 0) null else obtained * 100.0 / possible
}

/**
 * Every student on a roster of the class's Tests in the period, best overall percentage first; students with
 * no marks follow, by name. A student enrolled twice in the class is counted once.
 */
fun classStandings(data: AssessmentData, classGroupId: Long): List<StudentStanding> {
    val tests = data.tests.filter { it.classGroupId == classGroupId }.associateBy { it.id }
    val rows = data.enrollments.filter { it.enrollment.classGroupId == classGroupId }.associateBy { it.enrollment.id }
    val byStudent = data.entries
        .filter { it.testId in tests && it.enrollmentId in rows }
        .groupBy { rows.getValue(it.enrollmentId).student.id }
    val unranked = byStudent.map { (_, entries) ->
        val studentRows = entries.map { rows.getValue(it.enrollmentId) }.distinctBy { it.enrollment.id }
        val latest = studentRows.maxBy { it.enrollment.admittedOn }
        // One entry per Test, preferring a recorded result if the student sits it under two enrollments.
        val perTest = entries.groupBy { it.testId }.values.map { list -> list.minBy { it.result.ordinalPriority() } }
        val marks = perTest.filter { it.result == TestResultStatus.MARKS }
        StudentStanding(
            student = latest.student,
            rollNumber = latest.enrollment.rollNumber,
            tests = perTest.size,
            results = marks.size,
            absent = perTest.count { it.result == TestResultStatus.ABSENT },
            notEntered = perTest.count { it.result == TestResultStatus.NOT_ENTERED },
            obtained = marks.sumOf { it.marks ?: 0 },
            possible = marks.sumOf { tests.getValue(it.testId).totalMarks },
            rank = null,
        )
    }
    val ranked = unranked.filter { it.percent != null }
        .sortedWith(compareByDescending<StudentStanding> { it.percent }.thenBy { it.student.name.lowercase() })
    val withRanks = ranked.mapIndexed { index, standing ->
        val first = ranked.indexOfFirst { samePercent(it.percent, standing.percent) }
        standing.copy(rank = if (first >= 0) first + 1 else index + 1)
    }
    val rest = unranked.filter { it.percent == null }.sortedBy { it.student.name.lowercase() }
    return withRanks + rest
}

private fun samePercent(a: Double?, b: Double?): Boolean = a != null && b != null && kotlin.math.abs(a - b) < 1e-9

private fun TestResultStatus.ordinalPriority(): Int = when (this) {
    TestResultStatus.MARKS -> 0
    TestResultStatus.ABSENT -> 1
    TestResultStatus.NOT_ENTERED -> 2
}

// endregion

// region Student report card

data class ReportCardTest(val test: TestRecord, val entry: TestRosterEntry) {
    val percent: Double?
        get() = if (entry.result == TestResultStatus.MARKS) marksPercentage(entry.marks ?: 0, test.totalMarks) else null
}

data class ReportCardSubject(
    val subjectId: Long,
    val subject: Subject?,
    /** Date order, oldest first. */
    val tests: List<ReportCardTest>,
) {
    private val marked get() = tests.filter { it.entry.result == TestResultStatus.MARKS }
    val results: Int get() = marked.size
    val absent: Int get() = tests.count { it.entry.result == TestResultStatus.ABSENT }
    val notEntered: Int get() = tests.count { it.entry.result == TestResultStatus.NOT_ENTERED }
    val obtained: Int get() = marked.sumOf { it.entry.marks ?: 0 }
    val possible: Int get() = marked.sumOf { it.test.totalMarks }
    val percent: Double? get() = if (results == 0 || possible <= 0) null else obtained * 100.0 / possible
}

data class StudentReportCard(
    val student: Student?,
    val classGroupId: Long,
    val subjects: List<ReportCardSubject>,
    /** Rank in the class over the period; null without any marks. */
    val rank: Int?,
    /** How many students of the class have marks in the period (the "of" in "3rd of 28"). */
    val rankedCount: Int,
) {
    val tests: Int get() = subjects.sumOf { it.tests.size }
    val results: Int get() = subjects.sumOf { it.results }
    val absent: Int get() = subjects.sumOf { it.absent }
    val notEntered: Int get() = subjects.sumOf { it.notEntered }
    val obtained: Int get() = subjects.sumOf { it.obtained }
    val possible: Int get() = subjects.sumOf { it.possible }
    val percent: Double? get() = if (results == 0 || possible <= 0) null else obtained * 100.0 / possible
}

/** One student's results in one class across every Subject in the period, Subjects in display order. */
fun studentReportCard(data: AssessmentData, classGroupId: Long, studentId: Long): StudentReportCard {
    val enrollmentIds = data.enrollments
        .filter { it.enrollment.classGroupId == classGroupId && it.enrollment.studentId == studentId }
        .mapTo(HashSet()) { it.enrollment.id }
    val student = data.enrollments.firstOrNull { it.student.id == studentId }?.student
    val testsById = data.tests.filter { it.classGroupId == classGroupId }.associateBy { it.id }
    val lines = data.entries
        .filter { it.testId in testsById && it.enrollmentId in enrollmentIds }
        .groupBy { it.testId }
        .map { (testId, list) -> ReportCardTest(testsById.getValue(testId), list.minBy { it.result.ordinalPriority() }) }
    val subjectsById = data.subjects.associateBy { it.id }
    val subjects = lines.groupBy { it.test.subjectId }
        .toSortedMap(subjectOrder(data.subjects))
        .map { (subjectId, tests) ->
            ReportCardSubject(subjectId, subjectsById[subjectId], tests.sortedWith(compareBy({ it.test.date }, { it.test.name })))
        }
    val standings = classStandings(data, classGroupId)
    return StudentReportCard(
        student = student,
        classGroupId = classGroupId,
        subjects = subjects,
        rank = standings.firstOrNull { it.student.id == studentId }?.rank,
        rankedCount = standings.count { it.rank != null },
    )
}

// endregion

// region Teacher performance

/** One Subject in one class that a teacher teaches, with the period's statistics. */
data class TeachingStats(
    val classGroupId: Long,
    val group: ClassGroup?,
    val subjectId: Long,
    val subject: Subject?,
    val stats: ResultStats,
)

data class TeacherPerformance(
    /** As typed in the assignment; null groups the Subjects without a teacher. */
    val teacherName: String?,
    val rows: List<TeachingStats>,
    /** All of the teacher's Tests in the period together. */
    val overall: ResultStats,
)

/**
 * Per teacher, every Subject + class they teach (optionally only in [classGroupId]): from the Tests in the period
 * and from assignments of active Subjects (shown with zero Tests). Teacher names match case-insensitively;
 * teachers sort by name and "No teacher assigned" comes last.
 */
fun teacherPerformance(data: AssessmentData, classGroupId: Long? = null): List<TeacherPerformance> {
    val subjectsById = data.subjects.associateBy { it.id }
    val classesById = data.classes.associateBy { it.id }
    val assignment = data.teachers
        .filter { it.teacherName.isNotBlank() }
        .associate { (it.classGroupId to it.subjectId) to it.teacherName.trim() }
    val tests = data.tests.filter { classGroupId == null || it.classGroupId == classGroupId }
    val testsByPair = tests.groupBy { it.classGroupId to it.subjectId }
    val assignedPairs = assignment.keys.filter { (classId, subjectId) ->
        (classGroupId == null || classId == classGroupId) && classId in classesById &&
            subjectsById[subjectId]?.isArchived == false
    }
    val pairs = (testsByPair.keys + assignedPairs).distinct()
    val entriesByTest = data.entries.groupBy { it.testId }
    val classOrder = data.classes.sortedClasses().withIndex().associate { (i, c) -> c.id to i }
    val subjectComparator = subjectOrder(data.subjects)

    fun statsFor(pairTests: List<TestRecord>) = resultStats(pairTests, pairTests.flatMap { entriesByTest[it.id].orEmpty() })

    return pairs
        .groupBy { pair -> assignment[pair]?.lowercase() }
        .map { (key, teacherPairs) ->
            val name = key?.let { teacherPairs.firstNotNullOf { pair -> assignment[pair] } }
            val rows = teacherPairs
                .sortedWith(
                    compareBy<Pair<Long, Long>> { classOrder[it.first] ?: Int.MAX_VALUE }
                        .thenComparator { a, b -> subjectComparator.compare(a.second, b.second) },
                )
                .map { pair ->
                    TeachingStats(pair.first, classesById[pair.first], pair.second, subjectsById[pair.second], statsFor(testsByPair[pair].orEmpty()))
                }
            TeacherPerformance(name, rows, statsFor(teacherPairs.flatMap { testsByPair[it].orEmpty() }))
        }
        .sortedWith(compareBy<TeacherPerformance> { it.teacherName == null }.thenComparator { a, b ->
            naturalCompare(a.teacherName.orEmpty().lowercase(), b.teacherName.orEmpty().lowercase())
        })
}

// endregion

// region Class assessment

data class SubjectAssessment(val subjectId: Long, val subject: Subject?, val stats: ResultStats)

data class ClassAssessment(
    val classGroupId: Long,
    val subjects: List<SubjectAssessment>,
    /** Every Test of the class in the period. */
    val overall: ResultStats,
    /** Best first; see [classStandings]. */
    val standings: List<StudentStanding>,
) {
    /** The best [TOP_STUDENTS] students with marks. */
    val topStudents: List<StudentStanding> get() = standings.filter { it.rank != null }.take(TOP_STUDENTS)

    /** Students whose overall percentage is below the pass mark, weakest first. */
    val needsAttention: List<StudentStanding>
        get() = standings.filter { standing -> standing.percent?.let { !isPass(it) } == true }.sortedBy { it.percent }

    companion object {
        const val TOP_STUDENTS = 5
    }
}

fun classAssessment(data: AssessmentData, classGroupId: Long): ClassAssessment {
    val tests = data.tests.filter { it.classGroupId == classGroupId }
    val ids = tests.mapTo(HashSet()) { it.id }
    val entries = data.entries.filter { it.testId in ids }
    val subjectsById = data.subjects.associateBy { it.id }
    val subjects = tests.groupBy { it.subjectId }
        .toSortedMap(subjectOrder(data.subjects))
        .map { (subjectId, subjectTests) ->
            val testIds = subjectTests.mapTo(HashSet()) { it.id }
            SubjectAssessment(subjectId, subjectsById[subjectId], resultStats(subjectTests, entries.filter { it.testId in testIds }))
        }
    return ClassAssessment(classGroupId, subjects, resultStats(tests, entries), classStandings(data, classGroupId))
}

// endregion

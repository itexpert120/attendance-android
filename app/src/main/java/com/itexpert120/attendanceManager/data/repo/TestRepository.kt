package com.itexpert120.attendanceManager.data.repo

import androidx.room3.withWriteTransaction
import com.itexpert120.attendanceManager.data.db.AttendanceDatabase
import com.itexpert120.attendanceManager.domain.AssessmentData
import com.itexpert120.attendanceManager.domain.DateRange
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.SubjectTeacher
import com.itexpert120.attendanceManager.domain.TestAggregate
import com.itexpert120.attendanceManager.domain.TestSummary
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.domain.formatMarks
import com.itexpert120.attendanceManager.domain.isActiveOn
import com.itexpert120.attendanceManager.domain.naturalCompare
import com.itexpert120.attendanceManager.domain.testReportAggregate
import com.itexpert120.attendanceManager.domain.testSummary
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.EnrollmentRow
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/** The editable fields of a Test; nullable ids and date let the form pass an unfilled field through to validation. */
data class TestInput(
    val name: String,
    val subjectId: Long?,
    val classGroupId: Long?,
    val date: LocalDate?,
    /** Hundredths of a mark: 40 marks is 4000. */
    val totalMarks: Int,
)

/** A Test Result as typed by the user. Clearing a result is `setTestResult(..., null)`. */
sealed interface TestResultInput {
    /** [hundredths] is the mark in hundredths, as produced by `parseMarks`. */
    data class Marks(val hundredths: Int) : TestResultInput

    data object Absent : TestResultInput
}

/** One line of a Test Roster: the enrolled student plus that member's Test Result. */
data class TestRosterRow(val enrollmentRow: EnrollmentRow, val entry: TestRosterEntry) {
    val enrollment: Enrollment get() = enrollmentRow.enrollment
    val student: Student get() = enrollmentRow.student
    val result: TestResultStatus get() = entry.result

    /** Hundredths; non-null exactly when [result] is MARKS. */
    val marks: Int? get() = entry.marks
}

/** A Test with its whole roster, so progress ([summary]) and reports can be derived without further queries. */
data class TestWithEntries(val test: TestRecord, val entries: List<TestRosterEntry>) {
    val summary: TestSummary get() = testSummary(entries)
}

/**
 * Which Tests a Subject Report or Student Report covers. A [studentId] makes it a Student Report, which
 * also needs a [classGroupId]; otherwise it is a Subject Report ([subjectId] and [classGroupId] null = all).
 */
data class TestReportFilter(
    val range: DateRange,
    val subjectId: Long? = null,
    val classGroupId: Long? = null,
    val studentId: Long? = null,
) {
    val isStudentReport: Boolean get() = studentId != null
}

data class TestReportSection(val subject: Subject, val tests: List<TestRecord>)

data class TestReport(
    val filter: TestReportFilter,
    /** Tests in the period (date descending), restricted to those the student sat in a Student Report. */
    val tests: List<TestRecord>,
    /** Roster entries of [tests]; for a Student Report only that student's own entries. */
    val entries: List<TestRosterEntry>,
    val aggregate: TestAggregate,
    /** [tests] grouped by Subject, in the Subject list's display order. */
    val sections: List<TestReportSection>,
) {
    fun entriesFor(testId: Long): List<TestRosterEntry> = entries.filter { it.testId == testId }

    /** The Test Summary of one Test within this report. */
    fun summaryFor(testId: Long): TestSummary = testSummary(entriesFor(testId))

    /** In a Student Report, the student's entry for [testId] (the first, if they hold several enrollments). */
    fun studentEntryFor(testId: Long): TestRosterEntry? = entries.firstOrNull { it.testId == testId }
}

/**
 * Pure report assembly (the logic of the PWA's TestReports screen). [studentEnrollmentIds] are the
 * enrollment ids of the reported student in the filter's class group; ignored for a Subject Report.
 */
fun buildTestReport(
    filter: TestReportFilter,
    allTests: List<TestRecord>,
    allEntries: List<TestRosterEntry>,
    subjects: List<Subject>,
    studentEnrollmentIds: Set<Long> = emptySet(),
): TestReport {
    val periodTests = allTests.filter { test ->
        test.date in filter.range &&
            (filter.subjectId == null || test.subjectId == filter.subjectId) &&
            (filter.classGroupId == null || test.classGroupId == filter.classGroupId)
    }
    val studentMode = filter.isStudentReport
    val tests = if (studentMode) {
        periodTests.filter { test ->
            allEntries.any { it.testId == test.id && it.enrollmentId in studentEnrollmentIds }
        }
    } else {
        periodTests
    }
    val testIds = tests.mapTo(HashSet()) { it.id }
    val entries = allEntries.filter {
        it.testId in testIds && (!studentMode || it.enrollmentId in studentEnrollmentIds)
    }
    val sections = subjects.sortedForDisplay().mapNotNull { subject ->
        val subjectTests = tests.filter { it.subjectId == subject.id }
        if (subjectTests.isEmpty()) null else TestReportSection(subject, subjectTests)
    }
    return TestReport(filter, tests, entries, testReportAggregate(tests, entries), sections)
}

class TestRepository(
    private val db: AttendanceDatabase,
    private val clock: Clock = Clock.System,
) {
    // ---- Observation ------------------------------------------------------------------------------

    /** All Tests, newest date first (ties: most recently created first). */
    fun observeTests(): Flow<List<TestRecord>> = db.tests().observeAll()

    fun observeTest(testId: Long): Flow<TestRecord?> = db.tests().observeById(testId)

    /** Every Test with its roster entries, for the Tests list (progress per Test). */
    fun observeTestsWithEntries(): Flow<List<TestWithEntries>> =
        combine(db.tests().observeAll(), db.tests().observeAllRosterEntries()) { tests, entries ->
            val byTest = entries.groupBy { it.testId }
            tests.map { TestWithEntries(it, byTest[it.id].orEmpty()) }
        }

    /** The Test Roster with each member's Test Result, sorted by roll number (natural order). */
    fun observeRoster(testId: Long): Flow<List<TestRosterRow>> =
        combine(db.tests().observeRoster(testId), db.tests().observeRosterRows(testId)) { entries, rows ->
            val rowByEnrollment = rows.associateBy { it.enrollment.id }
            entries
                .mapNotNull { entry -> rowByEnrollment[entry.enrollmentId]?.let { TestRosterRow(it, entry) } }
                .sortedWith { a, b -> naturalCompare(a.enrollment.rollNumber, b.enrollment.rollNumber) }
        }

    /** Progress and statistics of one Test; marks statistics are in mark units, not hundredths. */
    fun observeSummary(testId: Long): Flow<TestSummary> =
        db.tests().observeRoster(testId).map { testSummary(it) }

    /** The Subject Report or Student Report for [filter], re-computed whenever Tests or results change. */
    fun observeReport(filter: TestReportFilter): Flow<TestReport> {
        val studentId = filter.studentId
        val classGroupId = filter.classGroupId
        val studentEnrollments: Flow<Set<Long>> =
            if (studentId != null && classGroupId != null) {
                db.enrollments().observeRowsForClass(classGroupId).map { rows ->
                    rows.filter { it.enrollment.studentId == studentId }.mapTo(HashSet()) { it.enrollment.id }
                }
            } else {
                flowOf(emptySet())
            }
        return combine(
            db.tests().observeAll(),
            db.tests().observeAllRosterEntries(),
            db.subjects().observeAll(),
            studentEnrollments,
        ) { tests, entries, subjects, enrollmentIds ->
            buildTestReport(filter, tests, entries, subjects, enrollmentIds)
        }
    }

    /**
     * The Tests dated within [range] with their roster entries, plus every enrollment, Subject, Class Group and
     * teacher assignment: the snapshot the report card, teacher and class assessment reports are computed from.
     */
    fun observeAssessmentData(range: DateRange): Flow<AssessmentData> {
        val results = combine(db.tests().observeAll(), db.tests().observeAllRosterEntries()) { tests, entries ->
            val inRange = tests.filter { it.date in range }
            val ids = inRange.mapTo(HashSet()) { it.id }
            inRange to entries.filter { it.testId in ids }
        }
        val lookups = combine(
            db.enrollments().observeAllRows(),
            db.subjects().observeAll(),
            db.classGroups().observeAll(),
            db.subjectTeachers().observeAll(),
        ) { enrollments, subjects, classes, teachers -> AssessmentLookups(enrollments, subjects, classes, teachers) }
        return combine(results, lookups) { (tests, entries), l ->
            AssessmentData(range, tests, entries, l.enrollments, l.subjects, l.classes, l.teachers)
        }
    }

    private data class AssessmentLookups(
        val enrollments: List<EnrollmentRow>,
        val subjects: List<Subject>,
        val classes: List<ClassGroup>,
        val teachers: List<SubjectTeacher>,
    )

    suspend fun getTest(testId: Long): TestRecord? = db.tests().getById(testId)

    // ---- Commands ---------------------------------------------------------------------------------

    /** Creates a Test whose roster is a snapshot of the enrollments active in the class on the Test date. */
    suspend fun createTest(input: TestInput): TestRecord {
        val name = input.name.trim()
        val fields = requireFields(input, name)
        return db.withWriteTransaction {
            validateTotalMarks(input.totalMarks)
            val subject = db.subjects().getById(fields.subjectId)
            if (subject == null || subject.isArchived) throw ValidationException("Select an active Subject.")
            requireClassGroup(fields.classGroupId)
            val normalized = normalizeCatalogName(name)
            requireNoDuplicate(fields, normalized, excludingTestId = 0)
            val eligible = eligibleEnrollments(fields.classGroupId, fields.date)
            val now = clock.now()
            val draft = TestRecord(
                name = name,
                normalizedName = normalized,
                subjectId = fields.subjectId,
                classGroupId = fields.classGroupId,
                date = fields.date,
                totalMarks = input.totalMarks,
                createdAt = now,
                updatedAt = now,
            )
            val test = draft.copy(id = db.tests().insert(draft))
            db.tests().insertRoster(eligible.map { TestRosterEntry(test.id, it.id, createdAt = now) })
            test
        }
    }

    /**
     * Records, changes or (with a null [value]) clears the Test Result of one roster member. Clearing returns
     * the member to Not Entered.
     */
    suspend fun setTestResult(testId: Long, enrollmentId: Long, value: TestResultInput?) {
        db.withWriteTransaction {
            val test = db.tests().getById(testId) ?: throw ValidationException("Test not found.")
            val entry = db.tests().getRosterEntry(testId, enrollmentId)
                ?: throw ValidationException("Student is not on this Test Roster.")
            val updated = when (value) {
                null -> entry.copy(result = TestResultStatus.NOT_ENTERED, marks = null, resultUpdatedAt = null)
                TestResultInput.Absent ->
                    entry.copy(result = TestResultStatus.ABSENT, marks = null, resultUpdatedAt = clock.now())
                is TestResultInput.Marks -> {
                    if (value.hundredths < 0 || value.hundredths > test.totalMarks) {
                        throw ValidationException(
                            "Marks must be between 0 and ${formatMarks(test.totalMarks)}, with up to two decimals.",
                        )
                    }
                    entry.copy(
                        result = TestResultStatus.MARKS,
                        marks = value.hundredths,
                        resultUpdatedAt = clock.now(),
                    )
                }
            }
            db.tests().upsertRosterEntry(updated)
        }
    }

    /** Rebuilds the roster from the enrollments active on the Test date; only before any result is entered. */
    suspend fun refreshTestRoster(testId: Long) {
        db.withWriteTransaction {
            val test = db.tests().getById(testId) ?: throw ValidationException("Test not found.")
            if (db.tests().countEnteredResults(testId) > 0) {
                throw ValidationException("Refresh the Test Roster before entering results.")
            }
            replaceRoster(test.id, test.classGroupId, test.date)
        }
    }

    /**
     * Edits a Test. Class Group and date are locked once any result is entered, Total Marks cannot drop below
     * the highest recorded mark, and changing the class or date (before results) rebuilds the roster.
     */
    suspend fun updateTest(testId: Long, input: TestInput): TestRecord {
        val name = input.name.trim()
        return db.withWriteTransaction {
            val current = db.tests().getById(testId) ?: throw ValidationException("Test not found.")
            val fields = requireFields(input, name)
            validateTotalMarks(input.totalMarks)
            val hasResults = db.tests().countEnteredResults(testId) > 0
            val rosterChanged = fields.classGroupId != current.classGroupId || fields.date != current.date
            if (hasResults && rosterChanged) {
                throw ValidationException("Class Group and date are locked after result entry begins.")
            }
            val highest = db.tests().highestMarks(testId) ?: 0
            if (input.totalMarks < highest) {
                throw ValidationException("Total Marks cannot be below recorded Marks (${formatMarks(highest)}).")
            }
            val subject = db.subjects().getById(fields.subjectId)
            if (subject == null || (subject.isArchived && subject.id != current.subjectId)) {
                throw ValidationException("Select an active Subject.")
            }
            requireClassGroup(fields.classGroupId)
            val normalized = normalizeCatalogName(name)
            requireNoDuplicate(fields, normalized, excludingTestId = testId)
            val updated = current.copy(
                name = name,
                normalizedName = normalized,
                subjectId = fields.subjectId,
                classGroupId = fields.classGroupId,
                date = fields.date,
                totalMarks = input.totalMarks,
                updatedAt = clock.now(),
            )
            db.tests().update(updated)
            if (rosterChanged) replaceRoster(testId, fields.classGroupId, fields.date)
            updated
        }
    }

    /** Adds a student of the Test's class to the roster by hand (a late joiner); a no-op if already on it. */
    suspend fun addTestRosterMember(testId: Long, enrollmentId: Long) {
        db.withWriteTransaction {
            val test = db.tests().getById(testId)
            val enrollment = db.enrollments().getById(enrollmentId)
            if (test == null || enrollment == null) throw ValidationException("Test or Enrollment not found.")
            if (enrollment.classGroupId != test.classGroupId) {
                throw ValidationException("Select a Student from this Class Group.")
            }
            if (db.tests().getRosterEntry(testId, enrollmentId) == null) {
                db.tests().insertRoster(listOf(TestRosterEntry(testId, enrollmentId, createdAt = clock.now())))
            }
        }
    }

    /** Removes a roster member, which is only allowed while their result is Not Entered. */
    suspend fun removeTestRosterMember(testId: Long, enrollmentId: Long) {
        db.withWriteTransaction {
            val entry = db.tests().getRosterEntry(testId, enrollmentId)
            if (entry != null && entry.result != TestResultStatus.NOT_ENTERED) {
                throw ValidationException("Clear this Student’s Test Result before removing them.")
            }
            db.tests().deleteRosterEntry(testId, enrollmentId)
        }
    }

    /** Deletes the Test together with its roster and results. */
    suspend fun deleteTest(testId: Long) {
        db.withWriteTransaction {
            val test = db.tests().getById(testId)
            if (test != null) db.tests().delete(test)
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private class Fields(val subjectId: Long, val classGroupId: Long, val date: LocalDate)

    private fun requireFields(input: TestInput, trimmedName: String): Fields {
        val subjectId = input.subjectId
        val classGroupId = input.classGroupId
        val date = input.date
        if (trimmedName.isEmpty() || subjectId == null || classGroupId == null || date == null) {
            throw ValidationException("Test name, Subject, Class Group, and date are required.")
        }
        return Fields(subjectId, classGroupId, date)
    }

    private fun validateTotalMarks(totalMarks: Int) {
        if (totalMarks <= 0) {
            throw ValidationException("Total Marks must be greater than zero, with up to two decimals.")
        }
    }

    private suspend fun requireClassGroup(classGroupId: Long) {
        if (db.classGroups().getById(classGroupId) == null) throw ValidationException("Select a Class Group.")
    }

    private suspend fun requireNoDuplicate(fields: Fields, normalizedName: String, excludingTestId: Long) {
        val duplicates = db.tests().countDuplicates(
            classGroupId = fields.classGroupId,
            subjectId = fields.subjectId,
            date = fields.date,
            normalizedName = normalizedName,
            excludingTestId = excludingTestId,
        )
        if (duplicates > 0) throw ValidationException("That Test already exists for this Class Group and date.")
    }

    private suspend fun eligibleEnrollments(classGroupId: Long, date: LocalDate): List<Enrollment> {
        val eligible = db.enrollments().getForClass(classGroupId).filter { it.isActiveOn(date) }
        if (eligible.isEmpty()) {
            throw ValidationException("No students are enrolled in this Class Group on the Test date.")
        }
        return eligible
    }

    private suspend fun replaceRoster(testId: Long, classGroupId: Long, date: LocalDate) {
        val eligible = eligibleEnrollments(classGroupId, date)
        val now = clock.now()
        db.tests().deleteRoster(testId)
        db.tests().insertRoster(eligible.map { TestRosterEntry(testId, it.id, createdAt = now) })
    }
}

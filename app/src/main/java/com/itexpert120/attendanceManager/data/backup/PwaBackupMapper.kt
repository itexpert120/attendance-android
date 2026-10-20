package com.itexpert120.attendanceManager.data.backup

import com.itexpert120.attendanceManager.domain.PhoneNormalizer
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Enrollment
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.FeeEntry
import com.itexpert120.attendanceManager.model.Holiday
import com.itexpert120.attendanceManager.model.HomeworkItem
import com.itexpert120.attendanceManager.model.HomeworkReport
import com.itexpert120.attendanceManager.model.InstallmentMeta
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Register
import com.itexpert120.attendanceManager.model.SchoolSettings
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.Student
import com.itexpert120.attendanceManager.model.StudentRemark
import com.itexpert120.attendanceManager.model.Subject
import com.itexpert120.attendanceManager.model.TestRecord
import com.itexpert120.attendanceManager.model.TestResultStatus
import com.itexpert120.attendanceManager.model.TestRosterEntry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

/** Old PWA id (a UUID string) to new database id, per table. Merged duplicates share the survivor's id. */
class IdMaps(
    val classGroups: Map<String, Long>,
    val students: Map<String, Long>,
    val subjects: Map<String, Long>,
    val enrollments: Map<String, Long>,
    val registers: Map<String, Long>,
    val tests: Map<String, Long>,
    val homeworkReports: Map<String, Long>,
)

/**
 * The backup converted to native entities, with their final ids already assigned (the importer inserts
 * them with those ids, so the maps here are authoritative). Lists are in insertion order: every row's
 * parents come before it.
 */
class MappedBackup(
    val settings: SchoolSettings?,
    val classGroups: List<ClassGroup>,
    val students: List<Student>,
    val subjects: List<Subject>,
    val enrollments: List<Enrollment>,
    val registers: List<Register>,
    val attendance: List<AttendanceMark>,
    val holidays: List<Holiday>,
    val feeEntries: List<FeeEntry>,
    val installmentMeta: List<InstallmentMeta>,
    val remarks: List<StudentRemark>,
    val tests: List<TestRecord>,
    val rosterEntries: List<TestRosterEntry>,
    val homeworkReports: List<HomeworkReport>,
    val homeworkItems: List<HomeworkItem>,
    val ids: IdMaps,
    val warnings: List<String>,
) {
    /** Rows that will be written, keyed like [PwaTables]; see [ImportResult.counts]. */
    val counts: Map<String, Int> = linkedMapOf(
        PwaTables.SETTINGS to (if (settings != null) 1 else 0),
        PwaTables.CLASS_GROUPS to classGroups.size,
        PwaTables.STUDENTS to students.size,
        PwaTables.ENROLLMENTS to enrollments.size,
        PwaTables.REGISTERS to registers.size,
        PwaTables.ATTENDANCE to attendance.size,
        PwaTables.HOLIDAYS to holidays.size,
        PwaTables.FEE_ENTRIES to feeEntries.size,
        PwaTables.INSTALLMENT_META to installmentMeta.size,
        PwaTables.REMARKS to remarks.size,
        PwaTables.SUBJECTS to subjects.size,
        PwaTables.TESTS to tests.size,
        PwaTables.TEST_ROSTER to rosterEntries.size,
        PwaTables.TEST_RESULTS to rosterEntries.count { it.result != TestResultStatus.NOT_ENTERED },
        PwaTables.HOMEWORK_REPORTS to homeworkReports.size,
        "homeworkItems" to homeworkItems.size,
    )
}

/** Stores a decoded photo and returns its file name; the importer backs this with the real `PhotoStore`. */
fun interface PhotoSink {
    suspend fun save(bytes: ByteArray, mimeType: String): String
}

class DecodedPhoto(val bytes: ByteArray, val mimeType: String)

object DataUrls {
    /** Decodes `data:<mime>[;params];base64,<payload>`; null when it isn't a base64 data URL or the payload is corrupt. */
    fun decode(dataUrl: String): DecodedPhoto? {
        val text = dataUrl.trimStart()
        if (!text.startsWith("data:", ignoreCase = true)) return null
        val comma = text.indexOf(',')
        if (comma < 0) return null
        val header = text.substring(5, comma).split(';').map { it.trim() }
        if (header.none { it.equals("base64", ignoreCase = true) }) return null
        val mime = header.first().takeIf { it.contains('/') }?.lowercase() ?: "application/octet-stream"
        val bytes = try {
            Base64.Mime.decode(text, comma + 1, text.length)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (bytes.isEmpty()) null else DecodedPhoto(bytes, mime)
    }
}

/**
 * Converts a [ParsedBackup] to the native schema. Pure apart from the [PhotoSink] it is handed, so it is
 * unit-testable on the JVM.
 *
 * Repair policy: a bad row never fails the import. Rows whose parent is missing (orphans), whose key fields
 * are unusable, or that would violate a unique constraint are skipped; rows that are fine apart from one
 * field (a bad timestamp, an unparsable birth date) are kept with that field repaired. Duplicate parents
 * (same class and section, same subject name, same register month) are merged, so their children survive
 * under the first one; two students sharing an admission number are both kept, the later one's number gets
 * a " (2)" style suffix. Every such decision is reported through the warnings.
 */
class PwaBackupMapper(
    private val clock: Clock = Clock.System,
    private val phoneNormalizer: PhoneNormalizer? = null,
) {
    suspend fun map(parsed: ParsedBackup, photos: PhotoSink): MappedBackup {
        val log = WarningLog()
        parsed.warnings.forEach(log::note)
        val now = clock.now()

        fun instant(raw: String?, table: String, field: String, example: String?): Instant {
            val parsedInstant = parseInstant(raw)
            if (parsedInstant != null) return parsedInstant
            log.add(table, "$field is ${if (raw.isNullOrBlank()) "missing" else "not a valid timestamp"}, set to the import time", example)
            return now
        }

        suspend fun photo(table: String, example: String, dataUrl: String?): String? {
            if (dataUrl.isNullOrBlank()) return null
            val decoded = DataUrls.decode(dataUrl)
            if (decoded == null) {
                log.add(table, "photo is not a valid base64 image and was dropped", example)
                return null
            }
            return photos.save(decoded.bytes, decoded.mimeType)
        }

        // ---- settings -------------------------------------------------------------------------
        if (parsed.settings.size > 1) log.note("settings: the file has ${parsed.settings.size} settings rows, using the first")
        val settingsRow = parsed.settings.firstOrNull { it.id == "school" } ?: parsed.settings.firstOrNull()
        val settings = settingsRow?.let { s ->
            val month = s.academicYearStartMonth?.exactInt()?.takeIf { it in 1..12 }
            if (month == null) log.note("settings: the academic year start month is missing or invalid, using April")
            SchoolSettings(
                schoolName = s.schoolName.orEmpty(),
                academicYearStartMonth = month ?: 4,
                currencyLabel = s.currencyLabel ?: "Rs.",
                // The PWA's own fallback: older backups called the Class Incharge the headmaster.
                classInchargeName = s.classInchargeName?.takeIf { it.isNotBlank() } ?: s.headmasterName.orEmpty(),
                logoFile = photo(PwaTables.SETTINGS, "school logo", s.logoDataUrl),
                absenceMessageTemplate = s.absenceMessageTemplate?.takeIf { it.isNotBlank() },
                updatedAt = instant(s.updatedAt, PwaTables.SETTINGS, "updatedAt", null),
            )
        }

        // ---- class groups ---------------------------------------------------------------------
        val classIds = HashMap<String, Long>()
        val classByKey = HashMap<String, Long>()
        val classGroups = ArrayList<ClassGroup>()
        for (c in parsed.classGroups) {
            val label = "${c.className} ${c.section}".trim()
            if (c.id in classIds) {
                log.add(PwaTables.CLASS_GROUPS, "skipped, duplicate id", c.id)
                continue
            }
            if (c.className == null) {
                log.add(PwaTables.CLASS_GROUPS, "skipped, the class name is missing", c.id)
                continue
            }
            val section = c.section.orEmpty()
            val key = asciiLower(c.className) + "\u0000" + asciiLower(section)
            val existing = classByKey[key]
            if (existing != null) {
                classIds[c.id] = existing
                log.add(PwaTables.CLASS_GROUPS, "duplicate class and section merged into the first one", label)
                continue
            }
            val id = classGroups.size + 1L
            classIds[c.id] = id
            classByKey[key] = id
            classGroups += ClassGroup(id, c.className, section, instant(c.createdAt, PwaTables.CLASS_GROUPS, "createdAt", label))
        }

        // ---- subjects -------------------------------------------------------------------------
        val subjectIds = HashMap<String, Long>()
        val subjectByName = HashMap<String, Long>()
        val subjects = ArrayList<Subject>()
        for (s in parsed.subjects) {
            if (s.id in subjectIds) {
                log.add(PwaTables.SUBJECTS, "skipped, duplicate id", s.id)
                continue
            }
            val name = s.name?.takeIf { it.isNotBlank() } ?: s.normalizedName?.takeIf { it.isNotBlank() }
            if (name == null) {
                log.add(PwaTables.SUBJECTS, "skipped, the subject has no name", s.id)
                continue
            }
            val normalized = s.normalizedName?.takeIf { it.isNotBlank() } ?: name.trim().lowercase()
            val existing = subjectByName[normalized]
            if (existing != null) {
                subjectIds[s.id] = existing
                log.add(PwaTables.SUBJECTS, "duplicate subject name merged into the first one", name)
                continue
            }
            val id = subjects.size + 1L
            subjectIds[s.id] = id
            subjectByName[normalized] = id
            val createdAt = instant(s.createdAt, PwaTables.SUBJECTS, "createdAt", name)
            val updatedAt = parseInstant(s.updatedAt) ?: createdAt
            val archivedAt = if (s.archivedAt.isNullOrBlank()) {
                null
            } else {
                parseInstant(s.archivedAt) ?: run {
                    log.add(PwaTables.SUBJECTS, "archivedAt is not a valid timestamp, set to the import time", name)
                    now
                }
            }
            subjects += Subject(id, name, normalized, archivedAt, createdAt, updatedAt)
        }

        // ---- students -------------------------------------------------------------------------
        val studentIds = HashMap<String, Long>()
        val studentCreated = HashMap<Long, Instant>()
        val admissionsTaken = HashSet<String>()
        val students = ArrayList<Student>()
        for (s in parsed.students) {
            if (s.id in studentIds) {
                log.add(PwaTables.STUDENTS, "skipped, duplicate id", s.id)
                continue
            }
            val label = s.name?.takeIf { it.isNotBlank() } ?: s.id
            var admission = s.admissionNumber.orEmpty()
            if (!admissionsTaken.add(asciiLower(admission))) {
                var n = 2
                while (!admissionsTaken.add(asciiLower("$admission ($n)"))) n++
                log.add(PwaTables.STUDENTS, "admission number is already used by another student, renamed to \"$admission ($n)\"", label)
                admission = "$admission ($n)"
            }
            val dob = if (s.dateOfBirth.isNullOrBlank()) {
                null
            } else {
                parseDate(s.dateOfBirth) ?: run {
                    log.add(PwaTables.STUDENTS, "date of birth is not a valid date and was cleared", label)
                    null
                }
            }
            val id = students.size + 1L
            studentIds[s.id] = id
            val createdAt = instant(s.createdAt, PwaTables.STUDENTS, "createdAt", label)
            studentCreated[id] = createdAt
            students += Student(
                id = id,
                admissionNumber = admission,
                name = s.name.orEmpty(),
                phone = normalizePhone(s.phone.orEmpty(), label, log),
                dateOfBirth = dob,
                photoFile = photo(PwaTables.STUDENTS, label, s.photoDataUrl),
                createdAt = createdAt,
            )
        }

        // ---- enrollments ----------------------------------------------------------------------
        val enrollmentIds = HashMap<String, Long>()
        val enrollments = ArrayList<Enrollment>()
        for (e in parsed.enrollments) {
            if (e.id in enrollmentIds) {
                log.add(PwaTables.ENROLLMENTS, "skipped, duplicate id", e.id)
                continue
            }
            val studentId = e.studentId?.let(studentIds::get)
            val classId = e.classGroupId?.let(classIds::get)
            if (studentId == null || classId == null) {
                log.add(PwaTables.ENROLLMENTS, "skipped, its student or class is missing from the backup", e.id)
                continue
            }
            val admitted = parseDate(e.admittedOn) ?: run {
                log.add(PwaTables.ENROLLMENTS, "admission date is missing or invalid, set to the day the student was created", e.id)
                utcDate(studentCreated.getValue(studentId))
            }
            val struckOff = if (e.struckOffOn.isNullOrBlank()) {
                null
            } else {
                parseDate(e.struckOffOn) ?: run {
                    log.add(PwaTables.ENROLLMENTS, "struck-off date is not a valid date and was cleared", e.id)
                    null
                }
            }
            val id = enrollments.size + 1L
            enrollmentIds[e.id] = id
            enrollments += Enrollment(id, studentId, classId, e.rollNumber.orEmpty(), admitted, struckOff)
        }

        // ---- registers and their rows ---------------------------------------------------------
        val registerIds = HashMap<String, Long>()
        val registerByMonth = HashMap<Triple<Long, Int, Int>, Long>()
        val registerMonth = HashMap<Long, Pair<Int, Int>>()
        val registers = ArrayList<Register>()
        for (r in parsed.registers) {
            if (r.id in registerIds) {
                log.add(PwaTables.REGISTERS, "skipped, duplicate id", r.id)
                continue
            }
            val classId = r.classGroupId?.let(classIds::get)
            val year = r.year?.exactInt()?.takeIf { it in 1..9999 }
            val month = r.month?.exactInt()?.takeIf { it in 1..12 }
            if (classId == null || year == null || month == null) {
                log.add(PwaTables.REGISTERS, "skipped, its class is missing or the month is invalid", r.id)
                continue
            }
            val existing = registerByMonth[Triple(classId, year, month)]
            if (existing != null) {
                registerIds[r.id] = existing
                log.add(PwaTables.REGISTERS, "duplicate register for the same class and month merged into the first one", "$year-$month")
                continue
            }
            val id = registers.size + 1L
            registerIds[r.id] = id
            registerByMonth[Triple(classId, year, month)] = id
            registerMonth[id] = year to month
            registers += Register(id, classId, year, month, instant(r.createdAt, PwaTables.REGISTERS, "createdAt", "$year-$month"))
        }

        val markKeys = HashSet<MarkKey>()
        val attendance = ArrayList<AttendanceMark>(parsed.attendance.size)
        for (a in parsed.attendance) {
            val example = a.id ?: "${a.registerId}:${a.enrollmentId}:${a.day}:${a.session}"
            val registerId = a.registerId?.let(registerIds::get)
            val enrollmentId = a.enrollmentId?.let(enrollmentIds::get)
            if (registerId == null || enrollmentId == null) {
                log.add(PwaTables.ATTENDANCE, "skipped, its register or enrollment is missing from the backup", example)
                continue
            }
            val (year, month) = registerMonth.getValue(registerId)
            val day = a.day?.exactInt()?.takeIf { it in 1..daysInMonth(year, month) }
            val session = a.session?.exactInt()?.let(Session::fromNumber)
            val status = a.status?.let(AttendanceStatus::fromCode)
            if (day == null || session == null || status == null) {
                log.add(PwaTables.ATTENDANCE, "skipped, the day, session or status is invalid", example)
                continue
            }
            if (!markKeys.add(MarkKey(registerId, enrollmentId, day, session.number))) {
                log.add(PwaTables.ATTENDANCE, "skipped, a mark for the same student, day and session already exists", example)
                continue
            }
            attendance += AttendanceMark(registerId, enrollmentId, day, session, status)
        }

        val holidayKeys = HashSet<Pair<Long, Int>>()
        val holidays = ArrayList<Holiday>()
        for (h in parsed.holidays) {
            val example = h.id ?: "${h.registerId}:${h.day}"
            val registerId = h.registerId?.let(registerIds::get)
            if (registerId == null) {
                log.add(PwaTables.HOLIDAYS, "skipped, its register is missing from the backup", example)
                continue
            }
            val (year, month) = registerMonth.getValue(registerId)
            val day = h.day?.exactInt()?.takeIf { it in 1..daysInMonth(year, month) }
            if (day == null) {
                log.add(PwaTables.HOLIDAYS, "skipped, the day is invalid", example)
                continue
            }
            if (!holidayKeys.add(registerId to day)) {
                log.add(PwaTables.HOLIDAYS, "skipped, the register already has a holiday on that day", example)
                continue
            }
            holidays += Holiday(registerId, day, h.title.orEmpty())
        }

        val feeKeys = HashSet<Triple<Long, Long, Int>>()
        val feeEntries = ArrayList<FeeEntry>()
        for (f in parsed.feeEntries) {
            val example = f.id ?: "${f.registerId}:${f.enrollmentId}:${f.installment}"
            val registerId = f.registerId?.let(registerIds::get)
            val enrollmentId = f.enrollmentId?.let(enrollmentIds::get)
            val installment = f.installment?.exactInt()?.let(InstallmentNumber::fromNumber)
            if (registerId == null || enrollmentId == null || installment == null) {
                log.add(PwaTables.FEE_ENTRIES, "skipped, its register or enrollment is missing or the installment is invalid", example)
                continue
            }
            if (!feeKeys.add(Triple(registerId, enrollmentId, installment.number))) {
                log.add(PwaTables.FEE_ENTRIES, "skipped, an entry for the same student and installment already exists", example)
                continue
            }
            feeEntries += FeeEntry(
                registerId, enrollmentId, installment,
                FeeAmounts(
                    ftf = f.ftf.minorUnits(), ff = f.ff.minorUnits(), arrears = f.arrears.minorUnits(),
                    lateCertificate = f.lateCertificate.minorUnits(), slc = f.slc.minorUnits(), dcf = f.dcf.minorUnits(),
                ),
            )
        }

        val metaKeys = HashSet<Pair<Long, Int>>()
        val installmentMeta = ArrayList<InstallmentMeta>()
        for (m in parsed.installmentMeta) {
            val example = m.id ?: "${m.registerId}:${m.installment}"
            val registerId = m.registerId?.let(registerIds::get)
            val installment = m.installment?.exactInt()?.let(InstallmentNumber::fromNumber)
            if (registerId == null || installment == null) {
                log.add(PwaTables.INSTALLMENT_META, "skipped, its register is missing or the installment is invalid", example)
                continue
            }
            if (!metaKeys.add(registerId to installment.number)) {
                log.add(PwaTables.INSTALLMENT_META, "skipped, the register already has this installment", example)
                continue
            }
            installmentMeta += InstallmentMeta(registerId, installment, m.rate.minorUnits(), m.receiverName.orEmpty())
        }

        val remarkKeys = HashSet<Pair<Long, Long>>()
        val remarks = ArrayList<StudentRemark>()
        for (r in parsed.remarks) {
            val example = r.id ?: "${r.registerId}:${r.enrollmentId}"
            val registerId = r.registerId?.let(registerIds::get)
            val enrollmentId = r.enrollmentId?.let(enrollmentIds::get)
            if (registerId == null || enrollmentId == null) {
                log.add(PwaTables.REMARKS, "skipped, its register or enrollment is missing from the backup", example)
                continue
            }
            if (!remarkKeys.add(registerId to enrollmentId)) {
                log.add(PwaTables.REMARKS, "skipped, the student already has a remark in this register", example)
                continue
            }
            remarks += StudentRemark(registerId, enrollmentId, r.text.orEmpty())
        }

        // ---- tests, with roster and results merged -------------------------------------------
        val testIds = HashMap<String, Long>()
        val testByKey = HashMap<TestKey, Long>()
        val testTotals = HashMap<Long, Int>()
        val tests = ArrayList<TestRecord>()
        for (t in parsed.tests) {
            val label = t.name?.takeIf { it.isNotBlank() } ?: t.id
            if (t.id in testIds) {
                log.add(PwaTables.TESTS, "skipped, duplicate id", t.id)
                continue
            }
            val classId = t.classGroupId?.let(classIds::get)
            val subjectId = t.subjectId?.let(subjectIds::get)
            if (classId == null || subjectId == null) {
                log.add(PwaTables.TESTS, "skipped, its class or subject is missing from the backup", label)
                continue
            }
            val date = parseDate(t.date)
            if (date == null) {
                log.add(PwaTables.TESTS, "skipped, the date is missing or invalid", label)
                continue
            }
            val total = t.totalMarks?.toHundredths()?.takeIf { it > 0 }
            if (total == null) {
                log.add(PwaTables.TESTS, "skipped, the total marks are missing or not a positive number", label)
                continue
            }
            val name = t.name?.takeIf { it.isNotBlank() } ?: t.normalizedName?.takeIf { it.isNotBlank() }
            if (name == null) {
                log.add(PwaTables.TESTS, "skipped, the test has no name", t.id)
                continue
            }
            val normalized = t.normalizedName?.takeIf { it.isNotBlank() } ?: name.trim().lowercase()
            val key = TestKey(classId, subjectId, date, normalized)
            val existing = testByKey[key]
            if (existing != null) {
                testIds[t.id] = existing
                log.add(PwaTables.TESTS, "duplicate test (same class, subject, date and name) merged into the first one", label)
                continue
            }
            val id = tests.size + 1L
            testIds[t.id] = id
            testByKey[key] = id
            testTotals[id] = total
            val createdAt = instant(t.createdAt, PwaTables.TESTS, "createdAt", label)
            tests += TestRecord(id, name, normalized, subjectId, classId, date, total, createdAt, parseInstant(t.updatedAt) ?: createdAt)
        }

        // Results keyed like the roster, so each roster entry can pick up its own.
        val resultsByKey = LinkedHashMap<Pair<Long, Long>, PwaTestResult>()
        for (r in parsed.testResults) {
            val example = r.id ?: "${r.testId}:${r.enrollmentId}"
            val testId = r.testId?.let(testIds::get)
            val enrollmentId = r.enrollmentId?.let(enrollmentIds::get)
            if (testId == null || enrollmentId == null) {
                log.add(PwaTables.TEST_RESULTS, "dropped, its test or enrollment is missing from the backup", example)
                continue
            }
            if (resultsByKey.putIfAbsent(testId to enrollmentId, r) != null) {
                log.add(PwaTables.TEST_RESULTS, "dropped, the student already has a result in this test", example)
            }
        }

        val rosterKeys = HashSet<Pair<Long, Long>>()
        val rosterEntries = ArrayList<TestRosterEntry>()
        for (r in parsed.testRoster) {
            val example = r.id ?: "${r.testId}:${r.enrollmentId}"
            val testId = r.testId?.let(testIds::get)
            val enrollmentId = r.enrollmentId?.let(enrollmentIds::get)
            if (testId == null || enrollmentId == null) {
                log.add(PwaTables.TEST_ROSTER, "skipped, its test or enrollment is missing from the backup", example)
                continue
            }
            val key = testId to enrollmentId
            if (!rosterKeys.add(key)) {
                log.add(PwaTables.TEST_ROSTER, "skipped, the student is already on this test's roster", example)
                continue
            }
            val createdAt = instant(r.createdAt, PwaTables.TEST_ROSTER, "createdAt", example)
            val result = resultsByKey.remove(key)
            rosterEntries += buildRosterEntry(testId, enrollmentId, createdAt, result, testTotals.getValue(testId), log, example)
        }
        for ((_, r) in resultsByKey) {
            log.add(
                PwaTables.TEST_RESULTS,
                "dropped, the student is not on the test's roster",
                r.id ?: "${r.testId}:${r.enrollmentId}",
            )
        }

        // ---- homework -------------------------------------------------------------------------
        val homeworkIds = HashMap<String, Long>()
        val homeworkKeys = HashSet<Pair<Long, LocalDate>>()
        val reports = ArrayList<HomeworkReport>()
        val items = ArrayList<HomeworkItem>()
        for (h in parsed.homeworkReports) {
            if (h.id in homeworkIds) {
                log.add(PwaTables.HOMEWORK_REPORTS, "skipped, duplicate id", h.id)
                continue
            }
            val classId = h.classGroupId?.let(classIds::get)
            if (classId == null) {
                log.add(PwaTables.HOMEWORK_REPORTS, "skipped, its class is missing from the backup", h.date ?: h.id)
                continue
            }
            val date = parseDate(h.date)
            if (date == null) {
                log.add(PwaTables.HOMEWORK_REPORTS, "skipped, the date is missing or invalid", h.id)
                continue
            }
            if (!homeworkKeys.add(classId to date)) {
                log.add(PwaTables.HOMEWORK_REPORTS, "skipped, the class already has a diary for that date", date.toString())
                continue
            }

            val reportId = reports.size + 1L
            val usedSubjects = HashSet<Long>()
            val reportItems = ArrayList<HomeworkItem>()
            // `order` decides the display order; renumber from 0 so dropped items leave no gaps.
            val ordered = h.items.withIndex().sortedBy { (index, item) -> item.order?.toHundredths() ?: (index * 100) }
            for ((_, item) in ordered) {
                val subjectId = item.subjectId?.let(subjectIds::get)
                if (subjectId == null) {
                    log.add(PwaTables.HOMEWORK_REPORTS, "homework item dropped, its subject is missing from the backup", date.toString())
                    continue
                }
                if (!usedSubjects.add(subjectId)) {
                    log.add(PwaTables.HOMEWORK_REPORTS, "homework item dropped, the subject already appears in the same diary", date.toString())
                    continue
                }
                reportItems += HomeworkItem(0, reportId, subjectId, item.details.orEmpty(), reportItems.size)
            }
            val photoFile = photo(PwaTables.HOMEWORK_REPORTS, date.toString(), h.photoDataUrl)
            if (reportItems.isEmpty() && photoFile == null) {
                homeworkKeys.remove(classId to date)
                log.add(PwaTables.HOMEWORK_REPORTS, "skipped, no usable homework items or photo remain", date.toString())
                continue
            }
            homeworkIds[h.id] = reportId
            val createdAt = instant(h.createdAt, PwaTables.HOMEWORK_REPORTS, "createdAt", date.toString())
            reports += HomeworkReport(
                id = reportId,
                classGroupId = classId,
                date = date,
                inchargeName = h.inchargeName.orEmpty(),
                parentNote = h.parentNote.orEmpty(),
                photoFile = photoFile,
                createdAt = createdAt,
                updatedAt = parseInstant(h.updatedAt) ?: createdAt,
            )
            // Item ids are assigned in one pass below, in report order.
            items += reportItems
        }
        val numberedItems = items.mapIndexed { index, item -> item.copy(id = index + 1L) }

        return MappedBackup(
            settings = settings,
            classGroups = classGroups,
            students = students,
            subjects = subjects,
            enrollments = enrollments,
            registers = registers,
            attendance = attendance,
            holidays = holidays,
            feeEntries = feeEntries,
            installmentMeta = installmentMeta,
            remarks = remarks,
            tests = tests,
            rosterEntries = rosterEntries,
            homeworkReports = reports,
            homeworkItems = numberedItems,
            ids = IdMaps(classIds, studentIds, subjectIds, enrollmentIds, registerIds, testIds, homeworkIds),
            warnings = log.toList(),
        )
    }

    private fun buildRosterEntry(
        testId: Long,
        enrollmentId: Long,
        createdAt: Instant,
        result: PwaTestResult?,
        totalMarks: Int,
        log: WarningLog,
        example: String,
    ): TestRosterEntry {
        val notEntered = TestRosterEntry(testId, enrollmentId, TestResultStatus.NOT_ENTERED, null, null, createdAt)
        if (result == null) return notEntered
        val updatedAt = parseInstant(result.updatedAt) ?: createdAt
        return when (result.status?.lowercase()) {
            "absent" -> notEntered.copy(result = TestResultStatus.ABSENT, resultUpdatedAt = updatedAt)
            "marks" -> {
                val marks = result.marks?.toHundredths()
                when {
                    marks == null || marks < 0 -> {
                        log.add(PwaTables.TEST_RESULTS, "marks are missing or invalid, result reset to Not Entered", example)
                        notEntered
                    }
                    marks > totalMarks -> {
                        log.add(PwaTables.TEST_RESULTS, "marks exceed the test's total marks, result reset to Not Entered", example)
                        notEntered
                    }
                    else -> notEntered.copy(result = TestResultStatus.MARKS, marks = marks, resultUpdatedAt = updatedAt)
                }
            }
            else -> {
                log.add(PwaTables.TEST_RESULTS, "unknown result status \"${result.status}\", reset to Not Entered", example)
                notEntered
            }
        }
    }

    private fun normalizePhone(raw: String, label: String, log: WarningLog): String {
        val normalizer = phoneNormalizer ?: return raw.trim()
        return try {
            normalizer.normalize(raw)
        } catch (_: ValidationException) {
            log.add(PwaTables.STUDENTS, "phone number is not valid and was kept as written", label)
            raw.trim()
        }
    }

    private data class MarkKey(val registerId: Long, val enrollmentId: Long, val day: Int, val session: Int)
    private data class TestKey(val classGroupId: Long, val subjectId: Long, val date: LocalDate, val normalizedName: String)

    companion object {
        /** SQLite's NOCASE folds ASCII letters only, so uniqueness checks must do exactly that. */
        internal fun asciiLower(text: String): String =
            buildString(text.length) { for (ch in text) append(if (ch in 'A'..'Z') ch + 32 else ch) }

        internal fun daysInMonth(year: Int, month: Int): Int = when (month) {
            2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        private val DATE_PREFIX = Regex("""\d{4}-\d{2}-\d{2}""")

        /** `yyyy-MM-dd`, also accepting a full ISO timestamp (the date part is used); null when unusable. */
        internal fun parseDate(raw: String?): LocalDate? {
            val text = raw?.trim() ?: return null
            if (text.length < 10 || !DATE_PREFIX.matches(text.substring(0, 10))) return null
            return try {
                LocalDate.parse(text.substring(0, 10))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** ISO-8601 timestamps as `Date.toISOString` writes them; a bare date counts as midnight UTC. */
        internal fun parseInstant(raw: String?): Instant? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return try {
                Instant.parse(text)
            } catch (_: IllegalArgumentException) {
                parseDate(text)?.takeIf { text.length == 10 }?.atStartOfDayIn(TimeZone.UTC)
            }
        }

        private fun utcDate(instant: Instant): LocalDate = instant.toLocalDateTime(TimeZone.UTC).date

        private fun BigDecimal.toLocalMagnitudeOk(): Boolean = precision() - scale() <= 15

        /** Whole number, or null when fractional or out of Int range. */
        internal fun BigDecimal.exactInt(): Int? {
            if (!toLocalMagnitudeOk()) return null
            return try {
                intValueExact()
            } catch (_: ArithmeticException) {
                null
            }
        }

        /** Marks as integer hundredths, rounding half up on exact decimal text (so 5.07 is 507, never 506). */
        internal fun BigDecimal.toHundredths(): Int? {
            if (!toLocalMagnitudeOk()) return null
            val hundredths = movePointRight(2).setScale(0, RoundingMode.HALF_UP)
            return try {
                hundredths.intValueExact()
            } catch (_: ArithmeticException) {
                null
            }
        }

        /** Money fields are already minor units in the PWA; missing means zero. */
        internal fun BigDecimal?.minorUnits(): Long {
            if (this == null || !toLocalMagnitudeOk()) return 0
            return try {
                setScale(0, RoundingMode.HALF_UP).longValueExact()
            } catch (_: ArithmeticException) {
                0
            }
        }
    }
}

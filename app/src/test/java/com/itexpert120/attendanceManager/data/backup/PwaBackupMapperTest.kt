package com.itexpert120.attendanceManager.data.backup

import com.itexpert120.attendanceManager.domain.PhoneNormalizer
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Session
import com.itexpert120.attendanceManager.model.TestResultStatus
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant

class PwaBackupMapperTest {
    private val now = Instant.parse("2026-09-01T10:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
    }

    /** Remembers what was "stored" so tests can assert on photo handling. */
    private class RecordingSink : PhotoSink {
        val saved = mutableListOf<Pair<String, String>>() // name to mime
        val bytes = mutableListOf<ByteArray>()

        override suspend fun save(bytes: ByteArray, mimeType: String): String {
            val name = "photo-${saved.size + 1}"
            saved += name to mimeType
            this.bytes += bytes
            return name
        }
    }

    // ---- building a synthetic Dexie export --------------------------------------------------------

    private fun obj(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in fields) {
            when (value) {
                null -> put(key, JsonNull)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is JsonElement -> put(key, value)
                is Double -> put(key, JsonPrimitive(value))
                else -> error("unsupported $value")
            }
        }
    }

    /** A decimal written exactly as JSON.stringify would, e.g. `5.07`, without going through Double. */
    private fun dec(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun dexie(tables: Map<String, List<JsonObject>>, databaseName: String = "students-attendance-register"): JsonObject =
        obj(
            "formatName" to "dexie",
            "formatVersion" to 1,
            "data" to obj(
                "databaseName" to databaseName,
                "databaseVersion" to 30,
                "tables" to JsonArray(tables.keys.map { obj("name" to it, "schema" to "id", "rowCount" to -1) }),
                "data" to JsonArray(
                    tables.map { (name, rows) ->
                        obj("tableName" to name, "inbound" to JsonPrimitive(true), "rows" to JsonArray(rows))
                    },
                ),
            ),
        )

    private val logoUrl = "data:image/png;base64,bG9nbw=="
    private val studentPhotoUrl = "data:image/jpeg;base64,dGVzdA=="

    private fun fullTables(): LinkedHashMap<String, List<JsonObject>> = linkedMapOf(
        "settings" to listOf(
            obj(
                "id" to "school", "schoolName" to "Green Valley", "academicYearStartMonth" to 4, "currencyLabel" to "Rs.",
                "classInchargeName" to "Ms Hina", "logoDataUrl" to logoUrl, "absenceMessageTemplate" to "Hi {student}",
                "updatedAt" to "2026-08-29T00:00:00.000Z",
            ),
        ),
        "classGroups" to listOf(
            obj("id" to "c1", "className" to "7", "section" to "A", "createdAt" to "2026-08-01T00:00:00.000Z"),
            obj("id" to "c2", "className" to "8", "section" to "B", "createdAt" to "2026-08-02T00:00:00.000Z"),
        ),
        "students" to listOf(
            obj(
                "id" to "s1", "admissionNumber" to "A-001", "name" to "Ayesha", "phone" to "+923001234567",
                "dateOfBirth" to "2014-05-12", "photoDataUrl" to studentPhotoUrl, "createdAt" to "2026-08-01T00:00:00.000Z",
            ),
            obj("id" to "s2", "admissionNumber" to "A-002", "name" to "Bilal", "phone" to "", "createdAt" to "2026-08-01T00:00:00.000Z"),
            obj("id" to "s3", "admissionNumber" to "A-003", "name" to "Chand", "phone" to "", "createdAt" to "2026-08-01T00:00:00.000Z"),
        ),
        "enrollments" to listOf(
            obj("id" to "e1", "studentId" to "s1", "classGroupId" to "c1", "rollNumber" to "1", "admittedOn" to "2026-08-01"),
            obj("id" to "e2", "studentId" to "s2", "classGroupId" to "c1", "rollNumber" to "2", "admittedOn" to "2026-08-01", "struckOffOn" to "2026-08-20"),
            obj("id" to "e3", "studentId" to "s3", "classGroupId" to "c2", "rollNumber" to "1", "admittedOn" to "2026-08-01"),
        ),
        "registers" to listOf(
            obj("id" to "r1", "classGroupId" to "c1", "year" to 2026, "month" to 8, "createdAt" to "2026-08-01T00:00:00.000Z"),
        ),
        "attendance" to listOf(
            obj("id" to "r1:e1:3:1", "registerId" to "r1", "enrollmentId" to "e1", "day" to 3, "session" to 1, "status" to "P"),
            obj("id" to "r1:e1:3:2", "registerId" to "r1", "enrollmentId" to "e1", "day" to 3, "session" to 2, "status" to "A"),
            obj("id" to "r1:e2:3:1", "registerId" to "r1", "enrollmentId" to "e2", "day" to 3, "session" to 1, "status" to "L"),
        ),
        "holidays" to listOf(obj("id" to "r1:15", "registerId" to "r1", "day" to 15, "title" to "Independence Day")),
        "feeEntries" to listOf(
            obj(
                "id" to "r1:e1:1", "registerId" to "r1", "enrollmentId" to "e1", "installment" to 1,
                "ftf" to 10000, "ff" to 500, "arrears" to 0, "lateCertificate" to 0, "slc" to 0, "dcf" to 250,
            ),
        ),
        "installmentMeta" to listOf(
            obj("id" to "r1:1", "registerId" to "r1", "installment" to 1, "rate" to 50000, "receiverName" to "Mr Khan"),
        ),
        "remarks" to listOf(obj("id" to "r1:e1", "registerId" to "r1", "enrollmentId" to "e1", "text" to "Needs a uniform")),
        "subjects" to listOf(
            obj("id" to "sub1", "name" to "Mathematics", "normalizedName" to "mathematics", "createdAt" to "2026-08-01T00:00:00.000Z", "updatedAt" to "2026-08-01T00:00:00.000Z"),
            obj("id" to "sub2", "name" to "English", "normalizedName" to "english", "archivedAt" to "2026-08-10T00:00:00.000Z", "createdAt" to "2026-08-01T00:00:00.000Z", "updatedAt" to "2026-08-10T00:00:00.000Z"),
        ),
        "tests" to listOf(
            obj(
                "id" to "t1", "name" to "Midterm", "normalizedName" to "midterm", "subjectId" to "sub1", "classGroupId" to "c1",
                "date" to "2026-08-15", "totalMarks" to 40, "createdAt" to "2026-08-15T00:00:00.000Z", "updatedAt" to "2026-08-15T00:00:00.000Z",
            ),
        ),
        "testRoster" to listOf(
            obj("id" to "t1:e1", "testId" to "t1", "enrollmentId" to "e1", "createdAt" to "2026-08-15T00:00:00.000Z"),
            obj("id" to "t1:e2", "testId" to "t1", "enrollmentId" to "e2", "createdAt" to "2026-08-15T00:00:00.000Z"),
        ),
        "testResults" to listOf(
            obj("id" to "t1:e1", "testId" to "t1", "enrollmentId" to "e1", "status" to "marks", "marks" to dec("32.5"), "updatedAt" to "2026-08-16T00:00:00.000Z"),
            // e3 is in another class and was never on this test's roster
            obj("id" to "t1:e3", "testId" to "t1", "enrollmentId" to "e3", "status" to "marks", "marks" to dec("5.07"), "updatedAt" to "2026-08-16T00:00:00.000Z"),
        ),
        "homeworkReports" to listOf(
            obj(
                "id" to "h1", "classGroupId" to "c1", "date" to "2026-08-15", "inchargeName" to "Ms Hina", "parentNote" to "Please sign.",
                "items" to JsonArray(
                    listOf(
                        obj("subjectId" to "sub2", "details" to "Read chapter 2.", "order" to 1),
                        obj("subjectId" to "sub1", "details" to "Exercise 4.", "order" to 0),
                    ),
                ),
                "createdAt" to "2026-08-15T00:00:00.000Z", "updatedAt" to "2026-08-15T01:00:00.000Z",
            ),
            obj(
                "id" to "h2", "classGroupId" to "c1", "date" to "2026-08-16", "inchargeName" to "Ms Hina", "parentNote" to "Please sign the photo.",
                "items" to JsonArray(emptyList()), "photoDataUrl" to "data:image/jpeg;base64,/9j/4AAQ",
                "createdAt" to "2026-08-16T00:00:00.000Z", "updatedAt" to "2026-08-16T00:00:00.000Z",
            ),
        ),
    )

    private suspend fun map(
        tables: Map<String, List<JsonObject>>,
        sink: RecordingSink = RecordingSink(),
        phoneNormalizer: PhoneNormalizer? = null,
    ): MappedBackup {
        val parsed = PwaBackupParser.fromJson(dexie(tables))
        return PwaBackupMapper(clock, phoneNormalizer).map(parsed, sink)
    }

    private fun assertWarning(mapped: MappedBackup, vararg fragments: String) {
        assertTrue(
            "expected a warning containing ${fragments.toList()} in ${mapped.warnings}",
            mapped.warnings.any { line -> fragments.all { it in line } },
        )
    }

    // ---- tests ----------------------------------------------------------------------------------------

    @Test
    fun `maps a complete backup with all 15 tables`() = runTest {
        val sink = RecordingSink()
        val mapped = map(fullTables(), sink)

        // settings and logo
        val settings = mapped.settings!!
        assertEquals("Green Valley", settings.schoolName)
        assertEquals(4, settings.academicYearStartMonth)
        assertEquals("Ms Hina", settings.classInchargeName)
        assertEquals("Hi {student}", settings.absenceMessageTemplate)
        assertEquals(Instant.parse("2026-08-29T00:00:00Z"), settings.updatedAt)
        assertEquals(1, settings.id)

        // photos went through the sink: logo, student photo, homework diary page
        assertEquals(listOf("image/png", "image/jpeg", "image/jpeg"), sink.saved.map { it.second })
        assertEquals("logo", sink.bytes[0].decodeToString())
        assertEquals("test", sink.bytes[1].decodeToString())
        assertEquals("photo-1", settings.logoFile)
        assertEquals("photo-2", mapped.students[0].photoFile)
        assertNull(mapped.students[1].photoFile)

        // ids are sequential Longs with an old-to-new map
        assertEquals(listOf(1L, 2L), mapped.classGroups.map { it.id })
        assertEquals(mapOf("c1" to 1L, "c2" to 2L), mapped.ids.classGroups)
        assertEquals(mapOf("s1" to 1L, "s2" to 2L, "s3" to 3L), mapped.ids.students)
        assertEquals(mapOf("e1" to 1L, "e2" to 2L, "e3" to 3L), mapped.ids.enrollments)
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), mapped.classGroups[0].createdAt)

        // students and enrollments
        val ayesha = mapped.students[0]
        assertEquals("A-001", ayesha.admissionNumber)
        assertEquals("+923001234567", ayesha.phone)
        assertEquals(LocalDate(2014, 5, 12), ayesha.dateOfBirth)
        assertNull(mapped.students[1].dateOfBirth)
        val bilal = mapped.enrollments[1]
        assertEquals(2L, bilal.studentId)
        assertEquals(1L, bilal.classGroupId)
        assertEquals(LocalDate(2026, 8, 20), bilal.struckOffOn)
        assertNull(mapped.enrollments[0].struckOffOn)

        // register, attendance composite keys, holiday
        assertEquals(1, mapped.registers.size)
        assertEquals(2026, mapped.registers[0].year)
        assertEquals(8, mapped.registers[0].month)
        // Two timings merge into one mark per day: e1's P + A becomes P.
        assertEquals(2, mapped.attendance.size)
        assertTrue(mapped.attendance.all { it.session == Session.FIRST })
        val merged = mapped.attendance.single { it.enrollmentId == 1L }
        assertEquals(AttendanceStatus.PRESENT, merged.status)
        assertEquals(3, merged.day)
        assertEquals(AttendanceStatus.LEAVE, mapped.attendance.first { it.enrollmentId == 2L }.status)
        assertEquals("Independence Day", mapped.holidays.single().title)

        // fees, meta, remark
        val fee = mapped.feeEntries.single()
        assertEquals(InstallmentNumber.FIRST, fee.installment)
        assertEquals(10000L, fee.amounts.ftf)
        assertEquals(250L, fee.amounts.dcf)
        assertEquals(10750L, fee.amounts.total)
        assertEquals(50000L, mapped.installmentMeta.single().rate)
        assertEquals("Mr Khan", mapped.installmentMeta.single().receiverName)
        assertEquals("Needs a uniform", mapped.remarks.single().text)

        // subjects and tests: 40 marks is 4000 hundredths
        assertEquals(listOf("Mathematics", "English"), mapped.subjects.map { it.name })
        assertNull(mapped.subjects[0].archivedAt)
        assertEquals(Instant.parse("2026-08-10T00:00:00Z"), mapped.subjects[1].archivedAt)
        val test = mapped.tests.single()
        assertEquals(4000, test.totalMarks)
        assertEquals(LocalDate(2026, 8, 15), test.date)
        assertEquals(1L, test.subjectId)

        // roster and results merged; the e3 result is dropped because e3 is not on the roster
        assertEquals(2, mapped.rosterEntries.size)
        val first = mapped.rosterEntries.first { it.enrollmentId == 1L }
        assertEquals(TestResultStatus.MARKS, first.result)
        assertEquals(3250, first.marks)
        assertEquals(Instant.parse("2026-08-16T00:00:00Z"), first.resultUpdatedAt)
        val notEntered = mapped.rosterEntries.first { it.enrollmentId == 2L }
        assertEquals(TestResultStatus.NOT_ENTERED, notEntered.result)
        assertNull(notEntered.marks)
        assertNull(notEntered.resultUpdatedAt)
        assertWarning(mapped, "testResults", "not on the test's roster")

        // homework: items ordered by `order`, photo-only report
        assertEquals(2, mapped.homeworkReports.size)
        val typed = mapped.homeworkReports[0]
        assertNull(typed.photoFile)
        val typedItems = mapped.homeworkItems.filter { it.reportId == typed.id }.sortedBy { it.position }
        assertEquals(listOf("Exercise 4.", "Read chapter 2."), typedItems.map { it.details })
        assertEquals(listOf(0, 1), typedItems.map { it.position })
        assertEquals(listOf(1L, 2L), typedItems.map { it.subjectId })
        assertEquals(Instant.parse("2026-08-15T01:00:00Z"), typed.updatedAt)
        val photoReport = mapped.homeworkReports[1]
        assertEquals("photo-3", photoReport.photoFile)
        assertTrue(mapped.homeworkItems.none { it.reportId == photoReport.id })
        assertEquals(listOf(1L, 2L), mapped.homeworkItems.map { it.id }.sorted())

        // counts
        assertEquals(1, mapped.counts["settings"])
        assertEquals(2, mapped.counts["attendance"])
        assertEquals(2, mapped.counts["testRoster"])
        assertEquals(1, mapped.counts["testResults"])
        assertEquals(2, mapped.counts["homeworkReports"])
        assertEquals(2, mapped.counts["homeworkItems"])
    }

    @Test
    fun `an older backup without the newer tables and fields still maps`() = runTest {
        val tables = fullTables()
        for (name in listOf("subjects", "tests", "testRoster", "testResults", "homeworkReports")) tables.remove(name)
        tables["settings"] = listOf(
            // pre-"classInchargeName" backup: only headmasterName, no logo, no template
            obj("id" to "school", "schoolName" to "Old School", "academicYearStartMonth" to 9, "currencyLabel" to "PKR", "headmasterName" to "Mr Head", "updatedAt" to "2025-01-01T00:00:00.000Z"),
        )

        val mapped = map(tables)

        assertEquals("Mr Head", mapped.settings!!.classInchargeName)
        assertEquals("PKR", mapped.settings!!.currencyLabel)
        assertNull(mapped.settings!!.absenceMessageTemplate)
        assertNull(mapped.settings!!.logoFile)
        assertTrue(mapped.subjects.isEmpty())
        assertTrue(mapped.tests.isEmpty())
        assertTrue(mapped.rosterEntries.isEmpty())
        assertTrue(mapped.homeworkReports.isEmpty())
        assertEquals(3, mapped.students.size)
        assertEquals(2, mapped.attendance.size)
        assertTrue(mapped.warnings.toString(), mapped.warnings.isEmpty())
    }

    @Test
    fun `classInchargeName wins over headmasterName and falls back only when absent`() = runTest {
        fun settings(vararg extra: Pair<String, Any?>) = mapOf(
            "settings" to listOf(
                obj("id" to "school", "schoolName" to "S", "academicYearStartMonth" to 4, "updatedAt" to "2026-01-01T00:00:00Z", *extra),
            ),
        )
        assertEquals("Incharge", map(settings("classInchargeName" to "Incharge", "headmasterName" to "Head")).settings!!.classInchargeName)
        assertEquals("Head", map(settings("headmasterName" to "Head")).settings!!.classInchargeName)
        assertEquals("", map(settings()).settings!!.classInchargeName)
    }

    @Test
    fun `marks become integer hundredths without floating point drift`() = runTest {
        val tables = linkedMapOf(
            "classGroups" to listOf(obj("id" to "c1", "className" to "7", "section" to "A", "createdAt" to "2026-08-01T00:00:00Z")),
            "students" to listOf(1, 2, 3, 4, 5, 6, 7).map {
                obj("id" to "s$it", "admissionNumber" to "A$it", "name" to "S$it", "phone" to "", "createdAt" to "2026-08-01T00:00:00Z")
            },
            "enrollments" to listOf(1, 2, 3, 4, 5, 6, 7).map {
                obj("id" to "e$it", "studentId" to "s$it", "classGroupId" to "c1", "rollNumber" to "$it", "admittedOn" to "2026-08-01")
            },
            "subjects" to listOf(obj("id" to "sub", "name" to "Math", "normalizedName" to "math", "createdAt" to "2026-08-01T00:00:00Z", "updatedAt" to "2026-08-01T00:00:00Z")),
            "tests" to listOf(
                obj(
                    "id" to "t", "name" to "T", "normalizedName" to "t", "subjectId" to "sub", "classGroupId" to "c1", "date" to "2026-08-15",
                    "totalMarks" to dec("40.5"), "createdAt" to "2026-08-15T00:00:00Z", "updatedAt" to "2026-08-15T00:00:00Z",
                ),
            ),
            "testRoster" to (1..7).map { obj("id" to "t:e$it", "testId" to "t", "enrollmentId" to "e$it", "createdAt" to "2026-08-15T00:00:00Z") },
            "testResults" to listOf("5.07", "32.5", "4.35", "1.005", "0", "0.29", "40.5").mapIndexed { index, value ->
                obj("id" to "t:e${index + 1}", "testId" to "t", "enrollmentId" to "e${index + 1}", "status" to "marks", "marks" to dec(value), "updatedAt" to "2026-08-16T00:00:00Z")
            },
        )

        val mapped = map(tables)

        assertEquals(4050, mapped.tests.single().totalMarks)
        val marks = (1L..7L).map { id -> mapped.rosterEntries.first { it.enrollmentId == id }.marks }
        // 4.35 * 100 is 434.99999999999994 in doubles, 1.005 * 100 is 100.49999999999999: exact decimals avoid both
        assertEquals(listOf(507, 3250, 435, 101, 0, 29, 4050), marks)
        assertTrue(mapped.rosterEntries.all { it.result == TestResultStatus.MARKS })
        assertTrue(mapped.warnings.toString(), mapped.warnings.isEmpty())
    }

    @Test
    fun `absent results, marks above the total, and unknown statuses`() = runTest {
        val tables = fullTables()
        tables["testResults"] = listOf(
            obj("id" to "t1:e1", "testId" to "t1", "enrollmentId" to "e1", "status" to "marks", "marks" to dec("40.01"), "updatedAt" to "2026-08-16T00:00:00Z"),
            obj("id" to "t1:e2", "testId" to "t1", "enrollmentId" to "e2", "status" to "absent", "updatedAt" to "2026-08-16T00:00:00Z"),
        )
        val mapped = map(tables)

        val entry1 = mapped.rosterEntries.first { it.enrollmentId == 1L }
        assertEquals(TestResultStatus.NOT_ENTERED, entry1.result)
        assertNull(entry1.marks)
        val entry2 = mapped.rosterEntries.first { it.enrollmentId == 2L }
        assertEquals(TestResultStatus.ABSENT, entry2.result)
        assertNull(entry2.marks)
        assertWarning(mapped, "testResults", "exceed")
    }

    @Test
    fun `a result for an enrollment that is not on the roster is dropped with a warning`() = runTest {
        val mapped = map(fullTables())

        assertEquals(setOf(1L, 2L), mapped.rosterEntries.map { it.enrollmentId }.toSet())
        assertWarning(mapped, "testResults", "dropped", "not on the test's roster")
    }

    @Test
    fun `homework with several items keeps their order, and a diary photo is stored through the photo sink`() = runTest {
        val sink = RecordingSink()
        val mapped = map(fullTables(), sink)

        val typed = mapped.homeworkReports.first { it.date == LocalDate(2026, 8, 15) }
        val photo = mapped.homeworkReports.first { it.date == LocalDate(2026, 8, 16) }
        assertEquals(2, mapped.homeworkItems.count { it.reportId == typed.id })
        assertNotNull(photo.photoFile)
        assertEquals(3, sink.saved.size)
        assertEquals(byteArrayOf(-1, -40, -1, -32, 0, 16).toList(), sink.bytes.last().toList())
    }

    @Test
    fun `homework items with a missing subject are dropped and an emptied report is skipped`() = runTest {
        val tables = fullTables()
        tables["homeworkReports"] = listOf(
            obj(
                "id" to "h1", "classGroupId" to "c1", "date" to "2026-08-15", "inchargeName" to "X", "parentNote" to "",
                "items" to JsonArray(
                    listOf(
                        obj("subjectId" to "ghost", "details" to "lost", "order" to 0),
                        obj("subjectId" to "sub1", "details" to "kept", "order" to 1),
                    ),
                ),
                "createdAt" to "2026-08-15T00:00:00Z", "updatedAt" to "2026-08-15T00:00:00Z",
            ),
            obj(
                "id" to "h2", "classGroupId" to "c1", "date" to "2026-08-16", "inchargeName" to "X", "parentNote" to "",
                "items" to JsonArray(listOf(obj("subjectId" to "ghost", "details" to "lost", "order" to 0))),
                "createdAt" to "2026-08-16T00:00:00Z", "updatedAt" to "2026-08-16T00:00:00Z",
            ),
        )
        val mapped = map(tables)

        assertEquals(1, mapped.homeworkReports.size)
        assertEquals(listOf("kept"), mapped.homeworkItems.map { it.details })
        assertEquals(listOf(0), mapped.homeworkItems.map { it.position }) // renumbered after the drop
        assertWarning(mapped, "homeworkReports", "subject is missing")
        assertWarning(mapped, "homeworkReports", "no usable homework items")
    }

    @Test
    fun `students with the same B-Form number are both kept and the later one is renamed`() = runTest {
        val tables = fullTables()
        tables["students"] = tables.getValue("students") + obj(
            "id" to "s4", "admissionNumber" to "a-001", "name" to "Dania", "phone" to "", "createdAt" to "2026-08-03T00:00:00Z",
        )
        val mapped = map(tables)

        assertEquals(4, mapped.students.size)
        assertEquals("A-001", mapped.students[0].admissionNumber)
        assertEquals("a-001 (2)", mapped.students[3].admissionNumber)
        assertEquals(4L, mapped.ids.students["s4"])
        assertEquals(4, mapped.students.map { it.admissionNumber.lowercase() }.toSet().size)
        assertWarning(mapped, "students", "B-Form number", "Dania")
    }

    @Test
    fun `duplicate classes, subjects, registers and tests are merged and their children survive`() = runTest {
        val tables = fullTables()
        tables["classGroups"] = tables.getValue("classGroups") + obj("id" to "c1b", "className" to "7", "section" to "a", "createdAt" to "2026-08-05T00:00:00Z")
        tables["registers"] = tables.getValue("registers") + obj("id" to "r1b", "classGroupId" to "c1b", "year" to 2026, "month" to 8, "createdAt" to "2026-08-05T00:00:00Z")
        tables["attendance"] = tables.getValue("attendance") + listOf(
            // same key as an existing mark: dropped; a different day: kept under the merged register
            obj("id" to "r1b:e1:3:1", "registerId" to "r1b", "enrollmentId" to "e1", "day" to 3, "session" to 1, "status" to "A"),
            obj("id" to "r1b:e1:4:1", "registerId" to "r1b", "enrollmentId" to "e1", "day" to 4, "session" to 1, "status" to "P"),
        )
        tables["subjects"] = tables.getValue("subjects") + obj("id" to "sub1b", "name" to "MATHEMATICS ", "normalizedName" to "mathematics", "createdAt" to "2026-08-05T00:00:00Z", "updatedAt" to "2026-08-05T00:00:00Z")
        val mapped = map(tables)

        assertEquals(2, mapped.classGroups.size)
        assertEquals(mapped.ids.classGroups["c1"], mapped.ids.classGroups["c1b"])
        assertEquals(1, mapped.registers.size)
        assertEquals(mapped.ids.registers["r1"], mapped.ids.registers["r1b"])
        assertEquals(3, mapped.attendance.size)
        assertEquals(AttendanceStatus.PRESENT, mapped.attendance.first { it.enrollmentId == 1L && it.day == 3 && it.session == Session.FIRST }.status)
        assertEquals(2, mapped.subjects.size)
        assertEquals(mapped.ids.subjects["sub1"], mapped.ids.subjects["sub1b"])
        assertWarning(mapped, "classGroups", "merged")
        assertWarning(mapped, "attendance", "already exists")
    }

    @Test
    fun `orphans and invalid rows are skipped with warnings instead of failing`() = runTest {
        val tables = fullTables()
        tables["enrollments"] = tables.getValue("enrollments") + listOf(
            obj("id" to "e9", "studentId" to "ghost", "classGroupId" to "c1", "rollNumber" to "9", "admittedOn" to "2026-08-01"),
            obj("id" to "e10", "studentId" to "s1", "classGroupId" to "c1", "rollNumber" to "10", "admittedOn" to "not-a-date"),
        )
        tables["attendance"] = tables.getValue("attendance") + listOf(
            obj("id" to "x1", "registerId" to "r1", "enrollmentId" to "e9", "day" to 3, "session" to 1, "status" to "P"),
            obj("id" to "x2", "registerId" to "r1", "enrollmentId" to "e1", "day" to 31, "session" to 1, "status" to "P"), // August has 31 days
            obj("id" to "x3", "registerId" to "r1", "enrollmentId" to "e1", "day" to 32, "session" to 1, "status" to "P"),
            obj("id" to "x4", "registerId" to "r1", "enrollmentId" to "e1", "day" to 5, "session" to 3, "status" to "P"),
            obj("id" to "x5", "registerId" to "r1", "enrollmentId" to "e1", "day" to 5, "session" to 1, "status" to "Q"),
            obj("id" to "x6", "registerId" to "ghost", "enrollmentId" to "e1", "day" to 5, "session" to 1, "status" to "P"),
        )
        tables["students"] = tables.getValue("students") + obj(
            "id" to "s5", "admissionNumber" to "A-005", "name" to "Esha", "phone" to "", "dateOfBirth" to "2014-02-30", "createdAt" to "yesterday",
        )
        tables["tests"] = tables.getValue("tests") + obj(
            "id" to "t2", "name" to "Bad", "normalizedName" to "bad", "subjectId" to "sub1", "classGroupId" to "c1", "date" to "someday",
            "totalMarks" to 10, "createdAt" to "2026-08-15T00:00:00Z", "updatedAt" to "2026-08-15T00:00:00Z",
        )
        val mapped = map(tables)

        // e9 orphan skipped; e10 kept with a repaired admission date (the day its student was created)
        assertEquals(4, mapped.enrollments.size)
        assertEquals(LocalDate(2026, 8, 1), mapped.enrollments.last().admittedOn)
        assertEquals(3, mapped.attendance.size) // the 2 merged originals + day 31
        assertWarning(mapped, "enrollments", "missing from the backup")
        assertWarning(mapped, "enrollments", "admission date")
        assertWarning(mapped, "attendance", "register or enrollment is missing")
        assertWarning(mapped, "attendance", "invalid")
        // student with bad dob/createdAt is kept
        val esha = mapped.students.first { it.name == "Esha" }
        assertNull(esha.dateOfBirth)
        assertEquals(now, esha.createdAt)
        assertWarning(mapped, "students", "date of birth")
        assertWarning(mapped, "students", "createdAt")
        // bad test date skips the test
        assertEquals(1, mapped.tests.size)
        assertWarning(mapped, "tests", "date")
    }

    @Test
    fun `an invalid photo data URL is dropped with a warning`() = runTest {
        val tables = fullTables()
        tables["students"] = listOf(
            obj("id" to "s1", "admissionNumber" to "A-001", "name" to "Ayesha", "phone" to "", "photoDataUrl" to "data:image/png;base64,@@@", "createdAt" to "2026-08-01T00:00:00Z"),
            obj("id" to "s2", "admissionNumber" to "A-002", "name" to "Bilal", "phone" to "", "photoDataUrl" to "https://example.com/p.png", "createdAt" to "2026-08-01T00:00:00Z"),
        )
        val mapped = map(tables)

        assertTrue(mapped.students.all { it.photoFile == null })
        assertWarning(mapped, "students", "photo", "2 rows")
    }

    @Test
    fun `rows in a table other than a JSON object are skipped by the parser`() = runTest {
        val tables = linkedMapOf<String, List<JsonObject>>("classGroups" to listOf(obj("id" to "c1", "className" to "7", "section" to "A", "createdAt" to "2026-08-01T00:00:00Z"), obj("className" to "no id")))
        val mapped = map(tables)

        assertEquals(1, mapped.classGroups.size)
        assertWarning(mapped, "classGroups", "not a valid record")
    }

    @Test
    fun `phone numbers go through the normalizer and invalid ones are kept as written`() = runTest {
        val normalizer = object : PhoneNormalizer {
            override fun normalize(input: String): String {
                if (input.startsWith("03")) return "+92" + input.drop(1)
                if (input.isBlank()) return ""
                throw ValidationException("bad phone")
            }

            override fun display(value: String) = value
        }
        val tables = fullTables()
        tables["students"] = listOf(
            obj("id" to "s1", "admissionNumber" to "A-001", "name" to "Ayesha", "phone" to "03001234567", "createdAt" to "2026-08-01T00:00:00Z"),
            obj("id" to "s2", "admissionNumber" to "A-002", "name" to "Bilal", "phone" to " abc ", "createdAt" to "2026-08-01T00:00:00Z"),
        )
        val mapped = map(tables, phoneNormalizer = normalizer)

        assertEquals("+923001234567", mapped.students[0].phone)
        assertEquals("abc", mapped.students[1].phone)
        assertWarning(mapped, "students", "phone number")
    }

    // ---- parser --------------------------------------------------------------------------------------

    @Test
    fun `parses the sample backup from a stream, including a UTF-8 byte order mark`() = runTest {
        val bytes = javaClass.getResourceAsStream("/sample-pwa-backup.json")!!.use { it.readBytes() }
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes

        for (data in listOf(bytes, withBom)) {
            val parsed = PwaBackupParser.parse(data.inputStream())
            assertEquals("students-attendance-register", parsed.databaseName)
            assertEquals(2, parsed.rowCounts["students"])
            assertEquals(0, parsed.rowCounts["subjects"])
            assertFalse("subjects" in parsed.presentTables)
            val mapped = PwaBackupMapper(clock).map(parsed, RecordingSink())
            assertEquals("Sample School", mapped.settings!!.schoolName)
            assertEquals("Mr Head", mapped.settings!!.classInchargeName)
            assertEquals(2, mapped.students.size)
            assertEquals(2, mapped.attendance.size)
            assertEquals("photo-1", mapped.students[0].photoFile)
        }
    }

    @Test
    fun `an empty but valid backup parses`() {
        val empty = dexie(PwaTables.ALL.associateWith { emptyList() })
        val parsed = PwaBackupParser.fromJson(empty)

        assertEquals(PwaTables.ALL.toSet(), parsed.presentTables)
        assertTrue(parsed.students.isEmpty())
    }

    @Test
    fun `files that are not Dexie backups are rejected with a clear message`() {
        val notBackups = listOf(
            "not json at all",
            "",
            "[]",
            """{"hello": "world"}""",
            """{"formatName":"other","formatVersion":1,"data":{"data":[]}}""",
            """{"formatName":"dexie","formatVersion":1}""",
            // a real Dexie export of some other database: no table of ours
            """{"formatName":"dexie","formatVersion":1,"data":{"databaseName":"other","databaseVersion":1,"tables":[{"name":"todos","schema":"id","rowCount":-1}],"data":[{"tableName":"todos","inbound":true,"rows":[{"id":1}]}]}}""",
        )
        for (text in notBackups) {
            try {
                PwaBackupParser.parse(text.byteInputStream())
                fail("expected a ValidationException for: $text")
            } catch (e: ValidationException) {
                assertEquals("This file is not an attendance register backup.", e.message)
            }
        }
    }
}

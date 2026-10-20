package com.itexpert120.attendanceManager.data.backup

import com.itexpert120.attendanceManager.domain.ValidationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.math.BigDecimal

/**
 * Reads the JSON that `dexie-export-import`'s `exportDB` writes:
 *
 * ```
 * { "formatName": "dexie", "formatVersion": 1,
 *   "data": { "databaseName": "...", "databaseVersion": 30,
 *             "tables": [ { "name": "students", "schema": "...", "rowCount": -1 } ],
 *             "data":   [ { "tableName": "students", "inbound": true, "rows": [ {...}, ... ] } ] } }
 * ```
 *
 * The file is decoded straight from the stream (no intermediate text copy), and each row's strings are
 * shared with the resulting records, so peak memory is roughly one copy of the file's text rather than
 * two. Tables the app doesn't know are ignored; tables an older backup lacks come back empty. The PWA
 * only ever stores plain JSON values and base64 data-URL strings, so Dexie's special type encoding is not
 * handled.
 */
object PwaBackupParser {
    const val NOT_A_BACKUP = "This file is not an attendance register backup."

    @OptIn(ExperimentalSerializationApi::class)
    fun parse(input: InputStream): ParsedBackup {
        val root: JsonElement = try {
            val stream = BufferedInputStream(input)
            skipUtf8Bom(stream)
            Json.decodeFromStream(JsonElement.serializer(), stream)
        } catch (_: SerializationException) {
            throw ValidationException(NOT_A_BACKUP)
        } catch (_: IllegalArgumentException) {
            throw ValidationException(NOT_A_BACKUP)
        }
        return fromJson(root)
    }

    /** Separate from [parse] so tests can feed an already-built tree. */
    fun fromJson(root: JsonElement): ParsedBackup {
        val file = root as? JsonObject ?: throw ValidationException(NOT_A_BACKUP)
        if (!file.text("formatName").equals("dexie", ignoreCase = true)) throw ValidationException(NOT_A_BACKUP)
        val data = file["data"] as? JsonObject ?: throw ValidationException(NOT_A_BACKUP)
        val tableData = data["data"] as? JsonArray ?: throw ValidationException(NOT_A_BACKUP)

        val rowsByTable = LinkedHashMap<String, List<JsonElement>>()
        for (table in tableData) {
            val obj = table as? JsonObject ?: continue
            val name = obj.text("tableName") ?: continue
            rowsByTable[name] = (obj["rows"] as? JsonArray) ?: emptyList()
        }
        val declared = (data["tables"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name") }
        val present = (rowsByTable.keys + declared).filter { it in PwaTables.ALL }.toSet()
        if (present.isEmpty()) throw ValidationException(NOT_A_BACKUP)

        val log = WarningLog()

        fun <T : Any> read(table: String, build: (JsonObject) -> T?): List<T> {
            val rows = rowsByTable[table] ?: return emptyList()
            val out = ArrayList<T>(rows.size)
            for ((index, row) in rows.withIndex()) {
                val obj = row.asRecord()
                val record = obj?.let(build)
                if (record == null) log.add(table, "skipped, not a valid record", "row ${index + 1}") else out += record
            }
            return out
        }

        val settings = read(PwaTables.SETTINGS) {
            PwaSettings(
                id = it.text("id"),
                schoolName = it.text("schoolName"),
                academicYearStartMonth = it.decimal("academicYearStartMonth"),
                currencyLabel = it.text("currencyLabel"),
                classInchargeName = it.text("classInchargeName"),
                headmasterName = it.text("headmasterName"),
                logoDataUrl = it.text("logoDataUrl"),
                absenceMessageTemplate = it.text("absenceMessageTemplate"),
                updatedAt = it.text("updatedAt"),
            )
        }
        val classGroups = read(PwaTables.CLASS_GROUPS) {
            PwaClassGroup(it.requiredId() ?: return@read null, it.text("className"), it.text("section"), it.text("createdAt"))
        }
        val students = read(PwaTables.STUDENTS) {
            PwaStudent(
                id = it.requiredId() ?: return@read null,
                admissionNumber = it.text("admissionNumber"),
                name = it.text("name"),
                phone = it.text("phone"),
                dateOfBirth = it.text("dateOfBirth"),
                photoDataUrl = it.text("photoDataUrl"),
                createdAt = it.text("createdAt"),
            )
        }
        val subjects = read(PwaTables.SUBJECTS) {
            PwaSubject(
                id = it.requiredId() ?: return@read null,
                name = it.text("name"),
                normalizedName = it.text("normalizedName"),
                archivedAt = it.text("archivedAt"),
                createdAt = it.text("createdAt"),
                updatedAt = it.text("updatedAt"),
            )
        }
        val enrollments = read(PwaTables.ENROLLMENTS) {
            PwaEnrollment(
                id = it.requiredId() ?: return@read null,
                studentId = it.text("studentId"),
                classGroupId = it.text("classGroupId"),
                rollNumber = it.text("rollNumber"),
                admittedOn = it.text("admittedOn"),
                struckOffOn = it.text("struckOffOn"),
            )
        }
        val registers = read(PwaTables.REGISTERS) {
            PwaRegister(
                id = it.requiredId() ?: return@read null,
                classGroupId = it.text("classGroupId"),
                year = it.decimal("year"),
                month = it.decimal("month"),
                createdAt = it.text("createdAt"),
            )
        }
        val attendance = read(PwaTables.ATTENDANCE) {
            PwaAttendanceMark(
                id = it.text("id"),
                registerId = it.text("registerId"),
                enrollmentId = it.text("enrollmentId"),
                day = it.decimal("day"),
                session = it.decimal("session"),
                status = it.text("status"),
            )
        }
        val holidays = read(PwaTables.HOLIDAYS) {
            PwaHoliday(it.text("id"), it.text("registerId"), it.decimal("day"), it.text("title"))
        }
        val feeEntries = read(PwaTables.FEE_ENTRIES) {
            PwaFeeEntry(
                id = it.text("id"),
                registerId = it.text("registerId"),
                enrollmentId = it.text("enrollmentId"),
                installment = it.decimal("installment"),
                ftf = it.decimal("ftf"),
                ff = it.decimal("ff"),
                arrears = it.decimal("arrears"),
                lateCertificate = it.decimal("lateCertificate"),
                slc = it.decimal("slc"),
                dcf = it.decimal("dcf"),
            )
        }
        val installmentMeta = read(PwaTables.INSTALLMENT_META) {
            PwaInstallmentMeta(
                it.text("id"), it.text("registerId"), it.decimal("installment"), it.decimal("rate"), it.text("receiverName"),
            )
        }
        val remarks = read(PwaTables.REMARKS) {
            PwaRemark(it.text("id"), it.text("registerId"), it.text("enrollmentId"), it.text("text"))
        }
        val tests = read(PwaTables.TESTS) {
            PwaTest(
                id = it.requiredId() ?: return@read null,
                name = it.text("name"),
                normalizedName = it.text("normalizedName"),
                subjectId = it.text("subjectId"),
                classGroupId = it.text("classGroupId"),
                date = it.text("date"),
                totalMarks = it.decimal("totalMarks"),
                createdAt = it.text("createdAt"),
                updatedAt = it.text("updatedAt"),
            )
        }
        val testRoster = read(PwaTables.TEST_ROSTER) {
            PwaRosterEntry(it.text("id"), it.text("testId"), it.text("enrollmentId"), it.text("createdAt"))
        }
        val testResults = read(PwaTables.TEST_RESULTS) {
            PwaTestResult(
                it.text("id"), it.text("testId"), it.text("enrollmentId"), it.text("status"), it.decimal("marks"), it.text("updatedAt"),
            )
        }
        val homework = read(PwaTables.HOMEWORK_REPORTS) { report ->
            PwaHomeworkReport(
                id = report.requiredId() ?: return@read null,
                classGroupId = report.text("classGroupId"),
                date = report.text("date"),
                inchargeName = report.text("inchargeName"),
                parentNote = report.text("parentNote"),
                items = (report["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                    (item as? JsonObject)?.let {
                        PwaHomeworkItem(it.text("subjectId"), it.text("details"), it.decimal("order"))
                    }
                },
                photoDataUrl = report.text("photoDataUrl"),
                createdAt = report.text("createdAt"),
                updatedAt = report.text("updatedAt"),
            )
        }

        val counts = PwaTables.ALL.associateWith { table -> rowsByTable[table]?.size ?: 0 }
        return ParsedBackup(
            databaseName = data.text("databaseName"),
            databaseVersion = data.decimal("databaseVersion")?.let { runCatching { it.intValueExact() }.getOrNull() },
            presentTables = present,
            rowCounts = counts,
            settings = settings,
            classGroups = classGroups,
            students = students,
            enrollments = enrollments,
            registers = registers,
            attendance = attendance,
            holidays = holidays,
            feeEntries = feeEntries,
            installmentMeta = installmentMeta,
            remarks = remarks,
            subjects = subjects,
            tests = tests,
            testRoster = testRoster,
            testResults = testResults,
            homeworkReports = homework,
            warnings = log.toList(),
        )
    }

    /** Dexie writes outbound-key tables as `[key, value]` pairs; the PWA's tables are all inbound objects. */
    private fun JsonElement.asRecord(): JsonObject? = when (this) {
        is JsonObject -> this
        is JsonArray -> if (size == 2) this[1] as? JsonObject else null
        else -> null
    }

    private fun JsonObject.requiredId(): String? = text("id")?.takeIf { it.isNotBlank() }

    /** A string or number as text; null for missing, JSON null, objects and arrays. */
    private fun JsonObject.text(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return if (value is JsonNull) null else value.content
    }

    private fun JsonObject.decimal(key: String): BigDecimal? = text(key)?.trim()?.toBigDecimalOrNull()

    private fun skipUtf8Bom(stream: BufferedInputStream) {
        stream.mark(3)
        val bom = ByteArray(3)
        val read = stream.read(bom)
        if (read != 3 || bom[0] != 0xEF.toByte() || bom[1] != 0xBB.toByte() || bom[2] != 0xBF.toByte()) {
            stream.reset()
        }
    }
}

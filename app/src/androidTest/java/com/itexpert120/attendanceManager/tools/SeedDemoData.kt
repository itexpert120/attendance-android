package com.itexpert120.attendanceManager.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itexpert120.attendanceManager.AttendanceApplication
import com.itexpert120.attendanceManager.data.db.exec
import com.itexpert120.attendanceManager.data.repo.HomeworkItemInput
import com.itexpert120.attendanceManager.data.repo.HomeworkReportInput
import com.itexpert120.attendanceManager.data.repo.InstallmentFees
import com.itexpert120.attendanceManager.data.repo.StudentInput
import com.itexpert120.attendanceManager.data.repo.TestInput
import com.itexpert120.attendanceManager.data.repo.TestResultInput
import com.itexpert120.attendanceManager.domain.daysInMonth
import com.itexpert120.attendanceManager.domain.isWeekend
import com.itexpert120.attendanceManager.model.AttendanceMark
import com.itexpert120.attendanceManager.model.AttendanceStatus
import com.itexpert120.attendanceManager.model.FeeAmounts
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.model.Session
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlin.time.Clock

/**
 * NOT a real test: fills the installed app's database with a realistic school so screens can be checked by
 * eye. It replaces whatever is there. Run it without uninstalling the app so the data persists:
 *
 *   gradlew installDebug installDebugAndroidTest
 *   adb shell am instrument -w -e class com.itexpert120.attendanceManager.tools.SeedDemoData \
 *       com.itexpert120.attendanceManager.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SeedDemoData {
    private val firstNames = listOf(
        "Ayaan", "Hamza", "Zainab", "Fatima", "Ali", "Maryam", "Usman", "Hira", "Bilal", "Sana", "Omar", "Iqra",
        "Danish", "Noor", "Saad", "Areeba", "Talha", "Mahnoor", "Hassan", "Laiba", "Rayyan", "Eman", "Zain", "Anaya",
    )
    private val lastNames = listOf("Khan", "Ahmed", "Malik", "Sheikh", "Raza", "Butt", "Qureshi", "Siddiqui")

    @Test
    fun seed(): Unit = runBlocking {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AttendanceApplication).container
        val db = container.database
        val random = Random(42)
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())

        // Wipe: deleting classes cascades to enrollments, registers, tests and homework.
        db.exec("DELETE FROM class_groups")
        db.exec("DELETE FROM students")
        db.exec("DELETE FROM subjects")
        db.exec("DELETE FROM school_settings")

        val classA = container.settings.completeSetup(
            schoolName = "Crescent Public School",
            className = "7",
            section = "A",
            academicYearStartMonth = 4,
            currencyLabel = "Rs.",
            classInchargeName = "Ms. Sana Ali",
        )
        container.classes.addClass("7", "B")
        container.classes.addClass("8", "A")

        val academicStart = LocalDate(if (today.month.number >= 4) today.year else today.year - 1, 4, 1)
        val enrollmentIds = mutableListOf<Long>()
        val admittedOn = mutableMapOf<Long, LocalDate>()
        val struckOffOn = mutableMapOf<Long, LocalDate>()
        firstNames.forEachIndexed { index, first ->
            val last = lastNames[index % lastNames.size]
            val admitted = if (index == 20) LocalDate(today.year, today.month.number, 1) else academicStart
            val leftOn = if (index == 22) LocalDate.fromEpochDays(LocalDate(today.year, today.month.number, 1).toEpochDays() - 5) else null
            val saved = container.students.saveStudent(
                StudentInput(
                    classGroupId = classA.id,
                    admissionNumber = "35202-" + (1001 + index).toString().padStart(7, '0') + "-1",
                    rollNumber = "${index + 1}",
                    name = "$first $last",
                    phone = if (index % 7 == 6) "" else "+92300123${(4000 + index).toString().padStart(4, '0')}",
                    admittedOn = admitted,
                    // One student left last month, to exercise struck-off handling.
                    struckOffOn = leftOn,
                ),
            )
            enrollmentIds += saved.enrollmentId
            admittedOn[saved.enrollmentId] = admitted
            leftOn?.let { struckOffOn[saved.enrollmentId] = it }
        }

        // Three registers: this month and the two before it, with believable attendance.
        val months = (2 downTo 0).map { back ->
            val total = today.year * 12 + (today.month.number - 1) - back
            total / 12 to total % 12 + 1
        }
        for ((year, month) in months) {
            val register = container.registers.createRegister(classA.id, year, month)
            val marks = mutableListOf<AttendanceMark>()
            for (day in 1..daysInMonth(year, month)) {
                val date = LocalDate(year, month, day)
                if (date > today || isWeekend(date)) continue
                enrollmentIds.forEach { id ->
                    if (admittedOn.getValue(id) > date) return@forEach
                    if (struckOffOn[id]?.let { it < date } == true) return@forEach
                    // Attendance is taken once a day.
                    val roll = random.nextInt(100)
                    val status = when {
                        roll < 90 -> AttendanceStatus.PRESENT
                        roll < 96 -> AttendanceStatus.ABSENT
                        else -> AttendanceStatus.LEAVE
                    }
                    marks += AttendanceMark(register.id, id, day, Session.FIRST, status)
                }
            }
            db.attendance().upsertAll(marks)

            if (month == today.month.number) {
                enrollmentIds.take(14).forEachIndexed { index, id ->
                    container.registers.saveFeeEntries(
                        register.id,
                        id,
                        listOf(
                            InstallmentFees(InstallmentNumber.FIRST, FeeAmounts(ftf = 150_000, ff = if (index % 4 == 0) 5_000 else 0)),
                            InstallmentFees(InstallmentNumber.SECOND, FeeAmounts(ftf = if (index < 8) 150_000 else 0, arrears = if (index == 3) 40_000 else 0)),
                        ),
                    )
                }
                container.registers.saveInstallmentMeta(register.id, InstallmentNumber.FIRST, 150_000, "Mr. Tariq")
            }
        }

        // Subjects, two Tests with results, and two homework diaries.
        val maths = container.subjects.saveSubject("Mathematics")
        val english = container.subjects.saveSubject("English")
        val science = container.subjects.saveSubject("Science")
        container.subjects.saveSubject("Urdu")

        val unitTest = container.tests.createTest(
            TestInput("Unit 1 Test", maths.id, classA.id, LocalDate.fromEpochDays(today.toEpochDays() - 12), 4_000),
        )
        val quiz = container.tests.createTest(
            TestInput("Spelling Quiz", english.id, classA.id, LocalDate.fromEpochDays(today.toEpochDays() - 4), 2_000),
        )
        container.tests.createTest(
            TestInput("Chapter 3 Test", science.id, classA.id, today, 2_500),
        )
        val unitRoster = db.tests().getRoster(unitTest.id).map { it.enrollmentId }.toSet()
        val quizRoster = db.tests().getRoster(quiz.id).map { it.enrollmentId }.toSet()
        enrollmentIds.forEachIndexed { index, id ->
            if (id !in unitRoster) return@forEachIndexed
            if (index == 9) {
                container.tests.setTestResult(unitTest.id, id, TestResultInput.Absent)
            } else {
                container.tests.setTestResult(unitTest.id, id, TestResultInput.Marks(random.nextInt(1800, 4001) / 25 * 25))
            }
            if (index < 14 && id in quizRoster) container.tests.setTestResult(quiz.id, id, TestResultInput.Marks(random.nextInt(800, 2001) / 25 * 25))
        }

        container.homework.createReport(
            HomeworkReportInput(
                classGroupId = classA.id,
                date = today,
                inchargeName = "Ms. Sana Ali",
                parentNote = "Please check the diary and sign every day.",
                items = listOf(
                    HomeworkItemInput(maths.id, "Exercise 4.2, questions 1 to 6."),
                    HomeworkItemInput(english.id, "Learn spellings of lesson 5 and write each three times."),
                ),
            ),
        )
        container.homework.createReport(
            HomeworkReportInput(
                classGroupId = classA.id,
                date = LocalDate.fromEpochDays(today.toEpochDays() - 1),
                inchargeName = "Ms. Sana Ali",
                parentNote = "Bring the completed notebook tomorrow.",
                items = listOf(HomeworkItemInput(science.id, "Draw and label the parts of a plant.")),
            ),
        )
    }
}

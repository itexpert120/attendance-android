package com.itexpert120.attendanceManager.ui.features.homework

import com.itexpert120.attendanceManager.data.repo.DiaryPhotoInput
import com.itexpert120.attendanceManager.data.repo.HomeworkItemInput
import com.itexpert120.attendanceManager.data.repo.HomeworkReportInput
import com.itexpert120.attendanceManager.data.repo.HomeworkReportWithItems
import com.itexpert120.attendanceManager.domain.ValidationException
import com.itexpert120.attendanceManager.domain.longDateLabel
import com.itexpert120.attendanceManager.domain.monthName
import com.itexpert120.attendanceManager.domain.weekdayName
import com.itexpert120.attendanceManager.model.ClassGroup
import com.itexpert120.attendanceManager.model.Subject
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** The note printed under the homework when the teacher doesn't write their own (the PWA's default). */
const val DEFAULT_PARENT_NOTE = "Please check that the homework has been completed and sign the diary."

fun homeworkToday(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/** "Class 7 · A" */
fun homeworkClassLabel(group: ClassGroup?): String =
    group?.let { "Class ${it.className} · ${it.section}" } ?: "Class"

// ---- Commands -----------------------------------------------------------------------------------

/** The outcome of a repository command: its value, or the message to show inline. */
sealed interface HomeworkResult<out T> {
    data class Ok<T>(val value: T) : HomeworkResult<T>

    data class Failed(val message: String) : HomeworkResult<Nothing>
}

/** Turns a [ValidationException] into its own message, and anything unexpected into [fallback]. */
suspend fun <T> homeworkAttempt(fallback: String, block: suspend () -> T): HomeworkResult<T> = try {
    HomeworkResult.Ok(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (invalid: ValidationException) {
    HomeworkResult.Failed(invalid.message ?: fallback)
} catch (_: Exception) {
    HomeworkResult.Failed(fallback)
}

// ---- The list of saved reports ---------------------------------------------------------------------

/** One saved report as the Homework tab shows it. */
data class HomeworkCardItem(
    val id: Long,
    val classGroupId: Long,
    val date: LocalDate,
    val classLabel: String,
    /** "Mathematics · Urdu · English", or "Photographed diary". */
    val preview: String,
    val subjectCount: Int,
    val photoFile: String?,
    val inchargeName: String,
) {
    val photoCount: Int get() = if (photoFile != null) 1 else 0
}

/** The reports of one date, under a date header. */
data class HomeworkDateGroup(val date: LocalDate, val label: String, val reports: List<HomeworkCardItem>)

/** "Today", "Yesterday", "Monday, October 5" this year, otherwise "Monday, October 5, 2025". */
fun homeworkDateHeader(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date == today.minus(DatePeriod(days = 1)) -> "Yesterday"
    date.year == today.year -> "${weekdayName(date)}, ${monthName(date.month.number)} ${date.day}"
    else -> longDateLabel(date)
}

fun pluralSubjects(count: Int): String = if (count == 1) "1 subject" else "$count subjects"

fun pluralReports(count: Int): String = if (count == 1) "1 report" else "$count reports"

fun homeworkCardItem(
    report: HomeworkReportWithItems,
    group: ClassGroup?,
    subjectNames: Map<Long, String>,
): HomeworkCardItem {
    val photo = report.report.photoFile
    return HomeworkCardItem(
        id = report.id,
        classGroupId = report.report.classGroupId,
        date = report.report.date,
        classLabel = homeworkClassLabel(group),
        preview = if (photo != null) {
            "Photographed diary"
        } else {
            report.items.joinToString(" · ") { subjectNames[it.subjectId] ?: "Subject" }
        },
        subjectCount = report.items.size,
        photoFile = photo,
        inchargeName = report.report.inchargeName,
    )
}

/**
 * The reports of [classFilter] (all when null) on [dateFilter] (any date when null), grouped by date newest
 * first; within a date, classes in the order of [classes] (already natural-sorted by the repository).
 */
fun buildHomeworkGroups(
    reports: List<HomeworkReportWithItems>,
    classes: List<ClassGroup>,
    subjects: List<Subject>,
    classFilter: Long?,
    today: LocalDate,
    dateFilter: LocalDate? = null,
): List<HomeworkDateGroup> {
    val groups = classes.associateBy { it.id }
    val classOrder = classes.withIndex().associate { (index, group) -> group.id to index }
    val subjectNames = subjects.associate { it.id to it.name }
    return reports
        .asSequence()
        .filter { classFilter == null || it.report.classGroupId == classFilter }
        .filter { dateFilter == null || it.report.date == dateFilter }
        .sortedWith(
            compareByDescending<HomeworkReportWithItems> { it.report.date }
                .thenBy { classOrder[it.report.classGroupId] ?: Int.MAX_VALUE }
                .thenBy { it.id },
        )
        .map { homeworkCardItem(it, groups[it.report.classGroupId], subjectNames) }
        .groupBy { it.date }
        .map { (date, items) -> HomeworkDateGroup(date, homeworkDateHeader(date, today), items) }
}

/** The reports on [date], or all of them when it is null (the Homework tab's date filter). */
fun reportsOnDate(reports: List<HomeworkReportWithItems>, date: LocalDate?): List<HomeworkReportWithItems> =
    if (date == null) reports else reports.filter { it.report.date == date }

// ---- Subjects -------------------------------------------------------------------------------------

/** How many reports have homework for each Subject (a report counts once per Subject), for the Subjects manager. */
fun homeworkReportCountBySubject(reports: List<HomeworkReportWithItems>): Map<Long, Int> {
    val counts = HashMap<Long, Int>()
    reports.forEach { report ->
        report.items.mapTo(HashSet()) { it.subjectId }.forEach { id -> counts[id] = (counts[id] ?: 0) + 1 }
    }
    return counts
}

/** "Active · 2 homework reports", as the web app's Subjects manager. */
fun homeworkSubjectUsage(subject: Subject, reportCount: Int): String =
    "${if (subject.isArchived) "Archived" else "Active"} · $reportCount homework report${if (reportCount == 1) "" else "s"}"

/** True when a typed homework row of [draft] uses [subjectId]; such a Subject can't be archived while editing. */
fun HomeworkDraft.usesSubject(subjectId: Long): Boolean = items.any { it.subjectId == subjectId }

/** The selected class filter, or null (all) when it points at a deleted class or there is only one class. */
fun effectiveClassFilter(selected: Long?, classes: List<ClassGroup>): Long? =
    selected?.takeIf { id -> classes.size > 1 && classes.any { it.id == id } }

// ---- The editor's draft ---------------------------------------------------------------------------

@Serializable
enum class HomeworkEntryMode { TYPED, PHOTO }

/** One homework card of the editor; [key] is stable for the lifetime of the draft (list keys, Undo). */
@Serializable
data class HomeworkDraftItem(val key: Long, val subjectId: Long?, val details: String)

/**
 * Everything the editor form holds except the photo bytes. Both the typed items and the photo are kept while
 * the teacher switches between the two modes; only the selected [mode] is submitted.
 */
@Serializable
data class HomeworkDraft(
    val classGroupId: Long?,
    val date: LocalDate?,
    val inchargeName: String,
    val parentNote: String,
    val mode: HomeworkEntryMode,
    val items: List<HomeworkDraftItem>,
    val nextKey: Long,
) {
    /** The first active Subject not yet on this draft, for a new item. */
    fun firstUnusedSubjectId(activeSubjects: List<Subject>): Long? {
        val used = items.mapNotNullTo(HashSet()) { it.subjectId }
        return activeSubjects.firstOrNull { it.id !in used }?.id
    }

    fun withItemAdded(subjectId: Long?): HomeworkDraft =
        copy(items = items + HomeworkDraftItem(nextKey, subjectId, ""), nextKey = nextKey + 1)

    fun withItem(key: Long, transform: (HomeworkDraftItem) -> HomeworkDraftItem): HomeworkDraft =
        copy(items = items.map { if (it.key == key) transform(it) else it })

    fun withoutItem(key: Long): HomeworkDraft = copy(items = items.filterNot { it.key == key })

    /** Puts a removed item back where it was (Undo). */
    fun withItemRestored(item: HomeworkDraftItem, index: Int): HomeworkDraft {
        if (items.any { it.key == item.key }) return this
        val list = items.toMutableList()
        list.add(index.coerceIn(0, list.size), item)
        return copy(items = list)
    }

    /** Moves an item one place up ([direction] -1) or down (+1); out-of-range moves do nothing. */
    fun withItemMoved(key: Long, direction: Int): HomeworkDraft {
        val index = items.indexOfFirst { it.key == key }
        val target = index + direction
        if (index < 0 || target !in items.indices) return this
        val list = items.toMutableList()
        list[index] = items[target]
        list[target] = items[index]
        return copy(items = list)
    }

    /** What the repository gets; [photo] is only sent in photo mode, the items only in typed mode. */
    fun toInput(photo: DiaryPhotoInput?): HomeworkReportInput =
        HomeworkReportInput(
            classGroupId = classGroupId,
            date = date,
            inchargeName = inchargeName,
            parentNote = parentNote,
            items = if (mode == HomeworkEntryMode.TYPED) items.map { HomeworkItemInput(it.subjectId, it.details) } else emptyList(),
            photo = if (mode == HomeworkEntryMode.PHOTO) photo else null,
        )
}

/** A blank report: the first class, today, the school's class incharge and one item for the first Subject. */
fun newHomeworkDraft(
    classes: List<ClassGroup>,
    activeSubjects: List<Subject>,
    inchargeName: String,
    today: LocalDate,
    preferredClassId: Long? = null,
): HomeworkDraft {
    val classId = preferredClassId?.takeIf { id -> classes.any { it.id == id } } ?: classes.firstOrNull()?.id
    val first = activeSubjects.firstOrNull()?.id
    return HomeworkDraft(
        classGroupId = classId,
        date = today,
        inchargeName = inchargeName,
        parentNote = DEFAULT_PARENT_NOTE,
        mode = HomeworkEntryMode.TYPED,
        items = if (first != null) listOf(HomeworkDraftItem(1, first, "")) else emptyList(),
        nextKey = 2,
    )
}

fun homeworkDraftFrom(report: HomeworkReportWithItems): HomeworkDraft {
    val items = report.items.sortedBy { it.position }.mapIndexed { index, item ->
        HomeworkDraftItem(index + 1L, item.subjectId, item.details)
    }
    return HomeworkDraft(
        classGroupId = report.report.classGroupId,
        date = report.report.date,
        inchargeName = report.report.inchargeName,
        parentNote = report.report.parentNote,
        mode = if (report.report.photoFile != null) HomeworkEntryMode.PHOTO else HomeworkEntryMode.TYPED,
        items = items,
        nextKey = items.size + 1L,
    )
}

/** A Subject offered for one item; a Subject already used by another item is shown but can't be chosen. */
data class SubjectChoice(val subject: Subject, val usedElsewhere: Boolean)

/** Active Subjects, plus the item's own archived Subject so an old report keeps rendering and saving. */
fun subjectChoicesFor(item: HomeworkDraftItem, draft: HomeworkDraft, subjects: List<Subject>): List<SubjectChoice> {
    val usedByOthers = draft.items.filter { it.key != item.key }.mapNotNullTo(HashSet()) { it.subjectId }
    return subjects
        .filter { !it.isArchived || it.id == item.subjectId }
        .map { SubjectChoice(it, usedElsewhere = it.id in usedByOthers) }
}

// ---- Inline validation ------------------------------------------------------------------------------

/** Where a repository message belongs in the form. */
enum class HomeworkErrorTarget { CLASS, DATE, INCHARGE, NOTE, ITEMS, PHOTO, GENERAL }

data class HomeworkFormError(val message: String, val target: HomeworkErrorTarget)

/** The per-field highlights, recomputed from the draft as the teacher fixes things. */
data class HomeworkFieldErrors(
    val classMissing: Boolean = false,
    val dateMissing: Boolean = false,
    val inchargeMissing: Boolean = false,
    val noteMissing: Boolean = false,
    /** Messages for an item's subject field, by item key. */
    val itemSubject: Map<Long, String> = emptyMap(),
    /** Messages for an item's homework text, by item key. */
    val itemDetails: Map<Long, String> = emptyMap(),
)

/** Mirrors the repository's rules field by field, so the form can mark exactly what to fix. */
fun homeworkFieldErrors(draft: HomeworkDraft): HomeworkFieldErrors {
    val itemSubject = HashMap<Long, String>()
    val itemDetails = HashMap<Long, String>()
    if (draft.mode == HomeworkEntryMode.TYPED) {
        val counts = draft.items.mapNotNull { it.subjectId }.groupingBy { it }.eachCount()
        draft.items.forEach { item ->
            when {
                item.subjectId == null -> itemSubject[item.key] = "Choose a subject"
                (counts[item.subjectId] ?: 0) > 1 -> itemSubject[item.key] = "This subject is already on the report"
            }
            if (item.details.isBlank()) itemDetails[item.key] = "Write the homework"
        }
    }
    return HomeworkFieldErrors(
        classMissing = draft.classGroupId == null,
        dateMissing = draft.date == null,
        inchargeMissing = draft.inchargeName.isBlank(),
        noteMissing = draft.parentNote.isBlank(),
        itemSubject = itemSubject,
        itemDetails = itemDetails,
    )
}

/** Places a repository [message] next to the field it is about. */
fun homeworkErrorTarget(message: String, draft: HomeworkDraft): HomeworkErrorTarget = when {
    message.startsWith("Class Group, date, incharge") -> when {
        draft.classGroupId == null -> HomeworkErrorTarget.CLASS
        draft.date == null -> HomeworkErrorTarget.DATE
        draft.inchargeName.isBlank() -> HomeworkErrorTarget.INCHARGE
        draft.parentNote.isBlank() -> HomeworkErrorTarget.NOTE
        else -> HomeworkErrorTarget.GENERAL
    }
    message == "Select a Class Group." -> HomeworkErrorTarget.CLASS
    message.startsWith("A Daily Homework Report already exists") -> HomeworkErrorTarget.DATE
    message.startsWith("Add at least one Homework Item") ->
        if (draft.mode == HomeworkEntryMode.PHOTO) HomeworkErrorTarget.PHOTO else HomeworkErrorTarget.ITEMS
    message.startsWith("Use either a diary photo") ||
        message.startsWith("Every Homework Item") ||
        message.startsWith("Each Subject") ||
        message.startsWith("Select an active Subject") -> HomeworkErrorTarget.ITEMS
    else -> HomeworkErrorTarget.GENERAL
}

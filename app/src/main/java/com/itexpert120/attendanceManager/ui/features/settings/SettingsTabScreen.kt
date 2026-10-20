package com.itexpert120.attendanceManager.ui.features.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.domain.monthName
import com.itexpert120.attendanceManager.ui.components.AppScreen
import com.itexpert120.attendanceManager.ui.components.ListGroup
import com.itexpert120.attendanceManager.ui.components.ListRow
import com.itexpert120.attendanceManager.ui.components.PageList
import com.itexpert120.attendanceManager.ui.components.SegmentedChoice
import com.itexpert120.attendanceManager.ui.components.StoredPhoto
import com.itexpert120.attendanceManager.ui.navigation.SettingsTab
import com.itexpert120.attendanceManager.ui.navigation.TabReselectEffect
import com.itexpert120.attendanceManager.ui.theme.ThemePreference

private enum class SettingsSheet { NONE, CLASSES, SUBJECTS }

/** The Settings tab: the school, its classes and subjects, appearance, backup and the app version. */
@Composable
fun SettingsTabScreen(onOpenSchoolSettings: () -> Unit, onOpenStudents: () -> Unit = {}) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        SettingsViewModel(container.settings, container.classes, container.students, container.subjects, container.themePreference)
    }
    val data by viewModel.data.collectAsStateWithLifecycle()
    val theme by viewModel.themePreference.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val backup = rememberBackupActions(snackbarHostState)
    val autoBackup = rememberAutoBackupUi()
    var sheet by rememberSaveable { mutableStateOf(SettingsSheet.NONE) }
    val listState = rememberLazyListState()
    TabReselectEffect(SettingsTab) { listState.animateScrollToItem(0) }

    AppScreen(
        title = "Settings",
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        SettingsContent(
            padding = padding,
            listState = listState,
            data = data,
            theme = theme,
            dynamicColor = dynamicColor,
            onThemeChange = viewModel::setTheme,
            onDynamicColorChange = viewModel::setDynamicColor,
            onOpenSchoolSettings = onOpenSchoolSettings,
            onOpenStudents = onOpenStudents,
            onOpenClasses = { sheet = SettingsSheet.CLASSES },
            onOpenSubjects = { sheet = SettingsSheet.SUBJECTS },
            backup = backup,
            autoBackup = autoBackup,
        )
    }

    when (sheet) {
        SettingsSheet.CLASSES -> ClassesSheet(
            classes = data?.classes.orEmpty(),
            actions = viewModel,
            onDismiss = { sheet = SettingsSheet.NONE },
        )
        SettingsSheet.SUBJECTS -> SubjectsSheet(
            subjects = data?.subjects.orEmpty(),
            actions = viewModel,
            onDismiss = { sheet = SettingsSheet.NONE },
        )
        SettingsSheet.NONE -> Unit
    }
}

@Composable
private fun SettingsContent(
    padding: androidx.compose.foundation.layout.PaddingValues,
    listState: LazyListState,
    data: SettingsTabData?,
    theme: ThemePreference,
    dynamicColor: Boolean,
    onThemeChange: (ThemePreference) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onOpenSchoolSettings: () -> Unit,
    onOpenStudents: () -> Unit,
    onOpenClasses: () -> Unit,
    onOpenSubjects: () -> Unit,
    backup: BackupActions,
    autoBackup: AutoBackupUi,
) {
    val school = data?.school
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val version = remember(context) { appVersion(context) }

    PageList(padding, state = listState) {
        item(key = "school") {
            ListGroup {
                item {
                    ListRow(
                        label = school?.schoolName ?: "School",
                        detail = "Name, logo and absence message",
                        onClick = onOpenSchoolSettings,
                        leading = { SchoolLogo(school?.logoFile, school?.schoolName) },
                        trailing = {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }

        item(key = "classroom") {
            ListGroup(header = "School") {
                item {
                    ListRow(
                        label = "Students",
                        icon = Icons.Rounded.Person,
                        value = data?.studentCount?.toString(),
                        chevron = true,
                        onClick = onOpenStudents,
                    )
                }
                item {
                    ListRow(
                        label = "Classes",
                        icon = Icons.Rounded.Groups,
                        value = data?.classes?.size?.toString(),
                        chevron = true,
                        onClick = onOpenClasses,
                    )
                }
                item {
                    ListRow(
                        label = "Subjects",
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                        value = data?.let { "${it.activeSubjectCount}" },
                        chevron = true,
                        onClick = onOpenSubjects,
                    )
                }
                item {
                    ListRow(
                        label = "Class incharge",
                        icon = Icons.Rounded.Badge,
                        value = school?.classInchargeName?.takeIf { it.isNotBlank() } ?: "Not set",
                        onClick = onOpenSchoolSettings,
                    )
                }
                item {
                    ListRow(
                        label = "Academic year starts",
                        icon = Icons.Rounded.CalendarMonth,
                        value = school?.academicYearStartMonth?.takeIf { it in 1..12 }?.let(::monthName),
                        onClick = onOpenSchoolSettings,
                    )
                }
            }
        }

        item(key = "appearance") {
            ListGroup(header = "Appearance") {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Theme", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "System follows your phone's dark theme setting.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        SegmentedChoice(
                            options = ThemePreference.entries,
                            selected = theme,
                            optionLabel = ::themeLabel,
                            onSelect = {
                                if (it != theme) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onThemeChange(it)
                            },
                        )
                    }
                }
                // Material You colours come from the wallpaper, which only Android 12 and later provide.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    item {
                        ListRow(
                            label = "Dynamic colour",
                            detail = "Use colours from your wallpaper",
                            icon = Icons.Rounded.Palette,
                            modifier = Modifier.toggleable(
                                value = dynamicColor,
                                role = Role.Switch,
                                onValueChange = { enabled ->
                                    haptics.performHapticFeedback(
                                        if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                                    )
                                    onDynamicColorChange(enabled)
                                },
                            ),
                            trailing = { Switch(checked = dynamicColor, onCheckedChange = null) },
                        )
                    }
                }
            }
        }

        item(key = "backup") {
            ListGroup(
                header = "Backup & restore",
                footer = BACKUP_FOOTER,
            ) {
                item {
                    ListRow(
                        label = "Automatic backups",
                        detail = autoBackup.detail,
                        detailMaxLines = 2,
                        icon = if (autoBackup.failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Schedule,
                        destructive = autoBackup.failed,
                        modifier = Modifier.toggleable(
                            value = autoBackup.enabled,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                haptics.performHapticFeedback(
                                    if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                                )
                                autoBackup.setEnabled(enabled)
                            },
                        ),
                        trailing = { Switch(checked = autoBackup.enabled, onCheckedChange = null) },
                    )
                }
                item {
                    ListRow(
                        label = "Back up now",
                        detail = AUTO_BACKUP_LOCATION,
                        icon = Icons.Rounded.Download,
                        enabled = !autoBackup.backingUpNow,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            autoBackup.backUpNow()
                        },
                    )
                }
                item {
                    ListRow(
                        label = "Back up to a file",
                        detail = "Save to Drive, Files or another app",
                        icon = Icons.Rounded.Backup,
                        onClick = backup.backUp,
                    )
                }
                item {
                    ListRow(
                        label = "Restore from a file",
                        detail = "Replace the data on this phone",
                        icon = Icons.Rounded.Restore,
                        onClick = backup.restore,
                    )
                }
                item {
                    ListRow(
                        label = "Import from web app backup",
                        detail = "A .json file saved by the old web app",
                        icon = Icons.Rounded.Language,
                        onClick = backup.importWebBackup,
                    )
                }
            }
        }

        item(key = "privacy") {
            ListGroup(header = "Privacy") {
                item { ListRow(label = "Works offline", icon = Icons.Rounded.CloudOff, value = "Always") }
                item { ListRow(label = "Student data", icon = Icons.Rounded.VerifiedUser, value = "On this device") }
            }
        }

        item(key = "about") {
            ListGroup(header = "About") {
                item {
                    ListRow(label = "Version", icon = Icons.Rounded.Info, value = version)
                }
            }
        }
    }
}

@Composable
private fun SchoolLogo(fileName: String?, schoolName: String?) {
    val colors = MaterialTheme.colorScheme
    StoredPhoto(
        fileName = fileName,
        contentDescription = schoolName?.let { "$it logo" },
        maxEdgePx = 160,
        modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceContainerHigh),
        placeholder = {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(colors.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.School, contentDescription = null, tint = colors.onSecondaryContainer)
            }
        },
    )
}

/** Where automatic backups go; see AutoBackupStorage. */
private val AUTO_BACKUP_LOCATION =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Saved in Download/Attendance backups" else "Saved in the app's files folder"

private const val BACKUP_FOOTER =
    "A backup is one file with all your data and photos. Restoring replaces everything on this phone. " +
        "Automatic backups run once a day while the battery isn't low."

private fun themeLabel(preference: ThemePreference): String = when (preference) {
    ThemePreference.SYSTEM -> "System"
    ThemePreference.LIGHT -> "Light"
    ThemePreference.DARK -> "Dark"
}

private fun appVersion(context: Context): String = runCatching {
    val manager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        manager.getPackageInfo(context.packageName, 0)
    }
    info.versionName
}.getOrNull() ?: "Unknown"

package com.itexpert120.attendanceManager.ui.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itexpert120.attendanceManager.LocalAppContainer
import com.itexpert120.attendanceManager.data.backup.AppBackup
import com.itexpert120.attendanceManager.data.backup.PwaBackupImporter

/**
 * The Backup & restore commands of the Settings tab. Built by [rememberBackupActions], which also registers
 * the Storage Access Framework launchers and shows the progress dialog and confirmation sheets they lead to.
 */
@Stable
internal class BackupActions(
    /** Export one `.zip` (database + photos) to a place the user picks. */
    val backUp: () -> Unit,
    /** Replace everything on this device with a `.zip` backup, after a confirmation. */
    val restore: () -> Unit,
    /** Import an old PWA Dexie `.json` backup, after a confirmation. */
    val importWebBackup: () -> Unit,
)

/** Zip files reach the picker under several types depending on the app that saved them. */
private val ZIP_TYPES = arrayOf(AppBackup.MIME_TYPE, "application/x-zip-compressed", "application/x-zip", "application/octet-stream")

/** Web-app backups are `.json`; some file managers report them as text or as unknown binaries. */
private val JSON_TYPES = arrayOf("application/json", "text/json", "text/plain", "application/octet-stream")

/**
 * Wires the three actions to SAF pickers and [BackupViewModel]. Results are reported on [snackbarHostState].
 * Besides returning the actions this emits the backup dialog/sheets, which float above the screen.
 */
@Composable
internal fun rememberBackupActions(snackbarHostState: SnackbarHostState): BackupActions {
    val container = LocalAppContainer.current
    val resolver = LocalContext.current.applicationContext.contentResolver
    val haptics = LocalHapticFeedback.current
    val viewModel = viewModel {
        BackupViewModel(
            contentResolver = resolver,
            appBackup = AppBackup(container.database, container.photoStore),
            webImporter = PwaBackupImporter(container.database, container.photoStore, phoneNormalizer = container.phoneNormalizer),
        )
    }
    val phase by viewModel.phase.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, snackbarHostState) {
        viewModel.messages.collect { message ->
            if (message.success) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message.text,
                withDismissAction = !message.success,
                duration = if (message.success) SnackbarDuration.Short else SnackbarDuration.Long,
            )
        }
    }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AppBackup.MIME_TYPE)) { uri ->
        if (uri != null) viewModel.backUp(uri)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.inspectRestore(uri)
    }
    val openWebBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.inspectWebImport(uri)
    }

    BackupPhaseUi(
        phase = phase,
        onConfirmRestore = viewModel::confirmRestore,
        onConfirmWebImport = viewModel::confirmWebImport,
        onDismiss = viewModel::dismiss,
    )

    return remember(viewModel, createBackup, openBackup, openWebBackup) {
        BackupActions(
            backUp = { createBackup.launch(viewModel.suggestedFileName()) },
            restore = { openBackup.launch(ZIP_TYPES) },
            importWebBackup = { openWebBackup.launch(JSON_TYPES) },
        )
    }
}

package com.itexpert120.attendanceManager.ui.features.registers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.itexpert120.attendanceManager.ui.components.SheetContentMotion
import com.itexpert120.attendanceManager.ui.components.SheetMotion
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itexpert120.attendanceManager.data.repo.RegisterData
import com.itexpert120.attendanceManager.domain.FeeField
import com.itexpert120.attendanceManager.domain.minorToDisplay
import com.itexpert120.attendanceManager.model.InstallmentNumber
import com.itexpert120.attendanceManager.ui.components.AppCard
import com.itexpert120.attendanceManager.ui.components.BadgeTone
import com.itexpert120.attendanceManager.ui.components.ErrorBanner
import com.itexpert120.attendanceManager.ui.components.StatusBadge
import kotlinx.coroutines.launch

/**
 * A bottom sheet like the kit's `AppSheet`, plus protection for unsaved edits: while [dirty], swiping the
 * sheet away, tapping the scrim or pressing back asks whether to discard instead of silently losing the
 * form. [footer] receives `requestClose`, which a Cancel button should call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuardedSheet(
    title: String,
    dirty: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    footer: @Composable RowScope.(requestClose: () -> Unit) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dirtyNow by rememberUpdatedState(dirty)
    var discarding by remember { mutableStateOf(false) }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && dirtyNow && !discarding) {
                askDiscard = true
                false
            } else {
                true
            }
        },
    )
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val requestClose: () -> Unit = { if (dirtyNow) askDiscard = true else close() }

    SheetMotion {
        ModalBottomSheet(
            // Every dismissal path (swipe, scrim, back) ends here once the sheet is hidden; with unsaved edits
            // the sheet is kept in the composition and the user is asked instead.
            onDismissRequest = { if (dirtyNow && !discarding) askDiscard = true else onDismiss() },
            sheetState = sheetState,
            // While dirty our own BackHandler asks first, so the sheet's built-in back dismissal must stand down.
            properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !dirty),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = modifier,
        ) {
            SheetContentMotion {
            BackHandler(enabled = dirty) { askDiscard = true }
            Column(Modifier.fillMaxWidth().imePadding()) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (description != null) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) { footer(requestClose) }
            }
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("What you entered has not been saved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        askDiscard = false
                        discarding = true
                        close()
                    },
                ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/** The filled "Save" button with an inline spinner while a save is running. */
@Composable
private fun SaveButton(enabled: Boolean, saving: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !saving) {
        if (saving) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(if (saving) "Saving" else "Save")
    }
}

private val FieldShape = RoundedCornerShape(12.dp)

@Composable
private fun AmountField(
    label: String,
    value: String,
    currency: String,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        prefix = if (currency.isBlank()) null else ({ Text("$currency ") }),
        placeholder = { Text("0") },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        shape = FieldShape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = modifier.fillMaxWidth(),
    )
}

private val FeeFormSaver = listSaver<FeeForm, String>(
    save = { it.toSaveable() },
    restore = { FeeForm.fromSaveable(it) },
)

private val MetaFormSaver = listSaver<InstallmentMetaForm, String>(
    save = { listOf(it.rate, it.receiver) },
    restore = { if (it.size == 2) InstallmentMetaForm(it[0], it[1]) else null },
)

/**
 * One student's fees: the three installments as tabs, one numeric field per fee category, a live total, the
 * remark and Save. Saving writes all three installments at once.
 */
@Composable
internal fun StudentFeeSheet(
    card: FeeCard,
    data: RegisterData,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (FeeForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = data.feeCurrency()
    val initial = remember(data, card.enrollmentId) { FeeForm.of(data, card.enrollmentId) }
    var form by rememberSaveable(card.enrollmentId, stateSaver = FeeFormSaver) { mutableStateOf(initial) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val dirty = !form.sameAs(initial)
    val student = card.row.student
    val installment = InstallmentNumber.entries[tab]

    GuardedSheet(
        title = student.name,
        description = "Roll ${card.row.enrollment.rollNumber} · B-Form ${student.admissionNumber}",
        dirty = dirty,
        onDismiss = onDismiss,
        modifier = modifier,
        footer = { requestClose ->
            TextButton(onClick = requestClose) { Text("Cancel") }
            SaveButton(enabled = dirty, saving = saving) { onSave(form) }
        },
    ) {
        AppCard(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Month total", style = MaterialTheme.typography.labelLarge)
                Text(feeMoney(form.grandTotal, currency), style = MaterialTheme.typography.headlineSmall)
            }
        }

        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            InstallmentNumber.entries.forEachIndexed { index, entry ->
                Tab(
                    selected = index == tab,
                    onClick = { tab = index },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(entry.shortLabel)
                            val total = form.totalOf(entry)
                            Text(
                                if (total > 0) minorToDisplay(total) else "None",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            }
        }

        val rate = data.installmentMeta[installment]?.rate ?: 0
        val status = installmentStatus(form.amountsOf(installment), rate)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(installment.longLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (rate > 0) "Rate ${feeMoney(rate, currency)} per student" else "No rate set for this installment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge(status.label(), tone = status.tone())
        }

        FeeField.entries.forEach { field ->
            AmountField(
                label = field.label,
                value = form.valueOf(installment, field),
                currency = currency,
                imeAction = ImeAction.Next,
                onValueChange = { form = form.withValue(installment, field, it) },
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        OutlinedTextField(
            value = form.remark,
            onValueChange = { form = form.withRemark(it) },
            label = { Text("Remark") },
            supportingText = { Text("Optional · applies to the whole month") },
            minLines = 1,
            maxLines = 3,
            shape = FieldShape,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) ErrorBanner(error)
    }
}

/** One installment's rate per student and receiver name. */
@Composable
internal fun InstallmentMetaSheet(
    installment: InstallmentNumber,
    data: RegisterData,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (InstallmentMetaForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = data.feeCurrency()
    val initial = remember(data, installment) { InstallmentMetaForm.of(data.installmentMeta[installment]) }
    var form by rememberSaveable(installment, stateSaver = MetaFormSaver) { mutableStateOf(initial) }
    val dirty = !form.sameAs(initial)

    GuardedSheet(
        title = installment.longLabel,
        description = "Shown on the printed register and used for the Paid / Partial status.",
        dirty = dirty,
        onDismiss = onDismiss,
        modifier = modifier,
        footer = { requestClose ->
            TextButton(onClick = requestClose) { Text("Cancel") }
            SaveButton(enabled = dirty, saving = saving) { onSave(form) }
        },
    ) {
        AmountField(
            label = "Rate per student",
            value = form.rate,
            currency = currency,
            imeAction = ImeAction.Next,
            onValueChange = { form = form.withRate(it) },
            supportingText = "A student whose F.T.F reaches this counts as Paid.",
        )
        OutlinedTextField(
            value = form.receiver,
            onValueChange = { form = form.withReceiver(it) },
            label = { Text("Receiver name") },
            singleLine = true,
            shape = FieldShape,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) ErrorBanner(error)
    }
}

internal fun FeeStatus.label(): String = when (this) {
    FeeStatus.PAID -> "Paid"
    FeeStatus.PARTIAL -> "Partial"
    FeeStatus.NONE -> "No fees"
}

internal fun FeeStatus.tone(): BadgeTone = when (this) {
    FeeStatus.PAID -> BadgeTone.SUCCESS
    FeeStatus.PARTIAL -> BadgeTone.WARNING
    FeeStatus.NONE -> BadgeTone.NEUTRAL
}

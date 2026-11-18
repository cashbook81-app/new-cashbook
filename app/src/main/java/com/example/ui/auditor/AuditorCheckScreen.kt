package com.example.ui.auditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.CashSession
import com.example.data.remote.model.AuditorCheckDto
import com.example.ui.calculator.ThousandsSeparatorVisualTransformation
import com.example.ui.theme.*
import com.example.ui.util.formatBirr
import com.example.ui.util.formatSignedBirr
import com.example.ui.viewmodel.CashViewModel
import com.example.ui.viewmodel.EtbParseResult
import com.example.ui.viewmodel.formatMinorAsFixedTwoDecimals
import com.example.ui.viewmodel.parseAndValidateEtbInput
import com.example.ui.viewmodel.utcDateMillisToLocalMidnight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditorCheckScreen(
    viewModel: CashViewModel,
    branchId: String,
    auditorUid: String,
    auditorStaffId: String,
    auditorName: String
) {
    val uiState by viewModel.uiState.collectAsState()
    val branchSessions by viewModel.sessionHistory.collectAsState()
    val branchChecks by viewModel.branchAuditorChecks.collectAsState()

    AuditorCheckContent(
        branchId = branchId,
        auditorStaffId = auditorStaffId,
        selectedBusinessDateMillis = uiState.selectedBusinessDateMillis,
        branchSessions = branchSessions,
        branchChecks = branchChecks,
        isSubmitting = uiState.isSubmittingAuditorCheck,
        submitNotice = uiState.auditorSubmitNotice,
        errorMessage = uiState.error,
        onDateSelected = { viewModel.updateBusinessDate(it) },
        onClearSubmitNotice = { viewModel.clearAuditorSubmitNotice() },
        onClearError = { viewModel.clearError() },
        onApproveSession = { sessionId ->
            viewModel.approveSession(sessionId, auditorName.ifBlank { auditorStaffId })
        },
        onSubmitCheck = { openingMinor, drMinor, crMinor, cashierCountedMinor, notes ->
            viewModel.submitAuditorVerification(
                branchId = branchId,
                auditorUid = auditorUid,
                auditorStaffId = auditorStaffId,
                auditorName = auditorName,
                businessDateMillis = uiState.selectedBusinessDateMillis,
                openingBalanceMinor = openingMinor,
                totalDRMinor = drMinor,
                totalCRMinor = crMinor,
                cashierCountedMinor = cashierCountedMinor,
                notes = notes
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditorCheckContent(
    branchId: String,
    auditorStaffId: String,
    selectedBusinessDateMillis: Long,
    branchSessions: List<CashSession>,
    branchChecks: List<AuditorCheckDto>,
    isSubmitting: Boolean,
    submitNotice: String? = null,
    errorMessage: String? = null,
    onDateSelected: (Long) -> Unit,
    onClearSubmitNotice: () -> Unit = {},
    onClearError: () -> Unit = {},
    onApproveSession: (String) -> Unit = {},
    onSubmitCheck: (
        openingBalanceMinor: Long,
        totalDRMinor: Long,
        totalCRMinor: Long,
        cashierCountedMinor: Long?,
        notes: String
    ) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }

    val selectedDayKey = remember(selectedBusinessDateMillis) {
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(selectedBusinessDateMillis))
    }
    val formattedSelectedDate = remember(selectedBusinessDateMillis) {
        SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault()).format(Date(selectedBusinessDateMillis))
    }

    val scopedBranchSessions = remember(branchSessions, branchId) {
        branchSessions.filter { it.branchId.equals(branchId, ignoreCase = true) }
    }

    val todayCashierSession = remember(scopedBranchSessions, selectedDayKey) {
        scopedBranchSessions.firstOrNull {
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(it.businessDate)) == selectedDayKey
        }
    }

    val yesterdaySession = remember(scopedBranchSessions, selectedBusinessDateMillis, selectedDayKey) {
        scopedBranchSessions
            .filter {
                SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(it.businessDate)) != selectedDayKey &&
                    it.businessDate < selectedBusinessDateMillis + 86_399_999L
            }
            .maxByOrNull { it.businessDate }
    }

    val existingCheckForDate = remember(branchChecks, branchId, selectedDayKey) {
        branchChecks.firstOrNull {
            it.branchId.equals(branchId, ignoreCase = true) &&
                SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(it.businessDate)) == selectedDayKey
        }
    }

    val initialOpeningMinor = remember(selectedDayKey, todayCashierSession, yesterdaySession, existingCheckForDate) {
        when {
            existingCheckForDate != null -> existingCheckForDate.openingBalanceMinor
            todayCashierSession != null -> todayCashierSession.openingBalanceMinor
            yesterdaySession != null -> yesterdaySession.totalCountedMinor
            else -> 0L
        }
    }

    var openingText by remember(selectedDayKey) {
        mutableStateOf(
            if (initialOpeningMinor > 0L) formatMinorAsFixedTwoDecimals(initialOpeningMinor) else ""
        )
    }
    var drText by remember(selectedDayKey) {
        mutableStateOf(
            existingCheckForDate?.takeIf { it.totalDRMinor > 0L }
                ?.let { formatMinorAsFixedTwoDecimals(it.totalDRMinor) }
                ?: ""
        )
    }
    var crText by remember(selectedDayKey) {
        mutableStateOf(
            existingCheckForDate?.takeIf { it.totalCRMinor > 0L }
                ?.let { formatMinorAsFixedTwoDecimals(it.totalCRMinor) }
                ?: ""
        )
    }
    var notesText by remember(selectedDayKey) {
        mutableStateOf(existingCheckForDate?.notes.orEmpty())
    }
    var inputValidationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialOpeningMinor) {
        if (openingText.isBlank() && initialOpeningMinor > 0L) {
            openingText = formatMinorAsFixedTwoDecimals(initialOpeningMinor)
        }
    }
    LaunchedEffect(existingCheckForDate?.checkId, existingCheckForDate?.totalDRMinor, existingCheckForDate?.totalCRMinor) {
        existingCheckForDate?.let { check ->
            if (drText.isBlank() && check.totalDRMinor > 0L) {
                drText = formatMinorAsFixedTwoDecimals(check.totalDRMinor)
            }
            if (crText.isBlank() && check.totalCRMinor > 0L) {
                crText = formatMinorAsFixedTwoDecimals(check.totalCRMinor)
            }
            if (notesText.isBlank() && check.notes.isNotBlank()) {
                notesText = check.notes
            }
        }
    }

    LaunchedEffect(submitNotice) {
        if (submitNotice != null) {
            snackbarHostState.showSnackbar(submitNotice)
            onClearSubmitNotice()
        }
    }

    val openingMinor = when (val parsed = parseAndValidateEtbInput(openingText)) {
        is EtbParseResult.Valid -> parsed.minor
        else -> 0L
    }
    val drMinor = when (val parsed = parseAndValidateEtbInput(drText)) {
        is EtbParseResult.Valid -> parsed.minor
        else -> 0L
    }
    val crMinor = when (val parsed = parseAndValidateEtbInput(crText)) {
        is EtbParseResult.Valid -> parsed.minor
        else -> 0L
    }

    val expectedEndingMinor = openingMinor + drMinor - crMinor
    val cashierCountedMinor = todayCashierSession?.totalCountedMinor
    val differenceMinor = if (cashierCountedMinor != null) cashierCountedMinor - expectedEndingMinor else 0L
    val verificationStatus = when {
        cashierCountedMinor == null -> "AWAITING COUNT"
        differenceMinor == 0L -> "BALANCED"
        differenceMinor < 0L -> "UNBALANCED · SHORTAGE"
        else -> "UNBALANCED · EXCESS"
    }

    val verdictBg = when {
        cashierCountedMinor == null -> CyanSoftTint
        differenceMinor == 0L -> SuccessSoftTint
        differenceMinor < 0L -> DangerSoftTint
        else -> AmberSoftTint
    }
    val verdictColor = when {
        cashierCountedMinor == null -> CyanDeep
        differenceMinor == 0L -> SuccessGreen
        differenceMinor < 0L -> DangerRed
        else -> AmberAccent
    }
    val currencyVisual = remember { ThousandsSeparatorVisualTransformation() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = IceSurface,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(White)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Auditor Check",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = PlusJakartaSansFontFamily,
                                fontSize = 16.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = VaultSlateDeep
                        )
                        Text(
                            text = "Staff · $auditorStaffId",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MutedNavyText
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SoftSurfaceVariant,
                        border = BorderStroke(1.dp, SubtleOutline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = branchId,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.Bold,
                                color = VaultSlateDeep,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = SubtleOutline)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(IceSurface)
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Compact Audit Date & Status Bar (replaces oversized dark hero card)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { showDatePicker = true },
                        shape = RoundedCornerShape(9.dp),
                        color = White,
                        border = BorderStroke(1.dp, CyanBorder),
                        modifier = Modifier.testTag("auditor_date_chip")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CalendarToday,
                                contentDescription = "Select audit date",
                                tint = CyanPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = formattedSelectedDate,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                fontWeight = FontWeight.Bold,
                                color = NavyText
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = verdictBg,
                        border = BorderStroke(1.dp, verdictColor.copy(alpha = 0.28f))
                    ) {
                        Text(
                            text = verificationStatus,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = verdictColor,
                            modifier = Modifier
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                                .testTag("auditor_balance_status_badge")
                        )
                    }
                }

                // Today's Ledger Entry Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = White,
                    border = BorderStroke(1.dp, SubtleOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            text = "DAILY DR & CR ENTRY",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MutedNavyText
                        )

                        LedgerAmountField(
                            value = openingText,
                            onValueChange = { raw ->
                                sanitizeDecimalOrNull(raw)?.let {
                                    openingText = it
                                    inputValidationError = null
                                }
                            },
                            label = "Opening Balance",
                            prefixText = "ETB ",
                            accentColor = CyanPrimary,
                            visualTransformation = currencyVisual,
                            imeAction = ImeAction.Next,
                            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                            tag = "auditor_opening_input"
                        )

                        LedgerAmountField(
                            value = drText,
                            onValueChange = { raw ->
                                sanitizeDecimalOrNull(raw)?.let {
                                    drText = it
                                    inputValidationError = null
                                }
                            },
                            label = "Cash In · DR (+)",
                            prefixText = "+ETB ",
                            accentColor = SuccessGreen,
                            visualTransformation = currencyVisual,
                            imeAction = ImeAction.Next,
                            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                            tag = "auditor_dr_input"
                        )

                        LedgerAmountField(
                            value = crText,
                            onValueChange = { raw ->
                                sanitizeDecimalOrNull(raw)?.let {
                                    crText = it
                                    inputValidationError = null
                                }
                            },
                            label = "Cash Out · CR (−)",
                            prefixText = "-ETB ",
                            accentColor = DangerRed,
                            visualTransformation = currencyVisual,
                            imeAction = ImeAction.Next,
                            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                            tag = "auditor_cr_input"
                        )

                        OutlinedTextField(
                            value = notesText,
                            onValueChange = { notesText = it.take(300) },
                            label = { Text("Audit Note (Optional)", fontSize = 12.5.sp) },
                            placeholder = { Text("Add remarks if needed", fontSize = 13.sp) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                color = NavyText
                            ),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = SubtleOutline,
                                focusedContainerColor = White,
                                unfocusedContainerColor = IceSurface,
                                cursorColor = CyanPrimary
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auditor_notes_input")
                        )
                    }
                }

                // Concise Ending Balance & Cashier Comparison Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = White,
                    border = BorderStroke(1.dp, SubtleOutline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auditor_comparison_card")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "RECONCILIATION",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                letterSpacing = 0.5.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MutedNavyText
                        )

                        ReconciliationLedgerRow(
                            label = "Auditor Ending Balance",
                            sublabel = "Opening + DR − CR",
                            value = formatBirr(expectedEndingMinor),
                            valueColor = CyanPrimary
                        )

                        HorizontalDivider(color = SubtleOutline)

                        ReconciliationLedgerRow(
                            label = "Cashier Ending Count",
                            sublabel = if (todayCashierSession != null) {
                                "Staff ${todayCashierSession.staffId}"
                            } else {
                                "Waiting for cashier submission"
                            },
                            value = cashierCountedMinor?.let { formatBirr(it) } ?: "Not submitted",
                            valueColor = if (cashierCountedMinor != null) NavyText else MutedNavyText
                        )

                        if (cashierCountedMinor != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = verdictBg,
                                border = BorderStroke(1.dp, verdictColor.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (differenceMinor == 0L) {
                                                Icons.Rounded.CheckCircle
                                            } else {
                                                Icons.Rounded.WarningAmber
                                            },
                                            contentDescription = null,
                                            tint = verdictColor,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Text(
                                            text = "Variance ($verificationStatus)",
                                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = verdictColor
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = formatSignedBirr(differenceMinor),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 14.5.sp,
                                            fontFeatureSettings = "tnum"
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = verdictColor
                                    )
                                }
                            }

                            val activeSession = todayCashierSession
                            if (activeSession.isFinal && !activeSession.isApproved) {
                                OutlinedButton(
                                    onClick = { onApproveSession(activeSession.sessionId) },
                                    shape = RoundedCornerShape(11.dp),
                                    border = BorderStroke(1.dp, SuccessGreen),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = SuccessSoftTint.copy(alpha = 0.55f),
                                        contentColor = SuccessGreen
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("auditor_approve_closing_button")
                                ) {
                                    Icon(Icons.Rounded.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Approve Closing", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                }
                            }
                        }
                    }
                }

                if (inputValidationError != null) {
                    Text(
                        text = inputValidationError!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = DangerRed
                    )
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val openParsed = parseAndValidateEtbInput(openingText)
                        val drParsed = parseAndValidateEtbInput(drText)
                        val crParsed = parseAndValidateEtbInput(crText)
                        if (openParsed is EtbParseResult.Invalid ||
                            drParsed is EtbParseResult.Invalid ||
                            crParsed is EtbParseResult.Invalid
                        ) {
                            inputValidationError = "Enter valid non-negative amounts for Opening, DR, and CR."
                            return@Button
                        }
                        onSubmitCheck(
                            openingMinor,
                            drMinor,
                            crMinor,
                            cashierCountedMinor,
                            notesText
                        )
                    },
                    enabled = !isSubmitting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("submit_auditor_check_button")
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Submitting...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (existingCheckForDate != null) "Update DR & CR" else "Submit DR & CR",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = selectedBusinessDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { utc ->
                        onDateSelected(utcDateMillisToLocalMidnight(utc))
                    }
                    showDatePicker = false
                }) {
                    Text("OK", color = CyanPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = onClearError,
            containerColor = White,
            title = { Text("Notice", fontWeight = FontWeight.Bold) },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = onClearError) {
                    Text("OK", color = CyanPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun LedgerAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    prefixText: String,
    accentColor: Color,
    visualTransformation: ThousandsSeparatorVisualTransformation,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    tag: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontWeight = FontWeight.Medium, fontSize = 12.5.sp) },
        placeholder = { Text("0.00", fontSize = 14.sp) },
        prefix = {
            Text(
                text = prefixText,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        },
        visualTransformation = visualTransformation,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
            color = NavyText
        ),
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            unfocusedBorderColor = SubtleOutline,
            focusedLabelColor = accentColor,
            focusedContainerColor = White,
            unfocusedContainerColor = IceSurface,
            cursorColor = accentColor
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    )
}

@Composable
private fun ReconciliationLedgerRow(
    label: String,
    sublabel: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                fontWeight = FontWeight.SemiBold,
                color = NavyText
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = MutedNavyText
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.5.sp,
                fontFeatureSettings = "tnum"
            ),
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

private fun sanitizeDecimalOrNull(input: String): String? {
    val filtered = input.filter { it.isDigit() || it == '.' }
    if (filtered.count { it == '.' } > 1) return null
    val parts = filtered.split('.')
    val intPart = parts[0].take(10)
    return if (parts.size == 2) "$intPart.${parts[1].take(2)}" else intPart
}

package com.example.ui.calculator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.PreviousApprovedClosingInfo
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CyanBorder
import com.example.ui.theme.CyanDeep
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanSoftTint
import com.example.ui.theme.IceSurface
import com.example.ui.theme.MutedNavyText
import com.example.ui.theme.NavyText
import com.example.ui.theme.PlusJakartaSansFontFamily
import com.example.ui.theme.SoftSurfaceVariant
import com.example.ui.theme.SubtleOutline
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.White
import com.example.ui.util.formatBirr
import com.example.ui.viewmodel.CashCalculatorUiState
import com.example.ui.viewmodel.CashViewModel
import com.example.ui.viewmodel.EtbParseResult
import com.example.ui.viewmodel.LoadedClosingMetadata
import com.example.ui.viewmodel.OpeningBalanceSource
import com.example.ui.viewmodel.PreviousBalanceStatus
import com.example.ui.viewmodel.PreviousBalanceStatusType
import com.example.ui.viewmodel.formatMinorAsFixedTwoDecimals
import com.example.ui.viewmodel.parseAndValidateEtbInput
import com.example.ui.viewmodel.utcDateMillisToLocalMidnight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OpeningInputSurface = IceSurface

class ThousandsSeparatorVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val dotIndex = raw.indexOf('.')
        val intEnd = if (dotIndex >= 0) dotIndex else raw.length
        val sb = StringBuilder(raw.length + 4)
        val origToTrans = IntArray(raw.length + 1)
        val transToOrigList = ArrayList<Int>(raw.length + 5)

        for (i in 0 until intEnd) {
            val remaining = intEnd - i
            if (i > 0 && remaining % 3 == 0) {
                sb.append(',')
                transToOrigList.add(i)
            }
            origToTrans[i] = sb.length
            sb.append(raw[i])
            transToOrigList.add(i)
        }
        for (i in intEnd until raw.length) {
            origToTrans[i] = sb.length
            sb.append(raw[i])
            transToOrigList.add(i)
        }
        origToTrans[raw.length] = sb.length
        transToOrigList.add(raw.length)

        val formatted = sb.toString()
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                origToTrans[offset.coerceIn(0, raw.length)]

            override fun transformedToOriginal(offset: Int): Int =
                transToOrigList[offset.coerceIn(0, formatted.length)]
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Step1OpeningBalance(
    state: CashCalculatorUiState,
    viewModel: CashViewModel,
    branchId: String,
    rawText: String,
    onRawTextChanged: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val thousandsTransformation = remember { ThousandsSeparatorVisualTransformation() }
    val interactionSource = remember { MutableInteractionSource() }

    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    var showLockedHint by remember { mutableStateOf(false) }

    // Synchronize rawText with programmatic updates (e.g., loading previous approved balance or clearing)
    LaunchedEffect(
        state.openingBalanceMinor,
        state.openingBalanceSource,
        state.hasConfirmedOpeningEntry,
        state.isPreviousBalanceLocked
    ) {
        val parsed = parseAndValidateEtbInput(rawText)
        when {
            !state.hasConfirmedOpeningEntry && state.openingBalanceMinor == 0L -> {
                if (rawText.isNotEmpty() && parsed !is EtbParseResult.Valid) {
                    onRawTextChanged("")
                } else if (rawText.isNotEmpty() && state.openingBalanceSource == OpeningBalanceSource.NONE) {
                    onRawTextChanged("")
                }
            }
            state.openingBalanceSource == OpeningBalanceSource.PREVIOUS_APPROVED -> {
                val expected = formatMinorAsFixedTwoDecimals(state.openingBalanceMinor)
                if (rawText != expected) {
                    onRawTextChanged(expected)
                }
            }
            parsed is EtbParseResult.Valid && parsed.minor != state.openingBalanceMinor -> {
                onRawTextChanged(
                    if (state.openingBalanceMinor == 0L && state.hasConfirmedOpeningEntry) {
                        "0.00"
                    } else {
                        formatMinorAsFixedTwoDecimals(state.openingBalanceMinor)
                    }
                )
            }
            parsed is EtbParseResult.Empty && state.openingBalanceMinor > 0L -> {
                onRawTextChanged(formatMinorAsFixedTwoDecimals(state.openingBalanceMinor))
            }
        }
    }

    LaunchedEffect(interactionSource, state.isPreviousBalanceLocked) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release && state.isPreviousBalanceLocked) {
                showLockedHint = true
            }
        }
    }

    LaunchedEffect(state.isPreviousBalanceLocked) {
        if (!state.isPreviousBalanceLocked) {
            showLockedHint = false
        }
    }

    val businessDateLabel = remember(state.selectedBusinessDateMillis) {
        SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault())
            .format(Date(state.selectedBusinessDateMillis))
    }

    val amountFontSize = remember(rawText.length) {
        when {
            rawText.length >= 13 -> 18.sp
            rawText.length >= 10 -> 20.sp
            else -> 22.sp
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Business Date",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MutedNavyText
            )
            Surface(
                onClick = { showDatePicker = true },
                shape = RoundedCornerShape(8.dp),
                color = White,
                border = BorderStroke(1.dp, SubtleOutline),
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .testTag("opening_business_date_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarToday,
                        contentDescription = "Select Business Date",
                        tint = CyanPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = businessDateLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = NavyText,
                        maxLines = 1
                    )
                }
            }
        }

        // Hairline Opening Balance Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = White,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, SubtleOutline)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Opening Balance",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = PlusJakartaSansFontFamily,
                            fontSize = 15.5.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = NavyText,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OpeningSourceBadge(source = state.openingBalanceSource)
                }

                // ETB Amount Input
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { input ->
                        val accepted = viewModel.onOpeningBalanceTextChanged(input)
                        if (accepted) {
                            when (val parsed = parseAndValidateEtbInput(input)) {
                                is EtbParseResult.Valid -> onRawTextChanged(parsed.normalizedRaw)
                                EtbParseResult.Empty -> onRawTextChanged("")
                                is EtbParseResult.Invalid -> Unit
                            }
                        }
                    },
                    readOnly = state.isPreviousBalanceLocked,
                    interactionSource = interactionSource,
                    visualTransformation = thousandsTransformation,
                    isError = state.openingInputError != null,
                    placeholder = {
                        Text(
                            text = "0.00",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = PlusJakartaSansFontFamily,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum"
                            ),
                            color = MutedNavyText.copy(alpha = 0.35f)
                        )
                    },
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = PlusJakartaSansFontFamily,
                        fontSize = amountFontSize,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        color = NavyText
                    ),
                    prefix = {
                        Text(
                            text = "ETB ",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                            fontWeight = FontWeight.Bold,
                            color = CyanDeep
                        )
                    },
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            if (state.isPreviousBalanceLocked) {
                                Surface(
                                    onClick = {
                                        showLockedHint = false
                                        viewModel.unlockPreviousBalanceForEdit()
                                        focusRequester.requestFocus()
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = CyanSoftTint,
                                    border = BorderStroke(1.dp, CyanBorder),
                                    modifier = Modifier
                                        .heightIn(min = 34.dp)
                                        .testTag("unlock_edit_opening_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Edit,
                                            contentDescription = "Edit loaded amount",
                                            tint = CyanDeep,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Edit",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = CyanDeep
                                        )
                                    }
                                }
                            }
                            if (rawText.isNotEmpty() || state.hasConfirmedOpeningEntry) {
                                IconButton(
                                    onClick = {
                                        if (state.openingBalanceMinor > 0L ||
                                            state.openingBalanceSource == OpeningBalanceSource.PREVIOUS_APPROVED
                                        ) {
                                            showClearConfirmDialog = true
                                        } else {
                                            onRawTextChanged("")
                                            viewModel.clearOpeningBalance()
                                        }
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("clear_opening_balance_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Clear amount",
                                        tint = MutedNavyText,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            val lostFocus = hasFocus && !focusState.isFocused
                            hasFocus = focusState.isFocused
                            if (lostFocus && !state.isPreviousBalanceLocked && rawText.isNotEmpty()) {
                                val parsed = parseAndValidateEtbInput(rawText)
                                if (parsed is EtbParseResult.Valid) {
                                    onRawTextChanged(formatMinorAsFixedTwoDecimals(parsed.minor))
                                }
                            }
                        }
                        .testTag("opening_balance_input"),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (rawText.isNotEmpty()) {
                                val parsed = parseAndValidateEtbInput(rawText)
                                if (parsed is EtbParseResult.Valid) {
                                    onRawTextChanged(formatMinorAsFixedTwoDecimals(parsed.minor))
                                }
                            }
                            focusManager.clearFocus()
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = if (state.isPreviousBalanceLocked) CyanBorder else SubtleOutline,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        focusedContainerColor = OpeningInputSurface,
                        unfocusedContainerColor = if (state.isPreviousBalanceLocked) CyanSoftTint.copy(alpha = 0.25f) else OpeningInputSurface,
                        errorContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.04f),
                        cursorColor = CyanPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                if (state.isPreviousBalanceLocked && showLockedHint) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Locked · Tap Edit to modify",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = CyanDeep
                        )
                    }
                } else if (state.openingBalanceSource == OpeningBalanceSource.ADJUSTED_FROM_PREVIOUS &&
                    state.loadedClosingMetadata != null
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Modified from ${formatBirr(state.loadedClosingMetadata.amountMinor)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = AmberAccent,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                viewModel.restoreLoadedClosingBalance()
                                onRawTextChanged(formatMinorAsFixedTwoDecimals(state.loadedClosingMetadata.amountMinor))
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("restore_loaded_closing_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                tint = CyanPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Restore Closing",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary
                            )
                        }
                    }
                }

                // Inline Validation Error Callout
                AnimatedVisibility(
                    visible = state.openingInputError != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    state.openingInputError?.let { errMsg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("opening_input_error"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = "Validation error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errMsg,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            IconButton(
                                onClick = { viewModel.dismissOpeningInputError() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // Load Previous Approved Balance Action
                FilledTonalButton(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.loadPreviousBalance(branchId)
                    },
                    enabled = !state.isLoadingPreviousBalance,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("load_previous_balance_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CyanSoftTint,
                        contentColor = CyanDeep
                    ),
                    border = BorderStroke(1.dp, CyanBorder)
                ) {
                    if (state.isLoadingPreviousBalance) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(17.dp),
                            strokeWidth = 2.dp,
                            color = CyanDeep
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Loading Closing...",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Load Previous Approved Balance",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Actionable Feedback Banner (offline / error / not found; keep success state clean)
                val statusToShow = (state.previousBalanceStatus
                    ?.takeIf { it.type != PreviousBalanceStatusType.SUCCESS }
                    ?: state.previousBalanceNotice?.let {
                        PreviousBalanceStatus(PreviousBalanceStatusType.ERROR, it)
                    })
                AnimatedVisibility(
                    visible = statusToShow != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    statusToShow?.let { status ->
                        PreviousBalanceStatusBanner(status = status)
                    }
                }
            }
        }

        // Clean fast-focus opening balance screen without bottom summary strip
    }

    // Confirmation Dialog before clearing non-zero / loaded opening balance
    if (showClearConfirmDialog) {
        ClearOpeningBalanceDialog(
            currentBalanceText = formatBirr(state.openingBalanceMinor),
            onConfirmClear = {
                showClearConfirmDialog = false
                onRawTextChanged("")
                viewModel.clearOpeningBalance()
            },
            onDismiss = { showClearConfirmDialog = false }
        )
    }

    // Overwrite Protection Dialog when a manual/existing opening balance would be replaced
    state.pendingOverwriteClosing?.let { pendingClosing ->
        ReplaceOpeningBalanceDialog(
            currentBalanceMinor = state.openingBalanceMinor,
            currentSource = state.openingBalanceSource,
            pendingClosing = pendingClosing,
            onConfirmReplace = {
                viewModel.confirmOverwriteWithPreviousBalance()
                onRawTextChanged(formatMinorAsFixedTwoDecimals(pendingClosing.amountMinor))
            },
            onKeepCurrent = { viewModel.dismissOverwriteWithPreviousBalance() }
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.selectedBusinessDateMillis
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedUtcMillis ->
                            viewModel.updateBusinessDate(
                                utcDateMillisToLocalMidnight(selectedUtcMillis)
                            )
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("SET DATE", color = CyanPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("CANCEL", color = MutedNavyText)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun OpeningSourceBadge(source: OpeningBalanceSource) {
    val (label, bgColor, borderColor, textColor, icon) = when (source) {
        OpeningBalanceSource.NONE -> Tuple5(
            "Awaiting Entry",
            SoftSurfaceVariant,
            SubtleOutline,
            MutedNavyText,
            Icons.Rounded.Info
        )
        OpeningBalanceSource.MANUAL -> Tuple5(
            "Manual Float",
            CyanSoftTint,
            CyanBorder,
            CyanDeep,
            Icons.Rounded.Edit
        )
        OpeningBalanceSource.PREVIOUS_APPROVED -> Tuple5(
            "Approved Closing",
            SuccessGreen.copy(alpha = 0.12f),
            SuccessGreen.copy(alpha = 0.35f),
            SuccessGreen,
            Icons.Rounded.Verified
        )
        OpeningBalanceSource.ADJUSTED_FROM_PREVIOUS -> Tuple5(
            "Adjusted Float",
            AmberAccent.copy(alpha = 0.12f),
            AmberAccent.copy(alpha = 0.35f),
            AmberAccent,
            Icons.Rounded.Tune
        )
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .testTag("opening_source_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PreviousBalanceStatusBanner(status: PreviousBalanceStatus) {
    val (bgColor, tintColor, icon) = when (status.type) {
        PreviousBalanceStatusType.SUCCESS -> Triple(
            SuccessGreen.copy(alpha = 0.10f),
            SuccessGreen,
            Icons.Rounded.CheckCircle
        )
        PreviousBalanceStatusType.WARNING_OFFLINE -> Triple(
            AmberAccent.copy(alpha = 0.10f),
            AmberAccent,
            Icons.Rounded.CloudOff
        )
        PreviousBalanceStatusType.INFO -> Triple(
            CyanSoftTint,
            CyanDeep,
            Icons.Rounded.Info
        )
        PreviousBalanceStatusType.ERROR -> Triple(
            MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
            MaterialTheme.colorScheme.error,
            Icons.Rounded.ErrorOutline
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("previous_balance_status_banner"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = status.message,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = if (status.type == PreviousBalanceStatusType.ERROR) tintColor else NavyText
        )
    }
}

@Composable
private fun ClearOpeningBalanceDialog(
    currentBalanceText: String,
    onConfirmClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = White,
        modifier = Modifier.testTag("confirm_clear_opening_dialog"),
        title = {
            Text(
                text = "Clear Opening Balance?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NavyText
            )
        },
        text = {
            Text(
                text = "Reset the current opening balance of $currentBalanceText? You can re-enter a starting float or reload the previous approved closing.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedNavyText
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmClear,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("confirm_clear_balance_button")
            ) {
                Text("CLEAR", color = White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_clear_balance_button")
            ) {
                Text("KEEP", color = CyanPrimary, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun ReplaceOpeningBalanceDialog(
    currentBalanceMinor: Long,
    currentSource: OpeningBalanceSource,
    pendingClosing: PreviousApprovedClosingInfo,
    onConfirmReplace: () -> Unit,
    onKeepCurrent: () -> Unit
) {
    val closingDateText = remember(pendingClosing.businessDateMillis) {
        SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault())
            .format(Date(pendingClosing.businessDateMillis))
    }
    val currentSourceLabel = when (currentSource) {
        OpeningBalanceSource.MANUAL -> "Manual Float"
        OpeningBalanceSource.ADJUSTED_FROM_PREVIOUS -> "Adjusted Float"
        OpeningBalanceSource.PREVIOUS_APPROVED -> "Previously Loaded Closing"
        OpeningBalanceSource.NONE -> "Current Entry"
    }

    AlertDialog(
        onDismissRequest = onKeepCurrent,
        containerColor = White,
        modifier = Modifier.testTag("confirm_replace_opening_dialog"),
        title = {
            Text(
                text = "Replace Opening Balance?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NavyText
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "You already have an active opening balance. Choose whether to replace it with the previous approved closing:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedNavyText
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SoftSurfaceVariant,
                    border = BorderStroke(1.dp, SubtleOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column {
                            Text(
                                text = "CURRENT ($currentSourceLabel)",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = MutedNavyText
                            )
                            Text(
                                text = formatBirr(currentBalanceMinor),
                                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Bold,
                                color = NavyText
                            )
                        }
                        HorizontalDivider(color = SubtleOutline)
                        Column {
                            Text(
                                text = "PREVIOUS APPROVED ($closingDateText)",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = CyanDeep
                            )
                            Text(
                                text = formatBirr(pendingClosing.amountMinor),
                                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Bold,
                                color = CyanDeep
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmReplace,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                modifier = Modifier.testTag("confirm_replace_balance_button")
            ) {
                Text("REPLACE", color = White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onKeepCurrent,
                modifier = Modifier.testTag("keep_current_balance_button")
            ) {
                Text("KEEP CURRENT", color = MutedNavyText, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

private data class Tuple5<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)

package com.example.ui.calculator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import com.example.data.local.SecurityManager
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberSoftTint
import com.example.ui.theme.CyanBorder
import com.example.ui.theme.CyanBright
import com.example.ui.theme.CyanDeep
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanSoftTint
import com.example.ui.theme.DangerBorder
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DangerSoftTint
import com.example.ui.theme.IceSurface
import com.example.ui.theme.MutedNavyText
import com.example.ui.theme.NavyText
import com.example.ui.theme.PlusJakartaSansFontFamily
import com.example.ui.theme.SoftSurfaceVariant
import com.example.ui.theme.SubtleOutline
import com.example.ui.theme.SuccessBorder
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessSoftTint
import com.example.ui.theme.VaultSlateDeep
import com.example.ui.theme.White
import com.example.ui.util.formatAmount
import com.example.ui.util.formatBirr
import com.example.ui.util.formatDenomination
import com.example.ui.util.formatSignedBirr
import com.example.ui.viewmodel.CashCalculatorUiState
import com.example.ui.viewmodel.CashViewModel
import com.example.ui.viewmodel.EtbParseResult
import com.example.ui.viewmodel.formatMinorAsFixedTwoDecimals
import com.example.ui.viewmodel.parseAndValidateEtbInput
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StepLabels = listOf(
    "Opening",
    "Bundles",
    "Petty",
    "Verify",
    "Closing"
)

private val InputSurfaceTint = Color(0xFFF8FCFF)

private fun sanitizeDecimalInput(input: String): String? {
    val filtered = input.filter { it.isDigit() || it == '.' }
    if (filtered.count { it == '.' } > 1) return null
    val parts = filtered.split('.')
    val intPart = parts[0].take(10)
    return if (parts.size == 2) {
        "$intPart.${parts[1].take(2)}"
    } else {
        intPart
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashCalculatorScreen(
    viewModel: CashViewModel,
    branchId: String,
    staffId: String,
    securityManager: SecurityManager
) {
    val uiState by viewModel.uiState.collectAsState()
    val biometricEnabled by securityManager.isBiometricEnabled().collectAsState(initial = false)
    val requireBiometricOnSubmit by securityManager.isRequireBiometricOnSubmit().collectAsState(initial = true)

    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val imeInsets = WindowInsets.ime
    val imeThresholdPx = with(density) { 48.dp.roundToPx() }
    val isKeyboardVisible by remember(imeInsets, density, imeThresholdPx) {
        derivedStateOf { imeInsets.getBottom(density) > imeThresholdPx }
    }
    var openingRawText by remember {
        mutableStateOf(
            if (uiState.hasConfirmedOpeningEntry || uiState.openingBalanceMinor > 0L) {
                formatMinorAsFixedTwoDecimals(uiState.openingBalanceMinor)
            } else {
                ""
            }
        )
    }

    LaunchedEffect(uiState.auditorNotificationMessage) {
        uiState.auditorNotificationMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissAuditorNotification()
        }
    }

    BackHandler(enabled = uiState.currentStep > 1 && !uiState.isSaving) {
        focusManager.clearFocus()
        viewModel.prevStep()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = IceSurface,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (uiState.currentStep !in listOf(2, 3)) {
                FintechCalculatorHeader(
                    currentStep = uiState.currentStep,
                    branchId = branchId
                )
            }
        },
        bottomBar = {
            when {
                uiState.currentStep in listOf(2, 3) -> {
                    if (!isKeyboardVisible) {
                        TableFooterNavigation(
                            onBack = {
                                focusManager.clearFocus()
                                viewModel.prevStep()
                            },
                            onContinue = {
                                focusManager.clearFocus()
                                viewModel.nextStep()
                            }
                        )
                    }
                }
                else -> {
                    WizardNavigation(
                        currentStep = uiState.currentStep,
                        onBack = {
                            focusManager.clearFocus()
                            viewModel.prevStep()
                        },
                        onContinue = {
                            focusManager.clearFocus()
                            if (uiState.currentStep == 1) {
                                val normalizedOnValid = when (val parsed = parseAndValidateEtbInput(openingRawText)) {
                                    is EtbParseResult.Valid -> formatMinorAsFixedTwoDecimals(parsed.minor)
                                    else -> null
                                }
                                if (viewModel.confirmOpeningAndProceed(openingRawText) && normalizedOnValid != null) {
                                    openingRawText = normalizedOnValid
                                }
                            } else {
                                viewModel.nextStep()
                            }
                        },
                        isSaving = uiState.isSaving,
                        continueEnabled = !(uiState.currentStep == 1 && uiState.isLoadingPreviousBalance)
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(IceSurface)
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedContent(
                targetState = uiState.currentStep,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp),
                transitionSpec = {
                    val duration = 160
                    if (targetState > initialState) {
                        slideInHorizontally(tween(duration)) { width -> width / 4 } + fadeIn(tween(duration)) togetherWith
                            slideOutHorizontally(tween(duration)) { width -> -width / 4 } + fadeOut(tween(duration))
                    } else {
                        slideInHorizontally(tween(duration)) { width -> -width / 4 } + fadeIn(tween(duration)) togetherWith
                            slideOutHorizontally(tween(duration)) { width -> width / 4 } + fadeOut(tween(duration))
                    }
                },
                label = "step_transition"
            ) { step ->
                val isTableStep = step in listOf(2, 3)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = if (isTableStep) 12.dp else 14.dp,
                            vertical = if (isTableStep) 8.dp else 12.dp
                        )
                ) {
                    when (step) {
                        1 -> Step1OpeningBalance(
                            state = uiState,
                            viewModel = viewModel,
                            branchId = branchId,
                            rawText = openingRawText,
                            onRawTextChanged = { openingRawText = it }
                        )
                        2 -> Step2BundleCash(uiState, viewModel)
                        3 -> Step3PettyCash(uiState, viewModel)
                        4 -> Step4SystemVerification(uiState, viewModel)
                        5 -> Step5SaveConfirmation(
                            state = uiState,
                            viewModel = viewModel,
                            branchId = branchId,
                            staffId = staffId,
                            biometricEnabled = biometricEnabled && requireBiometricOnSubmit,
                            securityManager = securityManager
                        )
                    }
                }
            }
        }
    }

    if (uiState.saveSuccess) {
        AlertDialog(
            onDismissRequest = {
                viewModel.clearSuccess()
                viewModel.prepareForNextDay()
            },
            containerColor = White,
            title = {
                Text(
                    if (uiState.saveWasUpdate) "Session Updated" else "Session Saved",
                    fontWeight = FontWeight.Bold,
                    color = NavyText
                )
            },
            text = {
                Text(
                    text = if (uiState.saveWasUpdate) {
                        "Updated this working day's calculation in History with your latest numbers."
                    } else {
                        "Cash session and balance saved to History."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedNavyText
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearSuccess()
                        viewModel.prepareForNextDay()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) { Text("NEXT DAY", color = White, fontWeight = FontWeight.Bold) }
            }
        )
    }

    if (uiState.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            containerColor = White,
            title = { Text("Notice") },
            text = { Text(uiState.error!!) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("OK", color = CyanPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun FintechCalculatorHeader(
    currentStep: Int,
    branchId: String
) {
    Surface(
        color = White,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "CASH CONTROL",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = PlusJakartaSansFontFamily,
                            fontSize = 13.5.sp,
                            letterSpacing = 0.5.sp
                        ),
                        fontWeight = FontWeight.ExtraBold,
                        color = VaultSlateDeep,
                        maxLines = 1
                    )
                    Text(
                        text = "· $currentStep/${StepLabels.size} ${StepLabels[(currentStep - 1).coerceIn(0, StepLabels.lastIndex)]}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MutedNavyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (branchId.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = CyanSoftTint,
                        border = BorderStroke(1.dp, CyanBorder)
                    ) {
                        Text(
                            text = branchId,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = CyanDeep,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (stepIndex in 1..StepLabels.size) {
                    val isCompleted = stepIndex < currentStep
                    val isActive = stepIndex == currentStep
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    isActive -> CyanPrimary
                                    isCompleted -> CyanDeep.copy(alpha = 0.72f)
                                    else -> SubtleOutline
                                }
                            )
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = SubtleOutline)
        }
    }
}

@Composable
fun WizardNavigation(
    currentStep: Int,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    isSaving: Boolean,
    continueEnabled: Boolean = true
) {
    Surface(
        color = White,
        shadowElevation = 0.dp
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = SubtleOutline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 1) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier
                            .height(48.dp)
                            .weight(1f)
                            .testTag("wizard_back_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, SubtleOutline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NavyText),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        enabled = !isSaving
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (currentStep == 5) "Back to Verify" else "Back",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (currentStep < 5) {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .height(48.dp)
                            .weight(1f)
                            .testTag("wizard_continue_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        enabled = !isSaving && continueEnabled
                    ) {
                        Text(
                            text = when (currentStep) {
                                1 -> "Continue to Bundles"
                                4 -> "Continue to Closing"
                                else -> "Continue"
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TableFooterNavigation(
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    Surface(
        color = White,
        shadowElevation = 0.dp
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = SubtleOutline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .height(48.dp)
                        .weight(1f)
                        .testTag("table_footer_back_button"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SubtleOutline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NavyText)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Back", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }

                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .height(48.dp)
                        .weight(1f)
                        .testTag("table_footer_continue_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text("Continue", fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun Step2BundleCash(
    state: CashCalculatorUiState,
    viewModel: CashViewModel
) {
    val activeNotesPerBundle = state.bundleDenominations.firstOrNull()?.notesPerBundle ?: 1000
    var showBundleConfigDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            shape = RoundedCornerShape(14.dp),
            color = White,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, SubtleOutline)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(White)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Bundle Cash",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = PlusJakartaSansFontFamily,
                                        fontSize = 15.5.sp
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = NavyText
                                )
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = SoftSurfaceVariant,
                                    border = BorderStroke(1.dp, SubtleOutline)
                                ) {
                                    Text(
                                        text = "2/5",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MutedNavyText,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = formatBirr(state.bundleCashTotalMinor),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontSize = 15.sp,
                                    fontFeatureSettings = "tnum"
                                ),
                                fontWeight = FontWeight.ExtraBold,
                                color = CyanPrimary,
                                modifier = Modifier.testTag("bundle_cash_header_total")
                            )
                        }
                        Surface(
                            onClick = { showBundleConfigDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            color = SoftSurfaceVariant,
                            border = BorderStroke(1.dp, SubtleOutline),
                            modifier = Modifier.heightIn(min = 36.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "1 bdl = ×$activeNotesPerBundle",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = NavyText
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = "Configure bundle notes",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(SubtleOutline)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.4f)
                                .fillMaxHeight()
                                .background(CyanPrimary)
                        )
                    }
                }
                HorizontalDivider(color = SubtleOutline)

                DenominationTableHeader(
                    denomHeader = "NOTE",
                    qtyHeader = "BUNDLES",
                    totalHeader = "SUBTOTAL (ETB)"
                )
                HorizontalDivider(color = SubtleOutline)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(
                        items = state.bundleDenominations,
                        key = { _, entry -> entry.denominationMinor }
                    ) { index, entry ->
                        val isLast = index == state.bundleDenominations.lastIndex
                        DenominationTableRow(
                            denominationLabel = "ETB${formatDenomination(entry.denominationBirr)}",
                            quantityValue = entry.bundles,
                            subtotalMinor = entry.subtotalMinor,
                            testTag = "bundle_${entry.denominationBirr}",
                            isLast = isLast,
                            maxDigits = 5,
                            extraCaption = if (entry.looseNotes > 0) "+${entry.looseNotes} loose" else null,
                            onQuantityChange = { bundles ->
                                viewModel.updateBundleEntry(
                                    index = index,
                                    bundles = bundles,
                                    notesPerBundle = entry.notesPerBundle,
                                    looseNotes = entry.looseNotes
                                )
                            }
                        )
                        if (!isLast) {
                            HorizontalDivider(
                                color = SubtleOutline.copy(alpha = 0.55f),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBundleConfigDialog) {
        var customNotes by remember { mutableStateOf(activeNotesPerBundle.toString()) }
        AlertDialog(
            onDismissRequest = { showBundleConfigDialog = false },
            containerColor = White,
            title = { Text("Notes per Bundle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Standard bank bundles contain 1,000 notes per bundle. Adjust if needed:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedNavyText
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(100, 500, 1000).forEach { preset ->
                            FilterChip(
                                selected = customNotes == preset.toString(),
                                onClick = { customNotes = preset.toString() },
                                label = { Text("$preset") }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = customNotes,
                        onValueChange = { input -> customNotes = input.filter { it.isDigit() }.take(4) },
                        label = { Text("Notes per bundle") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val notes = (customNotes.toIntOrNull() ?: 1000).coerceIn(1, 9999)
                    state.bundleDenominations.forEachIndexed { idx, entry ->
                        viewModel.updateBundleEntry(idx, entry.bundles, notes, entry.looseNotes)
                    }
                    showBundleConfigDialog = false
                }) { Text("APPLY", color = CyanPrimary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showBundleConfigDialog = false }) { Text("CANCEL") }
            }
        )
    }
}

@Composable
fun Step3PettyCash(
    state: CashCalculatorUiState,
    viewModel: CashViewModel
) {
    val activeRows = remember(state.pettyDenominations) {
        state.pettyDenominations.count { it.quantity > 0 }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            shape = RoundedCornerShape(14.dp),
            color = White,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, SubtleOutline)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(White)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Petty Cash",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = PlusJakartaSansFontFamily,
                                    fontSize = 15.5.sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = NavyText
                            )
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = SoftSurfaceVariant,
                                border = BorderStroke(1.dp, SubtleOutline)
                            ) {
                                Text(
                                    text = "3/5",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MutedNavyText,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (activeRows > 0) CyanSoftTint else SoftSurfaceVariant,
                            border = BorderStroke(1.dp, if (activeRows > 0) CyanBorder else SubtleOutline)
                        ) {
                            Text(
                                text = if (activeRows > 0) "$activeRows active" else "${state.pettyDenominations.size} denoms",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontFeatureSettings = "tnum"
                                ),
                                fontWeight = FontWeight.SemiBold,
                                color = if (activeRows > 0) CyanDeep else MutedNavyText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // 3-part header bar displaying Petty, Bundle, and Combined Total cleanly over table
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = IceSurface,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HeaderTotalMetric(
                                label = "PETTY",
                                value = formatBirr(state.pettyCashTotalMinor),
                                valueColor = NavyText,
                                modifier = Modifier.weight(1f),
                                testTag = "petty_cash_header_total"
                            )
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(SubtleOutline)
                            )
                            HeaderTotalMetric(
                                label = "BUNDLE",
                                value = formatBirr(state.bundleCashTotalMinor),
                                valueColor = MutedNavyText,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp),
                                testTag = "bundle_summary_header_total"
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = CyanSoftTint,
                                border = BorderStroke(1.dp, CyanBorder),
                                modifier = Modifier.weight(1.1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = "TOTAL",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            letterSpacing = 0.5.sp
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = CyanDeep
                                    )
                                    Text(
                                        text = formatBirr(state.totalCountedMinor),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontSize = 12.sp,
                                            fontFeatureSettings = "tnum"
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CyanPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("combined_cash_header_total")
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(SubtleOutline)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .fillMaxHeight()
                                .background(CyanPrimary)
                        )
                    }
                }
                HorizontalDivider(color = SubtleOutline)

                DenominationTableHeader(
                    denomHeader = "DENOM",
                    qtyHeader = "QTY",
                    totalHeader = "SUBTOTAL (ETB)"
                )
                HorizontalDivider(color = SubtleOutline)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    itemsIndexed(
                        items = state.pettyDenominations,
                        key = { _, entry -> entry.denominationMinor }
                    ) { index, entry ->
                        val isLast = index == state.pettyDenominations.lastIndex
                        DenominationTableRow(
                            denominationLabel = "ETB${formatDenomination(entry.denominationBirr)}",
                            quantityValue = entry.quantity,
                            subtotalMinor = entry.subtotalMinor,
                            testTag = "qty_${entry.denominationBirr}",
                            isLast = isLast,
                            maxDigits = 6,
                            onQuantityChange = { qty ->
                                viewModel.updatePettyQuantity(index, qty)
                            }
                        )
                        if (!isLast) {
                            HorizontalDivider(
                                color = SubtleOutline.copy(alpha = 0.55f),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderTotalMetric(
    label: String,
    value: String,
    valueColor: Color,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                letterSpacing = 0.4.sp
            ),
            fontWeight = FontWeight.Bold,
            color = MutedNavyText
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 11.5.sp,
                fontFeatureSettings = "tnum"
            ),
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun DenominationTableHeader(
    denomHeader: String,
    qtyHeader: String,
    totalHeader: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SoftSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = denomHeader,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                letterSpacing = 0.5.sp
            ),
            fontWeight = FontWeight.Bold,
            color = MutedNavyText,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(0.22f),
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = qtyHeader,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                letterSpacing = 0.5.sp
            ),
            fontWeight = FontWeight.Bold,
            color = MutedNavyText,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(0.40f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = totalHeader,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                letterSpacing = 0.5.sp
            ),
            fontWeight = FontWeight.Bold,
            color = MutedNavyText,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.38f),
            maxLines = 1
        )
    }
}

@Composable
private fun DenominationTableRow(
    denominationLabel: String,
    quantityValue: Int,
    subtotalMinor: Long,
    testTag: String,
    isLast: Boolean,
    maxDigits: Int = 6,
    extraCaption: String? = null,
    onQuantityChange: (Int) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    val hasValue = quantityValue > 0

    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 1.5.dp else 1.dp,
        label = "cell_border"
    )
    val borderColor = when {
        isFocused -> CyanPrimary
        hasValue -> CyanBorder
        else -> SubtleOutline
    }
    val inputBg = when {
        isFocused -> InputSurfaceTint
        hasValue -> CyanSoftTint.copy(alpha = 0.45f)
        else -> White
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Compact denomination badge
        Box(
            modifier = Modifier
                .weight(0.22f)
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (hasValue) CyanSoftTint.copy(alpha = 0.55f) else SoftSurfaceVariant)
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = denominationLabel,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = PlusJakartaSansFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                ),
                color = if (hasValue) CyanDeep else NavyText,
                maxLines = 1,
                softWrap = false
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Middle Column: Wider, well-proportioned quantity box
        Surface(
            modifier = Modifier
                .weight(0.40f)
                .height(38.dp),
            shape = RoundedCornerShape(8.dp),
            color = inputBg,
            border = BorderStroke(borderWidth, borderColor)
        ) {
            val textValue = if (quantityValue == 0) "" else quantityValue.toString()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp)
            ) {
                if (textValue.isEmpty()) {
                    Text(
                        text = "0",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Normal,
                            fontFeatureSettings = "tnum"
                        ),
                        color = MutedNavyText.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center
                    )
                }
                BasicTextField(
                    value = textValue,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(maxDigits)
                        onQuantityChange(digits.toIntOrNull() ?: 0)
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = PlusJakartaSansFontFamily,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        color = NavyText,
                        textAlign = TextAlign.Center
                    ),
                    cursorBrush = SolidColor(CyanPrimary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = if (isLast) ImeAction.Done else ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        onDone = { focusManager.clearFocus() }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused }
                        .testTag(testTag)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Right Column: Distinct SuccessGreen subtotal readout for instant visual separation
        Column(
            modifier = Modifier
                .weight(0.38f)
                .padding(end = 2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.End
        ) {
            if (subtotalMinor > 0L) {
                val amountFormatted = formatAmount(subtotalMinor)
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = SuccessSoftTint.copy(alpha = 0.72f)
                ) {
                    Text(
                        text = amountFormatted,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = if (amountFormatted.length > 11) 11.5.sp else 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        ),
                        color = SuccessGreen,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
            } else {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MutedNavyText.copy(alpha = 0.45f)
                )
            }
            if (extraCaption != null) {
                Text(
                    text = extraCaption,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MutedNavyText,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun Step4SystemVerification(state: CashCalculatorUiState, viewModel: CashViewModel) {
    val focusManager = LocalFocusManager.current
    val currentDrBirr = state.totalDRMinor / 100.0
    val currentCrBirr = state.totalCRMinor / 100.0

    var drText by remember {
        mutableStateOf(if (state.totalDRMinor == 0L) "" else formatEditableAmount(currentDrBirr))
    }
    var crText by remember {
        mutableStateOf(if (state.totalCRMinor == 0L) "" else formatEditableAmount(currentCrBirr))
    }

    LaunchedEffect(state.totalDRMinor) {
        val parsed = drText.toDoubleOrNull() ?: 0.0
        if (parsed != currentDrBirr) {
            drText = if (state.totalDRMinor == 0L) "" else formatEditableAmount(currentDrBirr)
        }
    }
    LaunchedEffect(state.totalCRMinor) {
        val parsed = crText.toDoubleOrNull() ?: 0.0
        if (parsed != currentCrBirr) {
            crText = if (state.totalCRMinor == 0L) "" else formatEditableAmount(currentCrBirr)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        state.latestAuditorCheckForDate?.let { check ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cashier_auditor_dr_cr_banner"),
                shape = RoundedCornerShape(10.dp),
                color = CyanSoftTint,
                border = BorderStroke(1.dp, CyanBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.FactCheck,
                            contentDescription = "Auditor Verified",
                            tint = CyanDeep,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = "Auditor ${check.auditorStaffId.ifBlank { "Check" }} · ${check.checkStatus}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyanDeep
                            )
                            Text(
                                text = "DR ${formatBirr(check.totalDRMinor)} · CR ${formatBirr(check.totalCRMinor)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = NavyText
                            )
                        }
                    }
                    TextButton(
                        onClick = { viewModel.applyAuditorCheckToCashier(check, showNotification = false) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("apply_auditor_dr_cr_button")
                    ) {
                        Text(
                            text = if (state.totalDRMinor == check.totalDRMinor && state.totalCRMinor == check.totalCRMinor) {
                                "Applied"
                            } else {
                                "Use DR/CR"
                            },
                            fontWeight = FontWeight.Bold,
                            color = CyanDeep
                        )
                    }
                }
            }
        }

        // Clean DR & CR Entry Card
        val currencyVisual = remember { ThousandsSeparatorVisualTransformation() }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = White,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, SubtleOutline)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = drText,
                    onValueChange = { input ->
                        val sanitized = sanitizeDecimalInput(input)
                        if (sanitized != null) {
                            drText = sanitized
                            viewModel.setDrAmount(sanitized.toDoubleOrNull() ?: 0.0)
                        }
                    },
                    label = { Text("Cash In · DR (+)") },
                    placeholder = { Text("0.00") },
                    prefix = { Text("ETB ", fontWeight = FontWeight.Bold, color = MutedNavyText) },
                    visualTransformation = currencyVisual,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                        color = NavyText
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dr_amount_input_0"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = SubtleOutline,
                        focusedLabelColor = CyanPrimary,
                        unfocusedLabelColor = MutedNavyText,
                        focusedContainerColor = White,
                        unfocusedContainerColor = IceSurface,
                        cursorColor = CyanPrimary
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                OutlinedTextField(
                    value = crText,
                    onValueChange = { input ->
                        val sanitized = sanitizeDecimalInput(input)
                        if (sanitized != null) {
                            crText = sanitized
                            viewModel.setCrAmount(sanitized.toDoubleOrNull() ?: 0.0)
                        }
                    },
                    label = { Text("Cash Out · CR (-)") },
                    placeholder = { Text("0.00") },
                    prefix = { Text("ETB ", fontWeight = FontWeight.Bold, color = MutedNavyText) },
                    visualTransformation = currencyVisual,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                        color = NavyText
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cr_amount_input_0"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = SubtleOutline,
                        focusedLabelColor = CyanPrimary,
                        unfocusedLabelColor = MutedNavyText,
                        focusedContainerColor = White,
                        unfocusedContainerColor = IceSurface,
                        cursorColor = CyanPrimary
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )
            }
        }

        // Compact Live Balance Comparison Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = White,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, SubtleOutline)
        ) {
            val varianceColor = when (state.reconciliationStatus) {
                "BALANCED" -> SuccessGreen
                "SHORTAGE" -> MaterialTheme.colorScheme.error
                else -> AmberAccent
            }
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
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
                    Surface(
                        color = varianceColor.copy(alpha = 0.13f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = state.reconciliationStatus,
                            color = varianceColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SoftSurfaceVariant,
                    border = BorderStroke(1.dp, SubtleOutline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Expected",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MutedNavyText
                            )
                            Text(
                                text = formatBirr(state.expectedEndingMinor),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.sp,
                                    fontFeatureSettings = "tnum"
                                ),
                                fontWeight = FontWeight.Bold,
                                color = NavyText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        VerticalDivider(
                            thickness = 1.dp,
                            color = SubtleOutline,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Counted",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MutedNavyText
                            )
                            Text(
                                text = formatBirr(state.totalCountedMinor),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.sp,
                                    fontFeatureSettings = "tnum"
                                ),
                                fontWeight = FontWeight.Bold,
                                color = NavyText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Variance",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MutedNavyText
                    )
                    Text(
                        text = formatBirr(state.differenceMinor),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 15.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        fontWeight = FontWeight.Bold,
                        color = varianceColor
                    )
                }
            }
        }
    }
}

private fun formatEditableAmount(amountBirr: Double): String {
    return String.format(Locale.US, "%.2f", amountBirr)
        .trimEnd('0')
        .trimEnd('.')
}

@Composable
fun Step5SaveConfirmation(
    state: CashCalculatorUiState,
    viewModel: CashViewModel,
    branchId: String,
    staffId: String,
    biometricEnabled: Boolean,
    securityManager: SecurityManager
) {
    val context = LocalContext.current
    var showCashBookPreview by remember { mutableStateOf(false) }
    val formattedBusinessDate = remember(state.selectedBusinessDateMillis) {
        SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault())
            .format(Date(state.selectedBusinessDateMillis))
    }

    val (varianceBg, varianceBorder, varianceColor, varianceTitle) = when (state.reconciliationStatus) {
        "BALANCED" -> QuadColor(SuccessSoftTint, SuccessBorder, SuccessGreen, "BALANCED")
        "SHORTAGE" -> QuadColor(DangerSoftTint, DangerBorder, DangerRed, "SHORTAGE")
        else -> QuadColor(AmberSoftTint, AmberAccent.copy(alpha = 0.35f), AmberAccent, "EXCESS")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            onClick = { showCashBookPreview = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .testTag("preview_cash_book_button"),
            shape = RoundedCornerShape(12.dp),
            color = White,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, SubtleOutline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = "Preview Physical Cash Book",
                        tint = CyanDeep,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Physical Cash Book",
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                        fontWeight = FontWeight.Bold,
                        color = NavyText
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = CyanSoftTint,
                    border = BorderStroke(1.dp, CyanBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Preview",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = FontWeight.Bold,
                            color = CyanDeep
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = CyanDeep,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = White,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, SubtleOutline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Confirm Daily Cash Closing",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = PlusJakartaSansFontFamily,
                            fontSize = 15.5.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = NavyText,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SoftSurfaceVariant,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.testTag("closing_business_date_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CalendarToday,
                                contentDescription = "Business Date",
                                tint = CyanDeep,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = formattedBusinessDate,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = NavyText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }

                // 1. Physical Cash Count Card (Bundle + Petty -> Total Counted)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("closing_counted_summary_card"),
                    shape = RoundedCornerShape(12.dp),
                    color = IceSurface,
                    border = BorderStroke(1.dp, SubtleOutline)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "BUNDLE CASH",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        letterSpacing = 0.4.sp
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MutedNavyText
                                )
                                Text(
                                    text = formatBirr(state.bundleCashTotalMinor),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.5.sp,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = NavyText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(28.dp)
                                    .background(SubtleOutline)
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "PETTY CASH",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        letterSpacing = 0.4.sp
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MutedNavyText
                                )
                                Text(
                                    text = formatBirr(state.pettyCashTotalMinor),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.5.sp,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = NavyText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        HorizontalDivider(color = SubtleOutline)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(White)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total Physical Cash",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                fontWeight = FontWeight.Bold,
                                color = NavyText
                            )
                            Text(
                                text = formatBirr(state.totalCountedMinor),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontSize = 15.sp,
                                    fontFeatureSettings = "tnum"
                                ),
                                fontWeight = FontWeight.ExtraBold,
                                color = CyanPrimary,
                                modifier = Modifier.testTag("closing_total_counted_value")
                            )
                        }
                    }
                }

                // 2. Ledger Expected vs. Counted Comparison & Status Variance Strip
                if (state.useSystemVerification) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("closing_reconciliation_card"),
                        shape = RoundedCornerShape(12.dp),
                        color = White,
                        border = BorderStroke(1.dp, SubtleOutline)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "SYSTEM RECONCILIATION",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = MutedNavyText
                            )
                            ClosingLedgerLine(
                                label = "Opening Balance",
                                value = formatBirr(state.openingBalanceMinor),
                                valueColor = NavyText
                            )
                            ClosingLedgerLine(
                                label = "Cash In · DR (+)",
                                value = formatSignedBirr(state.totalDRMinor),
                                valueColor = SuccessGreen
                            )
                            ClosingLedgerLine(
                                label = "Cash Out · CR (−)",
                                value = formatBirr(-kotlin.math.abs(state.totalCRMinor)),
                                valueColor = DangerRed
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = SubtleOutline
                            )
                            ClosingLedgerLine(
                                label = "Expected Ending Balance",
                                value = formatBirr(state.expectedEndingMinor),
                                valueColor = NavyText,
                                bold = true
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = varianceBg,
                                border = BorderStroke(1.dp, varianceBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("closing_variance_card")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(1.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(varianceColor)
                                            )
                                            Text(
                                                text = "VARIANCE · $varianceTitle",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.5.sp,
                                                    letterSpacing = 0.4.sp
                                                ),
                                                fontWeight = FontWeight.Bold,
                                                color = varianceColor
                                            )
                                        }
                                        Text(
                                            text = when (state.reconciliationStatus) {
                                                "BALANCED" -> "Physical count matches expected balance"
                                                "SHORTAGE" -> "Counted cash is below expected balance"
                                                else -> "Counted cash is above expected balance"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            color = MutedNavyText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = formatSignedBirr(state.differenceMinor),
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontSize = 14.5.sp,
                                            fontFeatureSettings = "tnum"
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = varianceColor,
                                        modifier = Modifier.testTag("closing_variance_amount")
                                    )
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.saveSession(branchId, staffId, isFinal = false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_draft_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, CyanBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanDeep),
                        enabled = !state.isSaving
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save as Draft", fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            if (biometricEnabled) {
                                val activity = context as? FragmentActivity
                                if (activity != null) {
                                    securityManager.showBiometricPrompt(
                                        activity = activity,
                                        title = "Final Submission",
                                        subtitle = "Authenticate to finalize today's cash count",
                                        onSuccess = { viewModel.saveSession(branchId, staffId, isFinal = true) },
                                        onError = { _, _ -> },
                                        onFailed = { }
                                    )
                                } else {
                                    viewModel.saveSession(branchId, staffId, isFinal = true)
                                }
                            } else {
                                viewModel.saveSession(branchId, staffId, isFinal = true)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_session_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                        enabled = !state.isSaving
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (biometricEnabled) Icons.Rounded.Fingerprint else Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Submit Final Closing", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showCashBookPreview) {
        CashBookPreviewDialog(
            state = state,
            branchId = branchId,
            staffId = staffId,
            onDismiss = { showCashBookPreview = false }
        )
    }
}

private data class QuadColor(
    val background: Color,
    val border: Color,
    val foreground: Color,
    val label: String
)

@Composable
private fun ClosingLedgerLine(
    label: String,
    value: String,
    valueColor: Color,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = if (bold) NavyText else MutedNavyText,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = if (bold) 13.5.sp else 12.5.sp,
                fontFeatureSettings = "tnum"
            ),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1
        )
    }
}

@Composable
fun SummaryRow(label: String, value: String, fontWeight: FontWeight = FontWeight.Normal) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = fontWeight,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun CashBookPreviewDialog(
    state: CashCalculatorUiState,
    branchId: String,
    staffId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var copiedNotice by remember { mutableStateOf(false) }
    val dateLabel = remember(state.selectedBusinessDateMillis) {
        SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault())
            .format(Date(state.selectedBusinessDateMillis))
    }

    LaunchedEffect(copiedNotice) {
        if (copiedNotice) {
            delay(1800)
            copiedNotice = false
        }
    }

    fun copyCashBook() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(
                "Cash Book Preview",
                buildCashBookPlainText(state, dateLabel, branchId, staffId)
            )
        )
        copiedNotice = true
    }

    val varianceColor = when (state.reconciliationStatus) {
        "BALANCED" -> SuccessGreen
        "SHORTAGE" -> MaterialTheme.colorScheme.error
        else -> AmberAccent
    }
    val tabularStyle = MaterialTheme.typography.bodySmall.copy(
        fontFeatureSettings = "tnum"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = 720.dp)
                .testTag("cash_book_preview_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = White,
            shadowElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(White)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PHYSICAL CASH BOOK",
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp),
                                fontWeight = FontWeight.ExtraBold,
                                color = NavyText,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = buildString {
                                    append(dateLabel)
                                    if (branchId.isNotBlank()) append(" · $branchId")
                                    if (staffId.isNotBlank()) append(" · $staffId")
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MutedNavyText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = { copyCashBook() },
                                modifier = Modifier.testTag("copy_cash_book_button")
                            ) {
                                Icon(
                                    imageVector = if (copiedNotice) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy Cash Book Text",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("close_cash_book_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Close Preview",
                                    tint = MutedNavyText,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = SubtleOutline)
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (copiedNotice) {
                        Surface(
                            color = SuccessGreen.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Copied ledger summary to clipboard.",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = SuccessGreen,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }

                    // 1. Bundle Cash Table
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = White,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SoftSurfaceVariant)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "BUNDLE CASH",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanPrimary
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("NOTE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MutedNavyText, modifier = Modifier.weight(0.28f))
                                Text("BDL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MutedNavyText, textAlign = TextAlign.Center, modifier = Modifier.weight(0.28f))
                                Text("VALUE (ETB)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MutedNavyText, textAlign = TextAlign.End, modifier = Modifier.weight(0.44f))
                            }
                            HorizontalDivider(color = SubtleOutline)

                            val sortedBundles = remember(state.bundleDenominations) {
                                state.bundleDenominations.sortedByDescending { it.denominationMinor }
                            }
                            sortedBundles.forEachIndexed { idx, entry ->
                                val rowBg = if (idx % 2 == 1) SoftSurfaceVariant.copy(alpha = 0.45f) else White
                                val active = entry.subtotalMinor > 0L
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(rowBg)
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatDenomination(entry.denominationBirr),
                                        style = tabularStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = NavyText,
                                        modifier = Modifier.weight(0.28f)
                                    )
                                    Text(
                                        text = entry.bundles.toString(),
                                        style = tabularStyle,
                                        color = if (active) NavyText else MutedNavyText,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(0.28f)
                                    )
                                    Text(
                                        text = formatAmount(entry.subtotalMinor),
                                        style = tabularStyle,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) NavyText else MutedNavyText,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(0.44f)
                                    )
                                }
                            }

                            HorizontalDivider(color = CyanBorder)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CyanSoftTint.copy(alpha = 0.45f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Bundle Cash Total",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanDeep
                                )
                                Text(
                                    text = formatBirr(state.bundleCashTotalMinor),
                                    style = tabularStyle.copy(fontSize = 13.sp),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NavyText
                                )
                            }
                        }
                    }

                    // 2. Petty Cash Table
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = White,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SoftSurfaceVariant)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PETTY CASH",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanPrimary
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("DENOM", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MutedNavyText, modifier = Modifier.weight(0.28f))
                                Text("QTY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MutedNavyText, textAlign = TextAlign.Center, modifier = Modifier.weight(0.28f))
                                Text("VALUE (ETB)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MutedNavyText, textAlign = TextAlign.End, modifier = Modifier.weight(0.44f))
                            }
                            HorizontalDivider(color = SubtleOutline)

                            val sortedPetty = remember(state.pettyDenominations) {
                                state.pettyDenominations.sortedByDescending { it.denominationMinor }
                            }
                            sortedPetty.forEachIndexed { idx, entry ->
                                val rowBg = if (idx % 2 == 1) SoftSurfaceVariant.copy(alpha = 0.45f) else White
                                val active = entry.subtotalMinor > 0L
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(rowBg)
                                        .padding(horizontal = 12.dp, vertical = 6.5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatDenomination(entry.denominationBirr),
                                        style = tabularStyle,
                                        fontWeight = FontWeight.Bold,
                                        color = NavyText,
                                        modifier = Modifier.weight(0.28f)
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%,d", entry.quantity),
                                        style = tabularStyle,
                                        color = if (active) NavyText else MutedNavyText,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(0.28f)
                                    )
                                    Text(
                                        text = formatAmount(entry.subtotalMinor),
                                        style = tabularStyle,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) NavyText else MutedNavyText,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(0.44f)
                                    )
                                }
                            }

                            HorizontalDivider(color = CyanBorder)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CyanSoftTint.copy(alpha = 0.45f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Petty Cash Total",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanDeep
                                )
                                Text(
                                    text = formatBirr(state.pettyCashTotalMinor),
                                    style = tabularStyle.copy(fontSize = 13.sp),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NavyText
                                )
                            }
                        }
                    }

                    // 3. DR, CR & Reconciliation Totals
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = White,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "CASH BOOK TOTALS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyanPrimary
                            )
                            CashBookLedgerLine("Opening Balance", formatBirr(state.openingBalanceMinor), tabularStyle)
                            CashBookLedgerLine("DR (+)", formatBirr(state.totalDRMinor), tabularStyle, valueColor = SuccessGreen)
                            CashBookLedgerLine("CR (-)", formatBirr(state.totalCRMinor), tabularStyle, valueColor = MaterialTheme.colorScheme.error)
                            CashBookLedgerLine("Expected Balance", formatBirr(state.expectedEndingMinor), tabularStyle, bold = true)
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = SubtleOutline)
                            CashBookLedgerLine("Bundle + Petty (Counted)", formatBirr(state.totalCountedMinor), tabularStyle, bold = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(varianceColor.copy(alpha = 0.10f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Difference (${state.reconciliationStatus})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = varianceColor
                                )
                                Text(
                                    text = formatBirr(state.differenceMinor),
                                    style = tabularStyle.copy(fontSize = 13.sp),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = varianceColor
                                )
                            }
                        }
                    }
                }

                // Bottom Copy & Done Actions
                HorizontalDivider(color = SubtleOutline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { copyCashBook() },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CyanBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("copy_cash_book_bottom_button")
                    ) {
                        Icon(
                            imageVector = if (copiedNotice) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (copiedNotice) "COPIED" else "COPY",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    ) {
                        Text("DONE", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun CashBookLedgerLine(
    label: String,
    value: String,
    valueStyle: TextStyle,
    bold: Boolean = false,
    valueColor: Color = NavyText
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (bold) NavyText else MutedNavyText
        )
        Text(
            text = value,
            style = valueStyle,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = valueColor
        )
    }
}

private fun buildCashBookPlainText(
    state: CashCalculatorUiState,
    dateLabel: String,
    branchId: String,
    staffId: String
): String {
    return buildString {
        appendLine("CASH CONTROL - PHYSICAL CASH BOOK")
        appendLine("Date: $dateLabel | Branch: $branchId | Staff: $staffId")
        appendLine("Opening Balance: ${formatBirr(state.openingBalanceMinor)}")
        appendLine()
        appendLine("BUNDLE CASH:")
        state.bundleDenominations
            .sortedByDescending { it.denominationMinor }
            .forEach { entry ->
                appendLine("  ${formatDenomination(entry.denominationBirr)} x ${entry.bundles} bdl = ${formatBirr(entry.subtotalMinor)}")
            }
        appendLine("  Bundle Total: ${formatBirr(state.bundleCashTotalMinor)}")
        appendLine()
        appendLine("PETTY CASH:")
        state.pettyDenominations
            .sortedByDescending { it.denominationMinor }
            .forEach { entry ->
                appendLine("  ${formatDenomination(entry.denominationBirr)} x ${entry.quantity} = ${formatBirr(entry.subtotalMinor)}")
            }
        appendLine("  Petty Total: ${formatBirr(state.pettyCashTotalMinor)}")
        appendLine()
        appendLine("DR (+): ${formatBirr(state.totalDRMinor)}")
        appendLine("CR (-): ${formatBirr(state.totalCRMinor)}")
        appendLine("Expected: ${formatBirr(state.expectedEndingMinor)}")
        appendLine("Counted: ${formatBirr(state.totalCountedMinor)}")
        appendLine("Difference (${state.reconciliationStatus}): ${formatBirr(state.differenceMinor)}")
    }
}


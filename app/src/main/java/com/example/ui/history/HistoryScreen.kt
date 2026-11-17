package com.example.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entities.CashSession
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberSoftTint
import com.example.ui.theme.CyanBorder
import com.example.ui.theme.CyanDeep
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanSoftTint
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DangerSoftTint
import com.example.ui.theme.IceSurface
import com.example.ui.theme.MutedNavyText
import com.example.ui.theme.NavyText
import com.example.ui.theme.PlusJakartaSansFontFamily
import com.example.ui.theme.SoftSurfaceVariant
import com.example.ui.theme.SubtleOutline
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessSoftTint
import com.example.ui.theme.VaultSlateDeep
import com.example.ui.theme.White
import com.example.ui.util.exportSessionsToCsv
import com.example.ui.util.formatAmount
import com.example.ui.util.formatBirr
import com.example.ui.util.formatDenomination
import com.example.ui.viewmodel.CashViewModel
import com.example.ui.viewmodel.SessionLedgerDetail
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: CashViewModel,
    branchId: String = "",
    isAllBranches: Boolean = false,
    canUseCounts: Boolean = true,
    canApprove: Boolean = false,
    approverName: String = "Approver",
    onNavigateToCalculator: () -> Unit = {}
) {
    val history by viewModel.sessionHistory.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedBranchFilter by remember { mutableStateOf("ALL") }

    val visibleHistory = remember(history, branchId, isAllBranches, selectedBranchFilter) {
        if (!isAllBranches) {
            history.filter { branchId.isBlank() || it.branchId.equals(branchId, ignoreCase = true) }
        } else if (selectedBranchFilter == "ALL") {
            history
        } else {
            history.filter { it.branchId.equals(selectedBranchFilter, ignoreCase = true) }
        }
    }

    val branchFilters = remember(history) {
        history.map { it.branchId.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }

    LaunchedEffect(uiState.historyNotice) {
        uiState.historyNotice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            viewModel.clearHistoryNotice()
        }
    }

    val pendingSyncCount = remember(visibleHistory) { visibleHistory.count { it.syncStatus != "SYNCED" } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = IceSurface,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(White)) {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "History",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = PlusJakartaSansFontFamily,
                                    fontSize = 18.sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = NavyText
                            )
                            val badgeLabel = if (isAllBranches) {
                                if (selectedBranchFilter == "ALL") "ALL BRANCHES" else selectedBranchFilter
                            } else {
                                branchId
                            }
                            if (badgeLabel.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(50.dp),
                                    color = CyanSoftTint,
                                    border = BorderStroke(1.dp, CyanBorder)
                                ) {
                                    Text(
                                        text = badgeLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = CyanDeep,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = White),
                    actions = {
                        IconButton(
                            onClick = { exportSessionsToCsv(context, visibleHistory) },
                            enabled = visibleHistory.isNotEmpty(),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("export_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Export CSV",
                                tint = if (visibleHistory.isNotEmpty()) CyanPrimary else MutedNavyText.copy(alpha = 0.4f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                )
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
            if (visibleHistory.isEmpty()) {
                EmptyHistoryState(
                    isSyncing = uiState.isSyncingHistory,
                    onSyncClick = {
                        if (isAllBranches || branchId.isNotBlank()) {
                            viewModel.refreshHistory(branchId)
                        }
                    }
                )
            } else {
                val latestSession = visibleHistory.first()
                val balancedCount = remember(visibleHistory) {
                    visibleHistory.count { it.reconciliationStatus == "BALANCED" }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 600.dp)
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp)
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(Brush.linearGradient(listOf(VaultSlateDeep, CyanDeep, CyanPrimary)))
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "LATEST ENDING BALANCE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            letterSpacing = 0.9.sp
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = White.copy(alpha = 0.78f)
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = formatBirr(latestSession.totalCountedMinor),
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontFeatureSettings = "tnum"
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = White.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, White.copy(alpha = 0.22f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        Text(
                                            text = "${visibleHistory.size} Records",
                                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                            fontWeight = FontWeight.Bold,
                                            color = White
                                        )
                                        Text(
                                            text = "$balancedCount balanced",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.5.sp,
                                                fontFeatureSettings = "tnum"
                                            ),
                                            color = White.copy(alpha = 0.82f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isAllBranches && branchFilters.size > 1) {
                        item {
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                item {
                                    FilterChip(
                                        selected = selectedBranchFilter == "ALL",
                                        onClick = { selectedBranchFilter = "ALL" },
                                        label = { Text("All (${history.size})") }
                                    )
                                }
                                items(branchFilters) { b ->
                                    FilterChip(
                                        selected = selectedBranchFilter == b,
                                        onClick = { selectedBranchFilter = b },
                                        label = { Text(b) }
                                    )
                                }
                            }
                        }
                    }

                    if (pendingSyncCount > 0) {
                        item {
                            Surface(
                                onClick = {
                                    if (isAllBranches || branchId.isNotBlank()) {
                                        viewModel.refreshHistory(branchId)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = CyanSoftTint,
                                border = BorderStroke(1.dp, CyanBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.CloudUpload,
                                            contentDescription = null,
                                            tint = CyanDeep,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(7.dp))
                                        Text(
                                            text = "$pendingSyncCount pending cloud sync",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                            fontWeight = FontWeight.Medium,
                                            color = NavyText
                                        )
                                    }
                                    Text(
                                        text = if (uiState.isSyncingHistory) "Syncing..." else "Sync now",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = CyanDeep
                                    )
                                }
                            }
                        }
                    }

                    items(visibleHistory, key = { it.sessionId }) { session ->
                        HistoryLedgerCard(
                            session = session,
                            showBranchTag = isAllBranches,
                            onView = { viewModel.openHistoryLedger(session) },
                            onRetrySync = { viewModel.retrySync(session.sessionId) }
                        )
                    }
                }
            }
        }
    }

    uiState.selectedHistoryLedger?.let { ledgerDetail ->
        HistoryCashBookDialog(
            ledgerDetail = ledgerDetail,
            canUseCounts = canUseCounts,
            canApprove = canApprove,
            onDismiss = { viewModel.closeHistoryLedger() },
            onLoadCounts = {
                val sid = ledgerDetail.session.sessionId
                viewModel.closeHistoryLedger()
                viewModel.loadDenominationsFromSession(
                    sessionId = sid,
                    onLoaded = onNavigateToCalculator
                )
            },
            onApprove = {
                viewModel.approveSession(ledgerDetail.session.sessionId, approverName)
                viewModel.closeHistoryLedger()
            }
        )
    }

    if (uiState.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            containerColor = White,
            title = { Text("Notice", fontWeight = FontWeight.Bold) },
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
private fun HistoryLedgerCard(
    session: CashSession,
    showBranchTag: Boolean = false,
    onView: () -> Unit,
    onRetrySync: () -> Unit
) {
    val businessDateFormatted = remember(session.businessDate, session.createdAt) {
        val displayMillis = if (session.businessDate > 0L) session.businessDate else session.createdAt
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(displayMillis))
    }
    val savedTimeFormatted = remember(session.createdAt) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.createdAt))
    }

    val (statusBg, statusColor) = when (session.reconciliationStatus) {
        "BALANCED" -> SuccessSoftTint to SuccessGreen
        "SHORTAGE" -> DangerSoftTint to DangerRed
        else -> AmberSoftTint to AmberAccent
    }

    Surface(
        onClick = onView,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = White,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, SubtleOutline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = businessDateFormatted,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = PlusJakartaSansFontFamily,
                            fontSize = 14.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = NavyText,
                        maxLines = 1
                    )
                    Text(
                        text = if (showBranchTag) "· ${session.branchId}" else "· $savedTimeFormatted",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (showBranchTag) CyanDeep else MutedNavyText,
                        fontWeight = if (showBranchTag) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Ending Balance",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = MutedNavyText
                        )
                        Surface(
                            shape = RoundedCornerShape(50.dp),
                            color = statusBg
                        ) {
                            Text(
                                text = session.reconciliationStatus,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = formatBirr(session.totalCountedMinor),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = PlusJakartaSansFontFamily,
                            fontSize = 16.5.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        fontWeight = FontWeight.Bold,
                        color = NavyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (session.syncStatus != "SYNCED") {
                    IconButton(
                        onClick = onRetrySync,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Retry Sync",
                            tint = CyanPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onView,
                    modifier = Modifier
                        .heightIn(min = 42.dp)
                        .testTag("inspect_cash_book_${session.sessionId}"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = CyanSoftTint,
                        contentColor = CyanDeep
                    ),
                    border = BorderStroke(1.dp, CyanBorder)
                ) {
                    Text(
                        text = "View",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryState(
    isSyncing: Boolean,
    onSyncClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(VaultSlateDeep, CyanDeep, CyanPrimary))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ReceiptLong,
                contentDescription = null,
                tint = White,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "No Cash Closings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NavyText
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Saved closings and branch audit ledgers will appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MutedNavyText,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        FilledTonalButton(
            onClick = onSyncClick,
            enabled = !isSyncing,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = CyanSoftTint,
                contentColor = CyanDeep
            ),
            border = BorderStroke(1.dp, CyanBorder),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            if (isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(15.dp), color = CyanDeep, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Syncing...")
            } else {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sync Cloud", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun HistoryCashBookDialog(
    ledgerDetail: SessionLedgerDetail,
    canUseCounts: Boolean = true,
    canApprove: Boolean = false,
    onDismiss: () -> Unit,
    onLoadCounts: () -> Unit,
    onApprove: () -> Unit
) {
    val session = ledgerDetail.session
    val context = LocalContext.current
    var copiedNotice by remember { mutableStateOf(false) }

    val dateLabel = remember(session.businessDate, session.createdAt) {
        val displayMillis = if (session.businessDate > 0L) session.businessDate else session.createdAt
        SimpleDateFormat("EEE, MMM dd, yyyy", Locale.getDefault()).format(Date(displayMillis))
    }

    LaunchedEffect(copiedNotice) {
        if (copiedNotice) {
            delay(1800)
            copiedNotice = false
        }
    }

    val varianceColor = if (session.reconciliationStatus == "SHORTAGE") {
        MaterialTheme.colorScheme.error
    } else {
        CyanDeep
    }
    val tabularStyle = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 560.dp)
                .heightIn(max = 720.dp)
                .testTag("history_cash_book_dialog"),
            shape = RoundedCornerShape(16.dp),
            color = White,
            shadowElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Executive Blue Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(listOf(VaultSlateDeep, CyanDeep, CyanPrimary)))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Physical Cash Book",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = PlusJakartaSansFontFamily,
                                fontSize = 15.5.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = White
                        )
                        Text(
                            text = "$dateLabel · ${session.branchId}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = White.copy(alpha = 0.88f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Physical Cash Book",
                                        buildHistoricCashBookText(ledgerDetail, dateLabel)
                                    )
                                )
                                copiedNotice = true
                            },
                            modifier = Modifier.testTag("copy_history_cash_book_button")
                        ) {
                            Icon(
                                imageVector = if (copiedNotice) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                contentDescription = "Copy text",
                                tint = White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Ledger Content
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (copiedNotice) {
                        Surface(
                            color = CyanSoftTint,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, CyanBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Copied to clipboard.",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = CyanDeep,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }

                    val bundles = remember(ledgerDetail.denominations) {
                        ledgerDetail.denominations
                            .filter { it.cashType == "BUNDLE" }
                            .sortedByDescending { it.denominationMinor }
                    }
                    if (bundles.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = White,
                            border = BorderStroke(1.dp, SubtleOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SoftSurfaceVariant)
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "BUNDLE CASH",
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = CyanPrimary
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "DENOM",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MutedNavyText,
                                        modifier = Modifier.weight(0.28f)
                                    )
                                    Text(
                                        text = "BUNDLES",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MutedNavyText,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(0.28f)
                                    )
                                    Text(
                                        text = "VALUE (ETB)",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MutedNavyText,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(0.44f)
                                    )
                                }
                                HorizontalDivider(color = SubtleOutline)

                                bundles.forEachIndexed { idx, b ->
                                    val rowBg = if (idx % 2 == 1) SoftSurfaceVariant.copy(alpha = 0.4f) else White
                                    val active = b.subtotalMinor > 0L
                                    val denomBirr = b.denominationMinor / 100.0
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(rowBg)
                                            .padding(horizontal = 12.dp, vertical = 5.5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "ETB${formatDenomination(denomBirr)}",
                                            style = tabularStyle,
                                            fontWeight = FontWeight.SemiBold,
                                            color = NavyText,
                                            modifier = Modifier.weight(0.28f)
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%,d", b.bundles),
                                            style = tabularStyle,
                                            color = if (active) NavyText else MutedNavyText,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.weight(0.28f)
                                        )
                                        Text(
                                            text = formatAmount(b.subtotalMinor),
                                            style = tabularStyle,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
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
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Bundle Cash Total",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = CyanDeep
                                    )
                                    Text(
                                        text = formatBirr(session.bundleCashMinor),
                                        style = tabularStyle.copy(fontSize = 13.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = NavyText
                                    )
                                }
                            }
                        }
                    }

                    val petty = remember(ledgerDetail.denominations) {
                        ledgerDetail.denominations
                            .filter { it.cashType == "PETTY" }
                            .sortedByDescending { it.denominationMinor }
                    }
                    if (petty.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = White,
                            border = BorderStroke(1.dp, SubtleOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SoftSurfaceVariant)
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "PETTY CASH",
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = CyanPrimary
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "DENOM",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MutedNavyText,
                                        modifier = Modifier.weight(0.28f)
                                    )
                                    Text(
                                        text = "QTY",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MutedNavyText,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.weight(0.28f)
                                    )
                                    Text(
                                        text = "VALUE (ETB)",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = MutedNavyText,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(0.44f)
                                    )
                                }
                                HorizontalDivider(color = SubtleOutline)

                                petty.forEachIndexed { idx, p ->
                                    val rowBg = if (idx % 2 == 1) SoftSurfaceVariant.copy(alpha = 0.4f) else White
                                    val active = p.subtotalMinor > 0L
                                    val denomBirr = p.denominationMinor / 100.0
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(rowBg)
                                            .padding(horizontal = 12.dp, vertical = 5.5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "ETB${formatDenomination(denomBirr)}",
                                            style = tabularStyle,
                                            fontWeight = FontWeight.SemiBold,
                                            color = NavyText,
                                            modifier = Modifier.weight(0.28f)
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%,d", p.quantity),
                                            style = tabularStyle,
                                            color = if (active) NavyText else MutedNavyText,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.weight(0.28f)
                                        )
                                        Text(
                                            text = formatAmount(p.subtotalMinor),
                                            style = tabularStyle,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
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
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Petty Cash Total",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = CyanDeep
                                    )
                                    Text(
                                        text = formatBirr(session.pettyCashMinor),
                                        style = tabularStyle.copy(fontSize = 13.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = NavyText
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = White,
                        border = BorderStroke(1.dp, SubtleOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            HistoricLine("Opening Balance", formatBirr(session.openingBalanceMinor), tabularStyle)
                            HistoricLine("DR (+)", formatBirr(session.totalDRMinor), tabularStyle)
                            HistoricLine("CR (-)", formatBirr(session.totalCRMinor), tabularStyle)
                            HistoricLine("Expected Balance", formatBirr(session.expectedEndingMinor), tabularStyle, bold = true)
                            HorizontalDivider(color = SubtleOutline, modifier = Modifier.padding(vertical = 2.dp))
                            HistoricLine("Ending Balance", formatBirr(session.totalCountedMinor), tabularStyle, bold = true)
                            HistoricLine("Variance (${session.reconciliationStatus})", formatBirr(session.differenceMinor), tabularStyle, bold = true, color = varianceColor)
                        }
                    }
                }

                HorizontalDivider(color = SubtleOutline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (canUseCounts) {
                        FilledTonalButton(
                            onClick = onLoadCounts,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = CyanSoftTint,
                                contentColor = CyanDeep
                            ),
                            border = BorderStroke(1.dp, CyanBorder),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .testTag("load_counts_button_${session.sessionId}")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.PlaylistAddCheck,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Use Counts",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    if (canApprove && !session.isApproved && session.isFinal) {
                        OutlinedButton(
                            onClick = onApprove,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CyanBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanDeep),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .testTag("approve_closing_button")
                        ) {
                            Text(
                                text = "Approve",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold, color = White)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoricLine(
    label: String,
    value: String,
    style: TextStyle,
    bold: Boolean = false,
    color: Color = NavyText
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (bold) NavyText else MutedNavyText
        )
        Text(
            text = value,
            style = style,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
            color = color
        )
    }
}

private fun buildHistoricCashBookText(ledgerDetail: SessionLedgerDetail, dateLabel: String): String {
    val session = ledgerDetail.session
    return buildString {
        appendLine("CASH CONTROL - AUDIT PHYSICAL CASH BOOK")
        appendLine("Date: $dateLabel | Branch: ${session.branchId} | Staff: ${session.staffId}")
        appendLine("Opening Balance: ${formatBirr(session.openingBalanceMinor)}")
        appendLine()
        appendLine("BUNDLE CASH:")
        ledgerDetail.denominations
            .filter { it.cashType == "BUNDLE" }
            .sortedByDescending { it.denominationMinor }
            .forEach { b ->
                val denom = formatDenomination(b.denominationMinor / 100.0)
                appendLine("  $denom x ${b.bundles} bdl = ${formatBirr(b.subtotalMinor)}")
            }
        appendLine("  Bundle Total: ${formatBirr(session.bundleCashMinor)}")
        appendLine()
        appendLine("PETTY CASH:")
        ledgerDetail.denominations
            .filter { it.cashType == "PETTY" }
            .sortedByDescending { it.denominationMinor }
            .forEach { p ->
                val denom = formatDenomination(p.denominationMinor / 100.0)
                appendLine("  $denom x ${p.quantity} = ${formatBirr(p.subtotalMinor)}")
            }
        appendLine("  Petty Total: ${formatBirr(session.pettyCashMinor)}")
        appendLine()
        appendLine("DR (+): ${formatBirr(session.totalDRMinor)}")
        appendLine("CR (-): ${formatBirr(session.totalCRMinor)}")
        appendLine("Expected: ${formatBirr(session.expectedEndingMinor)}")
        appendLine("Counted: ${formatBirr(session.totalCountedMinor)}")
        appendLine("Difference (${session.reconciliationStatus}): ${formatBirr(session.differenceMinor)}")
    }
}

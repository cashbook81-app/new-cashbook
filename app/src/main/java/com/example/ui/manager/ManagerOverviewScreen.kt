package com.example.ui.manager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.CashSession
import com.example.data.remote.model.AuditorCheckDto
import com.example.ui.history.HistoryCashBookDialog
import com.example.ui.theme.*
import com.example.ui.util.formatBirr
import com.example.ui.viewmodel.CashViewModel
import com.example.ui.viewmodel.SessionLedgerDetail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BranchCashSnapshot(
    val branchId: String,
    val latestSession: CashSession,
    val latestAuditorCheck: AuditorCheckDto?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerOverviewScreen(
    viewModel: CashViewModel,
    managerName: String
) {
    val allSessions by viewModel.sessionHistory.collectAsState()
    val allAuditorChecks by viewModel.branchAuditorChecks.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    ManagerOverviewContent(
        sessions = allSessions,
        auditorChecks = allAuditorChecks,
        selectedLedger = uiState.selectedHistoryLedger,
        isLoading = uiState.isSyncingHistory,
        onOpenSession = { viewModel.openHistoryLedger(it) },
        onCloseLedger = { viewModel.closeHistoryLedger() },
        onApproveSession = { sessionId ->
            viewModel.approveSession(sessionId, managerName.ifBlank { "Manager" })
        },
        onRefreshAll = { viewModel.refreshHistory("") }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerOverviewContent(
    sessions: List<CashSession>,
    auditorChecks: List<AuditorCheckDto>,
    selectedLedger: SessionLedgerDetail? = null,
    isLoading: Boolean = false,
    onOpenSession: (CashSession) -> Unit,
    onCloseLedger: () -> Unit = {},
    onApproveSession: (String) -> Unit = {},
    onRefreshAll: () -> Unit = {}
) {
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, UNBALANCED, PENDING_APPROVAL

    val branchSnapshots = remember(sessions, auditorChecks) {
        sessions
            .filter { it.branchId.isNotBlank() }
            .groupBy { it.branchId.uppercase() }
            .map { (branchKey, branchList) ->
                val latest = branchList.maxWith(
                    compareBy<CashSession>(
                        { it.businessDate },
                        { it.isFinal },
                        { it.version },
                        { it.createdAt }
                    )
                )
                val latestDayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(latest.businessDate))
                val matchingCheck = auditorChecks.firstOrNull { check ->
                    check.branchId.uppercase() == branchKey &&
                        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(check.businessDate)) == latestDayKey
                }
                BranchCashSnapshot(
                    branchId = latest.branchId,
                    latestSession = latest,
                    latestAuditorCheck = matchingCheck
                )
            }
            .sortedBy { it.branchId }
    }

    val filteredBranches = remember(branchSnapshots, selectedFilter) {
        when (selectedFilter) {
            "UNBALANCED" -> branchSnapshots.filter { it.latestSession.reconciliationStatus != "BALANCED" }
            "PENDING_APPROVAL" -> branchSnapshots.filter { it.latestSession.isFinal && !it.latestSession.isApproved }
            else -> branchSnapshots
        }
    }

    val grandTotalCashMinor = remember(branchSnapshots) {
        branchSnapshots.sumOf { it.latestSession.totalCountedMinor }
    }
    val grandBundleMinor = remember(branchSnapshots) {
        branchSnapshots.sumOf { it.latestSession.bundleCashMinor }
    }
    val grandPettyMinor = remember(branchSnapshots) {
        branchSnapshots.sumOf { it.latestSession.pettyCashMinor }
    }
    val balancedCount = remember(branchSnapshots) {
        branchSnapshots.count { it.latestSession.reconciliationStatus == "BALANCED" }
    }
    val varianceCount = branchSnapshots.size - balancedCount

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = IceSurface,
        topBar = {
            Column(modifier = Modifier.background(White)) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(Brush.linearGradient(listOf(VaultSlateDeep, CyanDeep, CyanPrimary))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AccountBalance,
                                    contentDescription = "All Branches",
                                    tint = White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "All Branches Cash",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = PlusJakartaSansFontFamily,
                                        fontSize = 17.5.sp
                                    ),
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NavyText
                                )
                                Text(
                                    text = "Manager Multi-Branch Summary",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = MutedNavyText
                                )
                            }
                        }
                    },
                    actions = {
                        Surface(
                            shape = RoundedCornerShape(50.dp),
                            color = CyanSoftTint,
                            border = BorderStroke(1.dp, CyanBorder),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text(
                                text = "${branchSnapshots.size} Branches",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = CyanDeep,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = White)
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 14.dp)
                    .testTag("manager_branch_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)
            ) {
                // Overall Multi-Branch Total Card
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(VaultSlateDeep, CyanDeep, CyanPrimary)))
                            .padding(16.dp)
                            .testTag("manager_grand_total_card")
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "OVERALL NETWORK ENDING CASH",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            letterSpacing = 0.9.sp
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = White.copy(alpha = 0.78f)
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = formatBirr(grandTotalCashMinor),
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontFeatureSettings = "tnum"
                                        ),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = White
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = White.copy(alpha = 0.16f),
                                    border = BorderStroke(1.dp, White.copy(alpha = 0.24f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        Text(
                                            text = "$balancedCount Balanced",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = White
                                        )
                                        Text(
                                            text = "$varianceCount Variance",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = White.copy(alpha = 0.85f)
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = White.copy(alpha = 0.16f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "TOTAL BUNDLES",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                        color = White.copy(alpha = 0.75f)
                                    )
                                    Text(
                                        text = formatBirr(grandBundleMinor),
                                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.Bold,
                                        color = White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "TOTAL PETTY",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                        color = White.copy(alpha = 0.75f)
                                    )
                                    Text(
                                        text = formatBirr(grandPettyMinor),
                                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.Bold,
                                        color = White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Filters
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val filters = listOf(
                            "ALL" to "All (${branchSnapshots.size})",
                            "PENDING_APPROVAL" to "Needs Approval",
                            "UNBALANCED" to "Unbalanced"
                        )
                        filters.forEach { (key, label) ->
                            FilterChip(
                                selected = selectedFilter == key,
                                onClick = { selectedFilter = key },
                                label = { Text(label) },
                                modifier = Modifier.testTag("manager_filter_$key")
                            )
                        }
                    }
                }

                if (filteredBranches.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = White,
                            border = BorderStroke(1.dp, SubtleOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "No Branch Cash Records",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = NavyText
                                )
                                Text(
                                    text = "Once branch Cashiers submit their daily counts, branch totals will appear here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedNavyText
                                )
                                FilledTonalButton(
                                    onClick = onRefreshAll,
                                    enabled = !isLoading
                                ) {
                                    Text(if (isLoading) "Refreshing..." else "Refresh Branches")
                                }
                            }
                        }
                    }
                } else {
                    items(filteredBranches, key = { it.branchId }) { snapshot ->
                        BranchOverviewCard(
                            snapshot = snapshot,
                            onClick = { onOpenSession(snapshot.latestSession) },
                            onApprove = { onApproveSession(snapshot.latestSession.sessionId) }
                        )
                    }
                }
            }
        }
    }

    selectedLedger?.let { detail ->
        HistoryCashBookDialog(
            ledgerDetail = detail,
            canUseCounts = false,
            canApprove = detail.session.isFinal && !detail.session.isApproved,
            onDismiss = onCloseLedger,
            onLoadCounts = {},
            onApprove = {
                onApproveSession(detail.session.sessionId)
                onCloseLedger()
            }
        )
    }
}

@Composable
private fun BranchOverviewCard(
    snapshot: BranchCashSnapshot,
    onClick: () -> Unit,
    onApprove: () -> Unit
) {
    val s = snapshot.latestSession
    val dateFormatted = remember(s.businessDate) {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(s.businessDate))
    }
    val (statusBg, statusColor) = when (s.reconciliationStatus) {
        "BALANCED" -> SuccessSoftTint to SuccessGreen
        "SHORTAGE" -> DangerSoftTint to DangerRed
        else -> AmberSoftTint to AmberAccent
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = White,
        border = BorderStroke(1.dp, SubtleOutline),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("manager_branch_card_${s.branchId}")
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.branchId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = NavyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$dateFormatted · Staff ${s.staffId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedNavyText
                    )
                }

                Surface(shape = RoundedCornerShape(50.dp), color = statusBg) {
                    Text(
                        text = s.reconciliationStatus,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Ending Cash Counted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedNavyText
                    )
                    Text(
                        text = formatBirr(s.totalCountedMinor),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.ExtraBold,
                        color = NavyText
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "DR / CR Net",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedNavyText
                    )
                    Text(
                        text = "+${formatBirr(s.totalDRMinor)} / -${formatBirr(s.totalCRMinor)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = CyanDeep
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val approvalBg = if (s.isApproved) SuccessSoftTint else SoftSurfaceVariant
                    val approvalColor = if (s.isApproved) SuccessGreen else MutedNavyText
                    Surface(shape = RoundedCornerShape(6.dp), color = approvalBg) {
                        Text(
                            text = if (s.isApproved) "Approved" else if (s.isFinal) "Pending Approval" else "Draft",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.SemiBold,
                            color = approvalColor,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    snapshot.latestAuditorCheck?.let { chk ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = CyanSoftTint,
                            border = BorderStroke(1.dp, CyanBorder)
                        ) {
                            Text(
                                text = "Auditor: ${chk.checkStatus}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = CyanDeep,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                if (s.isFinal && !s.isApproved) {
                    TextButton(
                        onClick = onApprove,
                        modifier = Modifier.testTag("manager_approve_${s.branchId}")
                    ) {
                        Text("Approve", fontWeight = FontWeight.Bold, color = CyanPrimary)
                    }
                }
            }
        }
    }
}

package com.example.ui.roles

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SecurityManager
import com.example.data.local.entities.CashDenomination
import com.example.data.local.entities.CashSession
import com.example.data.local.entities.CashTransaction
import com.example.data.remote.model.AuditorCheckDto
import com.example.data.remote.model.PENDING_STAFF_ID
import com.example.data.remote.model.StaffRole
import com.example.data.remote.model.StaffUserRecord
import com.example.data.remote.model.UNASSIGNED_BRANCH
import com.example.data.remote.model.UserDto
import com.example.data.repository.CashSessionRepository
import com.example.data.repository.PreviousClosingFetchResult
import com.example.data.repository.buildAuditorCheckId
import com.example.data.repository.buildDailySessionId
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.AuditorBalancedClosingPopupDialog
import com.example.CashierDrCrPopupDialog
import com.example.ui.admin.AdminUsersContent
import com.example.ui.auditor.AuditorCheckContent
import com.example.ui.auth.PendingActivationScreen
import com.example.ui.calculator.CashCalculatorScreen
import com.example.ui.manager.ManagerOverviewContent
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.CashViewModel
import com.example.ui.viewmodel.todayMidnightMillis
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeRoleCashRepository : CashSessionRepository {
    val sessionsFlow = MutableStateFlow<List<CashSession>>(emptyList())
    val checksFlow = MutableStateFlow<List<AuditorCheckDto>>(emptyList())
    val savedSessionIds = mutableListOf<String>()
    val appliedCheckIds = mutableListOf<String>()
    val approvedSessionIds = mutableListOf<String>()
    var syncAllBranchesCount = 0

    override fun getAllSessions(): Flow<List<CashSession>> = sessionsFlow
    override fun getSessionsForBranch(branchId: String): Flow<List<CashSession>> = sessionsFlow

    override suspend fun saveSession(
        session: CashSession,
        denominations: List<CashDenomination>,
        transactions: List<CashTransaction>
    ) {
        savedSessionIds.add(session.sessionId)
        sessionsFlow.value = sessionsFlow.value.filterNot { it.sessionId == session.sessionId } + session
    }

    override suspend fun trySyncSession(sessionId: String): Result<Unit> = Result.success(Unit)
    override suspend fun syncFromFirestore(branchId: String): Result<Unit> = Result.success(Unit)
    override suspend fun syncAllBranchesFromFirestore(): Result<Unit> {
        syncAllBranchesCount++
        return Result.success(Unit)
    }

    override suspend fun getLatestApprovedBalance(branchId: String): Long? = null
    override suspend fun fetchLatestApprovedClosing(branchId: String): PreviousClosingFetchResult =
        PreviousClosingFetchResult.NotFound(branchId = branchId, cloudSynced = true)

    override suspend fun getLatestSavedDenominations(branchId: String): List<CashDenomination> = emptyList()
    override suspend fun getDenominationsForSession(sessionId: String): List<CashDenomination> = emptyList()
    override suspend fun getTransactionsForSession(sessionId: String): List<CashTransaction> = emptyList()
    override suspend fun getExistingSessionForDate(branchId: String, businessDateMillis: Long): CashSession? = null
    override suspend fun hasFinalClosingForDate(branchId: String, businessDate: Long): Boolean = false
    override suspend fun hasUnapprovedSessions(branchId: String): Boolean = false
    override suspend fun approveSession(sessionId: String, approvedBy: String) {
        approvedSessionIds.add(sessionId)
        sessionsFlow.value = sessionsFlow.value.map {
            if (it.sessionId == sessionId) it.copy(isApproved = true, approvedBy = approvedBy) else it
        }
    }
    override suspend fun syncPendingSessions() {}

    override fun observeAuditorChecksForBranch(branchId: String): Flow<List<AuditorCheckDto>> = checksFlow
    override fun observeAllAuditorChecks(): Flow<List<AuditorCheckDto>> = checksFlow
    override suspend fun submitAuditorCheck(check: AuditorCheckDto): Result<AuditorCheckDto> {
        checksFlow.value = checksFlow.value.filterNot { it.checkId == check.checkId } + check
        return Result.success(check)
    }

    override suspend fun markAuditorCheckApplied(checkId: String): Result<Unit> {
        appliedCheckIds.add(checkId)
        checksFlow.value = checksFlow.value.map {
            if (it.checkId == checkId) it.copy(appliedByCashier = true) else it
        }
        return Result.success(Unit)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoleWorkflowsRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var securityManager: SecurityManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:1234567890:android:testappid")
                    .setApiKey("test-api-key")
                    .setProjectId("test-project-id")
                    .build()
            )
        }
        securityManager = SecurityManager(context)
    }

    @Test
    fun pendingActivationScreen_showsPendingDetailsAndRefreshes() {
        var refreshClicks = 0
        composeRule.setContent {
            MyApplicationTheme {
                PendingActivationScreen(
                    profile = UserDto(
                        fullName = "New Teller",
                        email = "teller@company.com",
                        branchId = UNASSIGNED_BRANCH,
                        staffId = PENDING_STAFF_ID,
                        role = StaffRole.CASHIER.firestoreValue,
                        active = false
                    ),
                    isRefreshing = false,
                    onRefreshAccess = { refreshClicks++ }
                )
            }
        }

        composeRule.onNodeWithTag("pending_activation_card").assertIsDisplayed()
        composeRule.onNodeWithText("PENDING ADMIN ACTIVATION").assertIsDisplayed()
        composeRule.onNodeWithText("teller@company.com").assertIsDisplayed()
        composeRule.onNodeWithTag("refresh_activation_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, refreshClicks)
    }

    @Test
    fun adminUsersScreen_assignsBranchRoleAndActivatesStaff() {
        var savedUid = ""
        var savedActive = false
        var savedBranch = ""
        var savedRole: StaffRole? = null
        var savedStaffId = ""

        val sampleUsers = listOf(
            StaffUserRecord(
                uid = "uid-pending-1",
                user = UserDto(
                    fullName = "Abebe Kebede",
                    email = "abebe@company.com",
                    branchId = UNASSIGNED_BRANCH,
                    staffId = PENDING_STAFF_ID,
                    role = StaffRole.CASHIER.firestoreValue,
                    active = false
                )
            ),
            StaffUserRecord(
                uid = "uid-admin-1",
                user = UserDto(
                    fullName = "System Admin",
                    email = "cashbook81@gmail.com",
                    branchId = "SODO DACHI",
                    staffId = "ADM001",
                    role = StaffRole.ADMIN.firestoreValue,
                    active = true
                )
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                AdminUsersContent(
                    users = sampleUsers,
                    currentAdminUid = "uid-admin-1",
                    knownBranches = listOf("SODO DACHI", "HAWASSA MAIN"),
                    onSaveUserAccess = { targetUid, active, branchId, role, staffId ->
                        savedUid = targetUid
                        savedActive = active
                        savedBranch = branchId
                        savedRole = role
                        savedStaffId = staffId
                        Result.success(Unit)
                    }
                )
            }
        }

        composeRule.onNodeWithText("Staff Access Admin").assertIsDisplayed()
        composeRule.onNodeWithTag("staff_user_card_uid-pending-1").assertIsDisplayed()
        composeRule.onNodeWithTag("manage_user_uid-pending-1").performClick()
        composeRule.waitForIdle()

        // Activate account, assign Auditor role & SODO DACHI branch
        composeRule.onNodeWithTag("admin_user_active_switch").performScrollTo().performClick()
        composeRule.onNodeWithTag("admin_role_AUDITOR").performScrollTo().performClick()
        composeRule.onNodeWithTag("admin_branch_input").performScrollTo().performTextReplacement("SODO DACHI")
        composeRule.onNodeWithTag("admin_staff_id_input").performScrollTo().performTextReplacement("AUD101")
        composeRule.onNodeWithTag("admin_save_access_button").performClick()
        composeRule.waitForIdle()

        assertEquals("uid-pending-1", savedUid)
        assertTrue(savedActive)
        assertEquals("SODO DACHI", savedBranch)
        assertEquals(StaffRole.AUDITOR, savedRole)
        assertEquals("AUD101", savedStaffId)
    }

    @Test
    fun auditorCheckScreen_usesYesterdayClosing_calculatesLiveStatus_andSubmitsDrCr() {
        val today = todayMidnightMillis()
        val yesterday = today - 86_400_000L

        val yesterdaySession = CashSession(
            sessionId = buildDailySessionId("SODO DACHI", yesterday),
            branchId = "SODO DACHI",
            staffId = "CSH01",
            businessDate = yesterday,
            openingBalanceMinor = 100_000_00L,
            totalDRMinor = 20_000_00L,
            totalCRMinor = 10_000_00L,
            expectedEndingMinor = 110_000_00L,
            bundleCashMinor = 100_000_00L,
            pettyCashMinor = 10_000_00L,
            totalCountedMinor = 110_000_00L,
            differenceMinor = 0L,
            reconciliationStatus = "BALANCED",
            status = "CLOSED",
            syncStatus = "SYNCED",
            isFinal = true,
            isApproved = true
        )
        val todayCashierSession = CashSession(
            sessionId = buildDailySessionId("SODO DACHI", today),
            branchId = "SODO DACHI",
            staffId = "CSH01",
            businessDate = today,
            openingBalanceMinor = 110_000_00L,
            totalDRMinor = 50_000_00L,
            totalCRMinor = 25_000_00L,
            expectedEndingMinor = 135_000_00L,
            bundleCashMinor = 120_000_00L,
            pettyCashMinor = 15_000_00L,
            totalCountedMinor = 135_000_00L,
            differenceMinor = 0L,
            reconciliationStatus = "BALANCED",
            status = "CLOSED",
            syncStatus = "SYNCED",
            isFinal = true,
            isApproved = false
        )

        var submittedOpening = -1L
        var submittedDr = -1L
        var submittedCr = -1L
        var approvedSessionId: String? = null

        composeRule.setContent {
            MyApplicationTheme {
                AuditorCheckContent(
                    branchId = "SODO DACHI",
                    auditorStaffId = "AUD01",
                    selectedBusinessDateMillis = today,
                    branchSessions = listOf(todayCashierSession, yesterdaySession),
                    branchChecks = emptyList(),
                    isSubmitting = false,
                    onDateSelected = {},
                    onClearSubmitNotice = {},
                    onClearError = {},
                    onApproveSession = { approvedSessionId = it },
                    onSubmitCheck = { opening, dr, cr, _, _ ->
                        submittedOpening = opening
                        submittedDr = dr
                        submittedCr = cr
                    }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Auditor Check").assertIsDisplayed()
        composeRule.onNodeWithText("TODAY'S AUDITOR BALANCE").assertDoesNotExist()
        composeRule.onNodeWithTag("auditor_yesterday_reference_card").assertDoesNotExist()
        composeRule.onNodeWithText("BALANCE SUMMARY").assertDoesNotExist()
        composeRule.onNodeWithText("RECONCILIATION").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Auditor Ending Balance").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("auditor_dr_input").performScrollTo().performTextReplacement("50000.00")
        composeRule.onNodeWithTag("auditor_cr_input").performScrollTo().performTextReplacement("25000.00")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("auditor_balance_status_badge").assertTextContains("BALANCED")

        composeRule.onNodeWithTag("submit_auditor_check_button").performScrollTo().performClick()
        assertEquals(110_000_00L, submittedOpening)
        assertEquals(50_000_00L, submittedDr)
        assertEquals(25_000_00L, submittedCr)

        composeRule.onNodeWithTag("auditor_approve_closing_button").performScrollTo().performClick()
        assertEquals(todayCashierSession.sessionId, approvedSessionId)
    }

    @Test
    fun managerOverviewScreen_aggregatesAllBranchesCashBalances() {
        val today = todayMidnightMillis()
        val sessions = listOf(
            CashSession(
                sessionId = buildDailySessionId("SODO DACHI", today),
                branchId = "SODO DACHI",
                staffId = "CSH01",
                businessDate = today,
                openingBalanceMinor = 100_000_00L,
                totalDRMinor = 0L,
                totalCRMinor = 0L,
                expectedEndingMinor = 100_000_00L,
                bundleCashMinor = 90_000_00L,
                pettyCashMinor = 10_000_00L,
                totalCountedMinor = 100_000_00L,
                differenceMinor = 0L,
                reconciliationStatus = "BALANCED",
                status = "CLOSED",
                syncStatus = "SYNCED",
                isFinal = true
            ),
            CashSession(
                sessionId = buildDailySessionId("HAWASSA MAIN", today),
                branchId = "HAWASSA MAIN",
                staffId = "CSH02",
                businessDate = today,
                openingBalanceMinor = 200_000_00L,
                totalDRMinor = 0L,
                totalCRMinor = 0L,
                expectedEndingMinor = 200_000_00L,
                bundleCashMinor = 180_000_00L,
                pettyCashMinor = 15_000_00L,
                totalCountedMinor = 195_000_00L,
                differenceMinor = -5_000_00L,
                reconciliationStatus = "SHORTAGE",
                status = "CLOSED",
                syncStatus = "SYNCED",
                isFinal = true
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                ManagerOverviewContent(
                    sessions = sessions,
                    auditorChecks = emptyList(),
                    onOpenSession = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("All Branches Cash").assertIsDisplayed()
        composeRule.onNodeWithTag("manager_grand_total_card").assertIsDisplayed()
        composeRule.onNodeWithText("ETB 295,000.00").assertIsDisplayed()
        composeRule.onNodeWithTag("manager_branch_list")
            .performScrollToNode(hasTestTag("manager_branch_card_HAWASSA MAIN"))
        composeRule.onNodeWithTag("manager_branch_card_HAWASSA MAIN").assertIsDisplayed()
        composeRule.onNodeWithTag("manager_branch_list")
            .performScrollToNode(hasTestTag("manager_branch_card_SODO DACHI"))
        composeRule.onNodeWithTag("manager_branch_card_SODO DACHI").assertIsDisplayed()
    }

    @Test
    fun cashierStep4_removesCircleHeroCard_andPromptsPopupBeforeFillingAuditorDrCr() {
        val today = todayMidnightMillis()
        val repo = FakeRoleCashRepository()
        val viewModel = CashViewModel(repo)
        viewModel.initializeForSession("uid-cashier-1", "SODO DACHI", StaffRole.CASHIER)
        viewModel.updateBusinessDate(today)
        viewModel.setStep(4)

        val checkId = buildAuditorCheckId("SODO DACHI", today)
        repo.checksFlow.value = listOf(
            AuditorCheckDto(
                checkId = checkId,
                branchId = "SODO DACHI",
                auditorUid = "uid-aud-1",
                auditorStaffId = "AUD09",
                auditorName = "Auditor Hana",
                businessDate = today,
                openingBalanceMinor = 100_000_00L,
                totalDRMinor = 42_000_00L,
                totalCRMinor = 12_000_00L,
                expectedEndingMinor = 130_000_00L,
                checkStatus = "BALANCED",
                appliedByCashier = false
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                CashCalculatorScreen(
                    viewModel = viewModel,
                    branchId = "SODO DACHI",
                    staffId = "CSH01",
                    securityManager = securityManager
                )
                val popup = uiState.pendingAuditorDrCrPopup
                if (popup != null) {
                    CashierDrCrPopupDialog(
                        check = popup,
                        onAccept = { viewModel.acceptAuditorDrCrPopup(popup) },
                        onDismiss = { viewModel.dismissAuditorDrCrPopup(popup) }
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000L) {
            viewModel.uiState.value.pendingAuditorDrCrPopup != null
        }
        composeRule.waitForIdle()

        // Does NOT overwrite DR/CR until cashier accepts
        assertEquals(0L, viewModel.uiState.value.totalDRMinor)
        assertEquals(0L, viewModel.uiState.value.totalCRMinor)
        composeRule.onNodeWithTag("cashier_auditor_dr_cr_popup_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("ETB 42,000.00").assertIsDisplayed()
        composeRule.onNodeWithText("ETB 12,000.00").assertIsDisplayed()

        // Accept fills today's DR and CR
        composeRule.onNodeWithTag("accept_auditor_dr_cr_popup_button").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            viewModel.uiState.value.totalDRMinor == 42_000_00L &&
                viewModel.uiState.value.totalCRMinor == 12_000_00L
        }
        composeRule.waitForIdle()

        assertEquals(42_000_00L, viewModel.uiState.value.totalDRMinor)
        assertEquals(12_000_00L, viewModel.uiState.value.totalCRMinor)
        assertNull(viewModel.uiState.value.pendingAuditorDrCrPopup)
        assertTrue(repo.appliedCheckIds.contains(checkId))
        assertNotNull(viewModel.uiState.value.latestAuditorCheckForDate)

        composeRule.onNodeWithTag("cashier_auditor_dr_cr_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("apply_auditor_dr_cr_button").assertTextContains("Applied")
        composeRule.onNodeWithText("STEP 4 · VERIFY").assertDoesNotExist()
    }

    @Test
    fun auditorReceivesBalancedClosingPopup_andApprovesCashierPosition() {
        val today = todayMidnightMillis()
        val repo = FakeRoleCashRepository()
        val viewModel = CashViewModel(repo)
        viewModel.initializeForSession("uid-aud-1", "SODO DACHI", StaffRole.AUDITOR)

        val closingId = buildDailySessionId("SODO DACHI", today)
        repo.sessionsFlow.value = listOf(
            CashSession(
                sessionId = closingId,
                branchId = "SODO DACHI",
                staffId = "CSH01",
                businessDate = today,
                openingBalanceMinor = 100_000_00L,
                totalDRMinor = 45_000_00L,
                totalCRMinor = 15_000_00L,
                expectedEndingMinor = 130_000_00L,
                bundleCashMinor = 120_000_00L,
                pettyCashMinor = 10_000_00L,
                totalCountedMinor = 130_000_00L,
                differenceMinor = 0L,
                reconciliationStatus = "BALANCED",
                status = "CLOSED",
                syncStatus = "SYNCED",
                isFinal = true,
                isApproved = false
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val popup = uiState.pendingAuditorClosingPopup
                if (popup != null) {
                    AuditorBalancedClosingPopupDialog(
                        session = popup,
                        onApprove = { viewModel.approveAuditorClosingPopup(popup, "Auditor Hana") },
                        onDismiss = { viewModel.dismissAuditorClosingPopup(popup) }
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 3_000L) {
            viewModel.uiState.value.pendingAuditorClosingPopup != null
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("auditor_balanced_closing_popup_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Position Balanced & Closed").assertIsDisplayed()
        composeRule.onNodeWithText("ETB 100,000.00").assertIsDisplayed()
        composeRule.onNodeWithText("ETB 45,000.00").assertIsDisplayed()
        composeRule.onNodeWithText("ETB 15,000.00").assertIsDisplayed()
        composeRule.onNodeWithText("ETB 130,000.00").assertIsDisplayed()

        composeRule.onNodeWithTag("approve_balanced_closing_popup_button").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            repo.approvedSessionIds.contains(closingId)
        }
        assertNull(viewModel.uiState.value.pendingAuditorClosingPopup)
    }

    @Test
    fun headOfficeRole_resolvesAndAdminCanAssignHeadOfficeAccess() {
        assertEquals(StaffRole.HEAD_OFFICE, StaffRole.fromRaw("HEAD_OFFICE"))

        var savedRole: StaffRole? = null
        var savedBranch = ""
        val sampleUsers = listOf(
            StaffUserRecord(
                uid = "uid-hq-1",
                user = UserDto(
                    fullName = "HQ Director",
                    email = "hq@company.com",
                    branchId = UNASSIGNED_BRANCH,
                    staffId = PENDING_STAFF_ID,
                    role = StaffRole.CASHIER.firestoreValue,
                    active = false
                )
            )
        )

        composeRule.setContent {
            MyApplicationTheme {
                AdminUsersContent(
                    users = sampleUsers,
                    currentAdminUid = "uid-admin-1",
                    knownBranches = listOf("SODO DACHI"),
                    onSaveUserAccess = { _, _, branchId, role, _ ->
                        savedBranch = branchId
                        savedRole = role
                        Result.success(Unit)
                    }
                )
            }
        }

        composeRule.onNodeWithTag("manage_user_uid-hq-1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("admin_role_HEAD_OFFICE").performScrollTo().performClick()
        composeRule.onNodeWithTag("admin_save_access_button").performClick()
        composeRule.waitForIdle()

        assertEquals(StaffRole.HEAD_OFFICE, savedRole)
        assertEquals("ALL BRANCHES", savedBranch)
    }
}

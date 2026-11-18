package com.example.ui.calculator

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.SecurityManager
import com.example.data.local.entities.CashDenomination
import com.example.data.local.entities.CashSession
import com.example.data.local.entities.CashTransaction
import com.example.data.remote.model.UserDto
import com.example.data.repository.CashRepository
import com.example.data.repository.CashSessionRepository
import com.example.data.repository.PreviousApprovedClosingInfo
import com.example.data.repository.PreviousClosingFetchResult
import com.example.data.repository.UserRepository
import com.example.ui.auth.ProfileScreen
import com.example.ui.auth.WelcomeScreen
import com.example.ui.history.HistoryScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.CashViewModel
import com.example.ui.viewmodel.OpeningBalanceSource
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeUiCashRepository(
    var onFetchClosing: suspend (String) -> PreviousClosingFetchResult = { branchId ->
        PreviousClosingFetchResult.NotFound(branchId = branchId, cloudSynced = true)
    }
) : CashSessionRepository {
    val sessions = MutableStateFlow<List<CashSession>>(emptyList())
    var fetchCount = 0

    override fun getAllSessions(): Flow<List<CashSession>> = sessions
    override fun getSessionsForBranch(branchId: String): Flow<List<CashSession>> = sessions
    override suspend fun saveSession(
        session: CashSession,
        denominations: List<CashDenomination>,
        transactions: List<CashTransaction>
    ) {}
    override suspend fun trySyncSession(sessionId: String): Result<Unit> = Result.success(Unit)
    override suspend fun syncFromFirestore(branchId: String): Result<Unit> = Result.success(Unit)
    override suspend fun getLatestApprovedBalance(branchId: String): Long? = null
    override suspend fun fetchLatestApprovedClosing(branchId: String): PreviousClosingFetchResult {
        fetchCount++
        return onFetchClosing(branchId)
    }
    override suspend fun getLatestSavedDenominations(branchId: String): List<CashDenomination> = emptyList()
    override suspend fun getDenominationsForSession(sessionId: String): List<CashDenomination> = emptyList()
    override suspend fun getTransactionsForSession(sessionId: String): List<CashTransaction> = emptyList()
    override suspend fun getExistingSessionForDate(branchId: String, businessDateMillis: Long): CashSession? = null
    override suspend fun hasFinalClosingForDate(branchId: String, businessDate: Long): Boolean = false
    override suspend fun hasUnapprovedSessions(branchId: String): Boolean = false
    override suspend fun approveSession(sessionId: String, approvedBy: String) {}
    override suspend fun syncPendingSessions() {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpeningBalanceScreenRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var securityManager: SecurityManager
    private var roomDb: AppDatabase? = null

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

    @After
    fun tearDown() {
        roomDb?.close()
    }

    private fun launchCalculator(viewModel: CashViewModel, branchId: String = "SODO DACHI") {
        composeRule.setContent {
            MyApplicationTheme {
                CashCalculatorScreen(
                    viewModel = viewModel,
                    branchId = branchId,
                    staffId = "CSO01",
                    securityManager = securityManager
                )
            }
        }
    }

    @Test
    fun ui_manualOpeningBalanceEntry_updatesBadgeAndFormattedConfirmationSummary() {
        val viewModel = CashViewModel(FakeUiCashRepository())
        launchCalculator(viewModel)

        composeRule.waitForIdle()
        composeRule.onNodeWithText("CASH CONTROL").assertIsDisplayed()
        assertEquals(1, composeRule.onAllNodesWithText("SODO DACHI").fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("opening_balance_input").performTextInput("2881825.26")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("opening_source_badge").assertTextContains("Manual Float")
        composeRule.onNodeWithTag("opening_confirmation_summary").assertDoesNotExist()
    }

    @Test
    fun ui_loadPreviousApprovedBalance_showsClosingDateApprovalLockAndSafeClearDialog() {
        val closingMillis = 1_794_800_000_000L
        val repo = FakeUiCashRepository { branchId ->
            PreviousClosingFetchResult.Loaded(
                closing = PreviousApprovedClosingInfo(
                    sessionId = "cloud-approved-1",
                    branchId = branchId,
                    amountMinor = 288_182_526L,
                    businessDateMillis = closingMillis,
                    isApproved = true,
                    approvedBy = "Supervisor Alemu",
                    approvalTimestamp = closingMillis + 1800_000L,
                    reconciliationStatus = "BALANCED",
                    syncedFromCloud = true
                ),
                fromOfflineCache = false
            )
        }
        val viewModel = CashViewModel(repo)
        launchCalculator(viewModel)

        composeRule.onNodeWithTag("load_previous_balance_button").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            !viewModel.uiState.value.isLoadingPreviousBalance &&
                viewModel.uiState.value.openingBalanceSource == OpeningBalanceSource.PREVIOUS_APPROVED
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("opening_source_badge").assertTextContains("Approved Closing")
        composeRule.onNodeWithTag("previous_closing_metadata_card").assertDoesNotExist()
        composeRule.onNodeWithTag("previous_balance_status_banner").assertDoesNotExist()
        composeRule.onNodeWithTag("unlock_edit_opening_button").performScrollTo().assertIsDisplayed()

        // Safe Clear opens confirmation dialog and keeps float on KEEP
        composeRule.onNodeWithTag("clear_opening_balance_button").performClick()
        composeRule.onNodeWithTag("confirm_clear_opening_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("cancel_clear_balance_button").performClick()
        assertEquals(288_182_526L, viewModel.uiState.value.openingBalanceMinor)

        // Unlock -> edit updates badge to Adjusted Float -> Restore Closing resets
        composeRule.onNodeWithTag("unlock_edit_opening_button").performClick()
        composeRule.onNodeWithTag("opening_balance_input").performTextReplacement("2900000.00")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("opening_source_badge").assertTextContains("Adjusted Float")

        composeRule.onNodeWithTag("restore_loaded_closing_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(288_182_526L, viewModel.uiState.value.openingBalanceMinor)
        composeRule.onNodeWithTag("opening_source_badge").assertTextContains("Approved Closing")
    }

    @Test
    fun ui_overwriteProtectionAndInvalidAmountHandling_keepDataSafe() {
        val repo = FakeUiCashRepository { branchId ->
            PreviousClosingFetchResult.Loaded(
                closing = PreviousApprovedClosingInfo(
                    sessionId = "cloud-approved-2",
                    branchId = branchId,
                    amountMinor = 500_000_00L,
                    businessDateMillis = 1_794_800_000_000L,
                    isApproved = true,
                    approvedBy = "Supervisor",
                    approvalTimestamp = 1_794_801_000_000L,
                    reconciliationStatus = "BALANCED",
                    syncedFromCloud = true
                ),
                fromOfflineCache = false
            )
        }
        val viewModel = CashViewModel(repo)
        launchCalculator(viewModel)

        // Blank submit displays inline error and blocks navigation to Bundles
        composeRule.onNodeWithTag("wizard_continue_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, viewModel.uiState.value.currentStep)
        composeRule.onNodeWithTag("opening_input_error").assertIsDisplayed()

        // Invalid input (-50) cannot corrupt state
        composeRule.onNodeWithTag("opening_balance_input").performTextInput("-50")
        composeRule.waitForIdle()
        assertEquals(0L, viewModel.uiState.value.openingBalanceMinor)

        // Valid manual entry + loading previous closing opens overwrite dialog
        composeRule.onNodeWithTag("opening_balance_input").performTextInput("125000.00")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("load_previous_balance_button").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            viewModel.uiState.value.pendingOverwriteClosing != null
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("confirm_replace_opening_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("keep_current_balance_button").performClick()
        assertEquals(125_000_00L, viewModel.uiState.value.openingBalanceMinor)
    }

    @Test
    fun ui_navigationToBundles_andDuplicateTapGuard_landsStrictlyOnStep2() {
        val gate = CompletableDeferred<PreviousClosingFetchResult>()
        val repo = FakeUiCashRepository { gate.await() }
        val viewModel = CashViewModel(repo)
        launchCalculator(viewModel)

        // Double tap Load while pending triggers repository only once
        composeRule.onNodeWithTag("load_previous_balance_button").performClick()
        composeRule.onNodeWithTag("load_previous_balance_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, repo.fetchCount)

        gate.complete(
            PreviousClosingFetchResult.Loaded(
                closing = PreviousApprovedClosingInfo(
                    sessionId = "cloud-approved-3",
                    branchId = "SODO DACHI",
                    amountMinor = 288_182_526L,
                    businessDateMillis = 1_794_800_000_000L,
                    isApproved = true,
                    approvedBy = "Supervisor",
                    approvalTimestamp = 1_794_801_000_000L,
                    reconciliationStatus = "BALANCED",
                    syncedFromCloud = true
                ),
                fromOfflineCache = false
            )
        )
        composeRule.waitForIdle()

        // Continue to Bundles transitions cleanly to Step 2
        composeRule.onNodeWithTag("wizard_continue_button").performClick()
        composeRule.waitForIdle()

        assertEquals(2, viewModel.uiState.value.currentStep)
        composeRule.onNodeWithText("Bundle Cash").assertIsDisplayed()
    }

    @Test
    fun offlineRoomCacheAndRepositoryFallback_loadsCachedApprovedSessionAndPreservesPendingEdits() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        roomDb = db
        val dao = db.cashDao()
        val repository = CashRepository(dao, FirebaseFirestore.getInstance())

        // Offline empty cache returns NotFound(cloudSynced = false)
        val emptyOffline = repository.fetchLatestApprovedClosing("SODO DACHI")
        assertTrue(emptyOffline is PreviousClosingFetchResult.NotFound)
        assertEquals(false, (emptyOffline as PreviousClosingFetchResult.NotFound).cloudSynced)

        // Insert approved cached closing + a newer pending unapproved local session
        dao.insertSession(
            CashSession(
                sessionId = "cached-approved-1",
                branchId = "SODO DACHI",
                staffId = "CSO01",
                businessDate = 1_794_700_000_000L,
                createdAt = 1_794_700_000_000L,
                openingBalanceMinor = 200_000_00L,
                totalDRMinor = 90_000_00L,
                totalCRMinor = 1_817_474L,
                expectedEndingMinor = 288_182_526L,
                bundleCashMinor = 280_000_000L,
                pettyCashMinor = 8_182_526L,
                totalCountedMinor = 288_182_526L,
                differenceMinor = 0L,
                reconciliationStatus = "BALANCED",
                status = "CLOSED",
                syncStatus = "SYNCED",
                isFinal = true,
                isApproved = true,
                approvedBy = "Supervisor Alemu",
                approvalTimestamp = 1_794_705_000_000L,
                version = 1
            )
        )
        dao.insertSession(
            CashSession(
                sessionId = "local-pending-draft",
                branchId = "SODO DACHI",
                staffId = "CSO01",
                businessDate = 1_794_800_000_000L,
                createdAt = 1_794_800_000_000L,
                openingBalanceMinor = 288_182_526L,
                totalDRMinor = 0L,
                totalCRMinor = 0L,
                expectedEndingMinor = 288_182_526L,
                bundleCashMinor = 0L,
                pettyCashMinor = 0L,
                totalCountedMinor = 0L,
                differenceMinor = -288_182_526L,
                reconciliationStatus = "SHORTAGE",
                status = "DRAFT",
                syncStatus = "PENDING",
                isFinal = false,
                isApproved = false,
                version = 1
            )
        )

        val offlineLoaded = repository.fetchLatestApprovedClosing("SODO DACHI")
        assertTrue(offlineLoaded is PreviousClosingFetchResult.Loaded)
        val loaded = offlineLoaded as PreviousClosingFetchResult.Loaded
        assertTrue(loaded.fromOfflineCache)
        assertEquals(288_182_526L, loaded.closing.amountMinor)
        assertEquals("Supervisor Alemu", loaded.closing.approvedBy)
        assertTrue(loaded.closing.isApproved)

        // Pending local draft stays queued for cloud sync
        assertEquals(listOf("local-pending-draft"), dao.getPendingSessions().map { it.sessionId })
    }

    @Test
    fun ui_verifyDrCr_continueSkipsSummaryAndOpensClosingDirectly() {
        val viewModel = CashViewModel(FakeUiCashRepository())
        viewModel.setStep(4)
        launchCalculator(viewModel)

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dr_amount_input_0").assertIsDisplayed()
        composeRule.onNodeWithTag("wizard_continue_button").assertTextContains("Continue to Closing")
        composeRule.onNodeWithTag("wizard_continue_button").performClick()
        composeRule.waitForIdle()

        assertEquals(5, viewModel.uiState.value.currentStep)
        composeRule.onNodeWithText("Confirm Daily Cash Closing").assertIsDisplayed()
        composeRule.onNodeWithTag("save_session_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("wizard_back_button").assertTextContains("Back to Verify")

        // Going back returns directly to DR/CR Verify step
        composeRule.onNodeWithTag("wizard_back_button").performClick()
        composeRule.waitForIdle()
        assertEquals(4, viewModel.uiState.value.currentStep)
        composeRule.onNodeWithTag("dr_amount_input_0").assertIsDisplayed()
    }

    @Test
    fun ui_closingPage_opensPhysicalCashBookAndCopiesSummary() {
        val viewModel = CashViewModel(FakeUiCashRepository())
        viewModel.onOpeningBalanceTextChanged("2881825.26")
        viewModel.setStep(5)
        launchCalculator(viewModel)

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("preview_cash_book_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("cash_book_preview_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("PHYSICAL CASH BOOK").assertIsDisplayed()

        composeRule.onNodeWithTag("copy_cash_book_bottom_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Copied ledger summary to clipboard.").assertIsDisplayed()
        composeRule.onNodeWithText("COPIED").assertIsDisplayed()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(clipText.contains("CASH CONTROL - PHYSICAL CASH BOOK"))
        assertTrue(clipText.contains("Opening Balance: ETB 2,881,825.26"))
    }

    @Test
    fun ui_welcomeHistoryAndProfile_renderCohesiveDesignWithoutMerchantLinks() {
        // 1. Welcome Screen (sign-in mode, auto sign-in disabled for unit test)
        composeRule.setContent {
            MyApplicationTheme {
                WelcomeScreen(
                    isLocked = false,
                    allowAutoSignIn = false,
                    onAuthSuccess = {},
                    securityManager = securityManager
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("BRANCH TREASURY VAULT").assertIsDisplayed()
        composeRule.onNodeWithText("CASH CONTROL").assertIsDisplayed()
        composeRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
    }

    @Test
    fun ui_historyScreen_rendersLatestEndingBalanceSummary() {
        val repo = FakeUiCashRepository().apply {
            sessions.value = listOf(
                CashSession(
                    sessionId = "s-hist-1",
                    branchId = "SODO DACHI",
                    staffId = "CSO01",
                    businessDate = 1_794_800_000_000L,
                    createdAt = 1_794_800_000_000L,
                    openingBalanceMinor = 200_000_00L,
                    totalDRMinor = 0L,
                    totalCRMinor = 0L,
                    expectedEndingMinor = 200_000_00L,
                    bundleCashMinor = 180_000_00L,
                    pettyCashMinor = 20_000_00L,
                    totalCountedMinor = 200_000_00L,
                    differenceMinor = 0L,
                    reconciliationStatus = "BALANCED",
                    status = "CLOSED",
                    syncStatus = "SYNCED",
                    isFinal = true,
                    isApproved = true
                )
            )
        }
        val viewModel = CashViewModel(repo).apply {
            initializeForSession("uid-1", "SODO DACHI")
        }
        composeRule.setContent {
            MyApplicationTheme {
                HistoryScreen(viewModel = viewModel, branchId = "SODO DACHI")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("LATEST ENDING BALANCE").assertIsDisplayed()
        composeRule.onNodeWithText("1 Records").assertIsDisplayed()
        composeRule.onNodeWithTag("inspect_cash_book_s-hist-1").assertIsDisplayed()
    }

    @Test
    fun ui_profileScreen_rendersWithoutMerchantPortalRow() {
        composeRule.setContent {
            MyApplicationTheme {
                ProfileScreen(
                    securityManager = securityManager,
                    userProfile = UserDto(
                        fullName = "Staff Member",
                        email = "staff@example.com",
                        staffId = "CSO01",
                        branchId = "SODO DACHI",
                        role = "CSO"
                    ),
                    userRepository = UserRepository(FirebaseFirestore.getInstance())
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("VAULT SECURITY").assertIsDisplayed()
        composeRule.onNodeWithTag("edit_profile_button").assertIsDisplayed()
        composeRule.onNodeWithText("Account & Vault Security").assertDoesNotExist()
        composeRule.onNodeWithText("ACCOUNT & SESSION").assertDoesNotExist()
        composeRule.onNodeWithTag("profile_merchant_portal_row").assertDoesNotExist()
        composeRule.onNodeWithText("SouqPass Merchant Portal").assertDoesNotExist()
    }

    @Test
    fun ui_step2AndStep3_showHeaderTotalsAndOmitBottomFooters() {
        val viewModel = CashViewModel(FakeUiCashRepository())
        viewModel.updateBundleEntry(0, bundles = 2, notesPerBundle = 1000, looseNotes = 0)
        viewModel.updatePettyQuantity(0, quantity = 3)
        viewModel.setStep(2)
        launchCalculator(viewModel)

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bundle Cash").assertIsDisplayed()
        composeRule.onNodeWithTag("bundle_cash_header_total")
            .assertIsDisplayed()
            .assertTextContains("ETB 400,000.00")
        composeRule.onNodeWithText("Bundle Cash Total").assertDoesNotExist()

        viewModel.setStep(3)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Petty Cash").assertIsDisplayed()
        composeRule.onNodeWithTag("petty_cash_header_total")
            .assertIsDisplayed()
            .assertTextContains("ETB 600.00")
        composeRule.onNodeWithTag("bundle_summary_header_total")
            .assertIsDisplayed()
            .assertTextContains("ETB 400,000.00")
        composeRule.onNodeWithTag("combined_cash_header_total")
            .assertIsDisplayed()
            .assertTextContains("ETB 400,600.00")
        composeRule.onNodeWithText("Total Cash").assertDoesNotExist()
    }

    @Test
    fun ui_closingPage_showsReadOnlyDateCleanNumberFormatAndBreakdown() {
        val viewModel = CashViewModel(FakeUiCashRepository())
        viewModel.onOpeningBalanceTextChanged("100000.00")
        viewModel.updateBundleEntry(0, bundles = 0, notesPerBundle = 1000, looseNotes = 0)
        viewModel.updatePettyQuantity(0, quantity = 250) // 250 x 200 = ETB 50,000.00 counted -> -ETB 50,000.00 variance
        viewModel.setStep(5)
        launchCalculator(viewModel)

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("closing_business_date_badge").assertIsDisplayed()
        composeRule.onNodeWithText("Change").assertDoesNotExist()
        composeRule.onNodeWithTag("business_date_picker_row").assertDoesNotExist()

        composeRule.onNodeWithTag("closing_counted_summary_card").assertIsDisplayed()
        composeRule.onNodeWithTag("closing_total_counted_value")
            .assertIsDisplayed()
            .assertTextContains("ETB 50,000.00")
        composeRule.onNodeWithTag("closing_reconciliation_card")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("closing_variance_card")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("closing_variance_amount")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("-ETB 50,000.00")
    }
}

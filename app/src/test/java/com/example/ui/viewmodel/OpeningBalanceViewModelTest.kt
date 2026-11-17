package com.example.ui.viewmodel

import com.example.data.local.entities.CashDenomination
import com.example.data.local.entities.CashSession
import com.example.data.local.entities.CashTransaction
import com.example.data.repository.CashSessionRepository
import com.example.data.repository.PreviousApprovedClosingInfo
import com.example.data.repository.PreviousClosingFetchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeOpeningCashRepository : CashSessionRepository {
    val sessionsFlow = MutableStateFlow<List<CashSession>>(emptyList())

    var fetchClosingCallCount = 0
    var syncPendingCallCount = 0
    var syncFromFirestoreCallCount = 0
    val callSequence = mutableListOf<String>()

    var fetchClosingHandler: suspend (String) -> PreviousClosingFetchResult = { branchId ->
        PreviousClosingFetchResult.NotFound(branchId = branchId, cloudSynced = true)
    }
    var syncFromFirestoreHandler: suspend (String) -> Result<Unit> = { Result.success(Unit) }

    override fun getAllSessions(): Flow<List<CashSession>> = sessionsFlow

    override fun getSessionsForBranch(branchId: String): Flow<List<CashSession>> = sessionsFlow

    override suspend fun saveSession(
        session: CashSession,
        denominations: List<CashDenomination>,
        transactions: List<CashTransaction>
    ) {
        sessionsFlow.value = sessionsFlow.value + session
    }

    override suspend fun trySyncSession(sessionId: String): Result<Unit> = Result.success(Unit)

    override suspend fun syncFromFirestore(branchId: String): Result<Unit> {
        syncFromFirestoreCallCount++
        callSequence.add("syncFromFirestore:$branchId")
        return syncFromFirestoreHandler(branchId)
    }

    override suspend fun getLatestApprovedBalance(branchId: String): Long? {
        return (fetchLatestApprovedClosing(branchId) as? PreviousClosingFetchResult.Loaded)
            ?.closing?.amountMinor
    }

    override suspend fun fetchLatestApprovedClosing(branchId: String): PreviousClosingFetchResult {
        fetchClosingCallCount++
        callSequence.add("fetchLatestApprovedClosing:$branchId")
        return fetchClosingHandler(branchId)
    }

    override suspend fun getLatestSavedDenominations(branchId: String): List<CashDenomination> = emptyList()

    override suspend fun getDenominationsForSession(sessionId: String): List<CashDenomination> = emptyList()

    override suspend fun getTransactionsForSession(sessionId: String): List<CashTransaction> = emptyList()

    override suspend fun getExistingSessionForDate(branchId: String, businessDateMillis: Long): CashSession? = null

    override suspend fun hasFinalClosingForDate(branchId: String, businessDate: Long): Boolean = false

    override suspend fun hasUnapprovedSessions(branchId: String): Boolean = false

    override suspend fun approveSession(sessionId: String, approvedBy: String) {}

    override suspend fun syncPendingSessions() {
        syncPendingCallCount++
        callSequence.add("syncPendingSessions")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OpeningBalanceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // 1. Manual opening balance entry
    @Test
    fun manualEntry_validDecimalEtb_preservesExactMinorUnitsWithoutFloatDrift() {
        val repo = FakeOpeningCashRepository()
        val viewModel = CashViewModel(repo)

        val accepted = viewModel.onOpeningBalanceTextChanged("2881825.26")

        assertTrue(accepted)
        val state = viewModel.uiState.value
        assertEquals(288_182_526L, state.openingBalanceMinor)
        assertEquals(OpeningBalanceSource.MANUAL, state.openingBalanceSource)
        assertTrue(state.hasConfirmedOpeningEntry)
        assertNull(state.openingInputError)
        assertEquals("2881825.26", formatMinorAsFixedTwoDecimals(state.openingBalanceMinor))
    }

    @Test
    fun manualEntry_zeroAndSubBirrDecimals_areAcceptedAndProceedToBundles() {
        val repo = FakeOpeningCashRepository()
        val viewModel = CashViewModel(repo)

        assertTrue(viewModel.onOpeningBalanceTextChanged(".75"))
        assertEquals(75L, viewModel.uiState.value.openingBalanceMinor)
        assertEquals("0.75", formatMinorAsFixedTwoDecimals(viewModel.uiState.value.openingBalanceMinor))

        assertTrue(viewModel.onOpeningBalanceTextChanged("0"))
        assertEquals(0L, viewModel.uiState.value.openingBalanceMinor)
        assertTrue(viewModel.uiState.value.hasConfirmedOpeningEntry)
        assertTrue(viewModel.confirmOpeningAndProceed("0"))
        assertEquals(2, viewModel.uiState.value.currentStep)
    }

    // 2. Loading previous approved closing balance + safe edit/overwrite
    @Test
    fun loadPreviousApprovedClosing_cloudSynced_populatesMetadataAndLocksField() = runTest {
        val closingDateMillis = 1_794_800_000_000L
        val repo = FakeOpeningCashRepository().apply {
            fetchClosingHandler = { branchId ->
                PreviousClosingFetchResult.Loaded(
                    closing = PreviousApprovedClosingInfo(
                        sessionId = "sess-approved-1",
                        branchId = branchId,
                        amountMinor = 288_182_526L,
                        businessDateMillis = closingDateMillis,
                        isApproved = true,
                        approvedBy = "Supervisor Alemu",
                        approvalTimestamp = closingDateMillis + 3_600_000L,
                        reconciliationStatus = "BALANCED",
                        syncedFromCloud = true
                    ),
                    fromOfflineCache = false
                )
            }
        }
        val viewModel = CashViewModel(repo)

        viewModel.loadPreviousBalance("SODO DACHI")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoadingPreviousBalance)
        assertEquals(288_182_526L, state.openingBalanceMinor)
        assertEquals(OpeningBalanceSource.PREVIOUS_APPROVED, state.openingBalanceSource)
        assertTrue(state.isPreviousBalanceLocked)
        assertTrue(state.hasConfirmedOpeningEntry)
        assertEquals(PreviousBalanceStatusType.SUCCESS, state.previousBalanceStatus?.type)
        assertNotNull(state.loadedClosingMetadata)
        assertEquals("Supervisor Alemu", state.loadedClosingMetadata?.approvedBy)
        assertTrue(state.loadedClosingMetadata?.isApproved == true)
        assertFalse(state.loadedClosingMetadata?.isOfflineCached == true)
    }

    @Test
    fun loadedPreviousClosing_unlockEditAndRestore_transitionsSourceSafely() = runTest {
        val repo = FakeOpeningCashRepository().apply {
            fetchClosingHandler = { branchId ->
                PreviousClosingFetchResult.Loaded(
                    closing = PreviousApprovedClosingInfo(
                        sessionId = "sess-approved-2",
                        branchId = branchId,
                        amountMinor = 150_000_00L,
                        businessDateMillis = 1_794_800_000_000L,
                        isApproved = true,
                        approvedBy = "Manager Hanna",
                        approvalTimestamp = 1_794_803_600_000L,
                        reconciliationStatus = "BALANCED",
                        syncedFromCloud = true
                    ),
                    fromOfflineCache = false
                )
            }
        }
        val viewModel = CashViewModel(repo)
        viewModel.loadPreviousBalance("SODO DACHI")
        advanceUntilIdle()

        // Locked field rejects direct text mutation until Edit is clicked
        assertFalse(viewModel.onOpeningBalanceTextChanged("200000"))
        assertEquals(150_000_00L, viewModel.uiState.value.openingBalanceMinor)
        assertNotNull(viewModel.uiState.value.openingInputError)

        // Unlock and edit -> switches badge source to ADJUSTED_FROM_PREVIOUS
        viewModel.unlockPreviousBalanceForEdit()
        assertFalse(viewModel.uiState.value.isPreviousBalanceLocked)
        assertTrue(viewModel.onOpeningBalanceTextChanged("165000.50"))
        assertEquals(165_000_50L, viewModel.uiState.value.openingBalanceMinor)
        assertEquals(OpeningBalanceSource.ADJUSTED_FROM_PREVIOUS, viewModel.uiState.value.openingBalanceSource)

        // Restore exact closing balance -> returns to PREVIOUS_APPROVED and locks
        viewModel.restoreLoadedClosingBalance()
        assertEquals(150_000_00L, viewModel.uiState.value.openingBalanceMinor)
        assertEquals(OpeningBalanceSource.PREVIOUS_APPROVED, viewModel.uiState.value.openingBalanceSource)
        assertTrue(viewModel.uiState.value.isPreviousBalanceLocked)
    }

    @Test
    fun loadPreviousClosing_withExistingManualFloat_promptsBeforeOverwriting() = runTest {
        val closing = PreviousApprovedClosingInfo(
            sessionId = "sess-approved-3",
            branchId = "SODO DACHI",
            amountMinor = 250_000_00L,
            businessDateMillis = 1_794_800_000_000L,
            isApproved = true,
            approvedBy = "Supervisor",
            approvalTimestamp = 1_794_801_000_000L,
            reconciliationStatus = "BALANCED",
            syncedFromCloud = true
        )
        val repo = FakeOpeningCashRepository().apply {
            fetchClosingHandler = { PreviousClosingFetchResult.Loaded(closing, fromOfflineCache = false) }
        }
        val viewModel = CashViewModel(repo)

        // User enters manual starting float first
        assertTrue(viewModel.onOpeningBalanceTextChanged("50000.00"))
        assertEquals(50_000_00L, viewModel.uiState.value.openingBalanceMinor)

        // Load previous closing must NOT silently overwrite manual float
        viewModel.loadPreviousBalance("SODO DACHI")
        advanceUntilIdle()

        assertEquals(50_000_00L, viewModel.uiState.value.openingBalanceMinor)
        assertEquals(closing, viewModel.uiState.value.pendingOverwriteClosing)

        // Keeping current preserves 50,000.00
        viewModel.dismissOverwriteWithPreviousBalance()
        assertNull(viewModel.uiState.value.pendingOverwriteClosing)
        assertEquals(50_000_00L, viewModel.uiState.value.openingBalanceMinor)
        assertEquals(OpeningBalanceSource.MANUAL, viewModel.uiState.value.openingBalanceSource)

        // Reload and confirm replace applies 250,000.00
        viewModel.loadPreviousBalance("SODO DACHI")
        advanceUntilIdle()
        viewModel.confirmOverwriteWithPreviousBalance()
        assertNull(viewModel.uiState.value.pendingOverwriteClosing)
        assertEquals(250_000_00L, viewModel.uiState.value.openingBalanceMinor)
        assertEquals(OpeningBalanceSource.PREVIOUS_APPROVED, viewModel.uiState.value.openingBalanceSource)
    }

    // 3. Invalid amount handling
    @Test
    fun invalidAmounts_negativeAlphaMultipleDotsExcessDecimalsOverflow_areRejectedWithoutStateCorruption() {
        val repo = FakeOpeningCashRepository()
        val viewModel = CashViewModel(repo)
        assertTrue(viewModel.onOpeningBalanceTextChanged("12500.50"))

        val invalidInputs = listOf(
            "-500",
            "12500-1",
            "12500abc",
            "12.34.56",
            "12500.123",
            "1000000000.00"
        )

        invalidInputs.forEach { bad ->
            assertFalse("Expected rejection for '$bad'", viewModel.onOpeningBalanceTextChanged(bad))
            assertNotNull("Expected error message for '$bad'", viewModel.uiState.value.openingInputError)
            assertEquals("Existing balance must stay intact for '$bad'", 1_250_050L, viewModel.uiState.value.openingBalanceMinor)
        }

        // Direct programmatic negative balance call is also rejected cleanly
        viewModel.updateOpeningBalance(-10.0)
        assertEquals(1_250_050L, viewModel.uiState.value.openingBalanceMinor)
        assertNotNull(viewModel.uiState.value.openingInputError)
    }

    @Test
    fun emptyOpeningBalance_continueToBundles_isBlockedWithInlineValidation() {
        val repo = FakeOpeningCashRepository()
        val viewModel = CashViewModel(repo)

        val proceeded = viewModel.confirmOpeningAndProceed("")

        assertFalse(proceeded)
        assertEquals(1, viewModel.uiState.value.currentStep)
        assertNotNull(viewModel.uiState.value.openingInputError)
    }

    // 4. Offline behavior
    @Test
    fun offlineBehavior_cachedApprovedClosingShowsOfflineWarning_andMissingCacheGuidesUser() = runTest {
        val cachedClosing = PreviousApprovedClosingInfo(
            sessionId = "offline-sess-1",
            branchId = "SODO DACHI",
            amountMinor = 98_500_25L,
            businessDateMillis = 1_794_700_000_000L,
            isApproved = true,
            approvedBy = "Supervisor",
            approvalTimestamp = 1_794_710_000_000L,
            reconciliationStatus = "BALANCED",
            syncedFromCloud = false
        )
        var returnCached = true
        val repo = FakeOpeningCashRepository().apply {
            fetchClosingHandler = { branchId ->
                if (returnCached) {
                    PreviousClosingFetchResult.Loaded(
                        closing = cachedClosing,
                        fromOfflineCache = true,
                        cloudError = "Network unavailable"
                    )
                } else {
                    PreviousClosingFetchResult.NotFound(
                        branchId = branchId,
                        cloudSynced = false,
                        cloudError = "Network unavailable"
                    )
                }
            }
        }
        val viewModel = CashViewModel(repo)

        // Offline with cached session
        viewModel.loadPreviousBalance("SODO DACHI")
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertEquals(98_500_25L, state.openingBalanceMinor)
        assertEquals(PreviousBalanceStatusType.WARNING_OFFLINE, state.previousBalanceStatus?.type)
        assertTrue(state.loadedClosingMetadata?.isOfflineCached == true)

        // Clear and test offline with no cached session
        viewModel.clearOpeningBalance()
        returnCached = false
        viewModel.loadPreviousBalance("SODO DACHI")
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertEquals(PreviousBalanceStatusType.WARNING_OFFLINE, state.previousBalanceStatus?.type)
        assertTrue(state.previousBalanceStatus?.message?.contains("Offline mode", ignoreCase = true) == true)
    }

    // 5 & 6. Duplicate submission prevention & navigation to Bundles
    @Test
    fun duplicateSubmissionPrevention_overlappingLoadAndDoubleContinue_areGuarded() = runTest {
        val gate = CompletableDeferred<PreviousClosingFetchResult>()
        val repo = FakeOpeningCashRepository().apply {
            fetchClosingHandler = { gate.await() }
        }
        val viewModel = CashViewModel(repo)

        // Rapid double-tap on Load Previous Approved Balance while in-flight
        viewModel.loadPreviousBalance("SODO DACHI")
        viewModel.loadPreviousBalance("SODO DACHI")
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isLoadingPreviousBalance)
        assertEquals(1, repo.fetchClosingCallCount)

        // Continue to Bundles must be blocked while closing fetch is still in-flight
        assertFalse(viewModel.confirmOpeningAndProceed("50000"))
        assertEquals(1, viewModel.uiState.value.currentStep)

        gate.complete(
            PreviousClosingFetchResult.Loaded(
                closing = PreviousApprovedClosingInfo(
                    sessionId = "sess-dup-1",
                    branchId = "SODO DACHI",
                    amountMinor = 288_182_526L,
                    businessDateMillis = 1_794_800_000_000L,
                    isApproved = true,
                    approvedBy = "Supervisor",
                    approvalTimestamp = 1_794_800_100_000L,
                    reconciliationStatus = "BALANCED",
                    syncedFromCloud = true
                ),
                fromOfflineCache = false
            )
        )
        advanceUntilIdle()

        val chosenDate = 1_794_888_000_000L
        viewModel.updateBusinessDate(chosenDate)

        // Rapid double-tap on Continue to Bundles lands strictly on Step 2 (Bundles), never Step 3
        val firstProceed = viewModel.confirmOpeningAndProceed("2881825.26")
        val secondProceed = viewModel.confirmOpeningAndProceed("2881825.26")

        assertTrue(firstProceed)
        assertFalse(secondProceed)
        assertEquals(2, viewModel.uiState.value.currentStep)
        assertEquals(288_182_526L, viewModel.uiState.value.openingBalanceMinor)
        assertEquals(chosenDate, viewModel.uiState.value.selectedBusinessDateMillis)
    }

    // 7. Firebase synchronization order & preservation
    @Test
    fun firebaseSynchronization_initializeAndLoadClosing_triggerSyncAndPreserveBranch() = runTest {
        val repo = FakeOpeningCashRepository().apply {
            fetchClosingHandler = { branchId ->
                PreviousClosingFetchResult.Loaded(
                    closing = PreviousApprovedClosingInfo(
                        sessionId = "sync-sess-1",
                        branchId = branchId,
                        amountMinor = 100_000_00L,
                        businessDateMillis = 1_794_800_000_000L,
                        isApproved = true,
                        approvedBy = "Auditor",
                        approvalTimestamp = 1_794_805_000_000L,
                        reconciliationStatus = "BALANCED",
                        syncedFromCloud = true
                    ),
                    fromOfflineCache = false
                )
            }
        }
        val viewModel = CashViewModel(repo)

        viewModel.initializeForSession(userId = "uid-1", branchId = "SODO DACHI")
        viewModel.loadPreviousBalance("SODO DACHI")
        advanceUntilIdle()

        assertTrue(repo.syncPendingCallCount >= 1)
        assertTrue(repo.syncFromFirestoreCallCount >= 1)
        assertEquals(1, repo.fetchClosingCallCount)
        assertEquals("fetchLatestApprovedClosing:SODO DACHI", repo.callSequence.last())
        assertEquals(100_000_00L, viewModel.uiState.value.openingBalanceMinor)
        assertEquals(OpeningBalanceSource.PREVIOUS_APPROVED, viewModel.uiState.value.openingBalanceSource)
    }

    @Test
    fun wizardSteps_afterDrCrVerify_goesDirectlyToClosingPage() {
        val repo = FakeOpeningCashRepository()
        val viewModel = CashViewModel(repo)

        viewModel.setStep(4) // DR & CR Verify
        assertEquals(4, viewModel.uiState.value.currentStep)

        viewModel.nextStep() // Must go directly to Closing (Step 5)
        assertEquals(5, viewModel.uiState.value.currentStep)

        viewModel.nextStep() // Cannot advance past Step 5
        assertEquals(5, viewModel.uiState.value.currentStep)

        viewModel.prevStep() // Returns directly to DR & CR Verify (Step 4)
        assertEquals(4, viewModel.uiState.value.currentStep)
    }
}

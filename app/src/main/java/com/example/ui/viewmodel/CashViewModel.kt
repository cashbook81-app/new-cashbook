package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.PersistedBundleCount
import com.example.data.local.PersistedDenominationCounts
import com.example.data.local.PersistedPettyCount
import com.example.data.local.SecurityManager
import com.example.data.local.entities.CashDenomination
import com.example.data.local.entities.CashSession
import com.example.data.local.entities.CashTransaction
import com.example.data.remote.model.AuditorCheckDto
import com.example.data.remote.model.StaffRole
import com.example.data.repository.CashSessionRepository
import com.example.data.repository.PreviousApprovedClosingInfo
import com.example.data.repository.PreviousClosingFetchResult
import com.example.data.repository.buildAuditorCheckId
import com.example.data.repository.buildDailySessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToLong

fun todayMidnightMillis(): Long {
    return Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

fun utcDateMillisToLocalMidnight(utcMillis: Long): Long {
    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = utcMillis
    }
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
        set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

data class DenominationEntry(
    val denominationBirr: Double,
    val quantity: Int = 0, // Used for petty cash
    val bundles: Int = 0, // Used for bundle cash
    val notesPerBundle: Int = 1000, // 1 bundle = 1,000 denominations
    val looseNotes: Int = 0, // Used for bundle cash
    val isBundle: Boolean = false
) {
    val denominationMinor: Long = (denominationBirr * 100.0).roundToLong()

    // Total quantity computed in Long to prevent Int overflow on large counts
    val totalQuantityLong: Long = if (isBundle) {
        (bundles.toLong() * notesPerBundle.toLong()) + looseNotes.toLong()
    } else {
        quantity.toLong()
    }

    val totalQuantity: Int = totalQuantityLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    val subtotalMinor: Long = denominationMinor * totalQuantityLong
}

data class TransactionEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // DR or CR
    val amountBirr: Double = 0.0,
    val description: String = ""
) {
    val amountMinor: Long = (amountBirr * 100.0).roundToLong()
}

data class SessionLedgerDetail(
    val session: CashSession,
    val denominations: List<CashDenomination>,
    val transactions: List<CashTransaction>
)

enum class OpeningBalanceSource {
    NONE,
    MANUAL,
    PREVIOUS_APPROVED,
    ADJUSTED_FROM_PREVIOUS
}

enum class PreviousBalanceStatusType {
    INFO,
    SUCCESS,
    WARNING_OFFLINE,
    ERROR
}

data class PreviousBalanceStatus(
    val type: PreviousBalanceStatusType,
    val message: String
)

data class LoadedClosingMetadata(
    val sessionId: String,
    val branchId: String,
    val amountMinor: Long,
    val businessDateMillis: Long,
    val isApproved: Boolean,
    val approvedBy: String?,
    val approvalTimestamp: Long?,
    val reconciliationStatus: String,
    val isOfflineCached: Boolean
)

data class CashCalculatorUiState(
    val currentStep: Int = 1,
    val selectedBusinessDateMillis: Long = todayMidnightMillis(),
    val openingBalanceBirr: Double = 0.0,
    val openingBalanceSource: OpeningBalanceSource = OpeningBalanceSource.NONE,
    val hasConfirmedOpeningEntry: Boolean = false,
    val loadedClosingMetadata: LoadedClosingMetadata? = null,
    val pendingOverwriteClosing: PreviousApprovedClosingInfo? = null,
    val isPreviousBalanceLocked: Boolean = false,
    val openingInputError: String? = null,
    val bundleDenominations: List<DenominationEntry> = listOf(200.0, 100.0, 50.0, 10.0, 5.0).map { DenominationEntry(it, isBundle = true) },
    val pettyDenominations: List<DenominationEntry> = listOf(200.0, 100.0, 50.0, 10.0, 5.0, 1.0, 0.50, 0.25, 0.10, 0.05, 0.01).map { DenominationEntry(it) },
    val drTransactions: List<TransactionEntry> = listOf(TransactionEntry(type = "DR")),
    val crTransactions: List<TransactionEntry> = listOf(TransactionEntry(type = "CR")),
    val useSystemVerification: Boolean = true,
    val isLoadingPreviousBalance: Boolean = false,
    val previousBalanceNotice: String? = null,
    val previousBalanceStatus: PreviousBalanceStatus? = null,
    val isSyncingHistory: Boolean = false,
    val historyNotice: String? = null,
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val saveWasUpdate: Boolean = false,
    val selectedHistoryLedger: SessionLedgerDetail? = null,
    val isLoadingHistoryLedger: Boolean = false,
    val latestAuditorCheckForDate: AuditorCheckDto? = null,
    val auditorNotificationMessage: String? = null,
    val appliedAuditorCheckId: String? = null,
    val pendingAuditorDrCrPopup: AuditorCheckDto? = null,
    val pendingAuditorClosingPopup: CashSession? = null,
    val isSubmittingAuditorCheck: Boolean = false,
    val auditorSubmitNotice: String? = null,
    val error: String? = null
) {
    val openingBalanceMinor: Long = (openingBalanceBirr * 100.0).roundToLong()
    val totalDRMinor: Long = drTransactions.sumOf { it.amountMinor }
    val totalCRMinor: Long = crTransactions.sumOf { it.amountMinor }

    val expectedEndingMinor: Long = openingBalanceMinor + totalDRMinor - totalCRMinor

    val bundleCashTotalMinor: Long = bundleDenominations.sumOf { it.subtotalMinor }
    val pettyCashTotalMinor: Long = pettyDenominations.sumOf { it.subtotalMinor }
    val totalCountedMinor: Long = bundleCashTotalMinor + pettyCashTotalMinor

    val differenceMinor: Long = totalCountedMinor - expectedEndingMinor

    val reconciliationStatus: String = when {
        differenceMinor == 0L -> "BALANCED"
        differenceMinor < 0L -> "SHORTAGE"
        else -> "EXCESS"
    }

    fun hasAnyDenominationCounts(): Boolean {
        return bundleDenominations.any { it.bundles > 0 || it.looseNotes > 0 } ||
            pettyDenominations.any { it.quantity > 0 }
    }
}

sealed interface BankAlertEvent {
    data class AuditorDrCrForCashier(val check: AuditorCheckDto) : BankAlertEvent
    data class BalancedClosingForAuditor(val session: CashSession) : BankAlertEvent
}

class CashViewModel(
    private val repository: CashSessionRepository,
    private val securityManager: SecurityManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(CashCalculatorUiState())
    val uiState: StateFlow<CashCalculatorUiState> = _uiState.asStateFlow()

    private val _bankAlertEvents = MutableSharedFlow<BankAlertEvent>(extraBufferCapacity = 8)
    val bankAlertEvents: SharedFlow<BankAlertEvent> = _bankAlertEvents.asSharedFlow()

    private val _activeBranchId = MutableStateFlow<String?>(null)
    private val _activeRole = MutableStateFlow(StaffRole.CASHIER)
    private var activeUserId: String = ""
    private var initializedKey: String? = null
    private var remoteSessionSyncJob: Job? = null
    private val seenInSessionAlertKeys = mutableSetOf<String>()
    private val persistedAcknowledgedKeys = mutableSetOf<String>()

    private fun isMultiBranchRole(role: StaffRole): Boolean =
        role == StaffRole.MANAGER || role == StaffRole.HEAD_OFFICE || role == StaffRole.ADMIN

    private fun drCrAlertKey(check: AuditorCheckDto): String =
        "DRCR|${check.checkId}|${check.totalDRMinor}|${check.totalCRMinor}"

    private fun closingAlertKey(session: CashSession): String =
        "CLOSING|${session.sessionId}|v${session.version}|${session.openingBalanceMinor}|${session.totalDRMinor}|${session.totalCRMinor}|${session.totalCountedMinor}"

    private fun dayKey(millis: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(millis))

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionHistory: StateFlow<List<CashSession>> = combine(_activeBranchId, _activeRole) { branchId, role ->
        branchId to role
    }.flatMapLatest { (branchId, role) ->
        if (isMultiBranchRole(role)) {
            repository.getAllSessions()
        } else if (branchId.isNullOrBlank()) {
            flowOf(emptyList())
        } else {
            repository.getSessionsForBranch(branchId)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val branchAuditorChecks: StateFlow<List<AuditorCheckDto>> = combine(_activeBranchId, _activeRole) { branchId, role ->
        branchId to role
    }.flatMapLatest { (branchId, role) ->
        if (isMultiBranchRole(role)) {
            repository.observeAllAuditorChecks()
        } else if (branchId.isNullOrBlank()) {
            flowOf(emptyList())
        } else {
            repository.observeAuditorChecksForBranch(branchId)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            combine(
                branchAuditorChecks,
                _activeBranchId,
                _activeRole,
                _uiState.map { it.selectedBusinessDateMillis }.distinctUntilChanged()
            ) { checks, branchId, role, selectedDate ->
                QuadCheckInput(checks, branchId.orEmpty(), role, selectedDate)
            }.collect { (checks, branchId, role, selectedDate) ->
                val targetKey = dayKey(selectedDate)
                val matchingCheck = checks.firstOrNull { check ->
                    check.branchId.equals(branchId, ignoreCase = true) &&
                        dayKey(check.businessDate) == targetKey
                }
                _uiState.update { it.copy(latestAuditorCheckForDate = matchingCheck) }

                if (role == StaffRole.CASHIER && matchingCheck != null && !matchingCheck.appliedByCashier) {
                    val key = drCrAlertKey(matchingCheck)
                    if (key !in seenInSessionAlertKeys && key !in persistedAcknowledgedKeys) {
                        seenInSessionAlertKeys.add(key)
                        _uiState.update { it.copy(pendingAuditorDrCrPopup = matchingCheck) }
                        _bankAlertEvents.tryEmit(BankAlertEvent.AuditorDrCrForCashier(matchingCheck))
                    }
                } else if (matchingCheck == null || matchingCheck.appliedByCashier) {
                    _uiState.update { current ->
                        if (current.pendingAuditorDrCrPopup != null &&
                            (matchingCheck == null || current.pendingAuditorDrCrPopup.checkId == matchingCheck.checkId)
                        ) {
                            current.copy(pendingAuditorDrCrPopup = null)
                        } else {
                            current
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(
                sessionHistory,
                _activeBranchId,
                _activeRole
            ) { sessions, branchId, role ->
                Triple(sessions, branchId.orEmpty(), role)
            }.collect { (sessions, branchId, role) ->
                if (role == StaffRole.AUDITOR && branchId.isNotBlank()) {
                    val balancedClosed = sessions
                        .filter { s ->
                            s.branchId.equals(branchId, ignoreCase = true) &&
                                s.status == "CLOSED" &&
                                s.isFinal &&
                                s.reconciliationStatus == "BALANCED" &&
                                !s.isApproved
                        }
                        .maxByOrNull { it.businessDate }

                    if (balancedClosed != null) {
                        val key = closingAlertKey(balancedClosed)
                        if (key !in seenInSessionAlertKeys && key !in persistedAcknowledgedKeys) {
                            seenInSessionAlertKeys.add(key)
                            _uiState.update { it.copy(pendingAuditorClosingPopup = balancedClosed) }
                            _bankAlertEvents.tryEmit(BankAlertEvent.BalancedClosingForAuditor(balancedClosed))
                        }
                    } else {
                        _uiState.update { current ->
                            if (current.pendingAuditorClosingPopup != null) {
                                current.copy(pendingAuditorClosingPopup = null)
                            } else {
                                current
                            }
                        }
                    }
                }
            }
        }
    }

    private data class QuadCheckInput(
        val checks: List<AuditorCheckDto>,
        val branchId: String,
        val role: StaffRole,
        val selectedDate: Long
    )

    fun initializeForSession(
        userId: String,
        branchId: String,
        role: StaffRole = StaffRole.CASHIER
    ) {
        activeUserId = userId
        _activeBranchId.value = branchId
        _activeRole.value = role
        val sessionKey = "$userId|$branchId|${role.name}"
        val isFirstForKey = initializedKey != sessionKey
        initializedKey = sessionKey

        if (isFirstForKey) {
            seenInSessionAlertKeys.clear()
            _uiState.update {
                it.copy(
                    pendingAuditorDrCrPopup = null,
                    pendingAuditorClosingPopup = null
                )
            }
            remoteSessionSyncJob?.cancel()
            remoteSessionSyncJob = viewModelScope.launch(Dispatchers.IO) {
                val flow = if (isMultiBranchRole(role)) {
                    repository.observeRemoteAllSessions()
                } else if (branchId.isNotBlank()) {
                    repository.observeRemoteSessionsForBranch(branchId)
                } else {
                    emptyFlow()
                }
                flow.collect()
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (isFirstForKey) {
                val persisted = securityManager?.getAcknowledgedAlertKeys(userId).orEmpty()
                if (persisted.isNotEmpty()) {
                    persistedAcknowledgedKeys.addAll(persisted)
                }
            }
            if (isFirstForKey && role == StaffRole.CASHIER && !_uiState.value.hasAnyDenominationCounts()) {
                val savedWorking = securityManager?.getWorkingCounts(userId)
                if (savedWorking != null && savedWorking.hasNonZeroCounts()) {
                    applyPersistedCounts(savedWorking)
                }
            }

            _uiState.update { it.copy(isSyncingHistory = true) }
            try {
                if (role == StaffRole.CASHIER) {
                    repository.syncPendingSessions()
                }
                if (isMultiBranchRole(role)) {
                    repository.syncAllBranchesFromFirestore()
                } else if (branchId.isNotBlank()) {
                    repository.syncFromFirestore(branchId)
                }
            } finally {
                _uiState.update { it.copy(isSyncingHistory = false) }
            }

            if (role == StaffRole.CASHIER && !_uiState.value.hasAnyDenominationCounts() && branchId.isNotBlank()) {
                val latestDenoms = repository.getLatestSavedDenominations(branchId)
                if (latestDenoms.isNotEmpty()) {
                    applyEntityDenominations(latestDenoms)
                    persistWorkingCounts(_uiState.value)
                }
            }
        }
    }

    fun applyAuditorCheckToCashier(check: AuditorCheckDto, showNotification: Boolean = false) {
        val drBirr = check.totalDRMinor / 100.0
        val crBirr = check.totalCRMinor / 100.0
        val auditorLabel = check.auditorName.ifBlank { check.auditorStaffId.ifBlank { "Auditor" } }
        val key = drCrAlertKey(check)
        seenInSessionAlertKeys.add(key)
        persistedAcknowledgedKeys.add(key)

        _uiState.update { state ->
            val drId = state.drTransactions.firstOrNull()?.id ?: UUID.randomUUID().toString()
            val crId = state.crTransactions.firstOrNull()?.id ?: UUID.randomUUID().toString()
            state.copy(
                useSystemVerification = true,
                drTransactions = listOf(TransactionEntry(id = drId, type = "DR", amountBirr = drBirr)),
                crTransactions = listOf(TransactionEntry(id = crId, type = "CR", amountBirr = crBirr)),
                appliedAuditorCheckId = check.checkId,
                pendingAuditorDrCrPopup = null,
                auditorNotificationMessage = if (showNotification) {
                    "$auditorLabel verified DR & CR. Filled into today's transactions."
                } else {
                    state.auditorNotificationMessage
                }
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            securityManager?.markAlertKeyAcknowledged(activeUserId, key)
            if (!check.appliedByCashier) {
                repository.markAuditorCheckApplied(check.checkId)
            }
        }
    }

    fun acceptAuditorDrCrPopup(check: AuditorCheckDto) {
        applyAuditorCheckToCashier(check, showNotification = true)
    }

    fun dismissAuditorDrCrPopup(check: AuditorCheckDto) {
        val key = drCrAlertKey(check)
        seenInSessionAlertKeys.add(key)
        persistedAcknowledgedKeys.add(key)
        _uiState.update { it.copy(pendingAuditorDrCrPopup = null) }
        viewModelScope.launch(Dispatchers.IO) {
            securityManager?.markAlertKeyAcknowledged(activeUserId, key)
        }
    }

    fun dismissAuditorClosingPopup(session: CashSession) {
        val key = closingAlertKey(session)
        seenInSessionAlertKeys.add(key)
        persistedAcknowledgedKeys.add(key)
        _uiState.update { it.copy(pendingAuditorClosingPopup = null) }
        viewModelScope.launch(Dispatchers.IO) {
            securityManager?.markAlertKeyAcknowledged(activeUserId, key)
        }
    }

    fun approveAuditorClosingPopup(session: CashSession, approvedBy: String) {
        dismissAuditorClosingPopup(session)
        approveSession(session.sessionId, approvedBy)
    }

    fun dismissAuditorNotification() {
        _uiState.update { it.copy(auditorNotificationMessage = null) }
    }

    fun submitAuditorVerification(
        branchId: String,
        auditorUid: String,
        auditorStaffId: String,
        auditorName: String,
        businessDateMillis: Long,
        openingBalanceMinor: Long,
        totalDRMinor: Long,
        totalCRMinor: Long,
        cashierCountedMinor: Long?,
        notes: String = ""
    ) {
        if (branchId.isBlank()) {
            _uiState.update { it.copy(error = "Branch is not assigned yet. Contact Admin.") }
            return
        }
        val expectedEnding = openingBalanceMinor + totalDRMinor - totalCRMinor
        val counted = cashierCountedMinor ?: 0L
        val difference = if (cashierCountedMinor != null) counted - expectedEnding else 0L
        val checkStatus = when {
            cashierCountedMinor == null -> "PENDING_CASHIER"
            difference == 0L -> "BALANCED"
            difference < 0L -> "SHORTAGE"
            else -> "EXCESS"
        }
        val checkId = buildAuditorCheckId(branchId, businessDateMillis)
        val dto = AuditorCheckDto(
            id = checkId,
            checkId = checkId,
            branchId = branchId,
            auditorUid = auditorUid,
            auditorStaffId = auditorStaffId.ifBlank { "AUD001" },
            auditorName = auditorName.ifBlank { "Branch Auditor" },
            businessDate = businessDateMillis,
            openingBalanceMinor = openingBalanceMinor,
            totalDRMinor = totalDRMinor,
            totalCRMinor = totalCRMinor,
            expectedEndingMinor = expectedEnding,
            cashierCountedMinor = counted,
            differenceMinor = difference,
            checkStatus = checkStatus,
            notes = notes.trim().take(500),
            appliedByCashier = false
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingAuditorCheck = true, auditorSubmitNotice = null, error = null) }
            val result = repository.submitAuditorCheck(dto)
            _uiState.update { state ->
                if (result.isSuccess) {
                    state.copy(
                        isSubmittingAuditorCheck = false,
                        auditorSubmitNotice = "Auditor DR/CR submitted ($checkStatus). Branch Cashier has been notified."
                    )
                } else {
                    state.copy(
                        isSubmittingAuditorCheck = false,
                        error = result.exceptionOrNull()?.localizedMessage ?: "Failed to submit auditor check."
                    )
                }
            }
        }
    }

    fun clearAuditorSubmitNotice() {
        _uiState.update { it.copy(auditorSubmitNotice = null) }
    }

    fun refreshHistory(branchId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingHistory = true, error = null) }
            val role = _activeRole.value
            if (role == StaffRole.CASHIER) {
                repository.syncPendingSessions()
            }
            val result = if (isMultiBranchRole(role)) {
                repository.syncAllBranchesFromFirestore()
            } else {
                repository.syncFromFirestore(branchId)
            }
            _uiState.update { state ->
                state.copy(
                    isSyncingHistory = false,
                    historyNotice = if (result.isSuccess) "Branch history updated." else null,
                    error = result.exceptionOrNull()?.localizedMessage
                )
            }
        }
    }

    fun loadDenominationsFromSession(sessionId: String, onLoaded: () -> Unit = {}) {
        viewModelScope.launch {
            val denoms = repository.getDenominationsForSession(sessionId)
            if (denoms.isNotEmpty()) {
                applyEntityDenominations(denoms)
                persistWorkingCounts(_uiState.value)
                _uiState.update {
                    it.copy(
                        currentStep = 2,
                        historyNotice = "Loaded bundle & petty quantities into calculator."
                    )
                }
                onLoaded()
            } else {
                _uiState.update {
                    it.copy(error = "No saved denomination breakdown found for that session.")
                }
            }
        }
    }

    private fun applyPersistedCounts(counts: PersistedDenominationCounts) {
        _uiState.update { state ->
            val bundleMap = counts.bundles.associateBy { it.denominationMinor }
            val pettyMap = counts.petty.associateBy { it.denominationMinor }
            val updatedBundles = state.bundleDenominations.map { entry ->
                val saved = bundleMap[entry.denominationMinor]
                if (saved != null) {
                    entry.copy(
                        bundles = saved.bundles.coerceAtLeast(0),
                        notesPerBundle = saved.notesPerBundle.coerceAtLeast(1),
                        looseNotes = saved.looseNotes.coerceAtLeast(0)
                    )
                } else {
                    entry
                }
            }
            val updatedPetty = state.pettyDenominations.map { entry ->
                val saved = pettyMap[entry.denominationMinor]
                if (saved != null) {
                    entry.copy(quantity = saved.quantity.coerceAtLeast(0))
                } else {
                    entry
                }
            }
            state.copy(
                bundleDenominations = updatedBundles,
                pettyDenominations = updatedPetty
            )
        }
    }

    private fun applyEntityDenominations(denoms: List<CashDenomination>) {
        val bundleMap = denoms.filter { it.cashType == "BUNDLE" }.associateBy { it.denominationMinor }
        val pettyMap = denoms.filter { it.cashType == "PETTY" }.associateBy { it.denominationMinor }
        _uiState.update { state ->
            val updatedBundles = state.bundleDenominations.map { entry ->
                val saved = bundleMap[entry.denominationMinor]
                if (saved != null) {
                    entry.copy(
                        bundles = saved.bundles.coerceAtLeast(0),
                        notesPerBundle = saved.notesPerBundle.coerceAtLeast(1),
                        looseNotes = saved.looseNotes.coerceAtLeast(0)
                    )
                } else {
                    entry
                }
            }
            val updatedPetty = state.pettyDenominations.map { entry ->
                val saved = pettyMap[entry.denominationMinor]
                if (saved != null) {
                    entry.copy(quantity = saved.quantity.coerceAtLeast(0))
                } else {
                    entry
                }
            }
            state.copy(
                bundleDenominations = updatedBundles,
                pettyDenominations = updatedPetty
            )
        }
    }

    private fun persistWorkingCounts(state: CashCalculatorUiState) {
        val manager = securityManager ?: return
        val uid = activeUserId.ifBlank { return }
        val payload = PersistedDenominationCounts(
            bundles = state.bundleDenominations.map {
                PersistedBundleCount(
                    denominationMinor = it.denominationMinor,
                    bundles = it.bundles,
                    notesPerBundle = it.notesPerBundle,
                    looseNotes = it.looseNotes
                )
            },
            petty = state.pettyDenominations.map {
                PersistedPettyCount(
                    denominationMinor = it.denominationMinor,
                    quantity = it.quantity
                )
            }
        )
        viewModelScope.launch(Dispatchers.IO) {
            manager.saveWorkingCounts(uid, payload)
        }
    }

    fun nextStep() {
        if (_uiState.value.currentStep < 5) {
            _uiState.update { it.copy(currentStep = it.currentStep + 1) }
        }
    }

    fun prevStep() {
        if (_uiState.value.currentStep > 1) {
            _uiState.update { it.copy(currentStep = it.currentStep - 1) }
        }
    }

    fun setStep(step: Int) {
        if (step in 1..5) {
            _uiState.update { it.copy(currentStep = step) }
        }
    }

    fun updateBusinessDate(localMidnightMillis: Long) {
        _uiState.update { it.copy(selectedBusinessDateMillis = localMidnightMillis) }
    }

    fun loadPreviousBalance(branchId: String) {
        if (branchId.isBlank()) {
            val msg = "Branch ID is not configured for this profile."
            _uiState.update {
                it.copy(
                    previousBalanceNotice = msg,
                    previousBalanceStatus = PreviousBalanceStatus(PreviousBalanceStatusType.ERROR, msg)
                )
            }
            return
        }

        var started = false
        _uiState.update { state ->
            if (state.isLoadingPreviousBalance) {
                state
            } else {
                started = true
                state.copy(
                    isLoadingPreviousBalance = true,
                    previousBalanceNotice = null,
                    previousBalanceStatus = null,
                    openingInputError = null,
                    pendingOverwriteClosing = null
                )
            }
        }
        if (!started) return

        viewModelScope.launch {
            try {
                when (val result = repository.fetchLatestApprovedClosing(branchId)) {
                    is PreviousClosingFetchResult.Loaded -> {
                        val closing = result.closing
                        val currentState = _uiState.value
                        val hasExistingEnteredValue = currentState.hasConfirmedOpeningEntry &&
                            (currentState.openingBalanceMinor > 0L ||
                                currentState.openingBalanceSource == OpeningBalanceSource.MANUAL)

                        val alreadyMatchesSameClosing =
                            currentState.openingBalanceSource == OpeningBalanceSource.PREVIOUS_APPROVED &&
                                currentState.loadedClosingMetadata?.sessionId == closing.sessionId &&
                                currentState.openingBalanceMinor == closing.amountMinor

                        if (alreadyMatchesSameClosing) {
                            val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
                                .format(java.util.Date(closing.businessDateMillis))
                            val statusMsg = if (result.fromOfflineCache) {
                                "Saved approved closing ($dateStr) is already loaded."
                            } else {
                                "Latest approved closing ($dateStr) is already loaded."
                            }
                            _uiState.update {
                                it.copy(
                                    isLoadingPreviousBalance = false,
                                    loadedClosingMetadata = closing.toLoadedMetadata(result.fromOfflineCache),
                                    previousBalanceStatus = PreviousBalanceStatus(
                                        type = if (result.fromOfflineCache) {
                                            PreviousBalanceStatusType.WARNING_OFFLINE
                                        } else {
                                            PreviousBalanceStatusType.INFO
                                        },
                                        message = statusMsg
                                    )
                                )
                            }
                        } else if (hasExistingEnteredValue && currentState.openingBalanceMinor != closing.amountMinor) {
                            // Never silently overwrite an existing entered opening balance
                            _uiState.update {
                                it.copy(
                                    isLoadingPreviousBalance = false,
                                    pendingOverwriteClosing = closing
                                )
                            }
                        } else {
                            applyLoadedClosing(closing, result.fromOfflineCache)
                        }
                    }

                    is PreviousClosingFetchResult.NotFound -> {
                        val msg = if (!result.cloudSynced) {
                            "Offline mode: no saved closing found for $branchId. Enter opening balance manually."
                        } else {
                            "No prior approved closing found for $branchId. Enter opening balance manually."
                        }
                        val statusType = if (!result.cloudSynced) {
                            PreviousBalanceStatusType.WARNING_OFFLINE
                        } else {
                            PreviousBalanceStatusType.INFO
                        }
                        _uiState.update {
                            it.copy(
                                isLoadingPreviousBalance = false,
                                previousBalanceNotice = msg,
                                previousBalanceStatus = PreviousBalanceStatus(statusType, msg)
                            )
                        }
                    }

                    is PreviousClosingFetchResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoadingPreviousBalance = false,
                                previousBalanceNotice = result.message,
                                previousBalanceStatus = PreviousBalanceStatus(
                                    PreviousBalanceStatusType.ERROR,
                                    result.message
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = "Unable to load previous balance: ${e.localizedMessage ?: "Unknown error"}"
                _uiState.update {
                    it.copy(
                        isLoadingPreviousBalance = false,
                        previousBalanceNotice = msg,
                        previousBalanceStatus = PreviousBalanceStatus(PreviousBalanceStatusType.ERROR, msg)
                    )
                }
            }
        }
    }

    private fun PreviousApprovedClosingInfo.toLoadedMetadata(fromOfflineCache: Boolean): LoadedClosingMetadata {
        return LoadedClosingMetadata(
            sessionId = sessionId,
            branchId = branchId,
            amountMinor = amountMinor,
            businessDateMillis = businessDateMillis,
            isApproved = isApproved,
            approvedBy = approvedBy,
            approvalTimestamp = approvalTimestamp,
            reconciliationStatus = reconciliationStatus,
            isOfflineCached = fromOfflineCache || !syncedFromCloud
        )
    }

    private fun applyLoadedClosing(closing: PreviousApprovedClosingInfo, fromOfflineCache: Boolean) {
        val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(closing.businessDateMillis))
        val metadata = closing.toLoadedMetadata(fromOfflineCache)
        val status = if (metadata.isOfflineCached) {
            PreviousBalanceStatus(
                type = PreviousBalanceStatusType.WARNING_OFFLINE,
                message = "Loaded saved closing from $dateStr (offline mode)."
            )
        } else {
            PreviousBalanceStatus(
                type = PreviousBalanceStatusType.SUCCESS,
                message = "Approved closing from $dateStr loaded."
            )
        }
        _uiState.update {
            it.copy(
                openingBalanceBirr = closing.amountMinor / 100.0,
                openingBalanceSource = OpeningBalanceSource.PREVIOUS_APPROVED,
                hasConfirmedOpeningEntry = true,
                loadedClosingMetadata = metadata,
                pendingOverwriteClosing = null,
                isPreviousBalanceLocked = true,
                openingInputError = null,
                isLoadingPreviousBalance = false,
                previousBalanceNotice = null,
                previousBalanceStatus = status
            )
        }
    }

    fun confirmOverwriteWithPreviousBalance() {
        val pending = _uiState.value.pendingOverwriteClosing ?: return
        applyLoadedClosing(pending, !pending.syncedFromCloud)
    }

    fun dismissOverwriteWithPreviousBalance() {
        _uiState.update {
            it.copy(
                pendingOverwriteClosing = null,
                previousBalanceStatus = PreviousBalanceStatus(
                    PreviousBalanceStatusType.INFO,
                    "Kept existing opening balance."
                )
            )
        }
    }

    fun unlockPreviousBalanceForEdit() {
        _uiState.update {
            it.copy(
                isPreviousBalanceLocked = false,
                openingInputError = null
            )
        }
    }

    fun restoreLoadedClosingBalance() {
        val meta = _uiState.value.loadedClosingMetadata ?: return
        val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(meta.businessDateMillis))
        _uiState.update {
            it.copy(
                openingBalanceBirr = meta.amountMinor / 100.0,
                openingBalanceSource = OpeningBalanceSource.PREVIOUS_APPROVED,
                hasConfirmedOpeningEntry = true,
                isPreviousBalanceLocked = true,
                openingInputError = null,
                previousBalanceStatus = PreviousBalanceStatus(
                    PreviousBalanceStatusType.SUCCESS,
                    "Restored approved closing from $dateStr."
                )
            )
        }
    }

    /**
     * Validates and applies manual ETB input using exact minor-unit conversion.
     * Returns true if the text edit should be reflected in the input field, or false if rejected.
     */
    fun onOpeningBalanceTextChanged(rawInput: String): Boolean {
        val state = _uiState.value
        if (state.isPreviousBalanceLocked) {
            _uiState.update {
                it.copy(
                    openingInputError = "Loaded closing is locked. Tap Edit to modify manually."
                )
            }
            return false
        }

        when (val parsed = parseAndValidateEtbInput(rawInput)) {
            EtbParseResult.Empty -> {
                val nextSource = if (state.loadedClosingMetadata != null) {
                    OpeningBalanceSource.ADJUSTED_FROM_PREVIOUS
                } else {
                    OpeningBalanceSource.NONE
                }
                _uiState.update {
                    it.copy(
                        openingBalanceBirr = 0.0,
                        openingBalanceSource = nextSource,
                        hasConfirmedOpeningEntry = false,
                        openingInputError = null,
                        previousBalanceNotice = null
                    )
                }
                return true
            }

            is EtbParseResult.Invalid -> {
                _uiState.update {
                    it.copy(openingInputError = parsed.message)
                }
                return false
            }

            is EtbParseResult.Valid -> {
                val meta = state.loadedClosingMetadata
                val nextSource = when {
                    meta != null && parsed.minor == meta.amountMinor &&
                        state.openingBalanceSource == OpeningBalanceSource.PREVIOUS_APPROVED ->
                        OpeningBalanceSource.PREVIOUS_APPROVED
                    meta != null -> OpeningBalanceSource.ADJUSTED_FROM_PREVIOUS
                    else -> OpeningBalanceSource.MANUAL
                }
                _uiState.update {
                    it.copy(
                        openingBalanceBirr = parsed.birr,
                        openingBalanceSource = nextSource,
                        hasConfirmedOpeningEntry = true,
                        openingInputError = null,
                        previousBalanceNotice = null
                    )
                }
                return true
            }
        }
    }

    fun clearOpeningBalance() {
        _uiState.update {
            it.copy(
                openingBalanceBirr = 0.0,
                openingBalanceSource = OpeningBalanceSource.NONE,
                hasConfirmedOpeningEntry = false,
                isPreviousBalanceLocked = false,
                openingInputError = null,
                previousBalanceNotice = null,
                previousBalanceStatus = if (it.loadedClosingMetadata != null) {
                    PreviousBalanceStatus(
                        PreviousBalanceStatusType.INFO,
                        "Opening balance cleared. Enter float or reload previous closing."
                    )
                } else {
                    null
                }
            )
        }
    }

    fun dismissOpeningInputError() {
        _uiState.update { it.copy(openingInputError = null) }
    }

    /**
     * Validates Step 1 opening balance before navigating to Bundles and prevents
     * rapid double-tap from skipping Step 2.
     */
    fun confirmOpeningAndProceed(rawInput: String): Boolean {
        val state = _uiState.value
        if (state.currentStep != 1 || state.isLoadingPreviousBalance || state.isSaving) {
            return false
        }

        if (state.openingInputError != null && !state.isPreviousBalanceLocked) {
            return false
        }

        when (val parsed = parseAndValidateEtbInput(rawInput)) {
            EtbParseResult.Empty -> {
                if (state.hasConfirmedOpeningEntry && state.openingBalanceMinor >= 0L && state.isPreviousBalanceLocked) {
                    _uiState.update { it.copy(currentStep = 2, openingInputError = null) }
                    return true
                }
                _uiState.update {
                    it.copy(
                        openingInputError = "Enter an opening balance (enter 0 for zero float) or load previous approved balance."
                    )
                }
                return false
            }

            is EtbParseResult.Invalid -> {
                _uiState.update { it.copy(openingInputError = parsed.message) }
                return false
            }

            is EtbParseResult.Valid -> {
                val meta = state.loadedClosingMetadata
                val nextSource = when {
                    meta != null && parsed.minor == meta.amountMinor &&
                        state.openingBalanceSource == OpeningBalanceSource.PREVIOUS_APPROVED ->
                        OpeningBalanceSource.PREVIOUS_APPROVED
                    meta != null && state.openingBalanceSource == OpeningBalanceSource.ADJUSTED_FROM_PREVIOUS ->
                        OpeningBalanceSource.ADJUSTED_FROM_PREVIOUS
                    state.openingBalanceSource == OpeningBalanceSource.PREVIOUS_APPROVED && meta == null ->
                        OpeningBalanceSource.PREVIOUS_APPROVED
                    else -> OpeningBalanceSource.MANUAL
                }
                _uiState.update {
                    it.copy(
                        openingBalanceBirr = parsed.birr,
                        openingBalanceSource = nextSource,
                        hasConfirmedOpeningEntry = true,
                        openingInputError = null,
                        currentStep = 2
                    )
                }
                return true
            }
        }
    }

    fun updateOpeningBalance(birr: Double) {
        if (birr < 0.0 || birr.isNaN() || birr.isInfinite()) {
            _uiState.update {
                it.copy(openingInputError = "Negative or invalid opening balances are not allowed.")
            }
            return
        }
        val minor = (birr * 100.0).roundToLong()
        if (minor > MAX_OPENING_BALANCE_MINOR) {
            _uiState.update {
                it.copy(openingInputError = "Amount exceeds the maximum branch limit (ETB 999,999,999.99).")
            }
            return
        }
        _uiState.update {
            it.copy(
                openingBalanceBirr = minor / 100.0,
                openingBalanceSource = if (minor > 0L) OpeningBalanceSource.MANUAL else it.openingBalanceSource,
                hasConfirmedOpeningEntry = true,
                isPreviousBalanceLocked = false,
                openingInputError = null,
                previousBalanceNotice = null
            )
        }
    }

    fun updateBundleEntry(index: Int, bundles: Int, notesPerBundle: Int, looseNotes: Int) {
        var latestState: CashCalculatorUiState? = null
        _uiState.update { state ->
            if (index !in state.bundleDenominations.indices) return@update state
            val newList = state.bundleDenominations.toMutableList()
            newList[index] = newList[index].copy(
                bundles = bundles.coerceAtLeast(0),
                notesPerBundle = notesPerBundle.coerceAtLeast(1),
                looseNotes = looseNotes.coerceAtLeast(0)
            )
            state.copy(bundleDenominations = newList).also { latestState = it }
        }
        latestState?.let { persistWorkingCounts(it) }
    }

    fun updatePettyQuantity(index: Int, quantity: Int) {
        var latestState: CashCalculatorUiState? = null
        _uiState.update { state ->
            if (index !in state.pettyDenominations.indices) return@update state
            val newList = state.pettyDenominations.toMutableList()
            newList[index] = newList[index].copy(quantity = quantity.coerceAtLeast(0))
            state.copy(pettyDenominations = newList).also { latestState = it }
        }
        latestState?.let { persistWorkingCounts(it) }
    }

    fun toggleSystemVerification(enable: Boolean) {
        _uiState.update { state ->
            state.copy(
                useSystemVerification = enable,
                drTransactions = if (enable && state.drTransactions.isEmpty()) listOf(TransactionEntry(type = "DR")) else state.drTransactions,
                crTransactions = if (enable && state.crTransactions.isEmpty()) listOf(TransactionEntry(type = "CR")) else state.crTransactions
            )
        }
    }

    fun addDrTransaction() {
        _uiState.update { it.copy(drTransactions = it.drTransactions + TransactionEntry(type = "DR")) }
    }

    fun updateDrTransaction(id: String, amountBirr: Double, description: String = "") {
        _uiState.update { state ->
            val newList = state.drTransactions.map {
                if (it.id == id) it.copy(amountBirr = amountBirr.coerceAtLeast(0.0), description = description) else it
            }
            state.copy(drTransactions = newList)
        }
    }

    fun removeDrTransaction(id: String) {
        _uiState.update { state ->
            val remaining = state.drTransactions.filter { t -> t.id != id }
            state.copy(drTransactions = remaining.ifEmpty { listOf(TransactionEntry(type = "DR")) })
        }
    }

    fun addCrTransaction() {
        _uiState.update { it.copy(crTransactions = it.crTransactions + TransactionEntry(type = "CR")) }
    }

    fun updateCrTransaction(id: String, amountBirr: Double, description: String = "") {
        _uiState.update { state ->
            val newList = state.crTransactions.map {
                if (it.id == id) it.copy(amountBirr = amountBirr.coerceAtLeast(0.0), description = description) else it
            }
            state.copy(crTransactions = newList)
        }
    }

    fun setDrAmount(amountBirr: Double) {
        val clamped = amountBirr.coerceAtLeast(0.0)
        _uiState.update { state ->
            val currentId = state.drTransactions.firstOrNull()?.id ?: UUID.randomUUID().toString()
            state.copy(
                useSystemVerification = true,
                drTransactions = listOf(TransactionEntry(id = currentId, type = "DR", amountBirr = clamped))
            )
        }
    }

    fun setCrAmount(amountBirr: Double) {
        val clamped = amountBirr.coerceAtLeast(0.0)
        _uiState.update { state ->
            val currentId = state.crTransactions.firstOrNull()?.id ?: UUID.randomUUID().toString()
            state.copy(
                useSystemVerification = true,
                crTransactions = listOf(TransactionEntry(id = currentId, type = "CR", amountBirr = clamped))
            )
        }
    }

    fun removeCrTransaction(id: String) {
        _uiState.update { state ->
            val remaining = state.crTransactions.filter { t -> t.id != id }
            state.copy(crTransactions = remaining.ifEmpty { listOf(TransactionEntry(type = "CR")) })
        }
    }

    /**
     * Prepares the calculator for the next day's reconciliation while strictly preserving
     * all entered Bundle Cash and Petty Cash denomination quantities.
     */
    fun prepareForNextDay() {
        var nextState: CashCalculatorUiState? = null
        _uiState.update { state ->
            state.copy(
                currentStep = 1,
                selectedBusinessDateMillis = todayMidnightMillis(),
                drTransactions = listOf(TransactionEntry(type = "DR")),
                crTransactions = listOf(TransactionEntry(type = "CR")),
                previousBalanceNotice = null,
                previousBalanceStatus = null,
                pendingOverwriteClosing = null,
                openingInputError = null,
                isSaving = false,
                saveSuccess = false,
                saveWasUpdate = false,
                error = null
            ).also { nextState = it }
        }
        nextState?.let { persistWorkingCounts(it) }
    }

    fun reset(clearDenominations: Boolean = false) {
        if (clearDenominations) {
            val cleared = CashCalculatorUiState()
            _uiState.value = cleared
            persistWorkingCounts(cleared)
        } else {
            prepareForNextDay()
        }
    }

    fun saveSession(branchId: String, staffId: String, isFinal: Boolean = false) {
        val state = _uiState.value
        val businessDate = state.selectedBusinessDateMillis

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val existing = repository.getExistingSessionForDate(branchId, businessDate)
                val isUpdate = existing != null
                val sessionId = existing?.sessionId ?: buildDailySessionId(branchId, businessDate)
                val createdAt = existing?.createdAt ?: System.currentTimeMillis()
                val nextVersion = (existing?.version ?: 0) + 1

                val session = CashSession(
                    sessionId = sessionId,
                    branchId = branchId,
                    staffId = staffId,
                    businessDate = businessDate,
                    createdAt = createdAt,
                    openingBalanceMinor = state.openingBalanceMinor,
                    totalDRMinor = if (state.useSystemVerification) state.totalDRMinor else 0L,
                    totalCRMinor = if (state.useSystemVerification) state.totalCRMinor else 0L,
                    expectedEndingMinor = if (state.useSystemVerification) state.expectedEndingMinor else state.openingBalanceMinor,
                    bundleCashMinor = state.bundleCashTotalMinor,
                    pettyCashMinor = state.pettyCashTotalMinor,
                    totalCountedMinor = state.totalCountedMinor,
                    differenceMinor = if (state.useSystemVerification) state.differenceMinor else 0L,
                    reconciliationStatus = if (state.useSystemVerification) state.reconciliationStatus else "BALANCED",
                    status = if (isFinal) "CLOSED" else "DRAFT",
                    syncStatus = "PENDING",
                    isFinal = isFinal,
                    isApproved = false,
                    approvedBy = null,
                    approvalTimestamp = null,
                    version = nextVersion
                )

                val denominations = state.bundleDenominations.map {
                    CashDenomination(
                        sessionId = sessionId,
                        cashType = "BUNDLE",
                        denominationMinor = it.denominationMinor,
                        quantity = it.totalQuantity,
                        bundles = it.bundles,
                        notesPerBundle = it.notesPerBundle,
                        looseNotes = it.looseNotes,
                        subtotalMinor = it.subtotalMinor
                    )
                } + state.pettyDenominations.map {
                    CashDenomination(
                        sessionId = sessionId,
                        cashType = "PETTY",
                        denominationMinor = it.denominationMinor,
                        quantity = it.quantity,
                        subtotalMinor = it.subtotalMinor
                    )
                }

                val transactions = if (state.useSystemVerification) {
                    buildList {
                        if (state.totalDRMinor > 0L || isUpdate) {
                            add(
                                CashTransaction(
                                    transactionId = "DR",
                                    sessionId = sessionId,
                                    transactionType = "DR",
                                    amountMinor = state.totalDRMinor,
                                    description = ""
                                )
                            )
                        }
                        if (state.totalCRMinor > 0L || isUpdate) {
                            add(
                                CashTransaction(
                                    transactionId = "CR",
                                    sessionId = sessionId,
                                    transactionType = "CR",
                                    amountMinor = state.totalCRMinor,
                                    description = ""
                                )
                            )
                        }
                    }
                } else emptyList()

                repository.saveSession(session, denominations, transactions)
                // Always retain the saved denomination quantities for next-day calculations
                persistWorkingCounts(state)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveSuccess = true,
                        saveWasUpdate = isUpdate
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Failed to save session.") }
            }
        }
    }

    fun approveSession(sessionId: String, approvedBy: String) {
        viewModelScope.launch {
            try {
                repository.approveSession(sessionId, approvedBy)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Approval failed: ${e.message ?: "Unknown error"}") }
            }
        }
    }

    fun retrySync(sessionId: String) {
        viewModelScope.launch {
            val result = repository.trySyncSession(sessionId)
            result.onFailure { e ->
                _uiState.update {
                    it.copy(error = "Sync failed: ${e.localizedMessage ?: "Check your connection or permissions."}")
                }
            }
        }
    }

    fun openHistoryLedger(session: CashSession) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingHistoryLedger = true) }
            try {
                val denoms = repository.getDenominationsForSession(session.sessionId)
                val txs = repository.getTransactionsForSession(session.sessionId)
                _uiState.update {
                    it.copy(
                        isLoadingHistoryLedger = false,
                        selectedHistoryLedger = SessionLedgerDetail(
                            session = session,
                            denominations = denoms,
                            transactions = txs
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingHistoryLedger = false,
                        error = "Unable to load ledger details: ${e.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun closeHistoryLedger() {
        _uiState.update { it.copy(selectedHistoryLedger = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearHistoryNotice() {
        _uiState.update { it.copy(historyNotice = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }
}

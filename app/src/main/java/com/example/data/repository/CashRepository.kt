package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.local.dao.CashDao
import com.example.data.local.entities.CashDenomination
import com.example.data.local.entities.CashSession
import com.example.data.local.entities.CashTransaction
import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.example.data.remote.model.AuditorCheckDto
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class PreviousApprovedClosingInfo(
    val sessionId: String,
    val branchId: String,
    val amountMinor: Long,
    val businessDateMillis: Long,
    val isApproved: Boolean,
    val approvedBy: String?,
    val approvalTimestamp: Long?,
    val reconciliationStatus: String,
    val syncedFromCloud: Boolean
)

sealed interface PreviousClosingFetchResult {
    data class Loaded(
        val closing: PreviousApprovedClosingInfo,
        val fromOfflineCache: Boolean,
        val cloudError: String? = null
    ) : PreviousClosingFetchResult

    data class NotFound(
        val branchId: String,
        val cloudSynced: Boolean,
        val cloudError: String? = null
    ) : PreviousClosingFetchResult

    data class Error(
        val message: String
    ) : PreviousClosingFetchResult
}

fun buildDailySessionId(branchId: String, businessDateMillis: Long): String {
    val safeBranch = branchId.trim()
        .uppercase(Locale.US)
        .replace(Regex("[^A-Z0-9]+"), "_")
        .trim('_')
        .ifBlank { "BRANCH" }
        .take(40)
    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(businessDateMillis))
    return "${safeBranch}_$dayKey"
}

fun buildAuditorCheckId(branchId: String, businessDateMillis: Long): String {
    val safeBranch = branchId.trim()
        .uppercase(Locale.US)
        .replace(Regex("[^A-Z0-9]+"), "_")
        .trim('_')
        .ifBlank { "BRANCH" }
        .take(40)
    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(businessDateMillis))
    return "CHK_${safeBranch}_$dayKey"
}

interface CashSessionRepository {
    fun getAllSessions(): Flow<List<CashSession>>
    fun getSessionsForBranch(branchId: String): Flow<List<CashSession>>
    suspend fun saveSession(
        session: CashSession,
        denominations: List<CashDenomination>,
        transactions: List<CashTransaction>
    )
    suspend fun trySyncSession(sessionId: String): Result<Unit>
    suspend fun syncFromFirestore(branchId: String): Result<Unit>
    suspend fun syncAllBranchesFromFirestore(): Result<Unit> = Result.success(Unit)
    suspend fun getLatestApprovedBalance(branchId: String): Long?
    suspend fun fetchLatestApprovedClosing(branchId: String): PreviousClosingFetchResult
    suspend fun getLatestSavedDenominations(branchId: String): List<CashDenomination>
    suspend fun getDenominationsForSession(sessionId: String): List<CashDenomination>
    suspend fun getTransactionsForSession(sessionId: String): List<CashTransaction>
    suspend fun getExistingSessionForDate(branchId: String, businessDateMillis: Long): CashSession?
    suspend fun hasFinalClosingForDate(branchId: String, businessDate: Long): Boolean
    suspend fun hasUnapprovedSessions(branchId: String): Boolean
    suspend fun approveSession(sessionId: String, approvedBy: String)
    suspend fun syncPendingSessions()
    fun observeAuditorChecksForBranch(branchId: String): Flow<List<AuditorCheckDto>> = emptyFlow()
    fun observeAllAuditorChecks(): Flow<List<AuditorCheckDto>> = emptyFlow()
    suspend fun submitAuditorCheck(check: AuditorCheckDto): Result<AuditorCheckDto> = Result.success(check)
    suspend fun markAuditorCheckApplied(checkId: String): Result<Unit> = Result.success(Unit)
}

class CashRepository(
    private val cashDao: CashDao,
    private val db: FirebaseFirestore
) : CashSessionRepository {
    private val auth by lazy { Firebase.auth }

    companion object {
        fun create(context: Context, cashDao: CashDao): CashRepository {
            val dbId = context.getString(R.string.firestore_database_id)
            return CashRepository(cashDao, FirebaseFirestore.getInstance(dbId))
        }
    }

    override fun getAllSessions(): Flow<List<CashSession>> = cashDao.getAllSessions()

    override fun getSessionsForBranch(branchId: String): Flow<List<CashSession>> = cashDao.getSessionsForBranch(branchId)

    override suspend fun saveSession(
        session: CashSession,
        denominations: List<CashDenomination>,
        transactions: List<CashTransaction>
    ) {
        val (dayStart, dayEnd) = dayRangeForMillis(session.businessDate)
        cashDao.saveSingleDailySession(
            session = session,
            denominations = denominations,
            transactions = transactions,
            dayStartMillis = dayStart,
            dayEndMillis = dayEnd
        )
        trySyncSession(session.sessionId)
    }

    override suspend fun trySyncSession(sessionId: String): Result<Unit> {
        val session = cashDao.getSessionById(sessionId) ?: return Result.success(Unit)
        if (session.syncStatus == "SYNCED") return Result.success(Unit)

        val uid = auth.currentUser?.uid ?: return Result.failure(
            IllegalStateException("User must be signed in to sync session.")
        )

        val denoms = cashDao.getDenominationsForSession(sessionId)
        val trans = cashDao.getTransactionsForSession(sessionId)

        val sessionRef = db.collection("cashSessions").document(sessionId)

        return try {
            db.runTransaction { transaction ->
                val existingSnapshot = transaction.get(sessionRef)

                if (!existingSnapshot.exists()) {
                    val sessionPayload = mutableMapOf<String, Any>(
                        "sessionId" to session.sessionId,
                        "branchId" to session.branchId,
                        "staffId" to session.staffId,
                        "ownerUid" to uid,
                        "businessDate" to session.businessDate,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "openingBalanceMinor" to session.openingBalanceMinor,
                        "totalDRMinor" to session.totalDRMinor,
                        "totalCRMinor" to session.totalCRMinor,
                        "expectedEndingMinor" to session.expectedEndingMinor,
                        "bundleCashMinor" to session.bundleCashMinor,
                        "pettyCashMinor" to session.pettyCashMinor,
                        "totalCountedMinor" to session.totalCountedMinor,
                        "differenceMinor" to session.differenceMinor,
                        "reconciliationStatus" to session.reconciliationStatus,
                        "status" to session.status,
                        "isFinal" to session.isFinal,
                        "isApproved" to session.isApproved,
                        "version" to session.version
                    )
                    session.approvedBy?.takeIf { it.isNotBlank() }?.let {
                        sessionPayload["approvedBy"] = it
                    }
                    session.approvalTimestamp?.let {
                        sessionPayload["approvalTimestamp"] = it
                    }
                    transaction.set(sessionRef, sessionPayload)

                    denoms.forEach { d ->
                        val denomId = "${d.cashType}_${d.denominationMinor}"
                        val denomRef = sessionRef.collection("denominations").document(denomId)
                        transaction.set(
                            denomRef,
                            mapOf(
                                "branchId" to session.branchId,
                                "staffId" to session.staffId,
                                "ownerUid" to uid,
                                "cashType" to d.cashType,
                                "denominationMinor" to d.denominationMinor,
                                "quantity" to d.quantity,
                                "bundles" to d.bundles,
                                "notesPerBundle" to d.notesPerBundle,
                                "looseNotes" to d.looseNotes,
                                "subtotalMinor" to d.subtotalMinor
                            )
                        )
                    }

                    trans.forEach { t ->
                        val transRef = sessionRef.collection("transactions").document(t.transactionId)
                        transaction.set(
                            transRef,
                            mapOf(
                                "branchId" to session.branchId,
                                "staffId" to session.staffId,
                                "ownerUid" to uid,
                                "transactionId" to t.transactionId,
                                "transactionType" to t.transactionType,
                                "amountMinor" to t.amountMinor,
                                "description" to t.description,
                                "createdAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                } else {
                    val existingStatus = existingSnapshot.getString("status") ?: "DRAFT"
                    if (existingStatus == "CLOSED" && session.isApproved && !session.approvedBy.isNullOrBlank()) {
                        val approvalUpdates = mutableMapOf<String, Any>(
                            "isApproved" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        session.approvedBy.takeIf { it.isNotBlank() }?.let {
                            approvalUpdates["approvedBy"] = it
                        }
                        session.approvalTimestamp?.let {
                            approvalUpdates["approvalTimestamp"] = it
                        }
                        transaction.update(sessionRef, approvalUpdates)
                    } else {
                        val sessionUpdates = mutableMapOf<String, Any>(
                            "staffId" to session.staffId,
                            "businessDate" to session.businessDate,
                            "openingBalanceMinor" to session.openingBalanceMinor,
                            "totalDRMinor" to session.totalDRMinor,
                            "totalCRMinor" to session.totalCRMinor,
                            "expectedEndingMinor" to session.expectedEndingMinor,
                            "bundleCashMinor" to session.bundleCashMinor,
                            "pettyCashMinor" to session.pettyCashMinor,
                            "totalCountedMinor" to session.totalCountedMinor,
                            "differenceMinor" to session.differenceMinor,
                            "reconciliationStatus" to session.reconciliationStatus,
                            "status" to session.status,
                            "isFinal" to session.isFinal,
                            "isApproved" to false,
                            "approvedBy" to FieldValue.delete(),
                            "approvalTimestamp" to FieldValue.delete(),
                            "version" to session.version,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        transaction.update(sessionRef, sessionUpdates)

                        denoms.forEach { d ->
                            val denomId = "${d.cashType}_${d.denominationMinor}"
                            val denomRef = sessionRef.collection("denominations").document(denomId)
                            transaction.set(
                                denomRef,
                                mapOf(
                                    "branchId" to session.branchId,
                                    "staffId" to session.staffId,
                                    "ownerUid" to uid,
                                    "cashType" to d.cashType,
                                    "denominationMinor" to d.denominationMinor,
                                    "quantity" to d.quantity,
                                    "bundles" to d.bundles,
                                    "notesPerBundle" to d.notesPerBundle,
                                    "looseNotes" to d.looseNotes,
                                    "subtotalMinor" to d.subtotalMinor
                                )
                            )
                        }

                        trans.forEach { t ->
                            val transRef = sessionRef.collection("transactions").document(t.transactionId)
                            transaction.set(
                                transRef,
                                mapOf(
                                    "branchId" to session.branchId,
                                    "staffId" to session.staffId,
                                    "ownerUid" to uid,
                                    "transactionId" to t.transactionId,
                                    "transactionType" to t.transactionType,
                                    "amountMinor" to t.amountMinor,
                                    "description" to t.description,
                                    "createdAt" to FieldValue.serverTimestamp()
                                )
                            )
                        }
                    }
                }
            }.await()

            cashDao.markAsSynced(sessionId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, sessionRef.path)
            Log.e("CashRepository", "Sync failed for session $sessionId", e)
            Result.failure(e)
        }
    }

    override suspend fun syncFromFirestore(branchId: String): Result<Unit> {
        if (auth.currentUser?.uid == null || branchId.isBlank()) {
            return Result.failure(IllegalStateException("User must be signed in to sync branch history."))
        }
        val queryPath = "cashSessions"
        return try {
            val snapshot = db.collection("cashSessions")
                .whereEqualTo("branchId", branchId)
                .get()
                .await()
            ingestSessionsSnapshot(snapshot, fallbackBranchId = branchId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, queryPath)
            Log.e("CashRepository", "History pull failed for branch $branchId", e)
            Result.failure(e)
        }
    }

    override suspend fun syncAllBranchesFromFirestore(): Result<Unit> {
        if (auth.currentUser?.uid == null) {
            return Result.failure(IllegalStateException("User must be signed in to sync all branches."))
        }
        val queryPath = "cashSessions"
        return try {
            val snapshot = db.collection("cashSessions")
                .get()
                .await()
            ingestSessionsSnapshot(snapshot, fallbackBranchId = "")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, queryPath)
            Log.e("CashRepository", "All-branches pull failed", e)
            Result.failure(e)
        }
    }

    private suspend fun ingestSessionsSnapshot(snapshot: QuerySnapshot, fallbackBranchId: String) {
        // Keep only the latest updated/version session per (branchId, dayKey) so Room never accumulates duplicates
        val docsByBranchAndDay = snapshot.documents
            .groupBy { doc ->
                val branch = (doc.getString("branchId") ?: fallbackBranchId).ifBlank { "UNKNOWN" }
                val bDate = doc.getLong("businessDate") ?: 0L
                val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(bDate))
                "$branch|$dayKey"
            }

        for ((_, docsForDay) in docsByBranchAndDay) {
            val doc = docsForDay.maxWithOrNull(
                compareBy<DocumentSnapshot>(
                    { (it.getBoolean("isFinal") == true) },
                    { (it.getLong("version") ?: 1L) },
                    {
                        it.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?.toDate()?.time ?: 0L
                    },
                    {
                        it.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?.toDate()?.time ?: 0L
                    }
                )
            ) ?: continue

            val sessionId = doc.getString("sessionId")?.takeIf { it.isNotBlank() } ?: doc.id
            val existingLocal = cashDao.getSessionById(sessionId)
            // Preserve local unsynced changes so offline edits are pushed rather than overwritten
            if (existingLocal != null && existingLocal.syncStatus == "PENDING") {
                continue
            }

            val branch = doc.getString("branchId") ?: fallbackBranchId
            val businessDate = doc.getLong("businessDate") ?: 0L
            val createdAtMillis = doc.getTimestamp(
                "createdAt",
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )?.toDate()?.time ?: businessDate
            val session = CashSession(
                sessionId = sessionId,
                branchId = branch,
                staffId = doc.getString("staffId") ?: "",
                businessDate = businessDate,
                createdAt = createdAtMillis,
                openingBalanceMinor = doc.getLong("openingBalanceMinor") ?: 0L,
                totalDRMinor = doc.getLong("totalDRMinor") ?: 0L,
                totalCRMinor = doc.getLong("totalCRMinor") ?: 0L,
                expectedEndingMinor = doc.getLong("expectedEndingMinor") ?: 0L,
                bundleCashMinor = doc.getLong("bundleCashMinor") ?: 0L,
                pettyCashMinor = doc.getLong("pettyCashMinor") ?: 0L,
                totalCountedMinor = doc.getLong("totalCountedMinor") ?: 0L,
                differenceMinor = doc.getLong("differenceMinor") ?: 0L,
                reconciliationStatus = doc.getString("reconciliationStatus") ?: "BALANCED",
                status = doc.getString("status") ?: "DRAFT",
                syncStatus = "SYNCED",
                isFinal = doc.getBoolean("isFinal") ?: false,
                isApproved = doc.getBoolean("isApproved") ?: false,
                approvedBy = doc.getString("approvedBy"),
                approvalTimestamp = doc.getLong("approvalTimestamp"),
                version = (doc.getLong("version") ?: 1L).toInt()
            )

            val denomSnap = doc.reference.collection("denominations").get().await()
            val denoms = denomSnap.documents.map { d ->
                CashDenomination(
                    sessionId = sessionId,
                    cashType = d.getString("cashType") ?: "PETTY",
                    denominationMinor = d.getLong("denominationMinor") ?: 0L,
                    quantity = (d.getLong("quantity") ?: 0L).toInt(),
                    bundles = (d.getLong("bundles") ?: 0L).toInt(),
                    notesPerBundle = ((d.getLong("notesPerBundle") ?: 1000L).toInt()).coerceAtLeast(1),
                    looseNotes = (d.getLong("looseNotes") ?: 0L).toInt(),
                    subtotalMinor = d.getLong("subtotalMinor") ?: 0L
                )
            }

            val transSnap = doc.reference.collection("transactions").get().await()
            val trans = transSnap.documents.mapNotNull { t ->
                val amountMinor = t.getLong("amountMinor") ?: 0L
                if (amountMinor <= 0L) null else {
                    CashTransaction(
                        transactionId = t.getString("transactionId")?.takeIf { it.isNotBlank() } ?: t.id,
                        sessionId = sessionId,
                        transactionType = t.getString("transactionType") ?: "DR",
                        amountMinor = amountMinor,
                        description = t.getString("description") ?: "",
                        createdAt = t.getTimestamp(
                            "createdAt",
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )?.toDate()?.time ?: createdAtMillis
                    )
                }
            }

            val (dayStart, dayEnd) = dayRangeForMillis(businessDate)
            cashDao.saveSingleDailySession(
                session = session,
                denominations = denoms,
                transactions = trans,
                dayStartMillis = dayStart,
                dayEndMillis = dayEnd
            )
        }
    }

    override suspend fun getLatestApprovedBalance(branchId: String): Long? {
        return when (val result = fetchLatestApprovedClosing(branchId)) {
            is PreviousClosingFetchResult.Loaded -> result.closing.amountMinor
            else -> null
        }
    }

    override suspend fun fetchLatestApprovedClosing(branchId: String): PreviousClosingFetchResult {
        if (branchId.isBlank()) {
            return PreviousClosingFetchResult.Error("Branch ID is missing. Contact Admin to assign your branch.")
        }

        var cloudSynced = false
        var cloudError: String? = null

        val isSignedIn = try {
            auth.currentUser != null
        } catch (e: Exception) {
            cloudError = e.localizedMessage
            false
        }

        if (isSignedIn) {
            try {
                syncPendingSessions()
            } catch (_: Exception) {
                // Non-fatal while offline
            }
            val syncResult = syncFromFirestore(branchId)
            cloudSynced = syncResult.isSuccess
            cloudError = syncResult.exceptionOrNull()?.localizedMessage
        } else {
            cloudError = "Not signed in to cloud sync."
        }

        val session = cashDao.getLatestApprovedSession(branchId)
            ?: return PreviousClosingFetchResult.NotFound(
                branchId = branchId,
                cloudSynced = cloudSynced,
                cloudError = cloudError
            )

        val displayDate = if (session.businessDate > 0L) session.businessDate else session.createdAt
        return PreviousClosingFetchResult.Loaded(
            closing = PreviousApprovedClosingInfo(
                sessionId = session.sessionId,
                branchId = session.branchId,
                amountMinor = session.totalCountedMinor,
                businessDateMillis = displayDate,
                isApproved = session.isApproved,
                approvedBy = session.approvedBy,
                approvalTimestamp = session.approvalTimestamp,
                reconciliationStatus = session.reconciliationStatus,
                syncedFromCloud = cloudSynced
            ),
            fromOfflineCache = !cloudSynced,
            cloudError = cloudError
        )
    }

    override suspend fun getLatestSavedDenominations(branchId: String): List<CashDenomination> {
        var latest = cashDao.getLatestSessionForBranch(branchId)
        if (latest == null && auth.currentUser != null) {
            syncFromFirestore(branchId)
            latest = cashDao.getLatestSessionForBranch(branchId)
        }
        val session = latest ?: return emptyList()
        return getDenominationsForSession(session.sessionId)
    }

    override suspend fun getDenominationsForSession(sessionId: String): List<CashDenomination> {
        val local = cashDao.getDenominationsForSession(sessionId)
        if (local.isNotEmpty()) return local
        if (auth.currentUser == null) return emptyList()
        val path = "cashSessions/$sessionId/denominations"
        return try {
            val snap = db.collection("cashSessions").document(sessionId)
                .collection("denominations")
                .get()
                .await()
            val remoteDenoms = snap.documents.map { d ->
                CashDenomination(
                    sessionId = sessionId,
                    cashType = d.getString("cashType") ?: "PETTY",
                    denominationMinor = d.getLong("denominationMinor") ?: 0L,
                    quantity = (d.getLong("quantity") ?: 0L).toInt(),
                    bundles = (d.getLong("bundles") ?: 0L).toInt(),
                    notesPerBundle = ((d.getLong("notesPerBundle") ?: 1000L).toInt()).coerceAtLeast(1),
                    looseNotes = (d.getLong("looseNotes") ?: 0L).toInt(),
                    subtotalMinor = d.getLong("subtotalMinor") ?: 0L
                )
            }
            if (remoteDenoms.isNotEmpty()) {
                cashDao.insertDenominations(remoteDenoms)
            }
            remoteDenoms
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            emptyList()
        }
    }

    override suspend fun getTransactionsForSession(sessionId: String): List<CashTransaction> {
        val local = cashDao.getTransactionsForSession(sessionId)
        if (local.isNotEmpty()) return local
        if (auth.currentUser == null) return emptyList()
        val path = "cashSessions/$sessionId/transactions"
        return try {
            val snap = db.collection("cashSessions").document(sessionId)
                .collection("transactions")
                .get()
                .await()
            val remoteTrans = snap.documents.mapNotNull { t ->
                val amountMinor = t.getLong("amountMinor") ?: 0L
                if (amountMinor <= 0L) null else {
                    CashTransaction(
                        transactionId = t.getString("transactionId")?.takeIf { it.isNotBlank() } ?: t.id,
                        sessionId = sessionId,
                        transactionType = t.getString("transactionType") ?: "DR",
                        amountMinor = amountMinor,
                        description = t.getString("description") ?: "",
                        createdAt = t.getTimestamp(
                            "createdAt",
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )?.toDate()?.time ?: System.currentTimeMillis()
                    )
                }
            }
            if (remoteTrans.isNotEmpty()) {
                cashDao.insertTransactions(remoteTrans)
            }
            remoteTrans
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            emptyList()
        }
    }

    private fun dayRangeForMillis(millis: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis
        return start to end
    }

    override suspend fun getExistingSessionForDate(branchId: String, businessDateMillis: Long): CashSession? {
        val (dayStart, dayEnd) = dayRangeForMillis(businessDateMillis)
        var existing = cashDao.getSessionForBranchInDayRange(branchId, dayStart, dayEnd)
        if (existing == null && auth.currentUser != null) {
            syncFromFirestore(branchId)
            existing = cashDao.getSessionForBranchInDayRange(branchId, dayStart, dayEnd)
        }
        return existing
    }

    override suspend fun hasFinalClosingForDate(branchId: String, businessDate: Long): Boolean {
        return cashDao.hasFinalClosingForDate(branchId, businessDate)
    }

    override suspend fun hasUnapprovedSessions(branchId: String): Boolean {
        return cashDao.getUnapprovedFinalClosings(branchId).isNotEmpty()
    }

    override suspend fun approveSession(sessionId: String, approvedBy: String) {
        cashDao.approveSession(sessionId, approvedBy, System.currentTimeMillis())
        trySyncSession(sessionId)
    }

    override suspend fun syncPendingSessions() {
        val pending = cashDao.getPendingSessions()
        pending.forEach { trySyncSession(it.sessionId) }
    }

    override fun observeAuditorChecksForBranch(branchId: String): Flow<List<AuditorCheckDto>> {
        if (branchId.isBlank() || auth.currentUser == null) return emptyFlow()
        val query = db.collection("auditorChecks").whereEqualTo("branchId", branchId)
        return query.snapshots()
            .map { snap ->
                snap.documents.mapNotNull { doc ->
                    val dto = doc.toObject(AuditorCheckDto::class.java) ?: return@mapNotNull null
                    val estCreated = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    val estUpdated = doc.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    dto.copy(
                        createdAt = estCreated ?: dto.createdAt,
                        updatedAt = estUpdated ?: dto.updatedAt
                    )
                }.sortedByDescending { it.businessDate }
            }
            .catch { error ->
                if (error is CancellationException) throw error
                handleFirestoreError(error, OperationType.LIST, "auditorChecks")
                emit(emptyList())
            }
    }

    override fun observeAllAuditorChecks(): Flow<List<AuditorCheckDto>> {
        if (auth.currentUser == null) return emptyFlow()
        val colRef = db.collection("auditorChecks")
        return colRef.snapshots()
            .map { snap ->
                snap.documents.mapNotNull { doc ->
                    val dto = doc.toObject(AuditorCheckDto::class.java) ?: return@mapNotNull null
                    val estCreated = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    val estUpdated = doc.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    dto.copy(
                        createdAt = estCreated ?: dto.createdAt,
                        updatedAt = estUpdated ?: dto.updatedAt
                    )
                }.sortedByDescending { it.businessDate }
            }
            .catch { error ->
                if (error is CancellationException) throw error
                handleFirestoreError(error, OperationType.LIST, "auditorChecks")
                emit(emptyList())
            }
    }

    override suspend fun submitAuditorCheck(check: AuditorCheckDto): Result<AuditorCheckDto> {
        val uid = auth.currentUser?.uid ?: return Result.failure(
            IllegalStateException("User must be signed in to submit an audit check.")
        )
        val checkId = check.checkId.ifBlank { buildAuditorCheckId(check.branchId, check.businessDate) }
        val docRef = db.collection("auditorChecks").document(checkId)
        return try {
            db.runTransaction { transaction ->
                val existing = transaction.get(docRef)
                val basePayload = mutableMapOf<String, Any>(
                    "checkId" to checkId,
                    "branchId" to check.branchId,
                    "auditorUid" to uid,
                    "auditorStaffId" to check.auditorStaffId.ifBlank { "AUD001" },
                    "auditorName" to check.auditorName.take(120),
                    "businessDate" to check.businessDate,
                    "openingBalanceMinor" to check.openingBalanceMinor.coerceAtLeast(0L),
                    "totalDRMinor" to check.totalDRMinor.coerceAtLeast(0L),
                    "totalCRMinor" to check.totalCRMinor.coerceAtLeast(0L),
                    "expectedEndingMinor" to check.expectedEndingMinor,
                    "cashierCountedMinor" to check.cashierCountedMinor,
                    "differenceMinor" to check.differenceMinor,
                    "checkStatus" to check.checkStatus,
                    "notes" to check.notes.take(500),
                    "appliedByCashier" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!existing.exists()) {
                    basePayload["createdAt"] = FieldValue.serverTimestamp()
                    transaction.set(docRef, basePayload)
                } else {
                    transaction.update(docRef, basePayload)
                }
            }.await()
            Result.success(check.copy(id = checkId, checkId = checkId, auditorUid = uid, appliedByCashier = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    override suspend fun markAuditorCheckApplied(checkId: String): Result<Unit> {
        if (checkId.isBlank() || auth.currentUser == null) return Result.success(Unit)
        val docRef = db.collection("auditorChecks").document(checkId)
        return try {
            docRef.update(
                mapOf(
                    "appliedByCashier" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }
}

package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entities.CashDenomination
import com.example.data.local.entities.CashSession
import com.example.data.local.entities.CashTransaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CashDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: CashSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDenominations(denominations: List<CashDenomination>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<CashTransaction>)

    @Query("DELETE FROM cash_denominations WHERE sessionId = :sessionId")
    suspend fun deleteDenominationsForSession(sessionId: String)

    @Query("DELETE FROM cash_transactions WHERE sessionId = :sessionId")
    suspend fun deleteTransactionsForSession(sessionId: String)

    @Query("DELETE FROM cash_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query(
        "SELECT sessionId FROM cash_sessions WHERE branchId = :branchId AND businessDate >= :dayStartMillis AND businessDate <= :dayEndMillis AND sessionId != :keepSessionId"
    )
    suspend fun getOtherSessionIdsInDayRange(
        branchId: String,
        dayStartMillis: Long,
        dayEndMillis: Long,
        keepSessionId: String
    ): List<String>

    @Transaction
    suspend fun saveFullSession(
        session: CashSession,
        denominations: List<CashDenomination>,
        transactions: List<CashTransaction>
    ) {
        insertSession(session)
        deleteDenominationsForSession(session.sessionId)
        deleteTransactionsForSession(session.sessionId)
        if (denominations.isNotEmpty()) {
            insertDenominations(denominations)
        }
        if (transactions.isNotEmpty()) {
            insertTransactions(transactions)
        }
    }

    @Transaction
    suspend fun saveSingleDailySession(
        session: CashSession,
        denominations: List<CashDenomination>,
        transactions: List<CashTransaction>,
        dayStartMillis: Long,
        dayEndMillis: Long
    ) {
        val staleIds = getOtherSessionIdsInDayRange(
            branchId = session.branchId,
            dayStartMillis = dayStartMillis,
            dayEndMillis = dayEndMillis,
            keepSessionId = session.sessionId
        )
        staleIds.forEach { staleId ->
            deleteDenominationsForSession(staleId)
            deleteTransactionsForSession(staleId)
            deleteSessionById(staleId)
        }
        saveFullSession(session, denominations, transactions)
    }

    @Query("SELECT * FROM cash_sessions WHERE branchId = :branchId AND isApproved = 1 ORDER BY businessDate DESC, createdAt DESC LIMIT 1")
    suspend fun getLatestApprovedSession(branchId: String): CashSession?

    @Query("SELECT * FROM cash_sessions WHERE branchId = :branchId ORDER BY businessDate DESC, version DESC, createdAt DESC LIMIT 1")
    suspend fun getLatestSessionForBranch(branchId: String): CashSession?

    @Query("SELECT * FROM cash_sessions WHERE branchId = :branchId AND businessDate >= :dayStartMillis AND businessDate <= :dayEndMillis ORDER BY isFinal DESC, version DESC, createdAt DESC LIMIT 1")
    suspend fun getSessionForBranchInDayRange(branchId: String, dayStartMillis: Long, dayEndMillis: Long): CashSession?

    @Query("SELECT EXISTS(SELECT 1 FROM cash_sessions WHERE branchId = :branchId AND businessDate = :businessDate AND isFinal = 1)")
    suspend fun hasFinalClosingForDate(branchId: String, businessDate: Long): Boolean

    @Query("UPDATE cash_sessions SET isApproved = 1, approvedBy = :approvedBy, approvalTimestamp = :timestamp, syncStatus = 'PENDING' WHERE sessionId = :sessionId")
    suspend fun approveSession(sessionId: String, approvedBy: String, timestamp: Long)

    @Query("SELECT * FROM cash_sessions WHERE branchId = :branchId AND isFinal = 1 AND isApproved = 0")
    suspend fun getUnapprovedFinalClosings(branchId: String): List<CashSession>

    @Query("SELECT * FROM cash_sessions ORDER BY businessDate DESC, createdAt DESC")
    fun getAllSessions(): Flow<List<CashSession>>

    @Query("SELECT * FROM cash_sessions WHERE branchId = :branchId ORDER BY businessDate DESC, createdAt DESC")
    fun getSessionsForBranch(branchId: String): Flow<List<CashSession>>

    @Query("SELECT * FROM cash_sessions WHERE sessionId = :sessionId")
    suspend fun getSessionById(sessionId: String): CashSession?

    @Query("SELECT * FROM cash_denominations WHERE sessionId = :sessionId")
    suspend fun getDenominationsForSession(sessionId: String): List<CashDenomination>

    @Query("SELECT * FROM cash_transactions WHERE sessionId = :sessionId")
    suspend fun getTransactionsForSession(sessionId: String): List<CashTransaction>

    @Query("UPDATE cash_sessions SET syncStatus = 'SYNCED' WHERE sessionId = :sessionId")
    suspend fun markAsSynced(sessionId: String)

    @Query("SELECT * FROM cash_sessions WHERE syncStatus = 'PENDING'")
    suspend fun getPendingSessions(): List<CashSession>
}

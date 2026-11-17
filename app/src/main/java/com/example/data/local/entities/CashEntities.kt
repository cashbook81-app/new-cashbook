package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "cash_sessions",
    indices = [androidx.room.Index(value = ["branchId", "businessDate", "isFinal"], unique = false)]
)
data class CashSession(
    @PrimaryKey val sessionId: String,
    val branchId: String,
    val staffId: String,
    val businessDate: Long, // date as epoch timestamp (e.g., start of day)
    val createdAt: Long = System.currentTimeMillis(),
    val openingBalanceMinor: Long,
    val totalDRMinor: Long,
    val totalCRMinor: Long,
    val expectedEndingMinor: Long,
    val bundleCashMinor: Long,
    val pettyCashMinor: Long,
    val totalCountedMinor: Long,
    val differenceMinor: Long,
    val reconciliationStatus: String, // BALANCED, SHORTAGE, EXCESS
    val status: String, // DRAFT, CLOSED
    val syncStatus: String, // PENDING, SYNCED
    val isFinal: Boolean = false,
    val isApproved: Boolean = false,
    val approvedBy: String? = null,
    val approvalTimestamp: Long? = null,
    val version: Int = 1
)

@Entity(tableName = "cash_denominations")
data class CashDenomination(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: String,
    val cashType: String, // BUNDLE, PETTY
    val denominationMinor: Long,
    val quantity: Int = 0, // Total quantity: (bundles * notesPerBundle) + looseNotes
    val bundles: Int = 0,
    val notesPerBundle: Int = 1000,
    val looseNotes: Int = 0,
    val subtotalMinor: Long
)

@Entity(tableName = "cash_transactions")
data class CashTransaction(
    @PrimaryKey val transactionId: String,
    val sessionId: String,
    val transactionType: String, // DR, CR
    val amountMinor: Long,
    val description: String,
    val createdAt: Long = System.currentTimeMillis()
)

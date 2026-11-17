package com.example.data.remote.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

const val SUPER_ADMIN_EMAIL = "cashbook81@gmail.com"
const val UNASSIGNED_BRANCH = "UNASSIGNED"
const val PENDING_STAFF_ID = "PENDING"

enum class StaffRole(val firestoreValue: String, val displayLabel: String) {
    CASHIER("CASHIER", "Cashier"),
    AUDITOR("AUDITOR", "Auditor"),
    MANAGER("MANAGER", "Manager"),
    ADMIN("ADMIN", "Admin");

    companion object {
        fun fromRaw(raw: String?): StaffRole {
            return when (raw?.trim()?.uppercase()) {
                "ADMIN" -> ADMIN
                "MANAGER", "SUPERVISOR" -> MANAGER
                "AUDITOR" -> AUDITOR
                else -> CASHIER
            }
        }
    }
}

fun UserDto.resolvedRole(): StaffRole {
    if (email.equals(SUPER_ADMIN_EMAIL, ignoreCase = true) || role.equals("ADMIN", ignoreCase = true)) {
        return StaffRole.ADMIN
    }
    return StaffRole.fromRaw(role)
}

fun UserDto.isSuperAdmin(): Boolean {
    return resolvedRole() == StaffRole.ADMIN
}

@Keep
@IgnoreExtraProperties
data class UserDto(
    val fullName: String = "",
    val email: String = "",
    val staffId: String = PENDING_STAFF_ID,
    val branchId: String = UNASSIGNED_BRANCH,
    val role: String = StaffRole.CASHIER.firestoreValue,
    val photoBase64: String = "",
    val active: Boolean = false,
    val canCreateCashSession: Boolean = true,
    val canEditOwnDraft: Boolean = true,
    val canFinalizeDailyClosing: Boolean = true,
    val canViewOwnBranchHistory: Boolean = true,
    val biometricEnabled: Boolean = false,
    val requireBiometricOnSubmit: Boolean = true,
    val autoLockTimeoutMinutes: Int = 5,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
data class StaffUserRecord(
    val uid: String = "",
    val user: UserDto = UserDto()
)

@Keep
@IgnoreExtraProperties
data class CashSessionDto(
    @DocumentId val id: String = "",
    val sessionId: String = "",
    val branchId: String = "",
    val staffId: String = "",
    val businessDate: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    val openingBalanceMinor: Long = 0L,
    val totalDRMinor: Long = 0L,
    val totalCRMinor: Long = 0L,
    val expectedEndingMinor: Long = 0L,
    val bundleCashMinor: Long = 0L,
    val pettyCashMinor: Long = 0L,
    val totalCountedMinor: Long = 0L,
    val differenceMinor: Long = 0L,
    val reconciliationStatus: String = "", // BALANCED, SHORTAGE, EXCESS
    val status: String = "", // DRAFT, CLOSED
    val isFinal: Boolean = false,
    val isApproved: Boolean = false,
    val approvedBy: String? = null,
    val approvalTimestamp: Long? = null,
    val version: Int = 1
)

@Keep
@IgnoreExtraProperties
data class CashDenominationDto(
    val cashType: String = "",
    val denominationMinor: Long = 0L,
    val quantity: Int = 0,
    val bundles: Int = 0,
    val notesPerBundle: Int = 1000,
    val looseNotes: Int = 0,
    val subtotalMinor: Long = 0L
)

@Keep
@IgnoreExtraProperties
data class CashTransactionDto(
    val transactionId: String = "",
    val transactionType: String = "",
    val amountMinor: Long = 0L,
    val description: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AuditorCheckDto(
    @DocumentId val id: String = "",
    val checkId: String = "",
    val branchId: String = "",
    val auditorUid: String = "",
    val auditorStaffId: String = "",
    val auditorName: String = "",
    val businessDate: Long = 0L,
    val openingBalanceMinor: Long = 0L,
    val totalDRMinor: Long = 0L,
    val totalCRMinor: Long = 0L,
    val expectedEndingMinor: Long = 0L,
    val cashierCountedMinor: Long = 0L,
    val differenceMinor: Long = 0L,
    val checkStatus: String = "PENDING_CASHIER", // BALANCED, SHORTAGE, EXCESS, PENDING_CASHIER
    val notes: String = "",
    val appliedByCashier: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

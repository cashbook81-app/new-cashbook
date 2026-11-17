package com.example.data.repository

import com.example.data.remote.OperationType
import com.example.data.remote.handleFirestoreError
import com.example.data.remote.model.PENDING_STAFF_ID
import com.example.data.remote.model.SUPER_ADMIN_EMAIL
import com.example.data.remote.model.StaffRole
import com.example.data.remote.model.StaffUserRecord
import com.example.data.remote.model.UNASSIGNED_BRANCH
import com.example.data.remote.model.UserDto
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

sealed class ProfileLookupResult {
    data class Found(val profile: UserDto) : ProfileLookupResult()
    data object NotFound : ProfileLookupResult()
    data class Error(val exception: Exception) : ProfileLookupResult()
}

class UserRepository(private val db: FirebaseFirestore) {

    private val auth by lazy { Firebase.auth }

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before accessing profile.")
    }

    suspend fun getUserProfile(): UserDto? {
        return (getUserProfileResult() as? ProfileLookupResult.Found)?.profile
    }

    suspend fun getUserProfileResult(): ProfileLookupResult {
        val currentUser = auth.currentUser ?: return ProfileLookupResult.Error(
            IllegalStateException("User must be signed in before accessing profile.")
        )
        val uid = currentUser.uid
        val docRef = db.collection("users").document(uid)
        return try {
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                return ProfileLookupResult.NotFound
            }
            val data = snapshot.data ?: return ProfileLookupResult.NotFound
            val profile = snapshot.toObject(UserDto::class.java) ?: return ProfileLookupResult.NotFound

            val authEmail = currentUser.email.orEmpty()
            val isSuperAdminEmail = authEmail.equals(SUPER_ADMIN_EMAIL, ignoreCase = true)

            if (isSuperAdminEmail && (!profile.active || !profile.role.equals(StaffRole.ADMIN.firestoreValue, ignoreCase = true))) {
                val adminPatch = mutableMapOf<String, Any>(
                    "role" to StaffRole.ADMIN.firestoreValue,
                    "active" to true,
                    "email" to authEmail,
                    "canCreateCashSession" to true,
                    "canEditOwnDraft" to true,
                    "canFinalizeDailyClosing" to true,
                    "canViewOwnBranchHistory" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (profile.branchId.isBlank() || profile.branchId == UNASSIGNED_BRANCH) {
                    adminPatch["branchId"] = "SODO DACHI"
                }
                if (profile.staffId.isBlank() || profile.staffId == PENDING_STAFF_ID) {
                    adminPatch["staffId"] = "ADM001"
                }
                if (profile.fullName.isBlank()) {
                    adminPatch["fullName"] = (currentUser.displayName ?: "").ifBlank { "System Admin" }
                }
                try {
                    docRef.update(adminPatch).await()
                    val upgraded = docRef.get().await().toObject(UserDto::class.java)
                    if (upgraded != null) {
                        return ProfileLookupResult.Found(upgraded)
                    }
                } catch (_: Exception) {
                    // Return hydrated local copy for Admin if offline
                    return ProfileLookupResult.Found(
                        profile.copy(
                            role = StaffRole.ADMIN.firestoreValue,
                            active = true,
                            email = authEmail,
                            branchId = profile.branchId.ifBlank { "SODO DACHI" }
                                .let { if (it == UNASSIGNED_BRANCH) "SODO DACHI" else it },
                            staffId = profile.staffId.ifBlank { "ADM001" }
                                .let { if (it == PENDING_STAFF_ID) "ADM001" else it }
                        )
                    )
                }
            }

            // Safely backfill missing non-privileged fields without mutating role/branch/active
            val safeMissing = mutableMapOf<String, Any>()
            if (data["fullName"] !is String || (data["fullName"] as String).isBlank()) {
                safeMissing["fullName"] = (currentUser.displayName ?: "").ifBlank { "Staff Member" }
            }
            if ((data["email"] !is String || (data["email"] as String).isBlank()) && authEmail.isNotBlank()) {
                safeMissing["email"] = authEmail
            }
            if (safeMissing.isNotEmpty()) {
                try {
                    safeMissing["updatedAt"] = FieldValue.serverTimestamp()
                    docRef.update(safeMissing).await()
                    val refreshed = docRef.get().await().toObject(UserDto::class.java)
                    if (refreshed != null) {
                        return ProfileLookupResult.Found(refreshed)
                    }
                } catch (_: Exception) {
                    // Non-fatal offline backfill
                }
            }

            ProfileLookupResult.Found(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            ProfileLookupResult.Error(e)
        }
    }

    suspend fun createUserProfile(user: UserDto) {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val authEmail = auth.currentUser?.email.orEmpty()
        val normalized = user.copy(
            fullName = user.fullName.ifBlank { "Staff Member" }.take(120),
            email = user.email.ifBlank { authEmail }.take(160),
            staffId = user.staffId.ifBlank { PENDING_STAFF_ID }.take(64),
            branchId = user.branchId.ifBlank { UNASSIGNED_BRANCH }.take(64),
            role = user.role.ifBlank { StaffRole.CASHIER.firestoreValue }
        )
        try {
            val payload = mutableMapOf<String, Any>(
                "fullName" to normalized.fullName,
                "email" to normalized.email,
                "staffId" to normalized.staffId,
                "branchId" to normalized.branchId,
                "role" to normalized.role,
                "photoBase64" to normalized.photoBase64.take(200_000),
                "active" to normalized.active,
                "canCreateCashSession" to normalized.canCreateCashSession,
                "canEditOwnDraft" to normalized.canEditOwnDraft,
                "canFinalizeDailyClosing" to normalized.canFinalizeDailyClosing,
                "canViewOwnBranchHistory" to normalized.canViewOwnBranchHistory,
                "biometricEnabled" to normalized.biometricEnabled,
                "requireBiometricOnSubmit" to normalized.requireBiometricOnSubmit,
                "autoLockTimeoutMinutes" to normalized.autoLockTimeoutMinutes,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
    }

    /**
     * Updates user's own mutable profile fields. Non-admin users cannot alter branchId, staffId, role, or active status.
     */
    suspend fun updateUserProfile(
        fullName: String,
        staffId: String? = null,
        branchId: String? = null,
        photoBase64: String? = null,
        biometricEnabled: Boolean? = null,
        requireBiometricOnSubmit: Boolean? = null,
        autoLockTimeoutMinutes: Int? = null,
        allowAdminAssignedFields: Boolean = false
    ): Result<UserDto> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        val cleanName = fullName.trim().ifBlank { "Staff Member" }.take(120)
        val cleanPhoto = photoBase64?.take(200_000)
        val currentEmail = auth.currentUser?.email.orEmpty()

        return try {
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                val isSuperAdmin = currentEmail.equals(SUPER_ADMIN_EMAIL, ignoreCase = true)
                val newUser = UserDto(
                    fullName = cleanName,
                    email = currentEmail,
                    staffId = if (isSuperAdmin) (staffId?.trim()?.ifBlank { null } ?: "ADM001") else PENDING_STAFF_ID,
                    branchId = if (isSuperAdmin) (branchId?.trim()?.ifBlank { null } ?: "SODO DACHI") else UNASSIGNED_BRANCH,
                    role = if (isSuperAdmin) StaffRole.ADMIN.firestoreValue else StaffRole.CASHIER.firestoreValue,
                    photoBase64 = cleanPhoto.orEmpty(),
                    active = isSuperAdmin,
                    biometricEnabled = biometricEnabled ?: false,
                    requireBiometricOnSubmit = requireBiometricOnSubmit ?: true,
                    autoLockTimeoutMinutes = autoLockTimeoutMinutes ?: 5
                )
                createUserProfile(newUser)
                val saved = getUserProfile() ?: newUser
                Result.success(saved)
            } else {
                val updates = mutableMapOf<String, Any>(
                    "fullName" to cleanName,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (currentEmail.isNotBlank()) {
                    updates["email"] = currentEmail
                }
                if (allowAdminAssignedFields) {
                    staffId?.trim()?.takeIf { it.isNotBlank() }?.let { updates["staffId"] = it.take(64) }
                    branchId?.trim()?.takeIf { it.isNotBlank() }?.let { updates["branchId"] = it.take(64) }
                }
                if (cleanPhoto != null) updates["photoBase64"] = cleanPhoto
                if (biometricEnabled != null) updates["biometricEnabled"] = biometricEnabled
                if (requireBiometricOnSubmit != null) updates["requireBiometricOnSubmit"] = requireBiometricOnSubmit
                if (autoLockTimeoutMinutes != null) updates["autoLockTimeoutMinutes"] = autoLockTimeoutMinutes

                docRef.update(updates).await()
                val updated = docRef.get().await().toObject(UserDto::class.java)
                    ?: UserDto(
                        fullName = cleanName,
                        email = currentEmail,
                        staffId = staffId.orEmpty(),
                        branchId = branchId.orEmpty(),
                        photoBase64 = cleanPhoto.orEmpty()
                    )
                Result.success(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateSecurityPreferences(
        biometricEnabled: Boolean? = null,
        requireBiometricOnSubmit: Boolean? = null,
        autoLockTimeoutMinutes: Int? = null,
        fallbackProfile: UserDto? = null
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(
            IllegalStateException("User must be signed in before updating preferences.")
        )
        val docRef = db.collection("users").document(uid)
        return try {
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) {
                val base = (fallbackProfile ?: UserDto()).copy(
                    email = (fallbackProfile?.email ?: "").ifBlank { auth.currentUser?.email.orEmpty() },
                    biometricEnabled = biometricEnabled ?: fallbackProfile?.biometricEnabled ?: false,
                    requireBiometricOnSubmit = requireBiometricOnSubmit ?: fallbackProfile?.requireBiometricOnSubmit ?: true,
                    autoLockTimeoutMinutes = autoLockTimeoutMinutes ?: fallbackProfile?.autoLockTimeoutMinutes ?: 5
                )
                createUserProfile(base)
            } else {
                val updates = mutableMapOf<String, Any>(
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (biometricEnabled != null) updates["biometricEnabled"] = biometricEnabled
                if (requireBiometricOnSubmit != null) updates["requireBiometricOnSubmit"] = requireBiometricOnSubmit
                if (autoLockTimeoutMinutes != null) updates["autoLockTimeoutMinutes"] = autoLockTimeoutMinutes
                docRef.set(updates, SetOptions.merge()).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeAllUsers(): Flow<Result<List<StaffUserRecord>>> {
        val colRef = db.collection("users")
        return colRef.snapshots()
            .map { snap ->
                val list = snap.documents.mapNotNull { doc ->
                    val dto = doc.toObject(UserDto::class.java) ?: return@mapNotNull null
                    val estCreated = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    val estUpdated = doc.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    StaffUserRecord(
                        uid = doc.id,
                        user = dto.copy(
                            createdAt = estCreated ?: dto.createdAt,
                            updatedAt = estUpdated ?: dto.updatedAt
                        )
                    )
                }.sortedWith(
                    compareBy<StaffUserRecord> { it.user.active } // Pending users first
                        .thenBy { it.user.branchId }
                        .thenBy { it.user.fullName.lowercase() }
                )
                Result.success(list)
            }
            .catch { error ->
                if (error is CancellationException) throw error
                handleFirestoreError(error, OperationType.LIST, colRef.path)
                emit(Result.failure(Exception(error.message ?: "Unable to load users.", error)))
            }
    }

    suspend fun adminUpdateUserAccess(
        targetUid: String,
        active: Boolean,
        branchId: String,
        role: StaffRole,
        staffId: String
    ): Result<Unit> {
        val cleanUid = targetUid.trim()
        val cleanBranch = branchId.trim().uppercase().take(64)
        val cleanStaffId = staffId.trim().uppercase().take(64)
        if (cleanUid.isBlank()) return Result.failure(IllegalArgumentException("Target user ID is required."))
        if (cleanBranch.isBlank()) return Result.failure(IllegalArgumentException("Branch name is required."))
        if (cleanStaffId.isBlank()) return Result.failure(IllegalArgumentException("Staff ID is required."))

        val docRef = db.collection("users").document(cleanUid)
        val isCashierLike = role == StaffRole.CASHIER || role == StaffRole.ADMIN
        val payload = mapOf<String, Any>(
            "active" to active,
            "branchId" to cleanBranch,
            "role" to role.firestoreValue,
            "staffId" to cleanStaffId,
            "canCreateCashSession" to isCashierLike,
            "canEditOwnDraft" to isCashierLike,
            "canFinalizeDailyClosing" to isCashierLike,
            "canViewOwnBranchHistory" to true,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }
}

package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.local.AppDatabase
import com.example.data.local.entities.CashDenomination
import com.example.data.local.entities.CashSession
import com.example.data.local.entities.CashTransaction
import com.example.data.remote.model.AuditorCheckDto
import com.example.data.remote.model.PENDING_STAFF_ID
import com.example.data.remote.model.SUPER_ADMIN_EMAIL
import com.example.data.remote.model.StaffRole
import com.example.data.remote.model.UNASSIGNED_BRANCH
import com.example.data.remote.model.UserDto
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class CashRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private lateinit var db: AppDatabase
    private lateinit var cashRepository: CashRepository
    private lateinit var userRepository: UserRepository

    @Before
    fun setUpRepos() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cashRepository = CashRepository(db.cashDao(), firestore)
        userRepository = UserRepository(firestore)
    }

    @After
    fun closeDb() {
        db.close()
    }

    private suspend fun registerStaffAndActivateAsAdmin(
        email: String,
        fullName: String,
        staffId: String,
        branchId: String,
        role: StaffRole
    ): String {
        val staffUid = signInTestUser(email)
        withTimeout(5000L) {
            userRepository.createUserProfile(
                UserDto(
                    fullName = fullName,
                    email = email,
                    staffId = PENDING_STAFF_ID,
                    branchId = UNASSIGNED_BRANCH,
                    role = StaffRole.CASHIER.firestoreValue,
                    active = false
                )
            )
        }
        signInTestUser(SUPER_ADMIN_EMAIL)
        withTimeout(5000L) {
            userRepository.createUserProfile(
                UserDto(
                    fullName = "System Admin",
                    email = SUPER_ADMIN_EMAIL,
                    staffId = "ADM001",
                    branchId = "SODO DACHI",
                    role = StaffRole.ADMIN.firestoreValue,
                    active = true
                )
            )
            val assignResult = userRepository.adminUpdateUserAccess(
                targetUid = staffUid,
                active = true,
                branchId = branchId,
                role = role,
                staffId = staffId
            )
            assertTrue(assignResult.isSuccess)
        }
        return signInTestUser(email)
    }

    @Test
    fun saveSession_authenticatedActiveCashier_syncsAndAuditorApproves() = runBlocking {
        registerStaffAndActivateAsAdmin(
            email = "cashier1@test.com",
            fullName = "Cashier One",
            staffId = "CSH001",
            branchId = "BRANCH001",
            role = StaffRole.CASHIER
        )

        val businessDate = 1731369600000L
        val sessionId = buildDailySessionId("BRANCH001", businessDate)
        val session = CashSession(
            sessionId = sessionId,
            branchId = "BRANCH001",
            staffId = "CSH001",
            businessDate = businessDate,
            openingBalanceMinor = 100000L,
            totalDRMinor = 50000L,
            totalCRMinor = 20000L,
            expectedEndingMinor = 130000L,
            bundleCashMinor = 100000L,
            pettyCashMinor = 30000L,
            totalCountedMinor = 130000L,
            differenceMinor = 0L,
            reconciliationStatus = "BALANCED",
            status = "CLOSED",
            syncStatus = "PENDING",
            isFinal = true,
            isApproved = false
        )
        val denoms = listOf(
            CashDenomination(
                sessionId = sessionId,
                cashType = "BUNDLE",
                denominationMinor = 20000L,
                quantity = 5,
                bundles = 0,
                notesPerBundle = 100,
                looseNotes = 5,
                subtotalMinor = 100000L
            ),
            CashDenomination(
                sessionId = sessionId,
                cashType = "PETTY",
                denominationMinor = 10000L,
                quantity = 3,
                subtotalMinor = 30000L
            )
        )
        val transactions = listOf(
            CashTransaction(
                transactionId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                transactionType = "DR",
                amountMinor = 50000L,
                description = "Cash in"
            )
        )

        withTimeout(5000L) {
            cashRepository.saveSession(session, denoms, transactions)
        }

        val local = db.cashDao().getSessionById(sessionId)
        assertNotNull(local)
        assertEquals("SYNCED", local?.syncStatus)

        val remote = withTimeout(5000L) {
            firestore.collection("cashSessions").document(sessionId).get().await()
        }
        assertTrue(remote.exists())
        assertEquals("BRANCH001", remote.getString("branchId"))

        // Switch to branch Auditor to submit AuditorCheck & approve closed session
        val auditorUid = registerStaffAndActivateAsAdmin(
            email = "auditor1@test.com",
            fullName = "Auditor One",
            staffId = "AUD001",
            branchId = "BRANCH001",
            role = StaffRole.AUDITOR
        )
        withTimeout(5000L) {
            val checkRes = cashRepository.submitAuditorCheck(
                AuditorCheckDto(
                    checkId = buildAuditorCheckId("BRANCH001", businessDate),
                    branchId = "BRANCH001",
                    auditorUid = auditorUid,
                    auditorStaffId = "AUD001",
                    auditorName = "Auditor One",
                    businessDate = businessDate,
                    openingBalanceMinor = 100000L,
                    totalDRMinor = 50000L,
                    totalCRMinor = 20000L,
                    expectedEndingMinor = 130000L,
                    cashierCountedMinor = 130000L,
                    differenceMinor = 0L,
                    checkStatus = "BALANCED"
                )
            )
            assertTrue(checkRes.isSuccess)
            cashRepository.approveSession(sessionId, "Auditor One")
        }

        val remoteApproved = withTimeout(5000L) {
            firestore.collection("cashSessions").document(sessionId).get().await()
        }
        assertEquals(true, remoteApproved.getBoolean("isApproved"))
        assertEquals("Auditor One", remoteApproved.getString("approvedBy"))
    }

    @Test
    fun trySyncSession_unauthenticatedUser_failsGracefullyWithoutSyncing() = runBlocking {
        auth.signOut()
        val sessionId = UUID.randomUUID().toString()
        val session = CashSession(
            sessionId = sessionId,
            branchId = "BRANCH001",
            staffId = "CSH001",
            businessDate = 1731369600000L,
            openingBalanceMinor = 0L,
            totalDRMinor = 0L,
            totalCRMinor = 0L,
            expectedEndingMinor = 0L,
            bundleCashMinor = 0L,
            pettyCashMinor = 0L,
            totalCountedMinor = 0L,
            differenceMinor = 0L,
            reconciliationStatus = "BALANCED",
            status = "DRAFT",
            syncStatus = "PENDING",
            isFinal = false
        )
        db.cashDao().saveFullSession(session, emptyList(), emptyList())

        val result = withTimeout(5000L) {
            cashRepository.trySyncSession(sessionId)
        }
        assertTrue(result.isFailure)
        val local = db.cashDao().getSessionById(sessionId)
        assertEquals("PENDING", local?.syncStatus)
    }

    @Test
    fun crossBranchSessionWrite_failsWithPermissionDenied() = runBlocking {
        registerStaffAndActivateAsAdmin(
            email = "cashier_branch2@test.com",
            fullName = "Cashier Branch Two",
            staffId = "CSH002",
            branchId = "BRANCH002",
            role = StaffRole.CASHIER
        )

        val sessionId = buildDailySessionId("BRANCH001", 1731369600000L)
        val session = CashSession(
            sessionId = sessionId,
            branchId = "BRANCH001", // Different branch than user's BRANCH002!
            staffId = "CSH002",
            businessDate = 1731369600000L,
            openingBalanceMinor = 0L,
            totalDRMinor = 0L,
            totalCRMinor = 0L,
            expectedEndingMinor = 0L,
            bundleCashMinor = 0L,
            pettyCashMinor = 0L,
            totalCountedMinor = 0L,
            differenceMinor = 0L,
            reconciliationStatus = "BALANCED",
            status = "DRAFT",
            syncStatus = "PENDING",
            isFinal = false
        )
        db.cashDao().saveFullSession(session, emptyList(), emptyList())

        val result = withTimeout(5000L) {
            cashRepository.trySyncSession(sessionId)
        }
        assertTrue(result.isFailure)
        val firestoreEx = result.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull(firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun nonAdminUser_canUpdateNameAndBiometrics_butCannotSelfEscalateBranchOrRole() = runBlocking {
        registerStaffAndActivateAsAdmin(
            email = "profile_sync@test.com",
            fullName = "Initial Name",
            staffId = "CSH010",
            branchId = "BRANCH010",
            role = StaffRole.CASHIER
        )

        withTimeout(5000L) {
            val safeUpdate = userRepository.updateUserProfile(
                fullName = "Updated Teller",
                biometricEnabled = true,
                requireBiometricOnSubmit = false,
                autoLockTimeoutMinutes = 15,
                allowAdminAssignedFields = false
            )
            assertTrue(safeUpdate.isSuccess)
            val loaded = userRepository.getUserProfile()
            assertNotNull(loaded)
            assertEquals("Updated Teller", loaded?.fullName)
            assertEquals("CSH010", loaded?.staffId)
            assertEquals("BRANCH010", loaded?.branchId)
            assertEquals(true, loaded?.biometricEnabled)
            assertEquals(false, loaded?.requireBiometricOnSubmit)
            assertEquals(15, loaded?.autoLockTimeoutMinutes)

            val forbiddenUpdate = userRepository.updateUserProfile(
                fullName = "Updated Teller",
                staffId = "ADM999",
                branchId = "BRANCH099",
                allowAdminAssignedFields = true
            )
            assertTrue(forbiddenUpdate.isFailure)
        }
    }

    @Test
    fun syncFromFirestore_restoresSavedHistoryDatesAndDenominationsIntoFreshRoomDb() = runBlocking {
        registerStaffAndActivateAsAdmin(
            email = "history_restore@test.com",
            fullName = "History Teller",
            staffId = "CSH020",
            branchId = "BRANCH020",
            role = StaffRole.CASHIER
        )

        val businessDate = 1731542400000L
        val sessionId = buildDailySessionId("BRANCH020", businessDate)
        val session = CashSession(
            sessionId = sessionId,
            branchId = "BRANCH020",
            staffId = "CSH020",
            businessDate = businessDate,
            openingBalanceMinor = 20000000L,
            totalDRMinor = 0L,
            totalCRMinor = 0L,
            expectedEndingMinor = 20000000L,
            bundleCashMinor = 20000000L,
            pettyCashMinor = 50000L,
            totalCountedMinor = 20050000L,
            differenceMinor = 50000L,
            reconciliationStatus = "EXCESS",
            status = "CLOSED",
            syncStatus = "PENDING",
            isFinal = true
        )
        val denoms = listOf(
            CashDenomination(
                sessionId = sessionId,
                cashType = "BUNDLE",
                denominationMinor = 20000L,
                quantity = 1000,
                bundles = 1,
                notesPerBundle = 1000,
                looseNotes = 0,
                subtotalMinor = 20000000L
            ),
            CashDenomination(
                sessionId = sessionId,
                cashType = "PETTY",
                denominationMinor = 10000L,
                quantity = 5,
                subtotalMinor = 50000L
            )
        )

        withTimeout(5000L) {
            cashRepository.saveSession(session, denoms, emptyList())
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val freshDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val freshRepo = CashRepository(freshDb.cashDao(), firestore)
            val syncResult = withTimeout(5000L) {
                freshRepo.syncFromFirestore("BRANCH020")
            }
            assertTrue(syncResult.isSuccess)
            val restoredSession = freshDb.cashDao().getSessionById(sessionId)
            assertNotNull(restoredSession)
            assertEquals(businessDate, restoredSession?.businessDate)

            val restoredDenoms = withTimeout(5000L) {
                freshRepo.getLatestSavedDenominations("BRANCH020")
            }
            assertEquals(2, restoredDenoms.size)
            val restoredBundle = restoredDenoms.first { it.cashType == "BUNDLE" }
            assertEquals(1, restoredBundle.bundles)
            assertEquals(1000, restoredBundle.notesPerBundle)
            val restoredPetty = restoredDenoms.first { it.cashType == "PETTY" }
            assertEquals(5, restoredPetty.quantity)
        } finally {
            freshDb.close()
        }
    }

    @Test
    fun saveSession_sameWorkingDay_overwritesLatestUpdateWithoutDuplicates() = runBlocking {
        registerStaffAndActivateAsAdmin(
            email = "edit_same_day@test.com",
            fullName = "Edit Teller",
            staffId = "CSH030",
            branchId = "BRANCH030",
            role = StaffRole.CASHIER
        )

        val businessDate = 1731628800000L
        val firstSessionId = buildDailySessionId("BRANCH030", businessDate)
        val initialSession = CashSession(
            sessionId = firstSessionId,
            branchId = "BRANCH030",
            staffId = "CSH030",
            businessDate = businessDate,
            openingBalanceMinor = 100000L,
            totalDRMinor = 0L,
            totalCRMinor = 0L,
            expectedEndingMinor = 100000L,
            bundleCashMinor = 100000L,
            pettyCashMinor = 0L,
            totalCountedMinor = 100000L,
            differenceMinor = 0L,
            reconciliationStatus = "BALANCED",
            status = "CLOSED",
            syncStatus = "PENDING",
            isFinal = true,
            version = 1
        )
        withTimeout(5000L) {
            cashRepository.saveSession(initialSession, emptyList(), emptyList())
        }

        val existing = withTimeout(5000L) {
            cashRepository.getExistingSessionForDate("BRANCH030", businessDate)
        }
        assertNotNull(existing)
        assertEquals(firstSessionId, existing?.sessionId)

        val editedSession = initialSession.copy(
            sessionId = existing!!.sessionId,
            createdAt = existing.createdAt,
            totalDRMinor = 20000L,
            expectedEndingMinor = 120000L,
            pettyCashMinor = 20000L,
            totalCountedMinor = 120000L,
            syncStatus = "PENDING",
            version = existing.version + 1
        )

        withTimeout(5000L) {
            cashRepository.saveSession(editedSession, emptyList(), emptyList())
        }

        val branchSession = db.cashDao().getSessionForBranchInDayRange(
            "BRANCH030",
            businessDate,
            businessDate + 86_399_999L
        )
        assertEquals(firstSessionId, branchSession?.sessionId)
        assertEquals(2, branchSession?.version)
        assertEquals(120000L, branchSession?.totalCountedMinor)
        assertEquals("SYNCED", branchSession?.syncStatus)

        val remoteDocs = withTimeout(5000L) {
            firestore.collection("cashSessions")
                .whereEqualTo("branchId", "BRANCH030")
                .get()
                .await()
        }
        assertEquals(1, remoteDocs.size())
        assertEquals(120000L, remoteDocs.documents.first().getLong("totalCountedMinor"))
        assertEquals(2L, remoteDocs.documents.first().getLong("version"))
    }
}

const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_uid_1";
const ADMIN_EMAIL = "cashbook81@gmail.com";
const CASHIER_1_UID = "cashier_branch1";
const AUDITOR_1_UID = "auditor_branch1";
const CASHIER_2_UID = "cashier_branch2";
const AUDITOR_2_UID = "auditor_branch2";
const MANAGER_UID = "manager_uid_1";
const PENDING_UID = "pending_new_user";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

async function seedUser(uid, {
  staffId = "EMP001",
  branchId = "SODO DACHI",
  role = "CASHIER",
  active = true,
  email = `${uid}@bank.com`,
} = {}) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc(uid).set({
      fullName: `Staff ${staffId}`,
      email,
      staffId,
      branchId,
      role,
      active,
      photoBase64: "",
      canCreateCashSession: true,
      canEditOwnDraft: true,
      canFinalizeDailyClosing: true,
      canViewOwnBranchHistory: true,
      biometricEnabled: false,
      requireBiometricOnSubmit: true,
      autoLockTimeoutMinutes: 5,
      createdAt: new Date(Date.now() - 10000),
      updatedAt: new Date(Date.now() - 10000),
    });
  });
}

function buildSessionPayload(sessionId, branchId, staffId, ownerUid, status = "CLOSED") {
  return {
    sessionId,
    branchId,
    staffId,
    ownerUid,
    businessDate: 1731369600000,
    createdAt: new Date(Date.now() - 5000),
    updatedAt: new Date(Date.now() - 5000),
    openingBalanceMinor: 100000,
    totalDRMinor: 50000,
    totalCRMinor: 20000,
    expectedEndingMinor: 130000,
    bundleCashMinor: 100000,
    pettyCashMinor: 30000,
    totalCountedMinor: 130000,
    differenceMinor: 0,
    reconciliationStatus: "BALANCED",
    status,
    isFinal: status === "CLOSED",
    isApproved: false,
    version: 1,
  };
}

function buildAuditorCheckPayload(checkId, branchId, auditorUid, auditorStaffId, businessDate = 1731369600000) {
  return {
    checkId,
    branchId,
    auditorUid,
    auditorStaffId,
    auditorName: "Auditor Abebe",
    businessDate,
    openingBalanceMinor: 100000,
    totalDRMinor: 50000,
    totalCRMinor: 20000,
    expectedEndingMinor: 130000,
    cashierCountedMinor: 130000,
    differenceMinor: 0,
    checkStatus: "BALANCED",
    notes: "Verified against voucher bundle",
    appliedByCashier: false,
    createdAt: new Date(Date.now() - 5000),
    updatedAt: new Date(Date.now() - 5000),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write any collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("cashSessions").where("branchId", "==", "SODO DACHI").get());
  await assertFails(unauthDb.collection("auditorChecks").where("branchId", "==", "SODO DACHI").get());
  await assertFails(unauthDb.collection("users").doc(CASHIER_1_UID).get());
  await assertFails(
    unauthDb.collection("cashSessions").doc("sess_1").set(
      buildSessionPayload("sess_1", "SODO DACHI", "CSH001", CASHIER_1_UID)
    )
  );
});

test("New Google user self-registration starts inactive CASHIER; cannot self-activate or change branch/role", async () => {
  const pendingDb = testEnv.authenticatedContext(PENDING_UID, {
    email: "newstaff@bank.com",
    email_verified: true,
  }).firestore();

  // Valid pending registration succeeds
  await assertSucceeds(
    pendingDb.collection("users").doc(PENDING_UID).set({
      fullName: "New Staff",
      email: "newstaff@bank.com",
      staffId: "PENDING",
      branchId: "UNASSIGNED",
      role: "CASHIER",
      active: false,
      photoBase64: "",
      canCreateCashSession: true,
      canEditOwnDraft: true,
      canFinalizeDailyClosing: true,
      canViewOwnBranchHistory: true,
      biometricEnabled: false,
      requireBiometricOnSubmit: true,
      autoLockTimeoutMinutes: 5,
      createdAt: new Date(Date.now() - 2000),
      updatedAt: new Date(Date.now() - 2000),
    })
  );

  // Self-registering directly as active=true or role=ADMIN must fail
  await assertFails(
    pendingDb.collection("users").doc("spoof_active").set({
      fullName: "Spoof",
      email: "newstaff@bank.com",
      staffId: "CSH999",
      branchId: "SODO DACHI",
      role: "CASHIER",
      active: true,
      createdAt: new Date(Date.now() - 2000),
      updatedAt: new Date(Date.now() - 2000),
    })
  );

  // Self-updating role, active, branchId, or staffId must fail
  await assertFails(pendingDb.collection("users").doc(PENDING_UID).update({ active: true, updatedAt: new Date() }));
  await assertFails(pendingDb.collection("users").doc(PENDING_UID).update({ role: "MANAGER", updatedAt: new Date() }));
  await assertFails(pendingDb.collection("users").doc(PENDING_UID).update({ branchId: "SODO DACHI", updatedAt: new Date() }));
  await assertFails(pendingDb.collection("users").doc(PENDING_UID).update({ staffId: "CSH777", updatedAt: new Date() }));

  // Inactive user cannot read or create cash sessions
  await assertFails(
    pendingDb.collection("cashSessions").where("branchId", "==", "SODO DACHI").get()
  );
});

test("Admin (cashbook81@gmail.com verified) self-registers as ADMIN, lists all users, activates & assigns branch/role", async () => {
  await seedUser(PENDING_UID, {
    staffId: "PENDING",
    branchId: "UNASSIGNED",
    role: "CASHIER",
    active: false,
    email: "staff2@bank.com",
  });

  // Unverified spoof claiming cashbook81@gmail.com cannot create ADMIN profile
  const unverifiedSpoofDb = testEnv.authenticatedContext("spoof_uid", {
    email: ADMIN_EMAIL,
    email_verified: false,
  }).firestore();
  await assertFails(
    unverifiedSpoofDb.collection("users").doc("spoof_uid").set({
      fullName: "Spoof Admin",
      email: ADMIN_EMAIL,
      staffId: "ADM999",
      branchId: "SODO DACHI",
      role: "ADMIN",
      active: true,
      createdAt: new Date(Date.now() - 2000),
      updatedAt: new Date(Date.now() - 2000),
    })
  );

  // Real verified admin creates ADMIN doc and manages staff accounts
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  await assertSucceeds(
    adminDb.collection("users").doc(ADMIN_UID).set({
      fullName: "System Admin",
      email: ADMIN_EMAIL,
      staffId: "ADM001",
      branchId: "SODO DACHI",
      role: "ADMIN",
      active: true,
      photoBase64: "",
      canCreateCashSession: true,
      canEditOwnDraft: true,
      canFinalizeDailyClosing: true,
      canViewOwnBranchHistory: true,
      biometricEnabled: false,
      requireBiometricOnSubmit: true,
      autoLockTimeoutMinutes: 5,
      createdAt: new Date(Date.now() - 2000),
      updatedAt: new Date(Date.now() - 2000),
    })
  );

  // Admin can list all users
  await assertSucceeds(adminDb.collection("users").get());

  // Non-admin cannot list users
  const cashierDb = testEnv.authenticatedContext(CASHIER_1_UID, {
    email: "cashier1@bank.com",
    email_verified: true,
  }).firestore();
  await seedUser(CASHIER_1_UID, { branchId: "SODO DACHI", role: "CASHIER", active: true });
  await assertFails(cashierDb.collection("users").get());

  // Admin activates pending staff and assigns branch + role (AUDITOR)
  await assertSucceeds(
    adminDb.collection("users").doc(PENDING_UID).update({
      active: true,
      branchId: "HAWASSA MAIN",
      role: "AUDITOR",
      staffId: "AUD009",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Branch isolation: Cashier & Auditor only access their assigned branch; Manager sees all branches", async () => {
  await seedUser(CASHIER_1_UID, { staffId: "CSH001", branchId: "SODO DACHI", role: "CASHIER", active: true });
  await seedUser(AUDITOR_1_UID, { staffId: "AUD001", branchId: "SODO DACHI", role: "AUDITOR", active: true });
  await seedUser(CASHIER_2_UID, { staffId: "CSH002", branchId: "HAWASSA MAIN", role: "CASHIER", active: true });
  await seedUser(AUDITOR_2_UID, { staffId: "AUD002", branchId: "HAWASSA MAIN", role: "AUDITOR", active: true });
  await seedUser(MANAGER_UID, { staffId: "MGR001", branchId: "HEAD OFFICE", role: "MANAGER", active: true });

  const cashier1Db = testEnv.authenticatedContext(CASHIER_1_UID).firestore();
  const auditor1Db = testEnv.authenticatedContext(AUDITOR_1_UID).firestore();
  const cashier2Db = testEnv.authenticatedContext(CASHIER_2_UID).firestore();
  const managerDb = testEnv.authenticatedContext(MANAGER_UID).firestore();

  const sessionId1 = "SODO_DACHI_20261116";
  await assertSucceeds(
    cashier1Db.collection("cashSessions").doc(sessionId1).set(
      buildSessionPayload(sessionId1, "SODO DACHI", "CSH001", CASHIER_1_UID, "CLOSED")
    )
  );

  // Branch 1 Cashier & Auditor can read Branch 1 sessions
  await assertSucceeds(cashier1Db.collection("cashSessions").where("branchId", "==", "SODO DACHI").get());
  await assertSucceeds(auditor1Db.collection("cashSessions").where("branchId", "==", "SODO DACHI").get());

  // Branch 2 Cashier CANNOT read or query Branch 1 sessions, or unfiltered cashSessions
  await assertFails(cashier2Db.collection("cashSessions").doc(sessionId1).get());
  await assertFails(cashier2Db.collection("cashSessions").where("branchId", "==", "SODO DACHI").get());
  await assertFails(cashier2Db.collection("cashSessions").get());

  // Branch 1 Auditor CANNOT write a cashSession as a Cashier
  await assertFails(
    auditor1Db.collection("cashSessions").doc("SODO_DACHI_20261117").set(
      buildSessionPayload("SODO_DACHI_20261117", "SODO DACHI", "AUD001", AUDITOR_1_UID, "CLOSED")
    )
  );

  // Manager CAN read all branches without branch filter
  await assertSucceeds(managerDb.collection("cashSessions").get());
  await assertSucceeds(managerDb.collection("cashSessions").doc(sessionId1).get());
});

test("AuditorCheck workflow: Branch Auditor submits DR/CR, Branch Cashier acknowledges, other branch blocked", async () => {
  await seedUser(CASHIER_1_UID, { staffId: "CSH001", branchId: "SODO DACHI", role: "CASHIER", active: true });
  await seedUser(AUDITOR_1_UID, { staffId: "AUD001", branchId: "SODO DACHI", role: "AUDITOR", active: true });
  await seedUser(CASHIER_2_UID, { staffId: "CSH002", branchId: "HAWASSA MAIN", role: "CASHIER", active: true });
  await seedUser(AUDITOR_2_UID, { staffId: "AUD002", branchId: "HAWASSA MAIN", role: "AUDITOR", active: true });

  const cashier1Db = testEnv.authenticatedContext(CASHIER_1_UID).firestore();
  const auditor1Db = testEnv.authenticatedContext(AUDITOR_1_UID).firestore();
  const auditor2Db = testEnv.authenticatedContext(AUDITOR_2_UID).firestore();
  const checkId = "SODO_DACHI_20261116";

  // Cashier cannot create an AuditorCheck
  await assertFails(
    cashier1Db.collection("auditorChecks").doc(checkId).set(
      buildAuditorCheckPayload(checkId, "SODO DACHI", CASHIER_1_UID, "CSH001")
    )
  );

  // Auditor from another branch cannot submit check for SODO DACHI
  await assertFails(
    auditor2Db.collection("auditorChecks").doc(checkId).set(
      buildAuditorCheckPayload(checkId, "SODO DACHI", AUDITOR_2_UID, "AUD002")
    )
  );

  // Assigned branch Auditor creates & updates AuditorCheck
  await assertSucceeds(
    auditor1Db.collection("auditorChecks").doc(checkId).set(
      buildAuditorCheckPayload(checkId, "SODO DACHI", AUDITOR_1_UID, "AUD001")
    )
  );

  // Branch Cashier can read and mark appliedByCashier = true ONLY
  await assertSucceeds(
    cashier1Db.collection("auditorChecks").where("branchId", "==", "SODO DACHI").get()
  );
  await assertSucceeds(
    cashier1Db.collection("auditorChecks").doc(checkId).update({
      appliedByCashier: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Cashier CANNOT tamper with DR/CR numbers on AuditorCheck
  await assertFails(
    cashier1Db.collection("auditorChecks").doc(checkId).update({
      totalDRMinor: 999999,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Approval rule: closed session can be approved by branch Auditor or Manager, never self-approved by Cashier", async () => {
  await seedUser(CASHIER_1_UID, { staffId: "CSH001", branchId: "SODO DACHI", role: "CASHIER", active: true });
  await seedUser(AUDITOR_1_UID, { staffId: "AUD001", branchId: "SODO DACHI", role: "AUDITOR", active: true });
  await seedUser(AUDITOR_2_UID, { staffId: "AUD002", branchId: "HAWASSA MAIN", role: "AUDITOR", active: true });

  const cashier1Db = testEnv.authenticatedContext(CASHIER_1_UID).firestore();
  const auditor1Db = testEnv.authenticatedContext(AUDITOR_1_UID).firestore();
  const auditor2Db = testEnv.authenticatedContext(AUDITOR_2_UID).firestore();

  const sessionId = "sess_approve_1";
  await assertSucceeds(
    cashier1Db.collection("cashSessions").doc(sessionId).set(
      buildSessionPayload(sessionId, "SODO DACHI", "CSH001", CASHIER_1_UID, "CLOSED")
    )
  );

  // Cashier self-approval must fail
  await assertFails(
    cashier1Db.collection("cashSessions").doc(sessionId).update({
      isApproved: true,
      approvedBy: "Cashier",
      approvalTimestamp: Date.now(),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Other branch Auditor cannot approve SODO DACHI closing
  await assertFails(
    auditor2Db.collection("cashSessions").doc(sessionId).update({
      isApproved: true,
      approvedBy: "AUD002",
      approvalTimestamp: Date.now(),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Same branch Auditor approves closing successfully
  await assertSucceeds(
    auditor1Db.collection("cashSessions").doc(sessionId).update({
      isApproved: true,
      approvedBy: "AUD001",
      approvalTimestamp: Date.now(),
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

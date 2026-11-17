package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Domain
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.AppDatabase
import com.example.data.local.SecurityManager
import com.example.data.remote.model.PENDING_STAFF_ID
import com.example.data.remote.model.SUPER_ADMIN_EMAIL
import com.example.data.remote.model.StaffRole
import com.example.data.remote.model.UNASSIGNED_BRANCH
import com.example.data.remote.model.UserDto
import com.example.data.remote.model.isSuperAdmin
import com.example.data.remote.model.resolvedRole
import com.example.data.repository.CashRepository
import com.example.data.repository.ProfileLookupResult
import com.example.data.repository.UserRepository
import com.example.ui.admin.AdminUsersScreen
import com.example.ui.auditor.AuditorCheckScreen
import com.example.ui.auth.PendingActivationScreen
import com.example.ui.auth.ProfileScreen
import com.example.ui.auth.WelcomeScreen
import com.example.ui.calculator.CashCalculatorScreen
import com.example.ui.history.HistoryScreen
import com.example.ui.manager.ManagerOverviewScreen
import com.example.ui.theme.CyanDeep
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanSoftTint
import com.example.ui.theme.IceSurface
import com.example.ui.theme.MutedNavyText
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SubtleOutline
import com.example.ui.theme.VaultSlateDeep
import com.example.ui.theme.White
import com.example.ui.viewmodel.CashViewModel
import com.example.ui.viewmodel.CashViewModelFactory
import com.example.data.remote.update.AppUpdateChecker
import com.example.data.remote.update.UpdateCheckResult
import com.example.ui.update.AppUpdateDialog
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private lateinit var securityManager: SecurityManager
    private var lastInteractionTime = System.currentTimeMillis()
    private val isLockedState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        securityManager = SecurityManager(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ResourcesCompat.getFont(applicationContext, R.font.plus_jakarta_sans)
                ResourcesCompat.getFont(applicationContext, R.font.inter)
            } catch (_: Exception) {
                // Non-fatal in test/preview environments
            }
            AppDatabase.getDatabase(applicationContext)
            val dbId = applicationContext.getString(R.string.firestore_database_id)
            FirebaseFirestore.getInstance(dbId)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.WHITE,
                android.graphics.Color.WHITE
            )
        )
        setContent {
            MyApplicationTheme {
                AppContent(securityManager, isLockedState.value) {
                    isLockedState.value = it
                }
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionTime = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (isLockedState.value) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (Firebase.auth.currentUser == null) return@launch
            val biometricEnabled = securityManager.isBiometricEnabled().first()
            if (!biometricEnabled) return@launch
            val timeoutMinutes = securityManager.getAutoLockTimeoutMinutes().first()
            if (timeoutMinutes > 0) {
                val timeoutMs = timeoutMinutes * 60 * 1000L
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastInteractionTime > timeoutMs) {
                    withContext(Dispatchers.Main) {
                        if (!isLockedState.value) {
                            isLockedState.value = true
                        }
                    }
                }
            }
        }
    }
}

private data class AppBootstrapState(
    val initialUser: FirebaseUser?,
    val initialBiometricEnabled: Boolean,
    val initialCachedProfile: UserDto?,
    val userRepository: UserRepository,
    val cashRepository: CashRepository
)

@Composable
fun AppContent(
    securityManager: SecurityManager,
    lockTrigger: Boolean = false,
    onLockChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    val bootstrapState by produceState<AppBootstrapState?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val dbId = appCtx.getString(R.string.firestore_database_id)
            val firestore = FirebaseFirestore.getInstance(dbId)
            val database = AppDatabase.getDatabase(appCtx)
            val userRepo = UserRepository(firestore)
            val cashRepo = CashRepository(database.cashDao(), firestore)
            val authUser = Firebase.auth.currentUser
            val bioEnabled = securityManager.isBiometricEnabled().first()
            val cachedProfile = authUser?.uid?.let { uid ->
                securityManager.getCachedUserProfile(uid)
            }
            AppBootstrapState(
                initialUser = authUser,
                initialBiometricEnabled = bioEnabled,
                initialCachedProfile = cachedProfile,
                userRepository = userRepo,
                cashRepository = cashRepo
            )
        }
    }

    val bootstrap = bootstrapState
    if (bootstrap == null) {
        // Ultra-lightweight Frame-1 surface matching windowBackground while singletons warm on IO
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(White)
        )
        return
    }

    var currentUser by remember { mutableStateOf(bootstrap.initialUser) }
    var userProfile by remember { mutableStateOf<UserDto?>(bootstrap.initialCachedProfile) }
    var isLoadingProfile by remember { mutableStateOf(false) }
    var suppressAutoSignIn by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var isRefreshingAccess by remember { mutableStateOf(false) }

    val biometricEnabled by securityManager.isBiometricEnabled()
        .collectAsState(initial = bootstrap.initialBiometricEnabled)
    var isAppLocked by remember {
        mutableStateOf(bootstrap.initialBiometricEnabled && bootstrap.initialUser != null)
    }

    // Sync with external lock trigger
    LaunchedEffect(lockTrigger, biometricEnabled, currentUser?.uid) {
        if (lockTrigger && biometricEnabled && currentUser != null) {
            isAppLocked = true
        }
    }

    val isWelcomeVisible = currentUser == null || isAppLocked
    if (!view.isInEditMode) {
        val shouldUseLightStatusIcons = !isWelcomeVisible
        LaunchedEffect(shouldUseLightStatusIcons) {
            val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            // White status bar icons over the Cyan header on WelcomeScreen; dark icons on white app screens
            if (insetsController.isAppearanceLightStatusBars != shouldUseLightStatusIcons) {
                insetsController.isAppearanceLightStatusBars = shouldUseLightStatusIcons
            }
            if (!insetsController.isAppearanceLightNavigationBars) {
                insetsController.isAppearanceLightNavigationBars = true
            }
        }
    }

    // Callback when unlocked
    val onUnlock = {
        isAppLocked = false
        onLockChanged(false)
    }
    var showBiometricEnrollment by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<UpdateCheckResult.UpdateAvailable?>(null) }
    var hasCheckedUpdatesThisSession by remember { mutableStateOf(false) }

    val canRunAutoUpdateCheck = currentUser != null &&
        !isAppLocked &&
        !isLoadingProfile &&
        !showBiometricEnrollment &&
        userProfile != null &&
        (userProfile!!.active || userProfile!!.isSuperAdmin())

    LaunchedEffect(canRunAutoUpdateCheck) {
        if (canRunAutoUpdateCheck && !hasCheckedUpdatesThisSession) {
            hasCheckedUpdatesThisSession = true
            delay(800)
            val result = withContext(Dispatchers.IO) {
                AppUpdateChecker(securityManager).checkForUpdate(ignoreSkippedVersion = false)
            }
            if (result is UpdateCheckResult.UpdateAvailable) {
                availableUpdate = result
            }
        }
    }

    LaunchedEffect(currentUser?.uid, isAppLocked, bootstrap.userRepository, refreshTrigger) {
        val signedInUser = currentUser
        if (signedInUser != null && !isAppLocked) {
            val uid = signedInUser.uid
            // Hydrate immediately from local cache on IO so returning users stay signed in seamlessly even offline
            val cachedProfile = if (userProfile != null && userProfile?.email.equals(signedInUser.email, ignoreCase = true)) {
                userProfile
            } else {
                withContext(Dispatchers.IO) {
                    securityManager.getCachedUserProfile(uid)
                }
            }
            if (userProfile == null && cachedProfile != null) {
                userProfile = cachedProfile
                isLoadingProfile = false
            } else if (userProfile == null) {
                isLoadingProfile = true
            }

            val repo = bootstrap.userRepository
            val isSuperAdminEmail = signedInUser.email.equals(SUPER_ADMIN_EMAIL, ignoreCase = true)
            when (val result = repo.getUserProfileResult()) {
                is ProfileLookupResult.Found -> {
                    val remoteProfile = result.profile
                    userProfile = remoteProfile
                    withContext(Dispatchers.IO) {
                        securityManager.saveCachedUserProfile(uid, remoteProfile)
                        val currentReqBio = securityManager.isRequireBiometricOnSubmit().first()
                        if (currentReqBio != remoteProfile.requireBiometricOnSubmit) {
                            securityManager.setRequireBiometricOnSubmit(remoteProfile.requireBiometricOnSubmit)
                        }
                        val currentTimeout = securityManager.getAutoLockTimeoutMinutes().first()
                        if (currentTimeout != remoteProfile.autoLockTimeoutMinutes) {
                            securityManager.setAutoLockTimeoutMinutes(remoteProfile.autoLockTimeoutMinutes)
                        }
                        if (biometricEnabled != remoteProfile.biometricEnabled &&
                            securityManager.canAuthenticate() == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                        ) {
                            securityManager.setBiometricEnabled(remoteProfile.biometricEnabled)
                        }
                    }
                    isLoadingProfile = false
                    isRefreshingAccess = false
                }
                is ProfileLookupResult.NotFound -> {
                    val (localReqBio, localTimeout) = withContext(Dispatchers.IO) {
                        securityManager.isRequireBiometricOnSubmit().first() to
                            securityManager.getAutoLockTimeoutMinutes().first()
                    }
                    val assignedRole = if (isSuperAdminEmail) StaffRole.ADMIN.firestoreValue else StaffRole.CASHIER.firestoreValue
                    val assignedBranch = if (isSuperAdminEmail) "SODO DACHI" else UNASSIGNED_BRANCH
                    val assignedStaffId = if (isSuperAdminEmail) "ADM001" else PENDING_STAFF_ID
                    val profileToSave = UserDto(
                        fullName = (signedInUser.displayName ?: "").ifBlank { if (isSuperAdminEmail) "System Admin" else "Staff Member" },
                        email = signedInUser.email.orEmpty(),
                        staffId = assignedStaffId,
                        branchId = assignedBranch,
                        role = assignedRole,
                        active = isSuperAdminEmail,
                        canCreateCashSession = true,
                        canEditOwnDraft = true,
                        canFinalizeDailyClosing = true,
                        canViewOwnBranchHistory = true,
                        biometricEnabled = biometricEnabled,
                        requireBiometricOnSubmit = localReqBio,
                        autoLockTimeoutMinutes = localTimeout
                    )
                    try {
                        repo.createUserProfile(profileToSave)
                    } catch (_: Exception) {
                        // Offline fallback handled below
                    }
                    val shouldPromptBiometric = withContext(Dispatchers.IO) {
                        securityManager.saveCachedUserProfile(uid, profileToSave)
                        val alreadyPrompted = securityManager.hasPromptedBiometricEnrollment(uid)
                        val canPrompt = (profileToSave.active || isSuperAdminEmail) &&
                            !alreadyPrompted &&
                            !profileToSave.biometricEnabled &&
                            securityManager.canAuthenticate() == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
                        if (canPrompt) {
                            securityManager.setPromptedBiometricEnrollment(uid, true)
                        }
                        canPrompt
                    }
                    userProfile = profileToSave
                    isLoadingProfile = false
                    isRefreshingAccess = false
                    if (shouldPromptBiometric) {
                        showBiometricEnrollment = true
                    }
                }
                is ProfileLookupResult.Error -> {
                    // Never overwrite saved profile on transient/offline Firestore errors
                    if (userProfile == null) {
                        val (localReqBio, localTimeout) = withContext(Dispatchers.IO) {
                            securityManager.isRequireBiometricOnSubmit().first() to
                                securityManager.getAutoLockTimeoutMinutes().first()
                        }
                        val offlineFallback = cachedProfile ?: UserDto(
                            fullName = (signedInUser.displayName ?: "").ifBlank { if (isSuperAdminEmail) "System Admin" else "Staff Member" },
                            email = signedInUser.email.orEmpty(),
                            staffId = if (isSuperAdminEmail) "ADM001" else PENDING_STAFF_ID,
                            branchId = if (isSuperAdminEmail) "SODO DACHI" else UNASSIGNED_BRANCH,
                            role = if (isSuperAdminEmail) StaffRole.ADMIN.firestoreValue else StaffRole.CASHIER.firestoreValue,
                            active = isSuperAdminEmail,
                            biometricEnabled = biometricEnabled,
                            requireBiometricOnSubmit = localReqBio,
                            autoLockTimeoutMinutes = localTimeout
                        )
                        userProfile = offlineFallback
                    }
                    isLoadingProfile = false
                    isRefreshingAccess = false
                }
            }
        } else if (signedInUser == null) {
            userProfile = null
            isLoadingProfile = false
            isRefreshingAccess = false
        }
    }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            val updated = auth.currentUser
            if (currentUser?.uid != updated?.uid) {
                currentUser = updated
            }
            if (updated == null) {
                isAppLocked = false
                showBiometricEnrollment = false
            }
        }
        Firebase.auth.addAuthStateListener(listener)
        onDispose { Firebase.auth.removeAuthStateListener(listener) }
    }

    if (currentUser == null || isAppLocked) {
        WelcomeScreen(
            isLocked = isAppLocked,
            allowAutoSignIn = !suppressAutoSignIn,
            onAuthSuccess = {
                suppressAutoSignIn = false
                currentUser = Firebase.auth.currentUser
                onUnlock()
            },
            onSwitchAccount = {
                suppressAutoSignIn = true
                isAppLocked = false
                onLockChanged(false)
            },
            securityManager = securityManager
        )
    } else if (isLoadingProfile) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(IceSurface)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = White,
                shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, SubtleOutline)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 36.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(VaultSlateDeep, CyanDeep, CyanPrimary))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccountBalance,
                            contentDescription = "Cash Control",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = CyanPrimary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Verifying branch access...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    } else if (userProfile != null) {
        val activeProfile = userProfile!!
        if (!activeProfile.active && !activeProfile.isSuperAdmin()) {
            PendingActivationScreen(
                profile = activeProfile,
                isRefreshing = isRefreshingAccess,
                onRefreshAccess = {
                    isRefreshingAccess = true
                    refreshTrigger++
                }
            )
        } else {
            val uid = currentUser!!.uid
            val resolvedRole = activeProfile.resolvedRole()
            key(uid, resolvedRole, activeProfile.branchId) {
                MainNavigation(
                    userId = uid,
                    profile = activeProfile,
                    userRepository = bootstrap.userRepository,
                    cashRepository = bootstrap.cashRepository,
                    securityManager = securityManager,
                    onProfileUpdated = { updated -> userProfile = updated },
                    onLockAppNow = {
                        isAppLocked = true
                        onLockChanged(true)
                    }
                )
            }

            if (showBiometricEnrollment) {
                AlertDialog(
                    onDismissRequest = { showBiometricEnrollment = false },
                    containerColor = White,
                    title = { Text("Enable Biometric Login?") },
                    text = { Text("Use your fingerprint or face to unlock Cash Control securely and quickly next time.") },
                    confirmButton = {
                        TextButton(onClick = {
                            coroutineScope.launch {
                                securityManager.setBiometricEnabled(true)
                                bootstrap.userRepository.updateSecurityPreferences(
                                    biometricEnabled = true,
                                    fallbackProfile = userProfile
                                )
                                userProfile = userProfile?.copy(biometricEnabled = true)
                                showBiometricEnrollment = false
                            }
                        }) { Text("ENABLE", color = CyanPrimary, fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBiometricEnrollment = false }) { Text("LATER") }
                    }
                )
            }

            val pendingUpdate = availableUpdate
            if (pendingUpdate != null && !showBiometricEnrollment) {
                AppUpdateDialog(
                    currentVersion = pendingUpdate.currentVersion,
                    release = pendingUpdate.release,
                    onDismiss = { availableUpdate = null },
                    onSkipVersion = { skippedVersion ->
                        availableUpdate = null
                        coroutineScope.launch(Dispatchers.IO) {
                            securityManager.setSkippedUpdateVersion(skippedVersion)
                        }
                    }
                )
            }
        }
    }
}

private data class AppNavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun MainNavigation(
    userId: String,
    profile: UserDto,
    userRepository: UserRepository,
    cashRepository: CashRepository,
    securityManager: SecurityManager,
    onProfileUpdated: (UserDto) -> Unit,
    onLockAppNow: () -> Unit
) {
    val navController = rememberNavController()
    val role = profile.resolvedRole()

    val viewModel: CashViewModel = viewModel(
        key = "$userId-${role.name}-${profile.branchId}",
        factory = CashViewModelFactory(cashRepository, securityManager)
    )

    LaunchedEffect(userId, profile.branchId, role) {
        viewModel.initializeForSession(userId, profile.branchId, role)
    }

    val uiState by viewModel.uiState.collectAsState()
    val sessionHistory by viewModel.sessionHistory.collectAsState()
    val knownBranches = remember(sessionHistory, profile.branchId) {
        (sessionHistory.map { it.branchId } + profile.branchId)
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it != UNASSIGNED_BRANCH }
            .distinct()
            .sorted()
    }

    val startDestination = when (role) {
        StaffRole.CASHIER -> "calculator"
        StaffRole.AUDITOR -> "auditor"
        StaffRole.MANAGER -> "manager"
        StaffRole.ADMIN -> "admin"
    }

    val navTabs = remember(role) {
        val historyTab = AppNavTab(
            route = "history",
            label = "History",
            selectedIcon = Icons.AutoMirrored.Rounded.ReceiptLong,
            unselectedIcon = Icons.AutoMirrored.Outlined.ReceiptLong,
            testTag = "nav_history"
        )
        val profileTab = AppNavTab(
            route = "profile",
            label = "Profile",
            selectedIcon = Icons.Rounded.Person,
            unselectedIcon = Icons.Outlined.Person,
            testTag = "nav_profile"
        )
        when (role) {
            StaffRole.CASHIER -> listOf(
                AppNavTab(
                    route = "calculator",
                    label = "Calculator",
                    selectedIcon = Icons.Rounded.Calculate,
                    unselectedIcon = Icons.Outlined.Calculate,
                    testTag = "nav_calculator"
                ),
                historyTab,
                profileTab
            )
            StaffRole.AUDITOR -> listOf(
                AppNavTab(
                    route = "auditor",
                    label = "Audit",
                    selectedIcon = Icons.Rounded.FactCheck,
                    unselectedIcon = Icons.Outlined.FactCheck,
                    testTag = "nav_auditor"
                ),
                historyTab,
                profileTab
            )
            StaffRole.MANAGER -> listOf(
                AppNavTab(
                    route = "manager",
                    label = "Branches",
                    selectedIcon = Icons.Rounded.Domain,
                    unselectedIcon = Icons.Outlined.Domain,
                    testTag = "nav_manager"
                ),
                historyTab,
                profileTab
            )
            StaffRole.ADMIN -> listOf(
                AppNavTab(
                    route = "admin",
                    label = "Staff",
                    selectedIcon = Icons.Rounded.AdminPanelSettings,
                    unselectedIcon = Icons.Outlined.AdminPanelSettings,
                    testTag = "nav_admin"
                ),
                AppNavTab(
                    route = "manager",
                    label = "Branches",
                    selectedIcon = Icons.Rounded.Domain,
                    unselectedIcon = Icons.Outlined.Domain,
                    testTag = "nav_manager"
                ),
                historyTab,
                profileTab
            )
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: startDestination
    val showAppBottomBar = currentRoute != "calculator" || uiState.currentStep !in listOf(2, 3)

    Scaffold(
        containerColor = White,
        bottomBar = {
            if (showAppBottomBar) {
                Column(modifier = Modifier.background(White)) {
                    HorizontalDivider(thickness = 1.dp, color = SubtleOutline)
                    NavigationBar(
                        containerColor = White,
                        tonalElevation = 0.dp
                    ) {
                        val navItemColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyanDeep,
                            selectedTextColor = CyanDeep,
                            indicatorColor = CyanSoftTint,
                            unselectedIconColor = MutedNavyText,
                            unselectedTextColor = MutedNavyText
                        )

                        navTabs.forEach { tab ->
                            val isSelected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                colors = navItemColors,
                                icon = {
                                    Icon(
                                        imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .background(White)
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            composable("calculator") {
                CashCalculatorScreen(
                    viewModel = viewModel,
                    branchId = profile.branchId,
                    staffId = profile.staffId,
                    securityManager = securityManager
                )
            }
            composable("auditor") {
                AuditorCheckScreen(
                    viewModel = viewModel,
                    branchId = profile.branchId,
                    auditorUid = userId,
                    auditorStaffId = profile.staffId,
                    auditorName = profile.fullName.ifBlank { profile.staffId }
                )
            }
            composable("manager") {
                ManagerOverviewScreen(
                    viewModel = viewModel,
                    managerName = profile.fullName.ifBlank { profile.staffId }
                )
            }
            composable("admin") {
                AdminUsersScreen(
                    userRepository = userRepository,
                    currentAdminUid = userId,
                    knownBranches = knownBranches
                )
            }
            composable("history") {
                HistoryScreen(
                    viewModel = viewModel,
                    branchId = profile.branchId,
                    isAllBranches = role == StaffRole.MANAGER || role == StaffRole.ADMIN,
                    canUseCounts = role == StaffRole.CASHIER,
                    canApprove = role == StaffRole.AUDITOR || role == StaffRole.MANAGER || role == StaffRole.ADMIN,
                    approverName = profile.fullName.ifBlank { profile.staffId },
                    onNavigateToCalculator = {
                        if (role == StaffRole.CASHIER) {
                            navController.navigate("calculator") {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
            composable("profile") {
                ProfileScreen(
                    securityManager = securityManager,
                    userProfile = profile,
                    userRepository = userRepository,
                    onProfileUpdated = onProfileUpdated,
                    onLockAppNow = onLockAppNow
                )
            }
        }
    }
}

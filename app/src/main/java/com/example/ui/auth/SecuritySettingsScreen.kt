package com.example.ui.auth

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.data.local.BiometricAvailability
import com.example.data.local.SecurityManager
import com.example.data.remote.model.StaffRole
import com.example.data.remote.model.UserDto
import com.example.data.remote.model.isSuperAdmin
import com.example.data.remote.model.resolvedRole
import com.example.data.remote.update.AppUpdateChecker
import com.example.data.remote.update.UpdateCheckResult
import com.example.data.remote.update.normalizeVersionString
import com.example.data.repository.UserRepository
import com.example.ui.update.AppUpdateDialog
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.AmberSoftTint
import com.example.ui.theme.CyanBorder
import com.example.ui.theme.CyanDeep
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanSoftTint
import com.example.ui.theme.DangerBorder
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DangerSoftTint
import com.example.ui.theme.IceSurface
import com.example.ui.theme.MutedNavyText
import com.example.ui.theme.NavyText
import com.example.ui.theme.PlusJakartaSansFontFamily
import com.example.ui.theme.SoftSurfaceVariant
import com.example.ui.theme.SubtleOutline
import com.example.ui.theme.SuccessBorder
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessSoftTint
import com.example.ui.theme.VaultSlateDeep
import com.example.ui.theme.White
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun SecuritySettingsScreen(
    securityManager: SecurityManager,
    userProfile: UserDto? = null,
    userRepository: UserRepository? = null,
    onProfileUpdated: (UserDto) -> Unit = {},
    onLockAppNow: () -> Unit = {}
) {
    ProfileScreen(
        securityManager = securityManager,
        userProfile = userProfile,
        userRepository = userRepository,
        onProfileUpdated = onProfileUpdated,
        onLockAppNow = onLockAppNow
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    securityManager: SecurityManager,
    userProfile: UserDto? = null,
    userRepository: UserRepository? = null,
    onProfileUpdated: (UserDto) -> Unit = {},
    onLockAppNow: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val credentialManagerLazy = remember(context) {
        lazy(LazyThreadSafetyMode.NONE) { CredentialManager.create(context) }
    }

    val biometricEnabled by securityManager.isBiometricEnabled().collectAsState(initial = false)
    val requireBiometricOnSubmit by securityManager.isRequireBiometricOnSubmit().collectAsState(initial = true)
    val autoLockMinutes by securityManager.getAutoLockTimeoutMinutes().collectAsState(initial = 5)
    val firebaseUser = remember {
        try {
            Firebase.auth.currentUser
        } catch (_: Exception) {
            null
        }
    }

    var biometricAvailability by remember { mutableStateOf(BiometricAvailability.UNAVAILABLE) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var isSavingProfile by remember { mutableStateOf(false) }
    var editedPhotoBase64 by remember { mutableStateOf(userProfile?.photoBase64.orEmpty()) }
    var isProcessingPhoto by remember { mutableStateOf(false) }

    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    var updateDialogResult by remember { mutableStateOf<UpdateCheckResult.UpdateAvailable?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            isProcessingPhoto = true
            coroutineScope.launch {
                val encoded = encodeProfilePhotoToBase64(context, uri)
                isProcessingPhoto = false
                if (encoded != null) {
                    editedPhotoBase64 = encoded
                } else {
                    snackbarHostState.showSnackbar("Unable to load selected photo.")
                }
            }
        }
    }

    val openEditProfile = {
        editedPhotoBase64 = userProfile?.photoBase64.orEmpty()
        showEditProfileDialog = true
    }

    LaunchedEffect(securityManager) {
        biometricAvailability = withContext(Dispatchers.IO) {
            securityManager.getBiometricAvailability()
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                coroutineScope.launch {
                    biometricAvailability = withContext(Dispatchers.IO) {
                        securityManager.getBiometricAvailability()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isBiometricAvailable = biometricAvailability == BiometricAvailability.AVAILABLE

    val statusBadgeText = when {
        biometricAvailability == BiometricAvailability.UNAVAILABLE -> "UNAVAILABLE"
        biometricAvailability == BiometricAvailability.NONE_ENROLLED -> "NOT ENROLLED"
        biometricEnabled -> "PROTECTED"
        else -> "DISABLED"
    }

    val statusAccentColor by animateColorAsState(
        targetValue = when {
            biometricAvailability == BiometricAvailability.UNAVAILABLE -> DangerRed
            biometricAvailability == BiometricAvailability.NONE_ENROLLED -> AmberAccent
            biometricEnabled -> SuccessGreen
            else -> MutedNavyText
        },
        label = "status_color"
    )
    val statusContainerColor by animateColorAsState(
        targetValue = when {
            biometricAvailability == BiometricAvailability.UNAVAILABLE -> DangerSoftTint
            biometricAvailability == BiometricAvailability.NONE_ENROLLED -> AmberSoftTint
            biometricEnabled -> SuccessSoftTint
            else -> SoftSurfaceVariant
        },
        label = "status_bg"
    )
    val statusBorderColor by animateColorAsState(
        targetValue = when {
            biometricAvailability == BiometricAvailability.UNAVAILABLE -> DangerBorder
            biometricAvailability == BiometricAvailability.NONE_ENROLLED -> AmberAccent.copy(alpha = 0.28f)
            biometricEnabled -> SuccessBorder
            else -> SubtleOutline
        },
        label = "status_border"
    )

    fun launchBiometricVerification(
        title: String,
        subtitle: String,
        onVerified: suspend () -> Unit,
        successMessage: String
    ) {
        if (activity == null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Biometric prompt requires an active screen context.")
            }
            return
        }
        securityManager.showBiometricPrompt(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onSuccess = {
                coroutineScope.launch {
                    securityManager.recordBiometricVerification()
                    onVerified()
                    snackbarHostState.showSnackbar(successMessage)
                }
            },
            onError = { code, errString ->
                if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                    code != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    code != BiometricPrompt.ERROR_CANCELED
                ) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Authentication error: $errString")
                    }
                }
            },
            onFailed = {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Fingerprint not recognized. Try again.")
                }
            }
        )
    }

    val displayName = userProfile?.fullName?.ifBlank { null }
        ?: firebaseUser?.displayName?.ifBlank { null }
        ?: "Authorized Staff"
    val email = userProfile?.email?.ifBlank { null }
        ?: firebaseUser?.email
        ?: "Authenticated via Google"
    val initials = remember(displayName) {
        displayName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifEmpty { "ST" }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(modifier = Modifier.background(White)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Profile",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = PlusJakartaSansFontFamily,
                            fontSize = 16.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = NavyText
                    )
                    Surface(
                        color = SoftSurfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, SubtleOutline)
                    ) {
                        Text(
                            text = userProfile?.resolvedRole()?.displayLabel?.uppercase() ?: "STAFF",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                letterSpacing = 0.6.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MutedNavyText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = SubtleOutline)
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = IceSurface
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(IceSurface)
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Matte Corporate Banking Credential Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(VaultSlateDeep)
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileAvatar(
                                photoBase64 = userProfile?.photoBase64.orEmpty(),
                                fallbackPhotoUrl = firebaseUser?.photoUrl,
                                initials = initials,
                                size = 50.dp,
                                borderColor = Color.White.copy(alpha = 0.4f),
                                backgroundColor = Color.White.copy(alpha = 0.16f),
                                initialsColor = White,
                                modifier = if (userProfile != null && userRepository != null) {
                                    Modifier.clickable(onClick = openEditProfile)
                                } else {
                                    Modifier
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = PlusJakartaSansFontFamily,
                                        fontSize = 15.5.sp
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = email,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = Color.White.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (userProfile != null && userRepository != null) {
                                    Surface(
                                        onClick = openEditProfile,
                                        color = Color.White.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                                        modifier = Modifier
                                            .heightIn(min = 32.dp)
                                            .testTag("edit_profile_button")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Edit,
                                                contentDescription = "Edit Profile",
                                                tint = White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Edit",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                fontWeight = FontWeight.Bold,
                                                color = White
                                            )
                                        }
                                    }
                                }
                                Surface(
                                    color = Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(50.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(SuccessGreen)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = "Verified",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            fontWeight = FontWeight.SemiBold,
                                            color = White
                                        )
                                    }
                                }
                            }
                        }

                        if (userProfile != null) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ProfileCredentialItem(
                                    label = "ROLE",
                                    value = userProfile.resolvedRole().displayLabel.uppercase(),
                                    modifier = Modifier.weight(0.9f)
                                )
                                ProfileCredentialItem(
                                    label = "STAFF ID",
                                    value = userProfile.staffId,
                                    modifier = Modifier.weight(1f)
                                )
                                ProfileCredentialItem(
                                    label = "BRANCH",
                                    value = when (userProfile.resolvedRole()) {
                                        StaffRole.MANAGER,
                                        StaffRole.HEAD_OFFICE,
                                        StaffRole.ADMIN -> "ALL BRANCHES"
                                        else -> userProfile.branchId
                                    },
                                    modifier = Modifier.weight(1.2f)
                                )
                            }
                        }
                    }
                }

                // Biometric & Access Controls Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = White,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, SubtleOutline)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "VAULT SECURITY",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    letterSpacing = 0.8.sp
                                ),
                                fontWeight = FontWeight.Bold,
                                color = MutedNavyText
                            )
                            Surface(
                                color = statusContainerColor,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, statusBorderColor)
                            ) {
                                Text(
                                    text = statusBadgeText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        letterSpacing = 0.4.sp
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = statusAccentColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Biometric Login Toggle
                        SecuritySwitchRow(
                            icon = Icons.Rounded.Fingerprint,
                            title = "Biometric Login",
                            subtitle = "Fingerprint unlock for Cash Control",
                            checked = biometricEnabled,
                            enabled = isBiometricAvailable || biometricEnabled,
                            switchTag = "biometric_toggle_switch",
                            onCheckedChange = { wantEnabled ->
                                if (wantEnabled) {
                                    launchBiometricVerification(
                                        title = "Enable Biometric Login",
                                        subtitle = "Verify your fingerprint to continue",
                                        onVerified = {
                                            securityManager.setBiometricEnabled(true)
                                            userRepository?.updateSecurityPreferences(
                                                biometricEnabled = true,
                                                fallbackProfile = userProfile
                                            )
                                            userProfile?.let { onProfileUpdated(it.copy(biometricEnabled = true)) }
                                        },
                                        successMessage = "Biometric login enabled."
                                    )
                                } else {
                                    if (isBiometricAvailable) {
                                        launchBiometricVerification(
                                            title = "Disable Biometric Login",
                                            subtitle = "Verify your identity to turn off",
                                            onVerified = {
                                                securityManager.setBiometricEnabled(false)
                                                userRepository?.updateSecurityPreferences(
                                                    biometricEnabled = false,
                                                    fallbackProfile = userProfile
                                                )
                                                userProfile?.let { onProfileUpdated(it.copy(biometricEnabled = false)) }
                                            },
                                            successMessage = "Biometric login disabled."
                                        )
                                    } else {
                                        coroutineScope.launch {
                                            securityManager.setBiometricEnabled(false)
                                            userRepository?.updateSecurityPreferences(
                                                biometricEnabled = false,
                                                fallbackProfile = userProfile
                                            )
                                            userProfile?.let { onProfileUpdated(it.copy(biometricEnabled = false)) }
                                        }
                                    }
                                }
                            }
                        )

                        AnimatedVisibility(visible = biometricEnabled && isBiometricAvailable) {
                            Column {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    color = SubtleOutline
                                )
                                SecuritySwitchRow(
                                    icon = Icons.Rounded.TaskAlt,
                                    title = "Confirm Day Closing",
                                    subtitle = "Fingerprint check before closing",
                                    checked = requireBiometricOnSubmit,
                                    enabled = true,
                                    switchTag = "biometric_submit_switch",
                                    onCheckedChange = { checked ->
                                        coroutineScope.launch {
                                            securityManager.setRequireBiometricOnSubmit(checked)
                                            userRepository?.updateSecurityPreferences(
                                                requireBiometricOnSubmit = checked,
                                                fallbackProfile = userProfile
                                            )
                                            userProfile?.let { onProfileUpdated(it.copy(requireBiometricOnSubmit = checked)) }
                                        }
                                    }
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = SubtleOutline
                        )

                        // Auto-Lock Timeout Segmented Bar
                        val options = listOf(1 to "1m", 5 to "5m", 15 to "15m", -1 to "Off")
                        val selectorEnabled = biometricEnabled && isBiometricAvailable
                        val activeTimeoutLabel = options.firstOrNull { it.first == autoLockMinutes }?.second ?: "5m"

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProfileSettingIcon(Icons.Rounded.Timer)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Session Auto-Lock",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                                        fontWeight = FontWeight.SemiBold,
                                        color = NavyText
                                    )
                                    Text(
                                        text = if (selectorEnabled) {
                                            "Lock after inactivity"
                                        } else {
                                            "Enable biometric login to customize"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = MutedNavyText
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = if (selectorEnabled) CyanSoftTint else SoftSurfaceVariant,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectorEnabled) CyanBorder else SubtleOutline
                                )
                            ) {
                                Text(
                                    text = if (selectorEnabled) activeTimeoutLabel else "Locked",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        fontFeatureSettings = "tnum"
                                    ),
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selectorEnabled) CyanDeep else MutedNavyText,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SoftSurfaceVariant,
                            border = BorderStroke(1.dp, SubtleOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                options.forEach { (minutes, label) ->
                                    val selected = autoLockMinutes == minutes
                                    val pillColor = when {
                                        selected && selectorEnabled -> White
                                        selected -> White.copy(alpha = 0.72f)
                                        else -> Color.Transparent
                                    }
                                    val pillBorder = when {
                                        selected && selectorEnabled -> BorderStroke(1.dp, CyanBorder)
                                        selected -> BorderStroke(1.dp, SubtleOutline)
                                        else -> null
                                    }
                                    Surface(
                                        onClick = {
                                            if (selectorEnabled) {
                                                coroutineScope.launch {
                                                    securityManager.setAutoLockTimeoutMinutes(minutes)
                                                    userRepository?.updateSecurityPreferences(
                                                        autoLockTimeoutMinutes = minutes,
                                                        fallbackProfile = userProfile
                                                    )
                                                    userProfile?.let { onProfileUpdated(it.copy(autoLockTimeoutMinutes = minutes)) }
                                                }
                                            }
                                        },
                                        enabled = selectorEnabled,
                                        shape = RoundedCornerShape(9.dp),
                                        color = pillColor,
                                        shadowElevation = if (selected && selectorEnabled) 1.dp else 0.dp,
                                        border = pillBorder,
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 48.dp)
                                            .testTag("timeout_chip_$minutes")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 12.5.sp,
                                                    fontFeatureSettings = "tnum"
                                                ),
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = when {
                                                    selected && selectorEnabled -> CyanDeep
                                                    selected -> NavyText.copy(alpha = 0.72f)
                                                    selectorEnabled -> NavyText.copy(alpha = 0.78f)
                                                    else -> MutedNavyText.copy(alpha = 0.75f)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = SubtleOutline
                        )

                        if (biometricAvailability == BiometricAvailability.NONE_ENROLLED) {
                            ProfileActionRow(
                                icon = Icons.Rounded.Fingerprint,
                                title = "Enroll Fingerprint",
                                subtitle = "Add biometric unlock in device security",
                                badgeText = "Set Up",
                                badgeContainerColor = AmberSoftTint,
                                badgeContentColor = AmberAccent,
                                badgeBorderColor = AmberAccent.copy(alpha = 0.25f),
                                iconContainerColor = AmberSoftTint,
                                iconTint = AmberAccent,
                                iconBorderColor = AmberAccent.copy(alpha = 0.25f),
                                testTag = "enroll_biometric_button",
                                onClick = {
                                    val enrollIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                                            putExtra(
                                                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                                                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                            )
                                        }
                                    } else {
                                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                                    }
                                    try {
                                        context.startActivity(enrollIntent)
                                    } catch (_: Exception) {
                                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                                    }
                                }
                            )
                        } else {
                            ProfileActionRow(
                                icon = Icons.Rounded.VerifiedUser,
                                title = "Verify Biometric Sensor",
                                subtitle = if (isBiometricAvailable) {
                                    "Test fingerprint recognition now"
                                } else {
                                    "Biometric hardware unavailable on device"
                                },
                                badgeText = if (isBiometricAvailable) "Verify" else "N/A",
                                enabled = isBiometricAvailable,
                                testTag = "verify_biometric_button",
                                onClick = {
                                    launchBiometricVerification(
                                        title = "Verify Identity",
                                        subtitle = "Touch the fingerprint sensor",
                                        onVerified = { },
                                        successMessage = "Verified."
                                    )
                                }
                            )

                            if (biometricEnabled && isBiometricAvailable) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = SubtleOutline
                                )
                                ProfileActionRow(
                                    icon = Icons.Rounded.Lock,
                                    title = "Lock Vault Now",
                                    subtitle = "Require fingerprint immediately",
                                    badgeText = "Lock",
                                    badgeContainerColor = CyanSoftTint,
                                    badgeContentColor = CyanDeep,
                                    badgeBorderColor = CyanBorder,
                                    testTag = "lock_app_now_button",
                                    onClick = onLockAppNow
                                )
                            }
                        }
                    }
                }

                // System & Session Card (Grouped Fintech Settings Rows)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = White,
                    shadowElevation = 0.dp,
                    border = BorderStroke(1.dp, SubtleOutline)
                ) {
                    val installedVersion = normalizeVersionString(BuildConfig.VERSION_NAME).ifBlank { "1.0.0" }
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "SYSTEM & SESSION",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                letterSpacing = 0.8.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MutedNavyText
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        ProfileActionRow(
                            icon = Icons.Rounded.SystemUpdate,
                            title = "Software & Updates",
                            subtitle = if (isCheckingUpdates) {
                                "Checking for latest release..."
                            } else {
                                "Cash Control · Version $installedVersion"
                            },
                            badgeText = if (isCheckingUpdates) null else "v$installedVersion",
                            isLoading = isCheckingUpdates,
                            enabled = !isCheckingUpdates,
                            testTag = "check_for_updates_button",
                            onClick = {
                                if (!isCheckingUpdates) {
                                    isCheckingUpdates = true
                                    updateStatusMessage = null
                                    coroutineScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            AppUpdateChecker(securityManager).checkForUpdate(
                                                ignoreSkippedVersion = true
                                            )
                                        }
                                        isCheckingUpdates = false
                                        when (result) {
                                            is UpdateCheckResult.UpdateAvailable -> {
                                                updateStatusMessage = "Version ${result.release.versionName} is available."
                                                updateDialogResult = result
                                            }
                                            is UpdateCheckResult.UpToDate -> {
                                                updateStatusMessage = "You are on the latest version (v${result.currentVersion})."
                                            }
                                            UpdateCheckResult.NotConfigured -> {
                                                updateStatusMessage = "App updates are managed by your branch administrator."
                                            }
                                            is UpdateCheckResult.Error -> {
                                                updateStatusMessage = result.message
                                            }
                                        }
                                    }
                                }
                            }
                        )

                        AnimatedVisibility(visible = !updateStatusMessage.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = IceSurface,
                                border = BorderStroke(1.dp, SubtleOutline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Info,
                                        contentDescription = null,
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = updateStatusMessage.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                        color = NavyText
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = SubtleOutline
                        )

                        ProfileActionRow(
                            icon = Icons.AutoMirrored.Rounded.Logout,
                            title = "Sign Out",
                            subtitle = "End active session on this device",
                            titleColor = DangerRed,
                            iconContainerColor = DangerSoftTint,
                            iconTint = DangerRed,
                            iconBorderColor = DangerBorder,
                            chevronTint = DangerRed.copy(alpha = 0.75f),
                            testTag = "logout_button",
                            onClick = { showLogoutDialog = true }
                        )
                    }
                }

                Text(
                    text = "Cash Control · Branch Cash Management",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = MutedNavyText.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 2.dp, bottom = 6.dp)
                )
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = White,
            title = {
                Text(
                    "Sign Out",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "End your session on this device?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedNavyText
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        signOut(credentialManagerLazy.value, coroutineScope)
                    }
                ) {
                    Text("SIGN OUT", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("CANCEL", color = MutedNavyText)
                }
            }
        )
    }

    if (showEditProfileDialog && userProfile != null && userRepository != null) {
        var editedName by remember(showEditProfileDialog, userProfile.fullName) { mutableStateOf(userProfile.fullName) }
        var editedStaffId by remember(showEditProfileDialog, userProfile.staffId) { mutableStateOf(userProfile.staffId) }
        var editedBranchId by remember(showEditProfileDialog, userProfile.branchId) { mutableStateOf(userProfile.branchId) }
        var editError by remember(showEditProfileDialog) { mutableStateOf<String?>(null) }

        val launchPhotoPicker = {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        AlertDialog(
            onDismissRequest = { if (!isSavingProfile && !isProcessingPhoto) showEditProfileDialog = false },
            containerColor = White,
            title = {
                Text(
                    "Edit Profile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NavyText
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .testTag("edit_profile_avatar_picker"),
                            contentAlignment = Alignment.Center
                        ) {
                            ProfileAvatar(
                                photoBase64 = editedPhotoBase64,
                                fallbackPhotoUrl = if (editedPhotoBase64.isBlank()) firebaseUser?.photoUrl else null,
                                initials = initials,
                                size = 84.dp,
                                borderColor = CyanBorder,
                                backgroundColor = CyanSoftTint,
                                initialsColor = CyanDeep,
                                modifier = Modifier.clickable(
                                    enabled = !isSavingProfile && !isProcessingPhoto,
                                    onClick = launchPhotoPicker
                                )
                            )
                            if (isProcessingPhoto) {
                                Box(
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.35f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.5.dp,
                                        color = White
                                    )
                                }
                            }
                            Surface(
                                onClick = launchPhotoPicker,
                                enabled = !isSavingProfile && !isProcessingPhoto,
                                shape = CircleShape,
                                color = CyanPrimary,
                                border = BorderStroke(2.dp, White),
                                shadowElevation = 2.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.PhotoCamera,
                                        contentDescription = "Choose profile photo",
                                        tint = White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = launchPhotoPicker,
                                enabled = !isSavingProfile && !isProcessingPhoto,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = CyanSoftTint,
                                    contentColor = CyanDeep
                                ),
                                border = BorderStroke(1.dp, CyanBorder),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 38.dp)
                                    .testTag("pick_profile_photo_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AddAPhoto,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (editedPhotoBase64.isNotBlank()) "Change Photo" else "Add Photo",
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (editedPhotoBase64.isNotBlank()) {
                                TextButton(
                                    onClick = { editedPhotoBase64 = "" },
                                    enabled = !isSavingProfile && !isProcessingPhoto,
                                    modifier = Modifier
                                        .heightIn(min = 38.dp)
                                        .testTag("remove_profile_photo_button")
                                ) {
                                    Text(
                                        text = "Remove",
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                        color = DangerRed,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    val canEditAdminFields = userProfile.isSuperAdmin()
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it; editError = null },
                        label = { Text("Full Name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_full_name_input")
                    )
                    OutlinedTextField(
                        value = editedStaffId,
                        onValueChange = { if (canEditAdminFields) { editedStaffId = it.uppercase(); editError = null } },
                        enabled = canEditAdminFields,
                        readOnly = !canEditAdminFields,
                        label = { Text("Staff ID") },
                        supportingText = if (!canEditAdminFields) {
                            { Text("Assigned by System Admin") }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_staff_id_input")
                    )
                    OutlinedTextField(
                        value = editedBranchId,
                        onValueChange = { if (canEditAdminFields) { editedBranchId = it.uppercase(); editError = null } },
                        enabled = canEditAdminFields,
                        readOnly = !canEditAdminFields,
                        label = { Text("Branch ID") },
                        supportingText = if (!canEditAdminFields) {
                            { Text("Assigned by System Admin (${userProfile.resolvedRole().displayLabel})") }
                        } else {
                            null
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_branch_id_input")
                    )
                    if (editError != null) {
                        Text(
                            text = editError!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                val canEditAdminFields = userProfile.isSuperAdmin()
                Button(
                    onClick = {
                        if (editedName.isBlank() || (canEditAdminFields && (editedStaffId.isBlank() || editedBranchId.isBlank()))) {
                            editError = "Required fields cannot be empty."
                            return@Button
                        }
                        isSavingProfile = true
                        coroutineScope.launch {
                            val result = userRepository.updateUserProfile(
                                fullName = editedName,
                                staffId = editedStaffId,
                                branchId = editedBranchId,
                                photoBase64 = editedPhotoBase64,
                                biometricEnabled = biometricEnabled,
                                requireBiometricOnSubmit = requireBiometricOnSubmit,
                                autoLockTimeoutMinutes = autoLockMinutes,
                                allowAdminAssignedFields = canEditAdminFields
                            )
                            result.onSuccess { updated ->
                                val uid = firebaseUser?.uid
                                if (!uid.isNullOrBlank()) {
                                    withContext(Dispatchers.IO) {
                                        securityManager.saveCachedUserProfile(uid, updated)
                                    }
                                }
                                isSavingProfile = false
                                onProfileUpdated(updated)
                                showEditProfileDialog = false
                                snackbarHostState.showSnackbar("Profile saved.")
                            }.onFailure { err ->
                                isSavingProfile = false
                                editError = err.localizedMessage ?: "Failed to save profile."
                            }
                        }
                    },
                    enabled = !isSavingProfile && !isProcessingPhoto,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    modifier = Modifier.testTag("save_profile_button")
                ) {
                    if (isSavingProfile) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = White
                        )
                    } else {
                        Text("SAVE", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditProfileDialog = false },
                    enabled = !isSavingProfile && !isProcessingPhoto
                ) {
                    Text("CANCEL", color = MutedNavyText)
                }
            }
        )
    }

    val manualUpdate = updateDialogResult
    if (manualUpdate != null) {
        AppUpdateDialog(
            currentVersion = manualUpdate.currentVersion,
            release = manualUpdate.release,
            onDismiss = { updateDialogResult = null },
            onSkipVersion = { skippedVersion ->
                updateDialogResult = null
                coroutineScope.launch(Dispatchers.IO) {
                    securityManager.setSkippedUpdateVersion(skippedVersion)
                }
                updateStatusMessage = "Skipped notifications for v$skippedVersion."
            }
        )
    }
}

@Composable
private fun SecuritySwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    switchTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileSettingIcon(icon)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = NavyText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MutedNavyText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = White,
                checkedTrackColor = CyanPrimary,
                uncheckedThumbColor = MutedNavyText,
                uncheckedTrackColor = SoftSurfaceVariant,
                uncheckedBorderColor = CyanBorder
            ),
            modifier = Modifier.testTag(switchTag)
        )
    }
}

@Composable
private fun ProfileActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    titleColor: Color = NavyText,
    badgeText: String? = null,
    badgeContainerColor: Color = SoftSurfaceVariant,
    badgeContentColor: Color = MutedNavyText,
    badgeBorderColor: Color = SubtleOutline,
    iconContainerColor: Color = CyanSoftTint,
    iconTint: Color = CyanPrimary,
    iconBorderColor: Color = CyanBorder.copy(alpha = 0.55f),
    chevronTint: Color = MutedNavyText
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileSettingIcon(
                    icon = icon,
                    containerColor = if (enabled) iconContainerColor else SoftSurfaceVariant,
                    tint = if (enabled) iconTint else MutedNavyText.copy(alpha = 0.6f),
                    borderColor = if (enabled) iconBorderColor else SubtleOutline
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) titleColor else MutedNavyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MutedNavyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = CyanPrimary
                    )
                } else if (!badgeText.isNullOrBlank()) {
                    Surface(
                        color = badgeContainerColor,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, badgeBorderColor)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontFeatureSettings = "tnum"
                            ),
                            fontWeight = FontWeight.SemiBold,
                            color = badgeContentColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = if (enabled) chevronTint else MutedNavyText.copy(alpha = 0.4f),
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun ProfileSettingIcon(
    icon: ImageVector,
    containerColor: Color = CyanSoftTint,
    tint: Color = CyanPrimary,
    borderColor: Color = CyanBorder.copy(alpha = 0.55f)
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ProfileCredentialItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                letterSpacing = 0.6.sp
            ),
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontFeatureSettings = "tnum"
            ),
            fontWeight = FontWeight.Bold,
            color = White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProfileAvatar(
    photoBase64: String,
    fallbackPhotoUrl: Uri?,
    initials: String,
    size: Dp,
    borderColor: Color,
    backgroundColor: Color,
    initialsColor: Color,
    modifier: Modifier = Modifier
) {
    val decodedBitmap by produceState<ImageBitmap?>(initialValue = null, photoBase64) {
        value = if (photoBase64.isNotBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val bytes = Base64.decode(photoBase64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(1.5.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = PlusJakartaSansFontFamily,
                fontSize = (size.value * 0.32f).sp
            ),
            fontWeight = FontWeight.Bold,
            color = initialsColor
        )
        if (decodedBitmap != null) {
            Image(
                bitmap = decodedBitmap!!,
                contentDescription = "Profile Picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (fallbackPhotoUrl != null) {
            AsyncImage(
                model = fallbackPhotoUrl,
                contentDescription = "Profile Picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private suspend fun encodeProfilePhotoToBase64(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return@withContext null

            var sampleSize = 1
            val maxDim = 512
            while (boundsOptions.outWidth / sampleSize > maxDim || boundsOptions.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawBitmap = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            val orientation = resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            val rotated = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(rawBitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(rawBitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(rawBitmap, 270f)
                else -> rawBitmap
            }
            if (rotated !== rawBitmap) rawBitmap.recycle()

            val cropSize = minOf(rotated.width, rotated.height)
            val cropX = (rotated.width - cropSize) / 2
            val cropY = (rotated.height - cropSize) / 2
            val square = Bitmap.createBitmap(rotated, cropX, cropY, cropSize, cropSize)
            if (square !== rotated) rotated.recycle()

            val targetSize = 256
            val finalBitmap = if (square.width > targetSize) {
                Bitmap.createScaledBitmap(square, targetSize, targetSize, true).also {
                    if (it !== square) square.recycle()
                }
            } else {
                square
            }

            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            finalBitmap.recycle()

            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

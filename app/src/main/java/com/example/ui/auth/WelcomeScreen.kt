package com.example.ui.auth

import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.fragment.app.FragmentActivity
import com.example.data.local.SecurityManager
import com.example.ui.theme.CyanBorder
import com.example.ui.theme.CyanBright
import com.example.ui.theme.CyanDeep
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanSoftTint
import com.example.ui.theme.IceSurface
import com.example.ui.theme.NavyText
import com.example.ui.theme.SoftSurfaceVariant
import com.example.ui.theme.SubtleOutline
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.VaultSlateDeep
import com.example.ui.theme.White
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WelcomeScreen(
    isLocked: Boolean = false,
    allowAutoSignIn: Boolean = true,
    onAuthSuccess: () -> Unit,
    onSwitchAccount: () -> Unit = {},
    securityManager: SecurityManager
) {
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManagerLazy = remember(context) {
        lazy(LazyThreadSafetyMode.NONE) { CredentialManager.create(context) }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val biometricEnabled by securityManager.isBiometricEnabled().collectAsState(initial = false)
    val signedInUser by produceState<FirebaseUser?>(initialValue = null, isLocked) {
        value = withContext(Dispatchers.IO) {
            try {
                Firebase.auth.currentUser
            } catch (_: Exception) {
                null
            }
        }
    }

    var startAnimations by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        startAnimations = true
    }

    var hasAttemptedAutoSignIn by remember(allowAutoSignIn, isLocked) { mutableStateOf(false) }
    // Attempt silent Credential Manager auto-sign-in only after window focus & SurfaceSyncGroup settle
    LaunchedEffect(allowAutoSignIn, isLocked, windowInfo.isWindowFocused) {
        if (!isLocked && allowAutoSignIn && !hasAttemptedAutoSignIn && windowInfo.isWindowFocused) {
            withFrameNanos { }
            withFrameNanos { }
            delay(450)
            val currentAuthUser = withContext(Dispatchers.IO) {
                try {
                    Firebase.auth.currentUser
                } catch (_: Exception) {
                    null
                }
            }
            if (currentAuthUser == null) {
                hasAttemptedAutoSignIn = true
                isLoading = true
                val cm = withContext(Dispatchers.IO) { credentialManagerLazy.value }
                attemptAutoSignIn(
                    context = context,
                    credentialManager = cm,
                    onAuthSuccess = {
                        isLoading = false
                        onAuthSuccess()
                    },
                    onUnauthenticated = {
                        isLoading = false
                    },
                    scope = coroutineScope
                )
            }
        }
    }

    val cardAlpha by animateFloatAsState(
        targetValue = if (startAnimations) 1f else 0.94f,
        animationSpec = tween(durationMillis = 260),
        label = "card_alpha"
    )
    val cardOffset by animateDpAsState(
        targetValue = if (startAnimations) 0.dp else 12.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "card_offset"
    )

    fun handleBiometricLogin() {
        val activity = context as? FragmentActivity
        if (activity == null) {
            error = "Unable to launch biometric prompt."
            return
        }
        error = null
        securityManager.showBiometricPrompt(
            activity = activity,
            title = "Unlock Cash Control",
            subtitle = "Verify your fingerprint or biometric credential",
            onSuccess = {
                coroutineScope.launch {
                    securityManager.recordBiometricVerification()
                }
                onAuthSuccess()
            },
            onError = { code, msg ->
                if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                    code != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                    code != BiometricPrompt.ERROR_CANCELED
                ) {
                    error = "Biometric error: $msg"
                }
            },
            onFailed = { error = "Fingerprint not recognized. Try again." }
        )
    }

    var hasAutoLaunchedBiometric by remember(isLocked) { mutableStateOf(false) }
    // Auto-launch biometrics only once window is focused and initial SurfaceSyncGroup has settled
    LaunchedEffect(isLocked, biometricEnabled, windowInfo.isWindowFocused) {
        if (isLocked && biometricEnabled && !hasAutoLaunchedBiometric && windowInfo.isWindowFocused) {
            withFrameNanos { }
            withFrameNanos { }
            delay(450)
            hasAutoLaunchedBiometric = true
            handleBiometricLogin()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(IceSurface)
    ) {
        val minHeight = maxHeight

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Executive Banking Vault Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                VaultSlateDeep,
                                CyanDeep,
                                CyanPrimary
                            )
                        )
                    )
                    .drawBehind {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.06f),
                            radius = size.width * 0.44f,
                            center = Offset(size.width * 0.90f, size.height * 0.14f)
                        )
                        drawCircle(
                            color = CyanBright.copy(alpha = 0.14f),
                            radius = size.width * 0.30f,
                            center = Offset(size.width * 0.10f, size.height * 0.80f)
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.08f),
                            radius = size.width * 0.28f,
                            center = Offset(size.width * 0.90f, size.height * 0.14f),
                            style = Stroke(width = 1.dp.toPx())
                        )
                        val wavePath = Path().apply {
                            moveTo(0f, size.height * 0.74f)
                            cubicTo(
                                size.width * 0.34f, size.height * 0.56f,
                                size.width * 0.66f, size.height * 0.88f,
                                size.width, size.height * 0.66f
                            )
                        }
                        drawPath(
                            path = wavePath,
                            color = Color.White.copy(alpha = 0.13f),
                            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    .statusBarsPadding()
                    .padding(top = 42.dp, bottom = 78.dp, start = 24.dp, end = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(50.dp),
                        color = Color.White.copy(alpha = 0.13f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f))
                    ) {
                        Text(
                            text = "BRANCH TREASURY VAULT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = Color.White.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.AccountBalance,
                                contentDescription = "Cash Control",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "CASH CONTROL",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            letterSpacing = 1.8.sp
                        ),
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Precision Reconciliation • Vault Audit",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.84f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Elevated White Auth Card overlapping header
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .padding(horizontal = 20.dp)
                    .offset(y = (-36).dp + cardOffset)
                    .alpha(cardAlpha),
                shape = RoundedCornerShape(28.dp),
                color = White,
                shadowElevation = 14.dp,
                border = BorderStroke(1.dp, CyanBorder.copy(alpha = 0.65f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isLocked) {
                        BiometricLockCardContent(
                            displayName = signedInUser?.displayName,
                            email = signedInUser?.email,
                            error = error,
                            isWindowFocused = windowInfo.isWindowFocused,
                            onUnlockClick = { handleBiometricLogin() },
                            onSwitchAccountClick = {
                                signOut(credentialManagerLazy.value, coroutineScope) {
                                    onSwitchAccount()
                                }
                            }
                        )
                    } else {
                        SignInCardContent(
                            isLoading = isLoading,
                            error = error,
                            onGoogleSignIn = {
                                isLoading = true
                                error = null
                                onGoogleSignInClicked(
                                    context = context,
                                    credentialManager = credentialManagerLazy.value,
                                    onAuthSuccess = {
                                        isLoading = false
                                        onAuthSuccess()
                                    },
                                    onAuthError = { e ->
                                        isLoading = false
                                        error = e
                                    },
                                    scope = coroutineScope,
                                    onAuthCancelled = { isLoading = false }
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = true))

            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Authorized Staff Only",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Cash Control • v1.1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun SignInCardContent(
    isLoading: Boolean,
    error: String?,
    onGoogleSignIn: () -> Unit
) {
    Text(
        text = "Welcome back",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "Sign in with your staff account to continue.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = onGoogleSignIn,
        enabled = !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("google_sign_in_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyanPrimary,
            contentColor = Color.White,
            disabledContainerColor = CyanPrimary.copy(alpha = 0.72f),
            disabledContentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.5.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Signing in...",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                GoogleGLogo(modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Continue with Google",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }

    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        error?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            AuthErrorBanner(message = msg)
        }
    }
}

@Composable
private fun BiometricLockCardContent(
    displayName: String?,
    email: String?,
    error: String?,
    isWindowFocused: Boolean,
    onUnlockClick: () -> Unit,
    onSwitchAccountClick: () -> Unit
) {
    val (pulseScale, pulseAlpha) = if (isWindowFocused) {
        val infiniteTransition = rememberInfiniteTransition(label = "fingerprint_pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.22f,
            targetValue = 0.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )
        scale to alpha
    } else {
        1f to 0.14f
    }

    Box(
        modifier = Modifier.size(88.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(CyanPrimary.copy(alpha = pulseAlpha))
        )
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(CyanSoftTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Fingerprint,
                contentDescription = "Fingerprint Unlock",
                tint = CyanPrimary,
                modifier = Modifier.size(34.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    Text(
        text = "Session Locked",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Verify your fingerprint to continue.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    if (!displayName.isNullOrBlank() || !email.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(50.dp),
            color = SoftSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = displayName?.takeIf { it.isNotBlank() } ?: email.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    Button(
        onClick = onUnlockClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("biometric_unlock_button"),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyanPrimary,
            contentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Fingerprint,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Unlock with Biometrics",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    TextButton(
        onClick = onSwitchAccountClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("switch_account_button")
    ) {
        Text(
            text = "Sign in with a different account",
            style = MaterialTheme.typography.labelLarge,
            color = CyanPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }

    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        error?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            AuthErrorBanner(message = msg)
        }
    }
}

@Composable
private fun AuthErrorBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = "Authentication Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun GoogleGLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.22f
        val inset = strokeWidth / 2f
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(inset, inset)

        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -10f,
            sweepAngle = 55f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 45f,
            sweepAngle = 95f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 140f,
            sweepAngle = 75f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 215f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(size.width * 0.50f, size.height * 0.50f),
            end = Offset(size.width - inset * 0.6f, size.height * 0.50f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Butt
        )
    }
}

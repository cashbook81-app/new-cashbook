package com.example.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import com.example.data.remote.model.SUPER_ADMIN_EMAIL
import com.example.data.remote.model.UserDto
import com.example.ui.theme.*

@Composable
fun PendingActivationScreen(
    profile: UserDto,
    isRefreshing: Boolean,
    onRefreshAccess: () -> Unit,
    onSignedOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManagerLazy = remember(context) {
        lazy(LazyThreadSafetyMode.NONE) { CredentialManager.create(context) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IceSurface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .testTag("pending_activation_card"),
            shape = RoundedCornerShape(24.dp),
            color = White,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, SubtleOutline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(VaultSlateDeep, CyanDeep, CyanPrimary))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HourglassTop,
                        contentDescription = "Pending Activation",
                        tint = White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50.dp),
                    color = AmberSoftTint,
                    border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "PENDING ADMIN ACTIVATION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.5.sp,
                            letterSpacing = 0.8.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = AmberAccent,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "Awaiting Branch & Role Assignment",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NavyText,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Your Google account is registered. Ask your System Admin ($SUPER_ADMIN_EMAIL) to activate your account and assign your branch and role (Cashier, Auditor, or Manager).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedNavyText,
                    textAlign = TextAlign.Center
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = SoftSurfaceVariant,
                    border = BorderStroke(1.dp, SubtleOutline)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PendingInfoLine("Account Name", profile.fullName.ifBlank { "Staff Member" })
                        PendingInfoLine("Google Email", profile.email.ifBlank { "—" })
                        PendingInfoLine("Assigned Branch", profile.branchId.ifBlank { "UNASSIGNED" })
                        PendingInfoLine("Assigned Role", profile.role.ifBlank { "PENDING" })
                    }
                }

                Button(
                    onClick = onRefreshAccess,
                    enabled = !isRefreshing,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("refresh_activation_button")
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking Access...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh Access Status", fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = {
                        signOut(credentialManagerLazy.value, coroutineScope)
                        onSignedOut()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, DangerBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("pending_sign_out_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign Out", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PendingInfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MutedNavyText
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = NavyText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

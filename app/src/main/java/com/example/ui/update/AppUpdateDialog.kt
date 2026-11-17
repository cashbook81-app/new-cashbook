package com.example.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.update.AppReleaseInfo
import com.example.ui.theme.CyanBorder
import com.example.ui.theme.CyanDeep
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanSoftTint
import com.example.ui.theme.IceSurface
import com.example.ui.theme.MutedNavyText
import com.example.ui.theme.NavyText
import com.example.ui.theme.PlusJakartaSansFontFamily
import com.example.ui.theme.SubtleOutline
import com.example.ui.theme.White
import java.util.Locale

fun formatFileSizeMb(bytes: Long?): String? {
    if (bytes == null || bytes <= 0L) return null
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}

fun openReleaseDownloadUrl(context: Context, url: String): Boolean {
    return try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "No browser found to open the update link.",
            Toast.LENGTH_SHORT
        ).show()
        false
    }
}

@Composable
fun AppUpdateDialog(
    currentVersion: String,
    release: AppReleaseInfo,
    onDismiss: () -> Unit,
    onSkipVersion: (String) -> Unit,
    onDownloadClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val sizeLabel = formatFileSizeMb(release.assetSizeBytes)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(CyanSoftTint),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = "App Update Available",
                        tint = CyanDeep,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Update Available",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = PlusJakartaSansFontFamily,
                            fontSize = 17.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = NavyText
                    )
                    Text(
                        text = release.releaseTitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        color = MutedNavyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = CyanSoftTint,
                    border = BorderStroke(1.dp, CyanBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "v$currentVersion  ->  v${release.versionName}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.5.sp,
                                fontFeatureSettings = "tnum"
                            ),
                            fontWeight = FontWeight.Bold,
                            color = CyanDeep
                        )
                        if (sizeLabel != null) {
                            Text(
                                text = sizeLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontFeatureSettings = "tnum"
                                ),
                                fontWeight = FontWeight.SemiBold,
                                color = MutedNavyText
                            )
                        }
                    }
                }

                if (release.assetName != null) {
                    Text(
                        text = "Package: ${release.assetName}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MutedNavyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = IceSurface,
                    border = BorderStroke(1.dp, SubtleOutline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 180.dp)
                ) {
                    Text(
                        text = release.releaseNotes,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp
                        ),
                        color = NavyText,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }

                Text(
                    text = "Opens GitHub in your browser to download the updated APK.",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MutedNavyText
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (onDownloadClick != null) {
                        onDownloadClick()
                    } else {
                        openReleaseDownloadUrl(context, release.downloadUrl)
                    }
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = White
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("update_download_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Download Update",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        onSkipVersion(release.versionName)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("update_skip_button")
                ) {
                    Text("Skip", color = MutedNavyText, fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("update_later_button")
                ) {
                    Text("Later", color = CyanDeep, fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

package com.example.ui.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.entities.CashSession
import com.example.data.remote.model.AuditorCheckDto
import com.example.ui.util.formatBirr

object BankAlertNotificationHelper {

    private const val CHANNEL_ID = "cash_control_branch_alerts"
    private const val CHANNEL_NAME = "Branch Cash Alerts"
    private const val CHANNEL_DESCRIPTION = "Notifications for Auditor DR/CR verifications and balanced daily closings."

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESCRIPTION
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildLaunchIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 1001, intent, flags)
    }

    fun notifyAuditorDrCrForCashier(context: Context, check: AuditorCheckDto) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val auditorName = check.auditorName.ifBlank { check.auditorStaffId.ifBlank { "Auditor" } }
        val title = "Auditor DR & CR Received · ${check.branchId}"
        val summary = "DR ${formatBirr(check.totalDRMinor)} · CR ${formatBirr(check.totalCRMinor)}"
        val bigBody = buildString {
            append("$auditorName submitted today's ledger amounts for ${check.branchId}.\n")
            append("Cash In (DR): +${formatBirr(check.totalDRMinor)}\n")
            append("Cash Out (CR): -${formatBirr(check.totalCRMinor)}")
            if (check.notes.isNotBlank()) {
                append("\nNote: ${check.notes}")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildLaunchIntent(context))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                ("drcr_${check.checkId}").hashCode(),
                notification
            )
        } catch (_: SecurityException) {
            // Ignored gracefully when permission was revoked concurrently
        }
    }

    fun notifyBalancedClosingForAuditor(context: Context, session: CashSession) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)

        val title = "Cashier Balanced & Closed · ${session.branchId}"
        val summary = "Ending Balance: ${formatBirr(session.totalCountedMinor)}"
        val bigBody = buildString {
            append("Staff ${session.staffId} closed a balanced position.\n")
            append("Opening Balance: ${formatBirr(session.openingBalanceMinor)}\n")
            append("Cash In (DR): +${formatBirr(session.totalDRMinor)}\n")
            append("Cash Out (CR): -${formatBirr(session.totalCRMinor)}\n")
            append("Ending Balance: ${formatBirr(session.totalCountedMinor)}")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildLaunchIntent(context))
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                ("closing_${session.sessionId}").hashCode(),
                notification
            )
        } catch (_: SecurityException) {
            // Ignored gracefully when permission was revoked concurrently
        }
    }
}

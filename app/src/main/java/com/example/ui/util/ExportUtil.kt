package com.example.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.local.entities.CashSession
import java.io.File

fun exportSessionsToCsv(context: Context, sessions: List<CashSession>) {
    val filename = "CashSessions_${System.currentTimeMillis()}.csv"
    val file = File(context.cacheDir, filename)
    
    val header = "Business Date,Saved At,Session ID,Branch,Staff,Opening Balance,Total DR,Total CR,Expected Ending,Bundle Cash,Petty Cash,Total Counted,Difference,Reconciliation Status,Workflow Status,Is Final,Is Approved,Approved By,Sync Status\n"
    val sb = StringBuilder(header)
    
    sessions.forEach { s ->
        val businessDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(if (s.businessDate > 0L) s.businessDate else s.createdAt))
        val savedAtStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(s.createdAt))
        sb.append("$businessDateStr,")
        sb.append("$savedAtStr,")
        sb.append("${s.sessionId},")
        sb.append("${s.branchId},")
        sb.append("${s.staffId},")
        sb.append("${s.openingBalanceMinor / 100.0},")
        sb.append("${s.totalDRMinor / 100.0},")
        sb.append("${s.totalCRMinor / 100.0},")
        sb.append("${s.expectedEndingMinor / 100.0},")
        sb.append("${s.bundleCashMinor / 100.0},")
        sb.append("${s.pettyCashMinor / 100.0},")
        sb.append("${s.totalCountedMinor / 100.0},")
        sb.append("${s.differenceMinor / 100.0},")
        sb.append("${s.reconciliationStatus},")
        sb.append("${s.status},")
        sb.append("${s.isFinal},")
        sb.append("${s.isApproved},")
        sb.append("${s.approvedBy ?: ""},")
        sb.append("${s.syncStatus}\n")
    }
    
    file.writeText(sb.toString())
    
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export History"))
}

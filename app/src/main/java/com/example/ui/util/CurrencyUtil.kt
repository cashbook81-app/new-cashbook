package com.example.ui.util

import java.util.Locale
import kotlin.math.abs

fun formatBirr(minor: Long): String {
    val prefix = if (minor < 0L) "-ETB " else "ETB "
    val whole = abs(minor) / 100.0
    return String.format(Locale.US, "%s%,.2f", prefix, whole)
}

fun formatSignedBirr(minor: Long): String {
    val prefix = when {
        minor > 0L -> "+ETB "
        minor < 0L -> "-ETB "
        else -> "ETB "
    }
    val whole = abs(minor) / 100.0
    return String.format(Locale.US, "%s%,.2f", prefix, whole)
}

fun formatAmount(minor: Long): String {
    val birr = minor / 100.0
    return String.format(Locale.US, "%,.2f", birr)
}

fun formatDenomination(denominationBirr: Double): String {
    return if (denominationBirr >= 1.0 && denominationBirr % 1.0 == 0.0) {
        denominationBirr.toInt().toString()
    } else {
        String.format(Locale.US, "%.2f", denominationBirr)
    }
}

package com.example.ui.util

import java.util.Locale

fun formatBirr(minor: Long): String {
    val birr = minor / 100.0
    return String.format(Locale.US, "ETB %,.2f", birr)
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

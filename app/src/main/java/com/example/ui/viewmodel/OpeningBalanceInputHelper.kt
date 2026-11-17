package com.example.ui.viewmodel

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

const val MAX_OPENING_BALANCE_MINOR: Long = 99_999_999_999L // ETB 999,999,999.99

sealed interface EtbParseResult {
    data object Empty : EtbParseResult
    data class Valid(
        val normalizedRaw: String,
        val minor: Long,
        val birr: Double,
        val isZero: Boolean
    ) : EtbParseResult

    data class Invalid(val message: String) : EtbParseResult
}

/**
 * Parses and validates ETB opening balance text using exact BigDecimal minor-unit math.
 * Rejects negative numbers, invalid characters, >2 decimal places, and overflow amounts.
 */
fun parseAndValidateEtbInput(rawInput: String): EtbParseResult {
    val cleaned = rawInput
        .replace(",", "")
        .replace(" ", "")
        .trim()

    if (cleaned.isEmpty()) {
        return EtbParseResult.Empty
    }

    if (cleaned.contains('-')) {
        return EtbParseResult.Invalid("Negative opening balances are not allowed.")
    }

    if (cleaned.any { !it.isDigit() && it != '.' }) {
        return EtbParseResult.Invalid("Enter numbers and up to one decimal point only.")
    }

    val dotCount = cleaned.count { it == '.' }
    if (dotCount > 1) {
        return EtbParseResult.Invalid("Enter only one decimal point.")
    }

    val parts = cleaned.split('.')
    val intPartRaw = parts[0]
    val fracPartRaw = if (parts.size == 2) parts[1] else ""

    if (fracPartRaw.length > 2) {
        return EtbParseResult.Invalid("ETB supports up to 2 decimal places (santim).")
    }

    val normalizedInt = when {
        intPartRaw.isEmpty() -> "0"
        intPartRaw.length > 1 -> intPartRaw.trimStart('0').ifEmpty { "0" }
        else -> intPartRaw
    }

    if (normalizedInt.length > 9) {
        return EtbParseResult.Invalid("Amount exceeds the maximum branch limit (ETB 999,999,999.99).")
    }

    val decimalCandidate = when {
        dotCount == 1 -> "$normalizedInt.$fracPartRaw"
        else -> normalizedInt
    }

    val numericCandidate = if (decimalCandidate.endsWith(".")) {
        "${decimalCandidate}0"
    } else {
        decimalCandidate
    }

    val minorLong = try {
        val bd = BigDecimal(numericCandidate)
        val minorBd = bd.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY)
        if (minorBd < BigDecimal.ZERO) {
            return EtbParseResult.Invalid("Negative opening balances are not allowed.")
        }
        if (minorBd > BigDecimal.valueOf(MAX_OPENING_BALANCE_MINOR)) {
            return EtbParseResult.Invalid("Amount exceeds the maximum branch limit (ETB 999,999,999.99).")
        }
        minorBd.longValueExact()
    } catch (_: Exception) {
        return EtbParseResult.Invalid("Enter a valid ETB amount.")
    }

    val rawPreservingTyping = when {
        intPartRaw.isEmpty() && dotCount == 1 -> ".$fracPartRaw"
        dotCount == 1 -> "$normalizedInt.$fracPartRaw"
        else -> normalizedInt
    }

    return EtbParseResult.Valid(
        normalizedRaw = rawPreservingTyping,
        minor = minorLong,
        birr = minorLong / 100.0,
        isZero = minorLong == 0L
    )
}

fun formatMinorAsEditableEtb(minor: Long): String {
    if (minor <= 0L) return ""
    val whole = minor / 100L
    val cents = (minor % 100L).toInt()
    return String.format(Locale.US, "%d.%02d", whole, cents)
}

fun formatMinorAsFixedTwoDecimals(minor: Long): String {
    val safeMinor = minor.coerceAtLeast(0L)
    val whole = safeMinor / 100L
    val cents = (safeMinor % 100L).toInt()
    return String.format(Locale.US, "%d.%02d", whole, cents)
}

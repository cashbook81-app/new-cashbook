package com.example.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class CashCalculatorLogicTest {

    @Test
    fun bundleFormula_oneBundleOf200_equals200000Birr() {
        val entry = DenominationEntry(
            denominationBirr = 200.0,
            bundles = 1,
            notesPerBundle = 1000,
            looseNotes = 0,
            isBundle = true
        )
        assertEquals(1000L, entry.totalQuantityLong)
        // 200.00 ETB = 20,000 minor; 1,000 notes = 20,000,000 minor = 200,000.00 ETB
        assertEquals(20_000_000L, entry.subtotalMinor)
    }

    @Test
    fun pettyCashFormula_quantityMultipliesByDenomination() {
        val entry = DenominationEntry(
            denominationBirr = 50.0,
            quantity = 12,
            isBundle = false
        )
        assertEquals(12L, entry.totalQuantityLong)
        assertEquals(60_000L, entry.subtotalMinor)
    }
}

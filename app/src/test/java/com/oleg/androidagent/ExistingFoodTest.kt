package com.oleg.androidagent

import org.junit.Assert.*
import org.junit.Test

class ExistingFoodTest {
    @Test fun sizeAndBrandMustMatchExactly() {
        val rows = listOf(FatUi.Row("Ролл с курицей", "200g"), FatUi.Row("Ролл с курицей small", "200g"), FatUi.Row("Ролл с курицей large", "300g"))
        assertEquals(1, ExistingFood.exactIndex("ролл с курицей SMALL", rows))
        try { ExistingFood.exactIndex("Ролл с курицей medium", rows); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun duplicateExactNamesCannotBeChosen() {
        val row = FatUi.Row("Ролл с курицей small", "200g")
        try { ExistingFood.exactIndex(row.name, listOf(row, row)); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun uniqueFullWordMatchPreservesSizeAndRejectsAmbiguousBrands() {
        val small = FatUi.Row("Ролл Цезарь с Курицей (SMALL)", "200g")
        val large = FatUi.Row("Ролл Цезарь с Курицей (LARGE)", "300g")
        assertEquals(0, ExistingFood.exactIndex("ролл с курицей small", listOf(small, large)))
        try { ExistingFood.exactIndex("ролл с курицей small", listOf(small, FatUi.Row(small.name + " Brand", "200g"))); fail() }
        catch (_: IllegalArgumentException) {}
    }
}

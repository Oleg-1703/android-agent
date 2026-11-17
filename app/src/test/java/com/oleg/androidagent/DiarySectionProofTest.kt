package com.oleg.androidagent

import org.junit.Assert.*
import org.junit.Test

class DiarySectionProofTest {
    @Test fun onlyAnAbsentCounterCanUseTheDetailedViewProof() {
        assertNull(DiarySectionProof.count(""))
        assertEquals(3, DiarySectionProof.count("3 items"))
        assertEquals(3, DiarySectionProof.count("3\u00a0продукта"))
        assertNull(DiarySectionProof.count("Carbs: 12.5g, Prot: 7.25g, Target: 10%"))
        assertNull(DiarySectionProof.count("Углеводы: 12,5г, Белки: 7,25г, Цель: 10%"))
        try { DiarySectionProof.count("unrecognised visible counter"); fail() }
        catch (_: DiaryReadFailure) {}
    }
    @Test fun detailedViewNeedsBothEntriesAndItsExpandedFooter() {
        assertTrue(DiarySectionProof.complete(null, 3, true, false))
        assertFalse(DiarySectionProof.complete(null, 3, false, false))
        assertFalse(DiarySectionProof.complete(null, 0, true, false))
        assertFalse(DiarySectionProof.complete(null, 0, false, false))
    }
    @Test fun visibleCountStillRejectsMissingEntries() {
        assertTrue(DiarySectionProof.complete(3, 3, true, false))
        assertFalse(DiarySectionProof.complete(3, 2, true, false))
        assertFalse(DiarySectionProof.complete(3, 4, true, false))
        assertTrue(DiarySectionProof.complete(0, 0, false, false))
    }
    @Test fun unknownRowsNeverPassEitherDisplayMode() {
        assertFalse(DiarySectionProof.complete(3, 3, true, true))
        assertFalse(DiarySectionProof.complete(null, 3, true, true))
    }
}

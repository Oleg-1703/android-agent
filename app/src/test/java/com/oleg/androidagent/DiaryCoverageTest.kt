package com.oleg.androidagent

import org.junit.Assert.*
import org.junit.Test

class DiaryCoverageTest {
    @Test fun clippedRowRequiresACompleteObservationOnReturnPass() {
        val coverage = DiaryCoverage<String>()
        assertFalse(coverage.add(0, 4, listOf("heading", null)))
        assertFalse(coverage.add(2, 4, listOf("boundary", "other")))
        assertEquals("1", coverage.missing())
        assertTrue(coverage.add(0, 4, listOf("heading", "entry")))
        assertEquals(listOf("heading", "entry", "boundary", "other"), coverage.rows.values.toList())
    }
    @Test fun unknownEmptyRowIsRetainedInsteadOfBeingDropped() {
        val coverage = DiaryCoverage<String>()
        assertTrue(coverage.add(0, 3, listOf("heading", "boundary", "other")))
        assertEquals("other", coverage.rows[2])
    }
    @Test fun changedRowsOrAdapterCountCannotBeMerged() {
        for (changeCount in listOf(false, true)) {
            val coverage = DiaryCoverage<String>()
            coverage.add(0, 3, listOf("heading", "entry"))
            try {
                coverage.add(1, if (changeCount) 4 else 3, listOf("different"))
                fail("Changed diary accepted")
            } catch (_: IllegalArgumentException) {}
        }
    }
}

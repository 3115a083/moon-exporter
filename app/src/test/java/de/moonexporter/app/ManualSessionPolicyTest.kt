package de.moonexporter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSessionPolicyTest {
    @Test
    fun `manual retry on same target replaces stale session payload`() {
        assertEquals(
            ManualSessionDecision.SUPERSEDE_AND_CREATE,
            ManualSessionPolicy.decide("content://readest", "content://readest"),
        )
    }

    @Test
    fun `manual retry on different target is blocked`() {
        assertEquals(
            ManualSessionDecision.BLOCK_OTHER_TARGET,
            ManualSessionPolicy.decide("content://old", "content://new"),
        )
    }

    @Test
    fun `automatic resume requires every stored book source`() {
        assertTrue(ManualSessionPolicy.canAutoResume(listOf(true, true)))
        assertFalse(ManualSessionPolicy.canAutoResume(listOf(true, false)))
        assertFalse(ManualSessionPolicy.canAutoResume(emptyList()))
    }
}

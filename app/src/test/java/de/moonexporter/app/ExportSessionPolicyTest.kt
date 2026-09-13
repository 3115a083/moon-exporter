package de.moonexporter.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportSessionPolicyTest {
    @Test
    fun `explicit export replaces unfinished session for same target`() {
        assertEquals(
            ExplicitSessionAction.SUPERSEDE_AND_CREATE,
            explicitSessionAction("content://readest/books", "content://readest/books"),
        )
    }

    @Test
    fun `explicit export blocks unfinished session for different target`() {
        assertEquals(
            ExplicitSessionAction.BLOCK_OTHER_TARGET,
            explicitSessionAction("content://old/books", "content://new/books"),
        )
    }

    @Test
    fun `explicit export creates session when none exists`() {
        assertEquals(
            ExplicitSessionAction.CREATE_NEW,
            explicitSessionAction(null, "content://readest/books"),
        )
    }
}

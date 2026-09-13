package de.moonexporter.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadestIdentityTest {
    @Test
    fun `source hash is used when existing target was not newly created or found by metadata`() {
        val exactSourceHash = "0123456789abcdef0123456789abcdef"
        assertEquals(
            exactSourceHash,
            ReadestIdentity.chooseHash(
                newHash = null,
                knownHash = null,
                libraryHash = null,
                sourceHash = exactSourceHash,
            ),
        )
    }

    @Test
    fun `existing known hash wins before expensive source fallback`() {
        assertEquals(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ReadestIdentity.chooseHash(
                newHash = null,
                knownHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                libraryHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                sourceHash = "cccccccccccccccccccccccccccccccc",
            ),
        )
    }
}

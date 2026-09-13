package de.moonexporter.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadestIdentityTest {
    @Test
    fun `source hash is used when no direct target identity is available`() {
        val exactSourceHash = "0123456789abcdef0123456789abcdef"
        assertEquals(
            exactSourceHash,
            ReadestIdentity.chooseHash(
                newHash = null,
                knownHash = null,
                recentHash = null,
                libraryHash = null,
                sourceHash = exactSourceHash,
            ),
        )
    }

    @Test
    fun `recently committed folder wins before metadata or source fallback`() {
        assertEquals(
            "dddddddddddddddddddddddddddddddd",
            ReadestIdentity.chooseHash(
                newHash = null,
                knownHash = null,
                recentHash = "dddddddddddddddddddddddddddddddd",
                libraryHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                sourceHash = "cccccccccccccccccccccccccccccccc",
            ),
        )
    }

    @Test
    fun `existing known hash wins before recently touched and expensive fallbacks`() {
        assertEquals(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ReadestIdentity.chooseHash(
                newHash = null,
                knownHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                recentHash = "dddddddddddddddddddddddddddddddd",
                libraryHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                sourceHash = "cccccccccccccccccccccccccccccccc",
            ),
        )
    }
}

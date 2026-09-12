package de.moonexporter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class UtilityTest {
    @Test fun `parses synthetic epub position`() {
        val p = parsePo("1700000000000*21@0#4826:11.1%")
        assertEquals(21, p.chapterOrPage)
        assertEquals(0, p.section)
        assertEquals(4826L, p.offset)
        assertEquals(11.1, p.percent ?: 0.0, 0.0001)
    }

    @Test fun `normalizes CWA base without duplicate kosync`() {
        assertEquals("https://192.0.2.1", KoSyncClient.normalizeBaseUrl("https://192.0.2.1/kosync/", ServerType.CALIBRE_WEB_AUTOMATED))
        assertEquals("https://192.0.2.1", KoSyncClient.normalizeBaseUrl("https://192.0.2.1", ServerType.CALIBRE_WEB_AUTOMATED))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects cleartext server url`() {
        KoSyncClient.normalizeBaseUrl("http://192.0.2.1", ServerType.STANDARD_KOSYNC)
    }

    @Test fun `partial md5 uses KOReader sampling offsets`() {
        val bytes = ByteArray((1 shl 20) + 2048) { (it % 251).toByte() }
        val hash = partialMd5(ByteArrayInputStream(bytes))
        assertEquals(32, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{32}")))
        assertEquals(0L, PARTIAL_MD5_OFFSETS.first())
        assertEquals(1L shl 30, PARTIAL_MD5_OFFSETS.last())
    }

    @Test fun `article sorting ignores common prefixes`() {
        assertEquals(sortTitle("The Example Book"), sortTitle("Example Book"))
        assertEquals(sortTitle("Der Beispielroman"), sortTitle("Beispielroman"))
    }

    @Test fun `mrpro tag mapping accepts one based and zero based candidates`() {
        val names = listOf("mrbooks.db", "positions10.xml", "Synthetic Book.epub")
        assertEquals(listOf("mrbooks.db", "positions10.xml", "1.tag"), MoonImporter.mrproLogicalCandidates("1.tag", names))
        assertEquals(listOf("mrbooks.db", "0.tag"), MoonImporter.mrproLogicalCandidates("0.tag", names))
    }

    @Test fun `detects sqlite database signature in numbered tag`() {
        val valid = "SQLite format 3\u0000synthetic".toByteArray(Charsets.US_ASCII)
        val invalid = "not a sqlite file".toByteArray(Charsets.US_ASCII)
        assertTrue(MoonImporter.looksLikeSqliteHeader(valid))
        assertFalse(MoonImporter.looksLikeSqliteHeader(invalid))
    }
}

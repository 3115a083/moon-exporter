package de.moonexporter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UtilityTest {
    @Test fun `parses synthetic epub position`() {
        val p = parsePo("1700000000000*21@0#4826:11.1%")
        assertEquals(21, p.chapterOrPage)
        assertEquals(0, p.section)
        assertEquals(4826L, p.offset)
        assertEquals(11.1, p.percent ?: 0.0, 0.0001)
    }

    @Test fun `parses positions10 position without timestamp`() {
        val p = ProgressRecovery.parseMoonPosition("21@0#4826:11.1%")!!
        assertEquals(null, p.timestampMs)
        assertEquals(21, p.chapterOrPage)
        assertEquals(0, p.section)
        assertEquals(4826L, p.offset)
        assertEquals(11.1, p.percent ?: 0.0, 0.0001)
    }

    @Test fun `parses positions10 shared preferences xml`() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
              <string name="/sdcard/Books/A &amp; B.epub">14@0#2952:23.0%</string>
              <string name="/sdcard/Books/Paper.pdf">42:56.5%</string>
            </map>
        """.trimIndent()
        val parsed = ProgressRecovery.parsePositions10Xml(xml)
        assertEquals(2, parsed.size)
        assertEquals(23.0, parsed["/sdcard/Books/A & B.epub"]?.percent ?: 0.0, 0.0001)
        assertEquals(14, parsed["/sdcard/Books/A & B.epub"]?.chapterOrPage)
        assertEquals(42, parsed["/sdcard/Books/Paper.pdf"]?.chapterOrPage)
        assertEquals(56.5, parsed["/sdcard/Books/Paper.pdf"]?.percent ?: 0.0, 0.0001)
    }

    @Test fun `rejects unsafe positions10 xml declarations`() {
        val xml = "<!DOCTYPE map [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><map><string name='x'>&xxe;</string></map>"
        assertTrue(ProgressRecovery.parsePositions10Xml(xml).isEmpty())
    }

    @Test fun `normalizes CWA base without duplicate kosync`() {
        assertEquals("https://192.0.2.1", KoSyncClient.normalizeBaseUrl("https://192.0.2.1/kosync/", ServerType.CALIBRE_WEB_AUTOMATED))
        assertEquals("https://192.0.2.1", KoSyncClient.normalizeBaseUrl("https://192.0.2.1", ServerType.CALIBRE_WEB_AUTOMATED))
    }

    @Test fun `normalizes BookLore base without duplicate koreader path`() {
        assertEquals("https://books.example.test", KoSyncClient.normalizeBaseUrl("https://books.example.test/api/koreader/", ServerType.BOOKLORE))
        val config = SyncConfig(ServerType.BOOKLORE, "https://books.example.test", "user", "secret")
        assertEquals("https://books.example.test/api/koreader", KoSyncClient.endpointRoot(config))
    }

    @Test fun `allows http for local network only`() {
        assertEquals("http://192.168.1.50", KoSyncClient.normalizeBaseUrl("http://192.168.1.50", ServerType.STANDARD_KOSYNC))
        assertEquals("http://booklore.local", KoSyncClient.normalizeBaseUrl("http://booklore.local", ServerType.STANDARD_KOSYNC))
        assertTrue(KoSyncClient.isLocalNetworkHost("10.0.0.2"))
        assertTrue(KoSyncClient.isLocalNetworkHost("172.16.2.5"))
        assertFalse(KoSyncClient.isLocalNetworkHost("8.8.8.8"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects public cleartext server url`() {
        KoSyncClient.normalizeBaseUrl("http://example.com", ServerType.STANDARD_KOSYNC)
    }

    @Test fun `partial md5 uses KOReader sampling offsets`() {
        val bytes = ByteArray((1 shl 20) + 2048) { (it % 251).toByte() }
        val hash = partialMd5(ByteArrayInputStream(bytes))
        assertEquals(32, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{32}")))
        assertEquals(1L shl 9, PARTIAL_MD5_OFFSETS.first())
        assertEquals(1L shl 31, PARTIAL_MD5_OFFSETS.last())
    }

    @Test fun `Readest partial md5 matches Readest sampling`() {
        val bytes = ByteArray(5_000_000) { (it % 251).toByte() }
        assertEquals("94c784935ab8dda597646e9bc916bbad", ReadestDirectExporter.readestPartialMd5(ByteArrayInputStream(bytes)))
    }

    @Test fun `Readest highlight resolver creates a real text range CFI`() {
        val epub = syntheticEpub("<html xmlns='http://www.w3.org/1999/xhtml'><head/><body><p id='p1'>Alpha highlighted phrase omega.</p></body></html>")
        try {
            ReadestCfiResolver(epub, listOf("OEBPS/chapter.xhtml")).use { resolver ->
                val result = resolver.resolve("highlighted phrase", preferredSpine = 0, preferredPosition = null)
                assertNotNull(result)
                assertTrue(result!!.cfi.startsWith("epubcfi(/6/2!"))
                assertTrue(result.cfi.contains(","))
                assertTrue(result.cfi.contains(":6"))
                assertTrue(result.cfi.contains(":24"))
            }
        } finally {
            epub.delete()
        }
    }

    @Test fun `Readest highlight resolver accepts real world XHTML entities and inline markup`() {
        val epub = syntheticEpub("<html xmlns='http://www.w3.org/1999/xhtml'><head/><body><p>Alpha&nbsp;highlighted <em>phrase</em> omega.</p></body></html>")
        try {
            ReadestCfiResolver(epub, listOf("OEBPS/chapter.xhtml")).use { resolver ->
                val result = resolver.resolve("highlighted phrase", preferredSpine = 0, preferredPosition = null)
                assertNotNull(result)
                assertTrue(result!!.cfi.startsWith("epubcfi(/6/2!"))
                assertTrue(result.cfi.count { it == ',' } == 2)
            }
        } finally {
            epub.delete()
        }
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

    @Test fun `recognizes opaque numeric and hash book names`() {
        assertTrue(ProgressRecovery.looksOpaque("123456789.epub"))
        assertTrue(ProgressRecovery.looksOpaque("0123456789abcdef0123456789abcdef.epub"))
        assertFalse(ProgressRecovery.looksOpaque("A Real Book Title.epub"))
    }

    private fun syntheticEpub(xhtml: String): File {
        val epub = File.createTempFile("readest-cfi-test-", ".epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("OEBPS/chapter.xhtml"))
            zip.write(xhtml.toByteArray())
            zip.closeEntry()
        }
        return epub
    }
}

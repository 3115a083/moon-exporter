package de.moonexporter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class HardeningTest {
    @Test fun `shared formats cover Moon and Readest intersection`() {
        assertEquals(
            setOf("epub", "pdf", "mobi", "azw", "azw3", "fb2", "cbz", "zip", "txt", "md"),
            BookFormats.readestCompatible,
        )
        assertTrue(BookFormats.isReadestCompatible("comic.CBZ"))
        assertTrue(BookFormats.isReadestCompatible("book.azw3"))
        assertFalse(BookFormats.isReadestCompatible("comic.cbr"))
    }

    @Test fun `response reader stops before unbounded server body`() {
        val body = "x".repeat(100_000)
        assertEquals(16_384, KoSyncClient.readBounded(StringReader(body), 16_384).length)
    }

    @Test fun `cleartext address policy accepts private literals and rejects public literals`() {
        assertTrue(KoSyncClient.isLocalNetworkHost("127.0.0.1"))
        assertTrue(KoSyncClient.isLocalNetworkHost("192.168.1.10"))
        assertTrue(KoSyncClient.isLocalNetworkHost("10.0.0.8"))
        assertTrue(KoSyncClient.isLocalNetworkHost("fd00::1"))
        assertFalse(KoSyncClient.isLocalNetworkHost("8.8.8.8"))
        assertFalse(KoSyncClient.isLocalNetworkHost("1.1.1.1"))
    }

    @Test fun `foreground service rewrites per-book one-of-one progress to session progress`() {
        assertEquals("Buch 3/8 · 2/7 · Hash", ExportService.overallBookProgress("Buch 1/1 · 2/7 · Hash", 2, 8))
        assertEquals("Book 4/9 · 5/7 · Copy", ExportService.overallBookProgress("Book 1/1 · 5/7 · Copy", 3, 9))
    }

    @Test fun `backup name exposes date and device`() {
        val descriptor = BackupConsolidator.describe("2024-01-19 Boox Backup (Nova3Color).mrpro")
        assertEquals("2024-01-19", descriptor.date.toString())
        assertEquals("Nova3Color", descriptor.deviceName)
    }

    @Test fun `newest timestamp wins while annotations are unioned`() {
        fun book(percent: Double, timestamp: Long, noteId: Long) = BookItem(
            key = "book.epub",
            sourceFile = "/Books/book.epub",
            originalName = "/Books/book",
            extension = "epub",
            title = "Book",
            position = MoonPosition("", timestampMs = timestamp, percent = percent),
            annotation = AnnotationData(records = listOf(AnnotationRecord(id = noteId, original = "q$noteId", timestampMs = timestamp))),
        )
        val merged = BackupConsolidator.consolidate(listOf(listOf(book(20.0, 100, 1)), listOf(book(60.0, 200, 2))))
        assertEquals(1, merged.size)
        assertEquals(60.0, merged.single().position?.percent ?: -1.0, 0.0)
        assertEquals(2, merged.single().annotation?.records?.size)
    }
}

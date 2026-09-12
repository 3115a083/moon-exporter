package de.moonexporter.app

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal object BackupMetadata {
    private const val MAX_EPUB_ENTRY_BYTES = 4 * 1024 * 1024
    private const val MAX_EPUB_ENTRIES = 800
    private const val MAX_CAPTURED_BYTES = 20 * 1024 * 1024

    suspend fun enrich(context: Context, books: List<BookItem>, onProgress: (String) -> Unit): List<BookItem> = withContext(Dispatchers.IO) {
        val wanted = books.mapNotNull { book ->
            val match = book.epub ?: return@mapNotNull null
            val backup = match.backupUri ?: return@mapNotNull null
            val entry = match.archiveEntryName ?: return@mapNotNull null
            if (!match.fileName.endsWith(".epub", true)) return@mapNotNull null
            Triple(entry, backup, book.key)
        }
        if (wanted.isEmpty()) return@withContext books

        val byBackup = wanted.groupBy { it.second }
        val enriched = linkedMapOf<String, EpubMatch>()
        var done = 0
        for ((backupUri, entries) in byBackup) {
            val wantedEntries = entries.associateBy { it.first }
            context.contentResolver.openInputStream(backupUri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { outer ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val outerEntry = outer.nextEntry ?: break
                        if (outerEntry.isDirectory) continue
                        val requested = wantedEntries[outerEntry.name] ?: continue
                        val current = books.firstOrNull { it.key == requested.third }?.epub ?: continue
                        val meta = inspectNestedEpub(NonClosingInputStream(outer), current.fileName)
                        enriched[requested.third] = current.copy(
                            title = meta.title ?: current.title,
                            author = meta.author ?: current.author,
                            isbn = meta.isbn ?: current.isbn,
                            cover = meta.cover ?: current.cover,
                        )
                        done++
                        onProgress(tr("Cover und Metadaten $done/${wanted.size}", "Covers and metadata $done/${wanted.size}"))
                    }
                }
            }
        }

        books.map { book ->
            val match = enriched[book.key] ?: return@map book
            val betterTitle = match.title?.takeIf { it.isNotBlank() && !ProgressRecovery.looksOpaque(it) }
            book.copy(
                epub = match,
                title = betterTitle ?: book.title,
                author = book.author ?: match.author,
                isbn = book.isbn ?: match.isbn,
            )
        }
    }

    private data class Meta(val title: String?, val author: String?, val isbn: String?, val cover: android.graphics.Bitmap?)
    private data class ManifestItem(val id: String?, val href: String?, val properties: String?, val mediaType: String?)

    private fun inspectNestedEpub(input: java.io.InputStream, fallbackName: String): Meta {
        val files = linkedMapOf<String, ByteArray>()
        var captured = 0
        ZipInputStream(input).use { zip ->
            var count = 0
            while (count++ < MAX_EPUB_ENTRIES && captured < MAX_CAPTURED_BYTES) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val clean = entry.name.replace('\\', '/').trimStart('/')
                if (clean.startsWith("../") || clean.contains("/../")) continue
                val lower = clean.lowercase(Locale.ROOT)
                if (!lower.endsWith(".opf") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png") && !lower.endsWith(".webp")) continue
                val bytes = readBounded(zip, MAX_EPUB_ENTRY_BYTES) ?: continue
                captured += bytes.size
                if (captured <= MAX_CAPTURED_BYTES) files[clean] = bytes
            }
        }

        val opfEntry = files.entries.firstOrNull { it.key.endsWith(".opf", true) }
            ?: return Meta(fallbackName.substringBeforeLast('.'), null, null, null)
        val opf = opfEntry.value.toString(Charsets.UTF_8)
        fun tag(name: String): String? = Regex("<$name[^>]*>(.*?)</$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(opf)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), "")?.trim()

        val title = tag("dc:title")
        val author = tag("dc:creator")
        val isbn = Regex("<dc:identifier[^>]*>([^<]+)</dc:identifier>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(opf).map { it.groupValues[1] }.mapNotNull { value ->
                Regex("(?:97[89][ -]?)?[0-9][0-9 -]{8,15}[0-9Xx]").find(value)?.value?.replace(Regex("[ -]"), "")
            }.firstOrNull()

        val manifestItems = Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).map { match ->
            val attrs = attributes(match.value)
            ManifestItem(attrs["id"], attrs["href"], attrs["properties"], attrs["media-type"])
        }.toList()
        val coverId = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).map { attributes(it.value) }
            .firstOrNull { attrs -> attrs["name"]?.equals("cover", true) == true }?.get("content")

        val coverHref = manifestItems.firstOrNull { item -> item.properties?.split(Regex("\\s+"))?.any { it.equals("cover-image", true) } == true }?.href
            ?: coverId?.let { id -> manifestItems.firstOrNull { it.id == id }?.href }
            ?: manifestItems.firstOrNull { item ->
                item.mediaType?.startsWith("image/", true) == true && item.href?.substringAfterLast('/')?.contains("cover", true) == true
            }?.href

        val base = opfEntry.key.substringBeforeLast('/', "")
        val resolved = coverHref?.let { resolveArchivePath(base, it) }
        val image = resolved?.let { path -> files[path] ?: files.entries.firstOrNull { it.key.equals(path, true) }?.value }
        val bitmap = image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        return Meta(title, author, isbn, bitmap)
    }

    private fun attributes(tag: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        Regex("([A-Za-z0-9:_-]+)\\s*=\\s*[\"']([^\"']*)[\"']").findAll(tag).forEach { match ->
            out[match.groupValues[1].lowercase(Locale.ROOT)] = match.groupValues[2]
        }
        return out
    }

    private fun resolveArchivePath(base: String, href: String): String? {
        val parts = mutableListOf<String>()
        if (base.isNotBlank()) parts += base.split('/').filter { it.isNotBlank() }
        href.substringBefore('#').substringBefore('?').replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else return null
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private class NonClosingInputStream(input: java.io.InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

package de.moonexporter.app

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal object BackupMetadata {
    private const val MAX_EPUB_ENTRY_BYTES = 4 * 1024 * 1024
    private const val MAX_EPUB_ENTRIES = 800

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
                        val meta = inspectNestedEpub(outer, current.fileName)
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
            book.copy(
                epub = match,
                title = match.title?.takeIf { it.isNotBlank() } ?: book.title,
                author = book.author ?: match.author,
                isbn = book.isbn ?: match.isbn,
            )
        }
    }

    private data class Meta(val title: String?, val author: String?, val isbn: String?, val cover: android.graphics.Bitmap?)

    private fun inspectNestedEpub(input: java.io.InputStream, fallbackName: String): Meta {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (files.size < MAX_EPUB_ENTRIES) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val clean = entry.name.replace('\\', '/').trimStart('/')
                if (clean.contains("../") || clean.startsWith("..")) continue
                val lower = clean.lowercase(Locale.ROOT)
                if (!lower.endsWith(".opf") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png") && !lower.endsWith(".webp")) continue
                readBounded(zip, MAX_EPUB_ENTRY_BYTES)?.let { files[clean] = it }
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
        val coverId = Regex("<meta[^>]+name=[\"']cover[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.getOrNull(1)
        val href = coverId?.let { id -> Regex("<item[^>]+id=[\"']${Regex.escape(id)}[\"'][^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.getOrNull(1) }
            ?: Regex("<item[^>]+properties=[\"'][^\"']*cover-image[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.getOrNull(1)
        val base = opfEntry.key.substringBeforeLast('/', "")
        val cleanHref = href?.replace('\\', '/')?.trimStart('/')
        val full = if (cleanHref != null) listOf(base, cleanHref).filter { it.isNotBlank() }.joinToString("/") else null
        val image = full?.takeIf { !it.contains("../") }?.let { files[it] ?: files.entries.firstOrNull { e -> e.key.endsWith(cleanHref ?: "") }?.value }
        val bitmap = image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        return Meta(title, author, isbn, bitmap)
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

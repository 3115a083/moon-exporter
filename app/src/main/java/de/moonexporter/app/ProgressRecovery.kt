package de.moonexporter.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal object ProgressRecovery {
    private const val MAX_META_BYTES = 4 * 1024 * 1024
    private const val MAX_PO_BYTES = 64 * 1024
    private const val MAX_FILES = 40_000

    suspend fun recover(context: Context, backupUri: Uri, books: List<BookItem>, onProgress: (String) -> Unit): List<BookItem> = withContext(Dispatchers.IO) {
        if (books.isEmpty()) return@withContext books
        val index = readIndex(context, backupUri)
        val recovered = linkedMapOf<String, MoonPosition>()
        var entries = 0

        context.contentResolver.openInputStream(backupUri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (++entries > MAX_FILES) break
                    val clean = entry.name.replace('\\', '/').trimStart('/')
                    if (clean.contains("../") || clean.startsWith("..")) continue
                    val logical = logicalName(clean, index.names, index.zeroBased)
                    when {
                        logical.endsWith(".po", true) -> {
                            val bytes = readBounded(zip, MAX_PO_BYTES) ?: continue
                            addPosition(recovered, logical.removeSuffixIgnoreCase(".po"), bytes.toString(Charsets.UTF_8))
                        }
                        logical.endsWith("positions10.xml", true) -> {
                            val bytes = readBounded(zip, MAX_META_BYTES) ?: continue
                            parsePositionsPreferences(bytes, recovered)
                        }
                    }
                }
            }
        }

        var matched = 0
        val result = books.map { book ->
            val recoveredPosition = bookAliases(book).firstNotNullOfOrNull { recovered[it] }
            if (recoveredPosition != null && (book.position == null || newer(recoveredPosition, book.position))) {
                matched++
                book.copy(position = recoveredPosition)
            } else book
        }
        onProgress(tr("Lesefortschritt: $matched Zuordnungen aus Moon+ Positionsdaten", "Reading progress: $matched matches from Moon+ position data"))
        result
    }

    private fun newer(a: MoonPosition, b: MoonPosition): Boolean = (a.timestampMs ?: 0L) >= (b.timestampMs ?: 0L)

    private fun addPosition(out: MutableMap<String, MoonPosition>, source: String, raw: String) {
        val position = parsePo(raw)
        if (position.percent == null && position.chapterOrPage == null) return
        positionAliases(source).forEach { alias ->
            val old = out[alias]
            if (old == null || newer(position, old)) out[alias] = position
        }
    }

    internal fun parsePositionsPreferences(bytes: ByteArray, out: MutableMap<String, MoonPosition>) {
        val xml = bytes.toString(Charsets.UTF_8)
        if (Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return
        runCatching {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser()
            runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) }
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var type = parser.eventType
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.name.equals("string", true)) {
                    val key = (0 until parser.attributeCount).firstNotNullOfOrNull { i ->
                        if (parser.getAttributeName(i).equals("name", true)) parser.getAttributeValue(i) else null
                    }
                    val value = runCatching { parser.nextText() }.getOrNull()
                    if (!key.isNullOrBlank() && !value.isNullOrBlank()) addPosition(out, key, value)
                }
                type = parser.next()
            }
        }
    }

    fun reconstructTitles(books: List<BookItem>): List<BookItem> = books.map { book ->
        val current = book.title.trim()
        val metadataTitle = book.epub?.title?.trim().orEmpty()
        when {
            metadataTitle.isNotBlank() && !looksOpaque(metadataTitle) -> book.copy(title = metadataTitle, author = book.author ?: book.epub?.author, isbn = book.isbn ?: book.epub?.isbn)
            !looksOpaque(current) -> book
            else -> {
                val id = book.originalName.substringAfterLast('/').take(12)
                book.copy(title = tr("Unbekanntes Buch · $id", "Unknown book · $id"), isCryptic = true)
            }
        }
    }

    internal fun looksOpaque(value: String): Boolean {
        val base = value.substringAfterLast('/').substringBeforeLast('.').trim()
        if (base.isBlank()) return true
        if (base.matches(Regex("^\\d{5,}$"))) return true
        if (base.matches(Regex("^[0-9a-fA-F]{16,}$"))) return true
        if (base.matches(Regex("^[0-9a-fA-F-]{32,}$"))) return true
        return false
    }

    private data class Index(val names: List<String>, val zeroBased: Boolean)

    private fun readIndex(context: Context, uri: Uri): Index {
        var names = emptyList<String>()
        var zeroBased = false
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (++count > MAX_FILES) break
                    val base = entry.name.substringAfterLast('/')
                    if (base.equals("0.tag", true)) zeroBased = true
                    if (base.equals("_names.list", true)) {
                        names = readBounded(zip, MAX_META_BYTES)?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF")?.lines()?.map { it.trim() } ?: emptyList()
                    }
                }
            }
        }
        return Index(names, zeroBased)
    }

    private fun logicalName(path: String, names: List<String>, zeroBased: Boolean): String {
        val base = path.substringAfterLast('/')
        val n = Regex("^(\\d+)\\.tag$", RegexOption.IGNORE_CASE).matchEntire(base)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return base
        val preferred = if (zeroBased) names.getOrNull(n) else names.getOrNull(n - 1)
        val alternate = if (zeroBased) names.getOrNull(n - 1) else names.getOrNull(n)
        val interesting = listOfNotNull(preferred, alternate).firstOrNull { it.endsWith(".po", true) || it.endsWith("positions10.xml", true) }
        return interesting ?: preferred?.takeIf { it.isNotBlank() } ?: alternate?.takeIf { it.isNotBlank() } ?: base
    }

    private fun positionAliases(value: String): Set<String> {
        val clean = value.replace('\\', '/').trim().removePrefix("?")
        val base = clean.substringAfterLast('/')
        val stem = base.substringBeforeLast('.', base)
        return linkedSetOf(clean.lowercase(Locale.ROOT), base.lowercase(Locale.ROOT), stem.lowercase(Locale.ROOT))
    }

    private fun bookAliases(book: BookItem): Set<String> {
        val out = linkedSetOf<String>()
        listOf(book.sourceFile, book.originalName, book.epub?.fileName.orEmpty()).filter { it.isNotBlank() }.forEach { out += positionAliases(it) }
        return out
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String = if (endsWith(suffix, true)) dropLast(suffix.length) else this

    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
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

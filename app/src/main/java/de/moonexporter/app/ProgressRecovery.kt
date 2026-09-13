package de.moonexporter.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Recovers Moon+ reading progress from backup formats seen in the wild.
 *
 * Full .mrpro backups normally keep progress in Android SharedPreferences
 * `shared_prefs/positions10.xml`. Cloud sync folders instead use per-book `.po`
 * files. Both are supported here and the original Moon value is preserved.
 */
internal object ProgressRecovery {
    private const val MAX_META_BYTES = 8 * 1024 * 1024
    private const val MAX_PO_BYTES = 64 * 1024
    private const val MAX_FILES = 40_000

    suspend fun recover(context: Context, backupUri: Uri, books: List<BookItem>, onProgress: (String) -> Unit): List<BookItem> = withContext(Dispatchers.IO) {
        if (books.isEmpty()) return@withContext books
        val index = readIndex(context, backupUri)
        val byPath = linkedMapOf<String, MoonPosition>()
        val byBase = linkedMapOf<String, MutableList<Pair<String, MoonPosition>>>()
        var entries = 0
        var positions10Entries = 0
        var poEntries = 0

        fun register(source: String, position: MoonPosition) {
            val normalized = normalizePath(source)
            if (normalized.isBlank()) return
            byPath[normalized] = position
            val base = normalized.substringAfterLast('/')
            byBase.getOrPut(base) { mutableListOf() }.add(normalized to position)
        }

        val positionsIndexes = index.names.mapIndexedNotNull { zeroIndex, name ->
            if (name.endsWith("/positions10.xml", true) || name.equals("positions10.xml", true)) zeroIndex else null
        }
        val possiblePositionTags = positionsIndexes.flatMap { i -> listOf("${i + 1}.tag", "$i.tag") }.toSet()

        context.contentResolver.openInputStream(backupUri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (++entries > MAX_FILES) break
                    val clean = entry.name.replace('\\', '/').trimStart('/')
                    if (clean.contains("../") || clean.startsWith("..")) continue
                    val base = clean.substringAfterLast('/')
                    val logical = logicalName(clean, index.names, index.zeroBased)

                    if (base in possiblePositionTags || logical.endsWith("/positions10.xml", true) || logical.equals("positions10.xml", true)) {
                        val bytes = readBounded(zip, MAX_META_BYTES) ?: continue
                        parsePositions10Xml(bytes.toString(Charsets.UTF_8)).forEach { (source, position) -> register(source, position) }
                        positions10Entries++
                        onProgress(tr("Moon+-Lesefortschritt aus positions10.xml gelesen", "Moon+ reading progress loaded from positions10.xml"))
                        continue
                    }

                    if (logical.endsWith(".po", true)) {
                        val bytes = readBounded(zip, MAX_PO_BYTES) ?: continue
                        val position = parseMoonPosition(bytes.toString(Charsets.UTF_8)) ?: continue
                        register(logical.removeSuffixIgnoreCase(".po"), position)
                        poEntries++
                    }
                }
            }
        }

        var matched = 0
        val result = books.map { book ->
            if (book.position?.percent != null) return@map book
            val exact = bookAliases(book).firstNotNullOfOrNull { byPath[it] }
            val fallback = if (exact == null) {
                val bases = bookAliases(book).map { it.substringAfterLast('/') }.distinct()
                bases.firstNotNullOfOrNull { base ->
                    val candidates = byBase[base].orEmpty().distinctBy { it.first }
                    candidates.singleOrNull()?.second
                }
            } else null
            val position = exact ?: fallback
            if (position != null) {
                matched++
                book.copy(position = position)
            } else book
        }
        val totalWithProgress = result.count { it.position?.percent != null }
        onProgress(tr(
            "Lesefortschritt: $totalWithProgress Bücher erkannt ($matched neu; positions10: $positions10Entries, .po: $poEntries)",
            "Reading progress: $totalWithProgress books found ($matched new; positions10: $positions10Entries, .po: $poEntries)",
        ))
        result
    }

    /** Parse Android SharedPreferences positions10.xml. */
    internal fun parsePositions10Xml(xml: String): Map<String, MoonPosition> {
        if (Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return emptyMap()
        val out = linkedMapOf<String, MoonPosition>()
        val regex = Regex(
            "<string\\b[^>]*\\bname\\s*=\\s*([\\\"'])(.*?)\\1[^>]*>(.*?)</string>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        regex.findAll(xml).forEach { match ->
            val source = xmlUnescape(match.groupValues[2]).trim()
            val raw = xmlUnescape(match.groupValues[3]).trim()
            parseMoonPosition(raw)?.let { out[source] = it }
        }
        return out
    }

    /**
     * Supports both cloud `.po` values (`timestamp*chapter@section#offset:percent%`)
     * and positions10 values (`chapter@section#offset:percent%`).
     */
    internal fun parseMoonPosition(raw: String): MoonPosition? {
        val text = raw.trim()
        if (text.isBlank()) return null
        Regex("^(\\d+)\\*(\\d+)@(\\d+)#(\\d+):([0-9.]+)%$").matchEntire(text)?.let {
            return MoonPosition(text, it.groupValues[1].toLongOrNull(), it.groupValues[2].toIntOrNull(), it.groupValues[3].toIntOrNull(), it.groupValues[4].toLongOrNull(), it.groupValues[5].toDoubleOrNull())
        }
        Regex("^(\\d+)\\*(\\d+):([0-9.]+)%$").matchEntire(text)?.let {
            return MoonPosition(text, it.groupValues[1].toLongOrNull(), it.groupValues[2].toIntOrNull(), null, null, it.groupValues[3].toDoubleOrNull())
        }
        Regex("^(\\d+)(?:@(\\d+))?(?:#(\\d+))?:([0-9.]+)%$").matchEntire(text)?.let {
            return MoonPosition(
                raw = text,
                timestampMs = null,
                chapterOrPage = it.groupValues[1].toIntOrNull(),
                section = it.groupValues[2].takeIf(String::isNotBlank)?.toIntOrNull(),
                offset = it.groupValues[3].takeIf(String::isNotBlank)?.toLongOrNull(),
                percent = it.groupValues[4].toDoubleOrNull(),
            )
        }
        val percent = Regex("([0-9]+(?:\\.[0-9]+)?)%").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return null
        return MoonPosition(raw = text, percent = percent)
    }

    fun reconstructTitles(books: List<BookItem>): List<BookItem> = books.map { book ->
        val current = book.title.trim()
        val metadataTitle = book.epub?.title?.trim().orEmpty()
        when {
            metadataTitle.isNotBlank() && !looksOpaque(metadataTitle) -> book.copy(
                title = metadataTitle,
                author = book.author ?: book.epub?.author,
                isbn = book.isbn ?: book.epub?.isbn,
            )
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
                        names = readBounded(zip, MAX_META_BYTES)
                            ?.toString(Charsets.UTF_8)
                            ?.removePrefix("\uFEFF")
                            ?.lines()
                            ?.map { it.trim() }
                            ?: emptyList()
                    }
                }
            }
        }
        return Index(names, zeroBased)
    }

    private fun logicalName(path: String, names: List<String>, zeroBased: Boolean): String {
        val base = path.substringAfterLast('/')
        val n = Regex("^(\\d+)\\.tag$", RegexOption.IGNORE_CASE).matchEntire(base)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return base
        val preferred = if (zeroBased) names.getOrNull(n) else names.getOrNull(n - 1)
        val alternate = if (zeroBased) names.getOrNull(n - 1) else names.getOrNull(n)
        return when {
            preferred?.endsWith("positions10.xml", true) == true -> preferred
            alternate?.endsWith("positions10.xml", true) == true -> alternate
            preferred?.endsWith(".po", true) == true -> preferred
            alternate?.endsWith(".po", true) == true -> alternate
            !preferred.isNullOrBlank() -> preferred
            !alternate.isNullOrBlank() -> alternate
            else -> base
        }
    }

    private fun normalizePath(value: String): String {
        var clean = value.replace('\\', '/').trim().removePrefix("?").removePrefix("file://")
        clean = clean.replace(Regex("/+"), "/")
        if (clean.startsWith("/storage/emulated/0/", true)) clean = "/sdcard/" + clean.substringAfter("/storage/emulated/0/")
        return clean.lowercase(Locale.ROOT).trimEnd('/')
    }

    private fun bookAliases(book: BookItem): Set<String> {
        val out = linkedSetOf<String>()
        listOf(book.sourceFile, book.originalName, book.epub?.fileName.orEmpty()).filter { it.isNotBlank() }.forEach { value ->
            val normalized = normalizePath(value)
            out += normalized
            val base = normalized.substringAfterLast('/')
            out += base
            out += base.substringBeforeLast('.', base)
        }
        return out
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String = if (endsWith(suffix, true)) dropLast(suffix.length) else this

    private fun xmlUnescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.value }

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

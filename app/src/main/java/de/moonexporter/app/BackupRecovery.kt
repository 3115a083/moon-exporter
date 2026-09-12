package de.moonexporter.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal object BackupRecovery {
    private const val MAX_FILES = 40_000
    private const val MAX_META_BYTES = 4 * 1024 * 1024
    private const val MAX_PO_BYTES = 64 * 1024
    private const val MAX_AN_BYTES = 32 * 1024 * 1024

    private data class Recovered(
        val source: String,
        val position: MoonPosition? = null,
        val annotation: AnnotationData? = null,
        val titleHint: String? = null,
    )

    suspend fun countEntries(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        var count = 0
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) count++
                    if (count >= MAX_FILES) break
                }
            }
        }
        count
    }

    suspend fun recover(
        context: Context,
        uri: Uri,
        current: List<BookItem>,
        onProgress: (done: Int, total: Int, message: String) -> Unit,
    ): List<BookItem> = withContext(Dispatchers.IO) {
        val names = readNames(context, uri)
        val total = countEntries(context, uri).coerceAtLeast(1)
        val recovered = linkedMapOf<String, Recovered>()
        val hints = linkedMapOf<String, String>()
        var done = 0

        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    done++
                    val clean = entry.name.replace('\\', '/').trimStart('/')
                    if (clean.contains("../") || clean.startsWith("..")) continue
                    if (done % 20 == 0 || done == total) {
                        onProgress(done, total, tr("Lesefortschritt wird geprüft: $done/$total", "Checking reading progress: $done/$total"))
                    }

                    val base = clean.substringAfterLast('/')
                    if (base.equals("recent.list", true)) {
                        readBounded(zip, MAX_META_BYTES)?.toString(Charsets.UTF_8)?.let { parseHints(it, hints) }
                        continue
                    }
                    if (base.equals("_names.list", true)) continue

                    val candidates = MoonImporter.mrproLogicalCandidates(clean, names)
                    val poCandidates = candidates.filter { it.endsWith(".po", true) }
                    val anCandidates = candidates.filter { it.endsWith(".an", true) }
                    if (poCandidates.isEmpty() && anCandidates.isEmpty()) continue

                    val limit = if (anCandidates.isNotEmpty()) MAX_AN_BYTES else MAX_PO_BYTES
                    val bytes = readBounded(zip, limit) ?: continue

                    if (poCandidates.isNotEmpty()) {
                        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
                        if (looksLikePosition(text)) {
                            val source = poCandidates.first().substringAfterLast('/').removeSuffixIgnoreCase(".po")
                            putRecovered(recovered, source, position = parsePo(text))
                            continue
                        }
                    }

                    if (anCandidates.isNotEmpty()) {
                        val annotationText = decompress(bytes)
                        if (!annotationText.isNullOrBlank()) {
                            val source = anCandidates.first().substringAfterLast('/').removeSuffixIgnoreCase(".an")
                            val title = annotationTitle(annotationText)
                            putRecovered(
                                recovered,
                                source,
                                annotation = AnnotationData(originalMrexpt = annotationText),
                                titleHint = title,
                            )
                            title?.let { registerHint(hints, source, it) }
                        }
                    }
                }
            }
        }

        merge(current, recovered.values.toList(), hints)
    }

    fun reconstructTitles(books: List<BookItem>): List<BookItem> = books.map { book ->
        val opaque = book.isCryptic || isOpaqueName(book.sourceFile) || isOpaqueName(book.title)
        if (!opaque) book else {
            val title = book.epub?.title?.takeIf { !isOpaqueName(it) && it.isNotBlank() }
            if (title == null) book.copy(isCryptic = true)
            else book.copy(title = title, isCryptic = true, author = book.author ?: book.epub?.author, isbn = book.isbn ?: book.epub?.isbn)
        }
    }

    private fun merge(current: List<BookItem>, recovered: List<Recovered>, hints: Map<String, String>): List<BookItem> {
        val byAlias = linkedMapOf<String, Recovered>()
        recovered.forEach { item -> aliases(item.source).forEach { byAlias.putIfAbsent(it, item) } }
        val used = mutableSetOf<Recovered>()
        val merged = current.map { book ->
            val item = aliases(book.sourceFile).asSequence().mapNotNull(byAlias::get).firstOrNull()
                ?: aliases(book.originalName).asSequence().mapNotNull(byAlias::get).firstOrNull()
            if (item == null) {
                val titleHint = findHint(hints, book.sourceFile)
                return@map if (titleHint != null && isOpaqueName(book.title)) book.copy(title = titleHint, isCryptic = true) else book.copy(isCryptic = book.isCryptic || isOpaqueName(book.sourceFile))
            }
            used += item
            val hint = item.titleHint ?: findHint(hints, item.source) ?: findHint(hints, book.sourceFile)
            book.copy(
                position = item.position ?: book.position,
                annotation = item.annotation ?: book.annotation,
                title = hint?.takeIf { isOpaqueName(book.title) || book.title.equals(book.originalName.substringAfterLast('/'), true) } ?: book.title,
                isCryptic = book.isCryptic || isOpaqueName(book.sourceFile) || isOpaqueName(book.title),
            )
        }.toMutableList()

        recovered.filterNot { it in used }.forEach { item ->
            val base = item.source.substringAfterLast('/')
            val ext = base.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val original = base.substringBeforeLast('.', base)
            val title = item.titleHint ?: findHint(hints, item.source) ?: original
            merged += BookItem(
                key = item.source.lowercase(Locale.ROOT),
                sourceFile = item.source,
                originalName = original,
                extension = ext,
                title = title,
                position = item.position,
                annotation = item.annotation,
                isCryptic = isOpaqueName(item.source) || isOpaqueName(title),
            )
        }
        return merged.distinctBy { it.key }.sortedBy { sortTitle(it.title) }
    }

    private fun putRecovered(
        target: MutableMap<String, Recovered>,
        source: String,
        position: MoonPosition? = null,
        annotation: AnnotationData? = null,
        titleHint: String? = null,
    ) {
        val key = source.lowercase(Locale.ROOT)
        val old = target[key]
        target[key] = Recovered(
            source = source,
            position = position ?: old?.position,
            annotation = annotation ?: old?.annotation,
            titleHint = titleHint ?: old?.titleHint,
        )
    }

    private fun looksLikePosition(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_PO_BYTES) return false
        if (!text.contains('%')) return false
        return Regex("[0-9]+(?:\\.[0-9]+)?%").containsMatchIn(text) && (text.contains('*') || text.contains(':'))
    }

    private fun aliases(value: String): Set<String> {
        val clean = value.replace('\\', '/').trim().lowercase(Locale.ROOT)
        val base = clean.substringAfterLast('/')
        val stem = base.removeSuffixIgnoreCase(".po").removeSuffixIgnoreCase(".an")
        return linkedSetOf(clean, base, stem, normalize(stem)).filter { it.isNotBlank() }.toSet()
    }

    private fun findHint(hints: Map<String, String>, source: String): String? =
        aliases(source).asSequence().mapNotNull { hints[it] }.firstOrNull { it.isNotBlank() }

    private fun parseHints(text: String, out: MutableMap<String, String>) {
        text.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { raw ->
            val line = raw.trim()
            if (line.isBlank()) return@forEach
            val parts = line.split('\t', '|', '=', limit = 2).map(String::trim)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) registerHint(out, parts[0], parts[1])
        }
    }

    private fun registerHint(out: MutableMap<String, String>, source: String, title: String) {
        if (isOpaqueName(title)) return
        aliases(source).forEach { out.putIfAbsent(it, title.trim()) }
    }

    private fun annotationTitle(text: String): String? {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val marker = lines.indexOf("#")
        val oldStyle = if (marker >= 0 && marker + 2 < lines.size) lines[marker + 2].trim().ifBlank { null } else null
        if (!oldStyle.isNullOrBlank() && !isOpaqueName(oldStyle)) return oldStyle
        return lines.asSequence().map(String::trim).firstOrNull { line ->
            line.length in 3..180 && !line.startsWith("#") && !line.matches(Regex("[0-9|,:;._ -]+")) && !line.contains('\t')
        }
    }

    private fun isOpaqueName(value: String): Boolean {
        val base = value.substringAfterLast('/').substringBeforeLast('.', value.substringAfterLast('/')).trim()
        if (base.length < 8) return false
        return base.all(Char::isDigit) || base.matches(Regex("[0-9a-fA-F]{24,}")) || base.matches(Regex("[0-9a-fA-F-]{32,}"))
    }

    private fun decompress(bytes: ByteArray): String? = runCatching {
        InflaterInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    private fun readNames(context: Context, uri: Uri): List<String> {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("_names.list", true)) {
                        return readBounded(zip, MAX_META_BYTES)?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF")?.lines()?.map(String::trim) ?: emptyList()
                    }
                }
            }
        }
        return emptyList()
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String = if (endsWith(suffix, true)) dropLast(suffix.length) else this

    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
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

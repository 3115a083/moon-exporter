package de.moonexporter.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Direct writer for Readest's local Books library. */
internal object ReadestDirectExporter {
    private const val CONFIG_SCHEMA_VERSION = 3
    private const val MAX_META_FILE = 4 * 1024 * 1024
    private const val MAX_META_TOTAL = 16 * 1024 * 1024
    private val READEST_OFFSETS = longArrayOf(
        0L, 1024L, 4096L, 16384L, 65536L, 262144L,
        1048576L, 4194304L, 16777216L, 67108864L, 268435456L, 1073741824L,
    )

    internal data class Result(val exported: Int, val skipped: Int, val warnings: List<String>)

    private data class EpubInfo(
        val title: String? = null,
        val authors: List<String> = emptyList(),
        val identifiers: List<String> = emptyList(),
        val language: String? = null,
        val navToSpine: Map<Int, Int> = emptyMap(),
    )

    suspend fun export(
        context: Context,
        targetTree: Uri,
        books: List<BookItem>,
        normalizeBookNames: Boolean,
        onProgress: (String) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val selectedRoot = DocumentFile.fromTreeUri(context, targetTree)
            ?: error(tr("Readest-Zielordner nicht verfügbar", "Readest target folder unavailable"))
        val booksRoot = when {
            selectedRoot.name.equals("Books", true) -> selectedRoot
            selectedRoot.findFile("Books")?.isDirectory == true -> requireNotNull(selectedRoot.findFile("Books"))
            else -> selectedRoot.createDirectory("Books")
                ?: error(tr("Readest/Books konnte nicht angelegt werden", "Could not create Readest/Books"))
        }

        val libraryFile = booksRoot.findFile("library.json")
        val originalLibrary = libraryFile?.let { readText(context, it, 8 * 1024 * 1024) }.orEmpty()
        val library = runCatching { if (originalLibrary.isBlank()) JSONArray() else JSONArray(originalLibrary) }
            .getOrElse { error(tr("Vorhandene Readest library.json ist ungültig", "Existing Readest library.json is invalid")) }
        val byHash = linkedMapOf<String, JSONObject>()
        for (i in 0 until library.length()) {
            val row = library.optJSONObject(i) ?: continue
            row.optString("hash").takeIf { it.isNotBlank() }?.let { byHash[it] = row }
        }

        var exported = 0
        var skipped = 0
        val warnings = mutableListOf<String>()

        for ((index, book) in books.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(tr("Readest ${index + 1}/${books.size}: ${book.title}", "Readest ${index + 1}/${books.size}: ${book.title}"))
            if (!book.hasBookFile) {
                skipped++
                warnings += tr("${book.title}: keine Buchdatei", "${book.title}: no book file")
                continue
            }
            val source = book.epub
            if (source == null) {
                skipped++
                warnings += tr("${book.title}: Buchquelle fehlt", "${book.title}: book source missing")
                continue
            }
            val ext = source.fileName.substringAfterLast('.', book.extension).lowercase(Locale.ROOT)
            if (ext !in setOf("epub", "pdf")) {
                skipped++
                warnings += tr("${book.title}: Direkt-Export derzeit nur EPUB/PDF", "${book.title}: direct export currently supports EPUB/PDF")
                continue
            }

            val readestHash = openBookSource(context, source) { readestPartialMd5(it) }
            val epubInfo = if (ext == "epub") openBookSource(context, source) { inspectEpub(it) } else EpubInfo()
            val reconstructedTitle = epubInfo.title?.takeIf { it.isNotBlank() && !ProgressRecovery.looksOpaque(it) }
                ?: book.title.takeIf { it.isNotBlank() && !ProgressRecovery.looksOpaque(it) }
            val title = reconstructedTitle ?: book.title
            val authors = epubInfo.authors.ifEmpty { listOfNotNull(book.author?.takeIf { it.isNotBlank() }) }
            val identifiers = epubInfo.identifiers.ifEmpty { listOfNotNull(book.isbn?.takeIf { it.isNotBlank() }) }
            val metaHash = metadataHash(title, authors, identifiers)

            val existingDir = booksRoot.findFile(readestHash)?.takeIf { it.isDirectory }
            val dir = existingDir ?: booksRoot.createDirectory(readestHash)
                ?: error(tr("Readest-Buchordner konnte nicht angelegt werden", "Could not create Readest book folder"))

            val existingBook = dir.listFiles().firstOrNull {
                it.isFile && it.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in setOf("epub", "pdf")
            }
            if (existingBook == null) {
                val sourceBase = source.fileName.substringBeforeLast('.', source.fileName)
                val normalizedBase = reconstructedTitle?.let(::safeName)
                val targetBase = if (normalizeBookNames && !normalizedBase.isNullOrBlank()) normalizedBase else safeName(sourceBase)
                val targetName = "$targetBase.$ext"
                val targetBook = dir.createFile(mimeFor(ext), targetName)
                    ?: error(tr("Readest-Buchdatei konnte nicht angelegt werden", "Could not create Readest book file"))
                context.contentResolver.openOutputStream(targetBook.uri, "w")?.use { output ->
                    openBookSource(context, source) { input -> input.copyTo(output, 64 * 1024) }
                } ?: error(tr("Readest-Buchdatei konnte nicht geschrieben werden", "Could not write Readest book file"))
            }

            val now = System.currentTimeMillis()
            val coverHash = writeCoverIfNeeded(context, dir, source.cover)
            val configFile = dir.findFile("config.json")
            val originalConfig = configFile?.let { readText(context, it, 8 * 1024 * 1024) }.orEmpty()
            val config = if (originalConfig.isBlank()) JSONObject() else runCatching { JSONObject(originalConfig) }
                .getOrElse { error(tr("Vorhandene Readest config.json ist ungültig", "Existing Readest config.json is invalid")) }
            val existed = originalConfig.isNotBlank()
            if (existed) backupOnce(context, dir, "config.moon-exporter.bak.json", originalConfig, "application/json")
            val mappedNotes = mergeConfig(config, book, readestHash, metaHash, epubInfo, now, existed)
            if (book.hasAnnotations && mappedNotes < (book.annotation?.records?.size ?: 0)) {
                warnings += tr(
                    "${book.title}: nicht exakt zuordenbare Markierungen zusätzlich als .mrexpt erhalten",
                    "${book.title}: annotations that could not be mapped exactly were also preserved as .mrexpt",
                )
            }
            writeOrReplaceText(context, dir, "config.json", config.toString(), "application/json")
            if (book.hasAnnotations) {
                writeOrReplaceText(context, dir, "moon-export.mrexpt", Exporter.mrexptFor(book), "text/plain")
            }

            val row = byHash[readestHash] ?: JSONObject().also { byHash[readestHash] = it }
            mergeLibraryRow(row, title, authors, identifiers, epubInfo.language, readestHash, metaHash, coverHash, now, ext, book.position)
            exported++
        }

        if (originalLibrary.isNotBlank()) {
            backupOnce(context, booksRoot, "library.moon-exporter.bak.json", originalLibrary, "application/json")
        }
        val merged = JSONArray()
        byHash.values.forEach { merged.put(it) }
        writeOrReplaceText(context, booksRoot, "library.json", merged.toString(), "application/json")
        if (booksRoot.findFile(".nomedia") == null) booksRoot.createFile("application/octet-stream", ".nomedia")
        Result(exported, skipped, warnings)
    }

    private fun mergeConfig(
        config: JSONObject,
        book: BookItem,
        bookHash: String,
        metaHash: String,
        epubInfo: EpubInfo,
        now: Long,
        existed: Boolean,
    ): Int {
        val previousUpdatedAt = config.optLong("updatedAt", 0L)
        config.put("schemaVersion", maxOf(CONFIG_SCHEMA_VERSION, config.optInt("schemaVersion", 0)))
        config.put("bookHash", bookHash)
        config.put("metaHash", metaHash)
        if (!config.has("viewSettings")) config.put("viewSettings", JSONObject())
        if (!config.has("searchConfig")) config.put("searchConfig", JSONObject())

        val moonTimestamp = book.position?.timestampMs
        val shouldReplaceProgress = !existed || !config.has("progress") || (moonTimestamp != null && moonTimestamp > previousUpdatedAt)
        book.position?.percent?.let { percent ->
            if (shouldReplaceProgress) config.put("progress", progressPair(percent))
        }
        if (shouldReplaceProgress) {
            val chapter = book.position?.chapterOrPage
            val spineIndex = chapter?.let { epubInfo.navToSpine[it] }
            if (spineIndex != null) config.put("location", "epubcfi(/6/${2 * (spineIndex + 1)}!)")
        }
        config.put("updatedAt", now)

        val existing = config.optJSONArray("booknotes") ?: JSONArray()
        val knownIds = mutableSetOf<String>()
        for (i in 0 until existing.length()) existing.optJSONObject(i)?.optString("id")?.let(knownIds::add)
        var mapped = 0
        for (record in book.annotation?.records.orEmpty()) {
            val note = directNote(bookHash, metaHash, record, epubInfo) ?: continue
            if (knownIds.add(note.getString("id"))) existing.put(note)
            mapped++
        }
        if (existing.length() > 0) config.put("booknotes", existing)
        return mapped
    }

    private fun directNote(bookHash: String, metaHash: String, record: AnnotationRecord, epubInfo: EpubInfo): JSONObject? {
        val chapter = record.chapter ?: return null
        val spineIndex = epubInfo.navToSpine[chapter] ?: return null
        val text = record.original?.trim().orEmpty()
        val noteText = record.note.orEmpty()
        if (text.isBlank() && noteText.isBlank()) return null
        val cfi = "epubcfi(/6/${2 * (spineIndex + 1)}!)"
        val created = record.timestampMs?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val idSeed = "${record.id}|$chapter|${record.position}|$text|$noteText"
        return JSONObject()
            .put("bookHash", bookHash)
            .put("metaHash", metaHash)
            .put("id", "moon-${md5(idSeed).take(12)}")
            .put("type", "annotation")
            .put("cfi", cfi)
            .put("text", text)
            .put("style", "highlight")
            .put("color", moonColor(record.color))
            .put("note", noteText)
            .put("global", JSONObject.NULL)
            .put("createdAt", created)
            .put("updatedAt", created)
            .put("deletedAt", JSONObject.NULL)
    }

    private fun mergeLibraryRow(
        row: JSONObject,
        title: String,
        authors: List<String>,
        identifiers: List<String>,
        language: String?,
        hash: String,
        metaHash: String,
        coverHash: String?,
        now: Long,
        extension: String,
        position: MoonPosition?,
    ) {
        row.put("hash", hash)
        row.put("format", extension.uppercase(Locale.ROOT))
        row.put("metaHash", metaHash)
        row.put("title", title)
        row.put("sourceTitle", title)
        if (authors.isNotEmpty()) row.put("author", authors.joinToString(", "))
        language?.takeIf { it.isNotBlank() }?.let { row.put("primaryLanguage", it) }
        val metadata = row.optJSONObject("metadata") ?: JSONObject()
        metadata.put("title", title)
        metadata.put("sortAs", title)
        if (authors.isNotEmpty()) metadata.put("author", authors.joinToString(", "))
        language?.takeIf { it.isNotBlank() }?.let { metadata.put("language", it) }
        preferredIdentifiers(identifiers).firstOrNull()?.let { metadata.put("identifier", it) }
        identifiers.firstOrNull { it.lowercase(Locale.ROOT).contains("isbn") }?.let { metadata.put("isbn", normalizeIdentifier(it)) }
        if (identifiers.size > 1) metadata.put("altIdentifier", JSONArray(identifiers.map(::normalizeIdentifier)))
        row.put("metadata", metadata)
        if (!row.has("createdAt")) row.put("createdAt", now)
        if (!row.has("downloadedAt")) row.put("downloadedAt", now)
        row.put("updatedAt", now)
        row.put("metadataUpdatedAt", now)
        row.put("deletedAt", JSONObject.NULL)
        coverHash?.let { row.put("coverHash", it) }
        if (!row.has("progress")) position?.percent?.let { row.put("progress", progressPair(it)) }
    }

    private fun progressPair(percent: Double): JSONArray {
        val current = percent.coerceIn(0.0, 100.0).roundToInt()
        return JSONArray().put(current).put(100)
    }

    private fun metadataHash(title: String, authors: List<String>, identifiers: List<String>): String {
        val source = "$title|${authors.joinToString(",")}|${preferredIdentifiers(identifiers).joinToString(",")}"
        return md5(Normalizer.normalize(source, Normalizer.Form.NFC))
    }

    private fun preferredIdentifiers(values: List<String>): List<String> {
        if (values.isEmpty()) return emptyList()
        for (scheme in listOf("uuid", "calibre", "isbn")) {
            values.firstOrNull { scheme in it.lowercase(Locale.ROOT) }?.let { return listOf(normalizeIdentifier(it)) }
        }
        return values.filter { it.isNotBlank() }.map(::normalizeIdentifier)
    }

    private fun normalizeIdentifier(value: String): String = when {
        "urn:" in value -> value.substringAfterLast(':')
        ':' in value -> value.substringAfter(':')
        else -> value
    }.trim()

    internal fun readestPartialMd5(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val chunks = Array(READEST_OFFSETS.size) { ByteArray(1024) }
        val counts = IntArray(READEST_OFFSETS.size)
        var absolute = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            val blockEnd = absolute + n
            for (i in READEST_OFFSETS.indices) {
                val start = READEST_OFFSETS[i]
                val end = start + 1024L
                if (blockEnd <= start || absolute >= end) continue
                val from = maxOf(absolute, start)
                val to = minOf(blockEnd, end)
                val src = (from - absolute).toInt()
                val dst = (from - start).toInt()
                val len = (to - from).toInt()
                System.arraycopy(buffer, src, chunks[i], dst, len)
                counts[i] = maxOf(counts[i], dst + len)
            }
            absolute = blockEnd
        }
        for (i in chunks.indices) {
            if (READEST_OFFSETS[i] >= absolute) break
            if (counts[i] > 0) digest.update(chunks[i], 0, counts[i])
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun inspectEpub(input: InputStream): EpubInfo {
        val texts = linkedMapOf<String, String>()
        var total = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val clean = entry.name.replace('\\', '/').trimStart('/')
                if (clean.contains("../") || clean.startsWith("..")) continue
                val lower = clean.lowercase(Locale.ROOT)
                val wanted = lower.endsWith(".opf") || lower.endsWith(".ncx") ||
                    (lower.endsWith(".xhtml") && ("nav" in lower || "toc" in lower))
                if (!wanted) continue
                val bytes = readBounded(zip, MAX_META_FILE) ?: continue
                total += bytes.size
                if (total > MAX_META_TOTAL) break
                texts[clean] = bytes.toString(Charsets.UTF_8)
            }
        }
        val opfEntry = texts.entries.firstOrNull { it.key.endsWith(".opf", true) } ?: return EpubInfo()
        val opfPath = opfEntry.key
        val opf = opfEntry.value
        val title = tagValues(opf, "title").firstOrNull()?.let(::xmlDecode)
        val authors = tagValues(opf, "creator").map(::xmlDecode).filter { it.isNotBlank() }
        val identifiers = tagValues(opf, "identifier").map(::xmlDecode).filter { it.isNotBlank() }
        val language = tagValues(opf, "language").firstOrNull()?.let(::xmlDecode)

        val manifest = linkedMapOf<String, String>()
        Regex("<item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id")
            val href = attr(tag, "href")
            if (!id.isNullOrBlank() && !href.isNullOrBlank()) manifest[id] = resolvePath(opfPath, href)
        }
        val spineIds = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)
            .mapNotNull { attr(it.value, "idref") }.toList()
        val spinePaths = spineIds.mapNotNull(manifest::get)
        val pathToSpine = spinePaths.mapIndexed { index, path -> normalizeHref(path) to index }.toMap()

        val navHrefs = mutableListOf<String>()
        texts.entries.firstOrNull { it.key.endsWith(".ncx", true) }?.let { entry ->
            Regex("<content\\b[^>]*\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                .findAll(entry.value).forEach { navHrefs += resolvePath(entry.key, it.groupValues[1]) }
        }
        if (navHrefs.isEmpty()) {
            texts.entries.firstOrNull { it.key.endsWith(".xhtml", true) }?.let { entry ->
                Regex("<a\\b[^>]*\\bhref\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                    .findAll(entry.value).forEach { navHrefs += resolvePath(entry.key, it.groupValues[1]) }
            }
        }
        val navToSpine = linkedMapOf<Int, Int>()
        navHrefs.forEachIndexed { navIndex, href -> pathToSpine[normalizeHref(href)]?.let { navToSpine[navIndex] = it } }
        return EpubInfo(title, authors, identifiers, language, navToSpine)
    }

    private fun tagValues(xml: String, localName: String): List<String> =
        Regex("<(?:[A-Za-z0-9_-]+:)?$localName\\b[^>]*>(.*?)</(?:[A-Za-z0-9_-]+:)?$localName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml).map { it.groupValues[1].replace(Regex("<[^>]+>"), "").trim() }.toList()

    private fun attr(tag: String, name: String): String? =
        Regex("\\b${Regex.escape(name)}\\s*=\\s*['\"]([^'\"]*)['\"]", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.getOrNull(1)?.let(::xmlDecode)

    private fun xmlDecode(value: String): String = value
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")

    private fun resolvePath(containerPath: String, href: String): String {
        val raw = href.substringBefore('#').substringBefore('?').replace('\\', '/')
        if (raw.startsWith('/')) return raw.trimStart('/')
        val parts = (containerPath.substringBeforeLast('/', "") + "/" + raw).split('/')
        val out = ArrayDeque<String>()
        for (part in parts) when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeLast()
            else -> out.addLast(part)
        }
        return out.joinToString("/")
    }

    private fun normalizeHref(path: String): String = path.substringBefore('#').substringBefore('?').trimStart('/').lowercase(Locale.ROOT)

    private fun readBounded(input: InputStream, max: Int): ByteArray? {
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

    private fun writeCoverIfNeeded(context: Context, dir: DocumentFile, bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, out); out.toByteArray() }
        val hash = readestPartialMd5(bytes.inputStream())
        if (dir.findFile("cover.png") == null) {
            val file = dir.createFile("image/png", "cover.png") ?: return hash
            context.contentResolver.openOutputStream(file.uri, "w")?.use { it.write(bytes) }
        }
        return hash
    }

    private inline fun <T> openBookSource(context: Context, source: EpubMatch, block: (InputStream) -> T): T {
        source.uri?.let { uri ->
            return context.contentResolver.openInputStream(uri)?.buffered()?.use(block)
                ?: error(tr("Buchdatei konnte nicht geöffnet werden", "Could not open book file"))
        }
        if (source.backupUri != null && !source.archiveEntryName.isNullOrBlank()) {
            val wanted = source.archiveEntryName.replace('\\', '/').trimStart('/')
            context.contentResolver.openInputStream(source.backupUri)?.buffered()?.use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        if (entry.name.replace('\\', '/').trimStart('/') == wanted) return block(zip)
                    }
                }
            }
            error(tr("Buchdatei wurde im Backup nicht wiedergefunden", "Book file was not found again in the backup"))
        }
        source.embeddedPath?.let { path ->
            val file = File(path)
            if (file.isFile) return file.inputStream().buffered().use(block)
        }
        error(tr("Keine Buchdatei zugeordnet", "No book file matched"))
    }

    private fun readText(context: Context, file: DocumentFile, maxBytes: Int): String? =
        context.contentResolver.openInputStream(file.uri)?.use { input -> readBounded(input, maxBytes)?.toString(Charsets.UTF_8) }

    private fun backupOnce(context: Context, dir: DocumentFile, name: String, text: String, mime: String) {
        if (dir.findFile(name) != null) return
        val file = dir.createFile(mime, name) ?: return
        context.contentResolver.openOutputStream(file.uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
    }

    private fun writeOrReplaceText(context: Context, dir: DocumentFile, name: String, text: String, mime: String) {
        val file = dir.findFile(name) ?: dir.createFile(mime, name)
            ?: error(tr("Datei konnte nicht angelegt werden: $name", "Could not create file: $name"))
        context.contentResolver.openOutputStream(file.uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: error(tr("Datei konnte nicht geschrieben werden: $name", "Could not write file: $name"))
    }

    private fun moonColor(color: Int?): String = if (color != null && color < -10_000_000) "blue" else "yellow"

    private fun mimeFor(extension: String): String = when (extension) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}

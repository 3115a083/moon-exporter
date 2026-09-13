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
import java.io.OutputStream
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
    private const val STAGES_PER_BOOK = 7
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
        val spinePaths: List<String> = emptyList(),
    )

    private data class NoteBuild(val note: JSONObject, val exact: Boolean)

    suspend fun export(
        context: Context,
        targetTree: Uri,
        books: List<BookItem>,
        normalizeBookNames: Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val totalUnits = (books.size * STAGES_PER_BOOK + 1).coerceAtLeast(1)
        fun report(bookIndex: Int, stage: Int, message: String) {
            val done = (bookIndex * STAGES_PER_BOOK + stage).coerceIn(0, totalUnits)
            onProgress(ExportProgress(message, done.toFloat() / totalUnits.toFloat()))
        }

        onProgress(ExportProgress(tr("Readest-Zielordner wird geprüft…", "Checking Readest target folder…"), 0f))
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
            val prefix = tr("Buch ${index + 1}/${books.size}", "Book ${index + 1}/${books.size}")
            if (!book.hasBookFile || book.epub == null) {
                skipped++
                warnings += tr("${book.title}: keine Buchdatei", "${book.title}: no book file")
                continue
            }
            val source = book.epub
            val ext = source.fileName.substringAfterLast('.', book.extension).lowercase(Locale.ROOT)
            if (ext !in setOf("epub", "pdf")) {
                skipped++
                warnings += tr("${book.title}: Direkt-Export derzeit nur EPUB/PDF", "${book.title}: direct export currently supports EPUB/PDF")
                continue
            }

            report(index, 0, "$prefix · 1/7 · ${tr("Buchquelle einmalig vorbereiten", "Preparing book source once")} · ${book.title}")
            withPreparedBookFile(context, source, ext) { localBook ->
                coroutineContext.ensureActive()
                report(index, 1, "$prefix · 2/7 · ${tr("Readest-Buch-ID berechnen", "Calculating Readest book ID")}")
                val readestHash = localBook.inputStream().buffered().use(::readestPartialMd5)

                coroutineContext.ensureActive()
                report(index, 2, "$prefix · 3/7 · ${tr("EPUB-Struktur und Metadaten analysieren", "Analyzing EPUB structure and metadata")}")
                val epubInfo = if (ext == "epub") localBook.inputStream().buffered().use(::inspectEpub) else EpubInfo()
                val reconstructedTitle = epubInfo.title?.takeIf { it.isNotBlank() && !ProgressRecovery.looksOpaque(it) }
                    ?: book.title.takeIf { it.isNotBlank() && !ProgressRecovery.looksOpaque(it) }
                val title = reconstructedTitle ?: book.title
                val authors = epubInfo.authors.ifEmpty { listOfNotNull(book.author?.takeIf { it.isNotBlank() }) }
                val identifiers = epubInfo.identifiers.ifEmpty { listOfNotNull(book.isbn?.takeIf { it.isNotBlank() }) }
                val metaHash = metadataHash(title, authors, identifiers)

                val existingDir = booksRoot.findFile(readestHash)?.takeIf { it.isDirectory }
                val dir = existingDir ?: booksRoot.createDirectory(readestHash)
                    ?: error(tr("Readest-Buchordner konnte nicht angelegt werden", "Could not create Readest book folder"))

                coroutineContext.ensureActive()
                val noteCount = book.annotation?.records?.size ?: 0
                report(index, 3, "$prefix · 4/7 · ${tr("Markierungspositionen auflösen ($noteCount)", "Resolving highlight positions ($noteCount)")}")
                val resolver = if (ext == "epub" && epubInfo.spinePaths.isNotEmpty()) ReadestCfiResolver(localBook, epubInfo.spinePaths) else null

                coroutineContext.ensureActive()
                report(index, 4, "$prefix · 5/7 · ${tr("Buchdatei nach Readest kopieren", "Copying book file into Readest")}")
                val existingBook = dir.listFiles().firstOrNull {
                    it.isFile && it.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in setOf("epub", "pdf")
                }
                if (existingBook == null) {
                    val sourceBase = source.fileName.substringBeforeLast('.', source.fileName)
                    val normalizedBase = reconstructedTitle?.let(::safeName)
                    val targetBase = if (normalizeBookNames && !normalizedBase.isNullOrBlank()) normalizedBase else safeName(sourceBase)
                    val targetBook = dir.createFile(mimeFor(ext), "$targetBase.$ext")
                        ?: error(tr("Readest-Buchdatei konnte nicht angelegt werden", "Could not create Readest book file"))
                    context.contentResolver.openOutputStream(targetBook.uri, "w")?.use { output ->
                        localBook.inputStream().buffered().use { input -> input.copyTo(output, 256 * 1024) }
                    } ?: error(tr("Readest-Buchdatei konnte nicht geschrieben werden", "Could not write Readest book file"))
                }

                coroutineContext.ensureActive()
                report(index, 5, "$prefix · 6/7 · ${tr("Fortschritt und Markierungen in config.json schreiben", "Writing progress and highlights to config.json")}")
                val now = System.currentTimeMillis()
                val coverHash = writeCoverIfNeeded(context, dir, source.cover)
                val configFile = dir.findFile("config.json")
                val originalConfig = configFile?.let { readText(context, it, 8 * 1024 * 1024) }.orEmpty()
                val config = if (originalConfig.isBlank()) JSONObject() else runCatching { JSONObject(originalConfig) }
                    .getOrElse { error(tr("Vorhandene Readest config.json ist ungültig", "Existing Readest config.json is invalid")) }
                val existed = originalConfig.isNotBlank()
                if (existed) backupOnce(context, dir, "config.moon-exporter.bak.json", originalConfig, "application/json")
                val exactNotes = mergeConfig(config, book, readestHash, metaHash, epubInfo, resolver, now, existed)
                resolver?.close()
                if (book.hasAnnotations && exactNotes < noteCount) {
                    warnings += tr(
                        "${book.title}: $exactNotes von $noteCount Markierungen exakt positioniert; übrige Markierungen wurden erhalten oder am Kapitel verankert",
                        "${book.title}: $exactNotes of $noteCount highlights positioned exactly; remaining highlights were preserved or anchored to the chapter",
                    )
                }
                writeOrReplaceText(context, dir, "config.json", config.toString(), "application/json")
                if (book.hasAnnotations) writeOrReplaceText(context, dir, "moon-export.mrexpt", Exporter.mrexptFor(book), "text/plain")

                coroutineContext.ensureActive()
                report(index, 6, "$prefix · 7/7 · ${tr("Readest-Bibliothekseintrag aktualisieren", "Updating Readest library entry")}")
                val row = byHash[readestHash] ?: JSONObject().also { byHash[readestHash] = it }
                mergeLibraryRow(row, title, authors, identifiers, epubInfo.language, readestHash, metaHash, coverHash, now, ext, book.position)
                exported++
            }
        }

        onProgress(ExportProgress(tr("Readest library.json abschließen und sichern…", "Finalizing and backing up Readest library.json…"), (totalUnits - 1).toFloat() / totalUnits))
        if (originalLibrary.isNotBlank()) backupOnce(context, booksRoot, "library.moon-exporter.bak.json", originalLibrary, "application/json")
        val merged = JSONArray()
        byHash.values.forEach { merged.put(it) }
        writeOrReplaceText(context, booksRoot, "library.json", merged.toString(), "application/json")
        if (booksRoot.findFile(".nomedia") == null) booksRoot.createFile("application/octet-stream", ".nomedia")
        Result(exported, skipped, warnings)
    }

    private inline fun <T> withPreparedBookFile(context: Context, source: EpubMatch, extension: String, block: (File) -> T): T {
        source.embeddedPath?.let { path ->
            File(path).takeIf { it.isFile }?.let { return block(it) }
        }
        val temp = File.createTempFile("readest-export-", ".$extension", context.cacheDir)
        try {
            temp.outputStream().buffered(256 * 1024).use { output -> copySourceOnce(context, source, output) }
            return block(temp)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun copySourceOnce(context: Context, source: EpubMatch, output: OutputStream) {
        source.uri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.buffered()?.use { it.copyTo(output, 256 * 1024) }
                ?: error(tr("Buchdatei konnte nicht geöffnet werden", "Could not open book file"))
            return
        }
        if (source.backupUri != null && !source.archiveEntryName.isNullOrBlank()) {
            val wanted = source.archiveEntryName.replace('\\', '/').trimStart('/')
            var found = false
            context.contentResolver.openInputStream(source.backupUri)?.buffered()?.use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        if (entry.name.replace('\\', '/').trimStart('/') == wanted) {
                            zip.copyTo(output, 256 * 1024)
                            found = true
                            break
                        }
                    }
                }
            } ?: error(tr("Backup konnte nicht erneut geöffnet werden", "Could not reopen backup"))
            if (!found) error(tr("Buchdatei wurde im Backup nicht wiedergefunden", "Book file was not found again in the backup"))
            return
        }
        error(tr("Keine Buchdatei zugeordnet", "No book file matched"))
    }

    private fun mergeConfig(
        config: JSONObject,
        book: BookItem,
        bookHash: String,
        metaHash: String,
        epubInfo: EpubInfo,
        resolver: ReadestCfiResolver?,
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
        book.position?.percent?.let { percent -> if (shouldReplaceProgress) config.put("progress", progressPair(percent)) }
        if (shouldReplaceProgress) {
            val chapter = book.position?.chapterOrPage
            val spineIndex = chapter?.let { epubInfo.navToSpine[it] }
            if (spineIndex != null) config.put("location", "epubcfi(/6/${2 * (spineIndex + 1)}!)")
        }
        config.put("updatedAt", now)

        val records = book.annotation?.records.orEmpty()
        val old = config.optJSONArray("booknotes") ?: JSONArray()
        val byId = linkedMapOf<String, JSONObject>()
        val anonymous = mutableListOf<JSONObject>()
        for (i in 0 until old.length()) {
            val note = old.optJSONObject(i) ?: continue
            val id = note.optString("id")
            if (id.isBlank()) anonymous += note else byId[id] = note
        }

        var exact = 0
        for (record in records) {
            val built = directNote(bookHash, metaHash, record, epubInfo, resolver) ?: continue
            val id = built.note.getString("id")
            if (built.exact || id !in byId) byId[id] = built.note
            if (built.exact) exact++
        }

        val merged = JSONArray()
        anonymous.forEach(merged::put)
        byId.values.forEach(merged::put)
        if (merged.length() > 0) config.put("booknotes", merged) else config.remove("booknotes")
        return exact
    }

    private fun directNote(
        bookHash: String,
        metaHash: String,
        record: AnnotationRecord,
        epubInfo: EpubInfo,
        resolver: ReadestCfiResolver?,
    ): NoteBuild? {
        val text = record.original?.trim().orEmpty()
        if (text.isBlank()) return null
        val chapter = record.chapter
        val preferredSpine = chapter?.let { epubInfo.navToSpine[it] }
        val resolved = resolver?.resolve(text, preferredSpine, record.position)
        val fallbackSpine = preferredSpine ?: chapter?.takeIf { it in epubInfo.spinePaths.indices }
        val cfi = resolved?.cfi ?: fallbackSpine?.let { "epubcfi(/6/${2 * (it + 1)}!)" } ?: return null
        val created = record.timestampMs?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val note = JSONObject()
            .put("bookHash", bookHash)
            .put("metaHash", metaHash)
            .put("id", noteId(record))
            .put("type", "annotation")
            .put("cfi", cfi)
            .put("text", text)
            .put("style", "highlight")
            .put("color", moonColor(record.color))
            .put("note", record.note.orEmpty())
            .put("global", JSONObject.NULL)
            .put("createdAt", created)
            .put("updatedAt", created)
            .put("deletedAt", JSONObject.NULL)
        return NoteBuild(note, resolved != null)
    }

    private fun noteId(record: AnnotationRecord): String {
        val seed = "${record.id}|${record.chapter}|${record.position}|${record.original.orEmpty()}|${record.note.orEmpty()}"
        return "moon-${md5(seed).take(12)}"
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
        position?.percent?.let { percent ->
            if (!row.has("progress") || percent >= 99.95) row.put("progress", progressPair(percent))
            if (percent >= 99.95) {
                row.put("readingStatus", "finished")
                row.put("readingStatusUpdatedAt", now)
            }
        }
    }

    private fun progressPair(percent: Double): JSONArray = JSONArray().put(percent.coerceIn(0.0, 100.0).roundToInt()).put(100)

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
            val id = attr(m.value, "id")
            val href = attr(m.value, "href")
            if (!id.isNullOrBlank() && !href.isNullOrBlank()) manifest[id] = resolvePath(opfPath, href)
        }
        val spineIds = Regex("<itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf)
            .mapNotNull { attr(it.value, "idref") }.toList()
        val spinePaths = spineIds.mapNotNull(manifest::get)
        val pathToSpine = spinePaths.mapIndexed { i, path -> normalizeHref(path) to i }.toMap()

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
        return EpubInfo(title, authors, identifiers, language, navToSpine, spinePaths)
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

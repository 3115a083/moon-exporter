package de.moonexporter.app

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal object MoonImporter {
    private const val MAX_FILES = 40_000
    private const val MAX_PO_BYTES = 64 * 1024
    private const val MAX_AN_BYTES = 32 * 1024 * 1024
    private const val MAX_META_BYTES = 4 * 1024 * 1024
    private const val MAX_DB_BYTES = 384L * 1024 * 1024
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val BOOK_EXTENSIONS = setOf("epub", "pdf", "mobi", "azw3", "cbz", "cbr")

    suspend fun scanFolder(context: Context, uri: Uri, onProgress: (String) -> Unit): List<BookItem> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, uri) ?: return@withContext emptyList()
        val positions = linkedMapOf<String, MoonPosition>()
        val annotations = linkedMapOf<String, AnnotationData>()
        val hints = linkedMapOf<String, String>()
        var seen = 0

        suspend fun visit(dir: DocumentFile, depth: Int) {
            coroutineContext.ensureActive()
            if (depth > 10 || seen >= MAX_FILES) return
            for (child in dir.listFiles()) {
                coroutineContext.ensureActive()
                if (++seen % 100 == 0) onProgress(tr("$seen Dateien geprüft", "$seen files checked"))
                val name = child.name ?: continue
                if (child.isDirectory) {
                    visit(child, depth + 1)
                    continue
                }
                val lower = name.lowercase(Locale.ROOT)
                when {
                    lower == "_names.list" || lower == "recent.list" -> readSmallText(context, child.uri, 1024 * 1024)?.let { parseNameHints(it, hints) }
                    lower.endsWith(".po") -> readSmallText(context, child.uri, MAX_PO_BYTES)?.let { positions[name.removeSuffix(".po")] = parsePo(it) }
                    lower.endsWith(".an") -> readBytes(context, child.uri, MAX_AN_BYTES)?.let { decompressAnnotation(it)?.also { data -> annotations[name.removeSuffix(".an")] = data } }
                }
            }
        }
        visit(root, 0)
        buildFolderBooks(positions, annotations, hints)
    }

    suspend fun scanMrpro(context: Context, uri: Uri, onProgress: (String) -> Unit): List<BookItem> = withContext(Dispatchers.IO) {
        cleanupOldMrproCache(context)
        val workDir = File(context.cacheDir, "mrpro-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val names = readMrproNames(context, uri)
            val dbFile = File(workDir, "mrbooks.db")
            val embedded = linkedMapOf<String, EpubMatch>()
            val positions = linkedMapOf<String, MoonPosition>()
            val annotations = linkedMapOf<String, AnnotationData>()
            var entryCount = 0
            var sqliteDetectedBySignature = false

            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        entryCount++
                        if (entryCount > MAX_FILES) error(tr("Backup enthält zu viele Dateien", "Backup contains too many files"))
                        if (entryCount % 25 == 0) onProgress(tr("Backup: $entryCount Einträge geprüft", "Backup: $entryCount entries checked"))

                        val clean = entry.name.replace('\\', '/').trimStart('/')
                        if (clean.contains("../") || clean.startsWith("..")) continue
                        val candidates = mrproLogicalCandidates(clean, names)
                        val base = clean.substringAfterLast('/')
                        val numberedTag = Regex("^\\d+\\.tag$", RegexOption.IGNORE_CASE).matches(base)
                        val prefix = if (numberedTag) readPrefix(zip, SQLITE_HEADER.size) else byteArrayOf()

                        if (numberedTag && looksLikeSqliteHeader(prefix)) {
                            if (!dbFile.exists()) {
                                copyBoundedWithPrefix(zip, dbFile, MAX_DB_BYTES, prefix)
                                sqliteDetectedBySignature = true
                                onProgress(tr("Moon+-Datenbank erkannt", "Moon+ database detected"))
                            }
                            continue
                        }

                        val logical = chooseLogicalEntry(candidates)
                        when {
                            !numberedTag && logical.equals("mrbooks.db", true) -> copyBounded(zip, dbFile, MAX_DB_BYTES)
                            logical.equals("positions10.xml", true) -> readEntryBytes(zip, MAX_META_BYTES, prefix)?.let { parsePositionsXml(it.toString(Charsets.UTF_8), positions) }
                            logical.endsWith(".po", true) -> readEntryBytes(zip, MAX_PO_BYTES, prefix)?.toString(Charsets.UTF_8)?.let {
                                positions[logical.substringAfterLast('/').removeSuffix(".po")] = parsePo(it)
                            }
                            logical.endsWith(".an", true) -> readEntryBytes(zip, MAX_AN_BYTES, prefix)?.let { bytes ->
                                decompressAnnotation(bytes)?.let { data -> annotations[logical.substringAfterLast('/').removeSuffix(".an").lowercase(Locale.ROOT)] = data }
                            }
                            extensionOf(logical) in BOOK_EXTENSIONS -> {
                                val digest = hashArchiveEntry(zip, prefix)
                                val match = EpubMatch(
                                    backupUri = uri,
                                    archiveEntryName = clean,
                                    fileName = logical.substringAfterLast('/'),
                                    partialMd5 = digest.hash,
                                    size = digest.size,
                                )
                                registerEmbedded(embedded, logical, match)
                            }
                        }
                    }
                }
            } ?: error(tr("Backup konnte nicht geöffnet werden", "Could not open backup"))

            val fallback = fallbackBooks(positions, annotations, embedded)
            if (!dbFile.exists()) {
                if (fallback.isNotEmpty()) return@withContext fallback
                error(tr(
                    "Backup analysiert ($entryCount Einträge), aber keine lesbaren Moon+-Buchdaten gefunden.",
                    "Backup analyzed ($entryCount entries), but no readable Moon+ book data was found.",
                ))
            }

            val books = runCatching { readDatabase(dbFile, embedded, positions, annotations) }.getOrElse { cause ->
                if (fallback.isNotEmpty()) return@withContext fallback
                val mode = if (sqliteDetectedBySignature) tr("per SQLite-Signatur", "by SQLite signature") else tr("über Backup-Index", "through backup index")
                error(tr(
                    "Moon+-Datenbank $mode erkannt, aber Buchdaten konnten nicht gelesen werden: ${cause.message ?: "unbekannt"}",
                    "Moon+ database detected $mode, but book data could not be read: ${cause.message ?: "unknown"}",
                ))
            }
            if (books.isNotEmpty()) return@withContext books
            if (fallback.isNotEmpty()) return@withContext fallback
            error(tr(
                "Backup analysiert ($entryCount Einträge, Datenbank erkannt), aber keine Bücher konnten zugeordnet werden.",
                "Backup analyzed ($entryCount entries, database detected), but no books could be mapped.",
            ))
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    suspend fun inspectSelectedEpub(context: Context, uri: Uri): EpubMatch? = withContext(Dispatchers.IO) {
        val name = displayName(context, uri) ?: return@withContext null
        val md5 = context.contentResolver.openInputStream(uri)?.use { partialMd5(it.buffered()) } ?: return@withContext null
        val meta = context.contentResolver.openInputStream(uri)?.use { inspectEpubStream(it, name) }
        EpubMatch(uri = uri, fileName = name, title = meta?.title, author = meta?.author, isbn = meta?.isbn, partialMd5 = md5, cover = meta?.cover, size = querySize(context, uri))
    }

    fun autoMatch(books: List<BookItem>, epubs: List<EpubMatch>): List<BookItem> = books.map { book ->
        if (book.hasBookFile && !book.epub?.partialMd5.isNullOrBlank()) book else {
            val byFile = epubs.firstOrNull { normalize(it.fileName.substringBeforeLast('.')) == normalize(book.originalName.substringAfterLast('/')) }
            val wanted = normalize(book.title)
            val byTitle = epubs.firstOrNull { wanted.isNotBlank() && normalize(it.title.orEmpty()) == wanted }
            val match = byFile ?: byTitle
            if (match == null) book else book.copy(epub = match, author = book.author ?: match.author, isbn = book.isbn ?: match.isbn)
        }
    }

    private fun cleanupOldMrproCache(context: Context) {
        context.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("mrpro-") }?.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun chooseLogicalEntry(candidates: List<String>): String {
        val preferred = candidates.firstOrNull { candidate ->
            val lower = candidate.lowercase(Locale.ROOT)
            lower == "positions10.xml" || lower.endsWith(".po") || lower.endsWith(".an") || extensionOf(lower) in BOOK_EXTENSIONS
        }
        return preferred ?: candidates.firstOrNull().orEmpty()
    }

    private fun registerEmbedded(out: MutableMap<String, EpubMatch>, logical: String, match: EpubMatch) {
        out[logical.lowercase(Locale.ROOT)] = match
        out[logical.substringAfterLast('/').lowercase(Locale.ROOT)] = match
    }

    private data class ArchiveDigest(val hash: String, val size: Long)

    private fun hashArchiveEntry(input: java.io.InputStream, prefix: ByteArray = byteArrayOf()): ArchiveDigest {
        val digest = MessageDigest.getInstance("MD5")
        val chunks = Array(PARTIAL_MD5_OFFSETS.size) { ByteArray(1024) }
        val counts = IntArray(PARTIAL_MD5_OFFSETS.size)
        var absolute = 0L

        fun consume(bytes: ByteArray, length: Int) {
            for (i in PARTIAL_MD5_OFFSETS.indices) {
                val start = PARTIAL_MD5_OFFSETS[i]
                val end = start + 1024
                val blockEnd = absolute + length
                if (blockEnd <= start || absolute >= end) continue
                val from = maxOf(absolute, start)
                val to = minOf(blockEnd, end)
                val src = (from - absolute).toInt()
                val dst = (from - start).toInt()
                val len = (to - from).toInt()
                System.arraycopy(bytes, src, chunks[i], dst, len)
                counts[i] = maxOf(counts[i], dst + len)
            }
            absolute += length
        }

        if (prefix.isNotEmpty()) consume(prefix, prefix.size)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            consume(buffer, n)
        }
        for (i in chunks.indices) if (counts[i] > 0) digest.update(chunks[i], 0, counts[i])
        val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        return ArchiveDigest(hash, absolute)
    }

    private fun fallbackBooks(
        positions: Map<String, MoonPosition>,
        annotations: Map<String, AnnotationData>,
        embedded: Map<String, EpubMatch>,
    ): List<BookItem> {
        val keys = linkedSetOf<String>()
        keys += positions.keys
        keys += annotations.keys
        if (keys.isEmpty()) return emptyList()
        return keys.map { source ->
            val key = source.lowercase(Locale.ROOT)
            val match = embedded[key] ?: embedded[source.substringAfterLast('/').lowercase(Locale.ROOT)]
            folderBook(source, positions[source] ?: positions[key], annotations[key], match)
        }.sortedBy { sortTitle(it.title) }
    }

    private fun buildFolderBooks(positions: Map<String, MoonPosition>, annotations: Map<String, AnnotationData>, hints: Map<String, String>): List<BookItem> =
        (positions.keys + annotations.keys).distinct().map { source ->
            val original = source.substringBeforeLast('.', source)
            val cryptic = source.matches(Regex("^[0-9a-fA-F]{32}\\.(epub|pdf|mobi|azw3|cbr|cbz)$"))
            val hint = hints[source.lowercase(Locale.ROOT)] ?: hints[source.substringBefore('.').lowercase(Locale.ROOT)]
            BookItem(
                key = source.lowercase(Locale.ROOT), sourceFile = source, originalName = original,
                extension = source.substringAfterLast('.', "").lowercase(Locale.ROOT), title = hint ?: if (cryptic) source else original,
                position = positions[source], annotation = annotations[source] ?: annotations[source.lowercase(Locale.ROOT)], isCryptic = cryptic,
            )
        }.sortedBy { sortTitle(it.title) }

    private fun folderBook(source: String, position: MoonPosition?, annotation: AnnotationData?, match: EpubMatch?): BookItem {
        val original = source.substringBeforeLast('.', source)
        return BookItem(
            key = source.lowercase(Locale.ROOT),
            sourceFile = source,
            originalName = original,
            extension = source.substringAfterLast('.', "").lowercase(Locale.ROOT),
            title = original.substringAfterLast('/'),
            position = position,
            annotation = annotation,
            epub = match,
            includedInBackup = match?.backupUri != null,
        )
    }

    private fun readMrproNames(context: Context, uri: Uri): List<String> {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("_names.list", true)) {
                        return readBounded(zip, MAX_META_BYTES)?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF")?.lines()?.map { it.trim() } ?: emptyList()
                    }
                }
            }
        }
        return emptyList()
    }

    internal fun mrproLogicalCandidates(path: String, names: List<String>): List<String> {
        val base = path.substringAfterLast('/')
        val n = Regex("^(\\d+)\\.tag$", RegexOption.IGNORE_CASE).matchEntire(base)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return listOf(base)
        val out = mutableListOf<String>()
        if (n > 0 && n <= names.size) names[n - 1].takeIf { it.isNotBlank() }?.let(out::add)
        if (n >= 0 && n < names.size) names[n].takeIf { it.isNotBlank() && it !in out }?.let(out::add)
        if (base !in out) out += base
        return out
    }

    internal fun looksLikeSqliteHeader(bytes: ByteArray): Boolean =
        bytes.size >= SQLITE_HEADER.size && SQLITE_HEADER.indices.all { bytes[it] == SQLITE_HEADER[it] }

    private fun readDatabase(
        dbFile: File,
        embedded: Map<String, EpubMatch>,
        positions: Map<String, MoonPosition>,
        archiveAnnotations: Map<String, AnnotationData>,
    ): List<BookItem> {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            val bookTable = findBookTable(it) ?: error(tr("Keine passende Büchertabelle gefunden", "No compatible book table found"))
            val notes = readNotes(it)
            val result = mutableListOf<BookItem>()
            it.rawQuery("SELECT * FROM ${quoteIdentifier(bookTable)}", null).use { c ->
                while (c.moveToNext()) {
                    val file = c.firstString("filename", "lowerFilename", "file", "filePath", "filepath", "path", "bookPath", "bookpath")
                        ?: c.firstString("book")?.takeIf { value -> value.contains('.') || value.contains('/') }
                        ?: continue
                    val lower = (c.firstString("lowerFilename") ?: file).lowercase(Locale.ROOT)
                    val baseLower = file.substringAfterLast('/').lowercase(Locale.ROOT)
                    val match = embedded[lower] ?: embedded[baseLower]
                    val title = c.firstString("title", "bookName", "bookname", "name")
                        ?: c.firstString("book")?.takeIf { value -> value != file }
                        ?: file.substringAfterLast('/').substringBeforeLast('.')
                    val description = c.firstString("description", "desc")
                    val dbRecords = notes[lower] ?: notes[baseLower]
                    val archiveAnnotation = archiveAnnotations[lower] ?: archiveAnnotations[baseLower] ?: archiveAnnotations[file.substringBeforeLast('.').lowercase(Locale.ROOT)]
                    val annotation = when {
                        !dbRecords.isNullOrEmpty() -> AnnotationData(records = dbRecords)
                        archiveAnnotation != null -> archiveAnnotation
                        else -> null
                    }
                    val position = positions[file] ?: positions[lower] ?: positions[baseLower] ?: positions[file.substringBeforeLast('.')]
                    result += BookItem(
                        key = lower,
                        sourceFile = file,
                        originalName = file.substringBeforeLast('.', file),
                        extension = file.substringAfterLast('.', "").lowercase(Locale.ROOT),
                        title = title,
                        author = c.firstString("author", "authors", "writer"),
                        isbn = c.firstString("isbn") ?: extractIsbn(description),
                        position = position,
                        annotation = annotation,
                        isCryptic = file.substringAfterLast('/').matches(Regex("^[0-9a-fA-F]{32}\\..+")),
                        epub = match,
                        includedInBackup = match?.backupUri != null,
                    )
                }
            }
            return result.distinctBy { it.key }.sortedBy { book -> sortTitle(book.title) }
        }
    }

    private fun findBookTable(db: SQLiteDatabase): String? {
        val tables = mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let(tables::add)
        }
        tables.firstOrNull { it.equals("books", true) }?.let { return it }
        var best: Pair<String, Int>? = null
        for (table in tables) {
            val columns = tableColumns(db, table)
            var score = 0
            if (columns.any { it in setOf("filename", "lowerfilename", "file", "filepath", "path", "bookpath") }) score += 6
            if (columns.any { it in setOf("book", "title", "bookname", "name") }) score += 3
            if (columns.any { it in setOf("author", "authors", "writer") }) score += 1
            if (best == null || score > best!!.second) best = table to score
        }
        return best?.takeIf { it.second >= 6 }?.first
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val out = linkedSetOf<String>()
        db.rawQuery("PRAGMA table_info(${quoteIdentifier(table)})", null).use { c ->
            val idx = c.getColumnIndex("name")
            while (c.moveToNext()) if (idx >= 0) out += c.getString(idx).lowercase(Locale.ROOT)
        }
        return out
    }

    private fun readNotes(db: SQLiteDatabase): Map<String, MutableList<AnnotationRecord>> {
        val out = linkedMapOf<String, MutableList<AnnotationRecord>>()
        val table = listOf("notes", "annotations", "highlights").firstOrNull { candidate ->
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND lower(name)=? LIMIT 1", arrayOf(candidate)).use { it.moveToFirst() }
        } ?: return out
        runCatching {
            db.rawQuery("SELECT * FROM ${quoteIdentifier(table)}", null).use { c ->
                while (c.moveToNext()) {
                    val file = c.firstString("filename", "lowerFilename", "file", "filepath", "path", "book") ?: continue
                    val key = file.lowercase(Locale.ROOT)
                    out.getOrPut(key) { mutableListOf() }.add(
                        AnnotationRecord(
                            id = c.firstLong("_id", "id") ?: 0L,
                            chapter = c.firstInt("lastChapter", "chapter"),
                            splitIndex = c.firstInt("lastSplitIndex", "splitIndex"),
                            position = c.firstLong("lastPosition", "position"),
                            length = c.firstInt("highlightLength", "length"),
                            color = c.firstInt("highlightColor", "color"),
                            timestampMs = c.firstLong("time", "timestamp"),
                            bookmark = (c.firstInt("bookmark") ?: 0) != 0,
                            note = c.firstString("note", "text"),
                            original = c.firstString("original", "quote"),
                        )
                    )
                }
            }
        }
        return out
    }

    private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun extractIsbn(text: String?): String? = text?.let { Regex("(?:ISBN(?:-1[03])?[: ]*)?((?:97[89][ -]?)?[0-9][0-9 -]{8,15}[0-9Xx])").find(it)?.groupValues?.getOrNull(1)?.replace(Regex("[ -]"), "") }

    private fun parsePositionsXml(xml: String, out: MutableMap<String, MoonPosition>) {
        if (Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return
        runCatching {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser()
            runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) }
            parser.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8")
            var type = parser.eventType
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    var file: String? = null
                    var pos: String? = null
                    for (i in 0 until parser.attributeCount) {
                        when (parser.getAttributeName(i).lowercase(Locale.ROOT)) {
                            "filename", "file", "book" -> file = parser.getAttributeValue(i)
                            "position", "pos", "value" -> pos = parser.getAttributeValue(i)
                        }
                    }
                    if (!file.isNullOrBlank() && !pos.isNullOrBlank()) {
                        out[file] = parsePo(pos)
                        out[file.lowercase(Locale.ROOT)] = parsePo(pos)
                    }
                }
                type = parser.next()
            }
        }
    }

    private data class EpubMeta(val title: String?, val author: String?, val isbn: String?, val cover: android.graphics.Bitmap?)

    private fun inspectEpubStream(input: java.io.InputStream, name: String): EpubMeta? {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val clean = entry.name.replace('\\', '/').trimStart('/')
                if (entry.isDirectory || clean.contains("../") || entries.size >= 600) continue
                val bytes = readBounded(zip, 8 * 1024 * 1024) ?: continue
                if (clean.endsWith(".opf", true) || clean.endsWith(".jpg", true) || clean.endsWith(".jpeg", true) || clean.endsWith(".png", true)) entries[clean] = bytes
            }
        }
        val opfEntry = entries.entries.firstOrNull { it.key.endsWith(".opf", true) } ?: return EpubMeta(name.substringBeforeLast('.'), null, null, null)
        val opf = opfEntry.value.toString(Charsets.UTF_8)
        fun tag(tag: String) = Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(opf)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), "")?.trim()
        val title = tag("dc:title")
        val author = tag("dc:creator")
        val isbn = Regex("<dc:identifier[^>]*>([^<]*(?:ISBN)?[^<]*)</dc:identifier>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(opf).mapNotNull { extractIsbn(it.groupValues[1]) }.firstOrNull()
        val coverId = Regex("<meta[^>]+name=[\"']cover[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.getOrNull(1)
        val href = coverId?.let { id -> Regex("<item[^>]+id=[\"']${Regex.escape(id)}[\"'][^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.getOrNull(1) }
            ?: Regex("<item[^>]+properties=[\"'][^\"']*cover-image[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.getOrNull(1)
        val base = opfEntry.key.substringBeforeLast('/', "")
        val cleanHref = href?.replace('\\', '/')?.trimStart('/')
        val full = if (cleanHref != null) listOf(base, cleanHref).filter { it.isNotBlank() }.joinToString("/") else null
        val image = full?.takeIf { !it.contains("../") }?.let { entries[it] ?: entries.entries.firstOrNull { e -> e.key.endsWith(cleanHref ?: "") }?.value }
        return EpubMeta(title, author, isbn, image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
    }

    private fun decompressAnnotation(bytes: ByteArray): AnnotationData? = runCatching {
        val text = InflaterInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).readText()
        AnnotationData(originalMrexpt = text)
    }.getOrNull()

    private fun parseNameHints(text: String, out: MutableMap<String, String>) {
        text.lines().forEach { line ->
            val parts = line.split('\t', '|', '=', limit = 2).map { it.trim() }
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) out[parts[0].lowercase(Locale.ROOT)] = parts[1]
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return uri.lastPathSegment
    }

    private fun querySize(context: Context, uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0) }
        return null
    }

    private fun extensionOf(path: String): String = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun readSmallText(context: Context, uri: Uri, max: Int): String? = readBytes(context, uri, max)?.toString(Charsets.UTF_8)
    private fun readBytes(context: Context, uri: Uri, max: Int): ByteArray? = context.contentResolver.openInputStream(uri)?.use { readBounded(it, max) }

    private fun readPrefix(input: java.io.InputStream, count: Int): ByteArray {
        val out = ByteArrayOutputStream(count)
        val buffer = ByteArray(count)
        while (out.size() < count) {
            val n = input.read(buffer, 0, count - out.size())
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun readEntryBytes(input: java.io.InputStream, max: Int, prefix: ByteArray): ByteArray? =
        if (prefix.isEmpty()) readBounded(input, max) else readBoundedWithPrefix(input, max, prefix)

    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readBoundedWithPrefix(input: java.io.InputStream, max: Int, prefix: ByteArray): ByteArray? {
        if (prefix.size > max) return null
        val remainder = readBounded(input, max - prefix.size) ?: return null
        return prefix + remainder
    }

    private fun copyBounded(input: java.io.InputStream, target: File, max: Long) = copyBoundedWithPrefix(input, target, max, byteArrayOf())

    private fun copyBoundedWithPrefix(input: java.io.InputStream, target: File, max: Long, prefix: ByteArray) {
        FileOutputStream(target).use { out ->
            var total = prefix.size.toLong()
            if (total > max) error(tr("Backup-Datenbank zu groß", "Backup database too large"))
            out.write(prefix)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) error(tr("Backup-Datenbank zu groß", "Backup database too large"))
                out.write(buf, 0, n)
            }
        }
    }

    private fun Cursor.firstString(vararg names: String): String? {
        for (name in names) {
            val index = getColumnIndex(name)
            if (index >= 0 && !isNull(index)) getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun Cursor.firstInt(vararg names: String): Int? {
        for (name in names) {
            val index = getColumnIndex(name)
            if (index >= 0 && !isNull(index)) return getInt(index)
        }
        return null
    }

    private fun Cursor.firstLong(vararg names: String): Long? {
        for (name in names) {
            val index = getColumnIndex(name)
            if (index >= 0 && !isNull(index)) return getLong(index)
        }
        return null
    }
}

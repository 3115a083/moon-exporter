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
        val workDir = File(context.cacheDir, "mrpro-${UUID.randomUUID()}").apply { mkdirs() }
        val names = readMrproNames(context, uri)
        val dbFile = File(workDir, "mrbooks.db")
        val embedded = linkedMapOf<String, EpubMatch>()
        val xmlPositions = linkedMapOf<String, MoonPosition>()
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
                    if (entryCount % 20 == 0) onProgress(tr("Backup: $entryCount Einträge", "Backup: $entryCount entries"))
                    val clean = entry.name.replace('\\', '/').trimStart('/')
                    if (clean.contains("../")) continue
                    val candidates = mrproLogicalCandidates(clean, names)
                    val base = clean.substringAfterLast('/')
                    val isNumberedTag = Regex("^\\d+\\.tag$", RegexOption.IGNORE_CASE).matches(base)

                    if (isNumberedTag) {
                        val prefix = readPrefix(zip, SQLITE_HEADER.size)
                        when {
                            looksLikeSqliteHeader(prefix) -> {
                                if (!dbFile.exists()) {
                                    copyBoundedWithPrefix(zip, dbFile, MAX_DB_BYTES, prefix)
                                    sqliteDetectedBySignature = true
                                    onProgress(tr("Backup-Datenbank erkannt", "Backup database detected"))
                                }
                            }
                            candidates.any { it.equals("mrbooks.db", true) } -> copyBoundedWithPrefix(zip, dbFile, MAX_DB_BYTES, prefix)
                            candidates.any { it.equals("positions10.xml", true) } -> {
                                readBoundedWithPrefix(zip, MAX_META_BYTES, prefix)?.let { parsePositionsXml(it.toString(Charsets.UTF_8), xmlPositions) }
                            }
                            else -> {
                                val logicalBook = candidates.firstOrNull { candidate ->
                                    candidate.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("epub", "pdf", "mobi", "azw3")
                                }
                                if (logicalBook != null) {
                                    val target = File(workDir, safeName(logicalBook.substringAfterLast('/')))
                                    val md5 = copyBookAndHash(zip, target, prefix)
                                    val inspected = if (logicalBook.endsWith(".epub", true)) inspectEpubFile(target, logicalBook, md5)
                                    else EpubMatch(embeddedPath = target.absolutePath, fileName = logicalBook.substringAfterLast('/'), partialMd5 = md5, size = target.length())
                                    embedded[logicalBook.lowercase(Locale.ROOT)] = inspected
                                    embedded[logicalBook.substringAfterLast('/').lowercase(Locale.ROOT)] = inspected
                                }
                            }
                        }
                        continue
                    }

                    val logical = candidates.first()
                    when {
                        logical.equals("mrbooks.db", true) -> copyBounded(zip, dbFile, MAX_DB_BYTES)
                        logical.equals("positions10.xml", true) -> readBounded(zip, MAX_META_BYTES)?.let { parsePositionsXml(it.toString(Charsets.UTF_8), xmlPositions) }
                        logical.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("epub", "pdf", "mobi", "azw3") -> {
                            val target = File(workDir, safeName(logical.substringAfterLast('/')))
                            val md5 = copyBookAndHash(zip, target)
                            val inspected = if (logical.endsWith(".epub", true)) inspectEpubFile(target, logical, md5)
                            else EpubMatch(embeddedPath = target.absolutePath, fileName = logical.substringAfterLast('/'), partialMd5 = md5, size = target.length())
                            embedded[logical.lowercase(Locale.ROOT)] = inspected
                            embedded[logical.substringAfterLast('/').lowercase(Locale.ROOT)] = inspected
                        }
                    }
                }
            }
        } ?: error(tr("Backup konnte nicht geöffnet werden", "Could not open backup"))

        if (!dbFile.exists()) {
            if (xmlPositions.isNotEmpty()) return@withContext xmlPositions.map { (file, pos) -> folderBook(file, pos, null, null) }
            error(tr(
                "Backup analysiert ($entryCount Einträge), aber keine Moon+-Buchdatenbank oder Positionsdaten erkannt.",
                "Backup analyzed ($entryCount entries), but no Moon+ book database or position data was detected.",
            ))
        }

        val books = runCatching { readDatabase(dbFile, embedded, xmlPositions) }.getOrElse { cause ->
            if (xmlPositions.isNotEmpty()) return@withContext xmlPositions.map { (file, pos) -> folderBook(file, pos, null, null) }
            val mode = if (sqliteDetectedBySignature) tr("per SQLite-Signatur", "by SQLite signature") else tr("über Backup-Index", "through backup index")
            error(tr(
                "Moon+-Datenbank $mode erkannt, aber Buchdaten konnten nicht gelesen werden: ${cause.message ?: "unbekannt"}",
                "Moon+ database detected $mode, but book data could not be read: ${cause.message ?: "unknown"}",
            ))
        }
        if (books.isNotEmpty()) return@withContext books
        if (xmlPositions.isNotEmpty()) return@withContext xmlPositions.map { (file, pos) -> folderBook(file, pos, null, null) }
        error(tr(
            "Backup analysiert ($entryCount Einträge, Datenbank erkannt), aber die Datenbank enthält keine lesbaren Bücher.",
            "Backup analyzed ($entryCount entries, database detected), but the database contains no readable books.",
        ))
    }

    suspend fun inspectSelectedEpub(context: Context, uri: Uri): EpubMatch? = withContext(Dispatchers.IO) {
        val name = displayName(context, uri) ?: return@withContext null
        val md5 = context.contentResolver.openInputStream(uri)?.use { partialMd5(it.buffered()) } ?: return@withContext null
        val meta = context.contentResolver.openInputStream(uri)?.use { inspectEpubStream(it, name) }
        EpubMatch(uri = uri, fileName = name, title = meta?.title, author = meta?.author, isbn = meta?.isbn, partialMd5 = md5, cover = meta?.cover, size = querySize(context, uri))
    }

    fun autoMatch(books: List<BookItem>, epubs: List<EpubMatch>): List<BookItem> = books.map { book ->
        if (book.hasBookFile) book else {
            val byFile = epubs.firstOrNull { normalize(it.fileName.substringBeforeLast('.')) == normalize(book.originalName) }
            val wanted = normalize(book.title)
            val byTitle = epubs.firstOrNull { wanted.isNotBlank() && normalize(it.title.orEmpty()) == wanted }
            val match = byFile ?: byTitle
            if (match == null) book else book.copy(epub = match, author = book.author ?: match.author, isbn = book.isbn ?: match.isbn)
        }
    }

    private fun buildFolderBooks(positions: Map<String, MoonPosition>, annotations: Map<String, AnnotationData>, hints: Map<String, String>): List<BookItem> =
        (positions.keys + annotations.keys).distinct().map { source ->
            val original = source.substringBeforeLast('.', source)
            val cryptic = source.matches(Regex("^[0-9a-fA-F]{32}\\.(epub|pdf|mobi|azw3|cbr)$"))
            val hint = hints[source.lowercase(Locale.ROOT)] ?: hints[source.substringBefore('.').lowercase(Locale.ROOT)]
            BookItem(
                key = source.lowercase(Locale.ROOT), sourceFile = source, originalName = original,
                extension = source.substringAfterLast('.', "").lowercase(Locale.ROOT), title = hint ?: if (cryptic) source else original,
                position = positions[source], annotation = annotations[source], isCryptic = cryptic,
            )
        }.sortedBy { sortTitle(it.title) }

    private fun folderBook(source: String, position: MoonPosition?, annotation: AnnotationData?, match: EpubMatch?): BookItem {
        val original = source.substringBeforeLast('.', source)
        return BookItem(source.lowercase(Locale.ROOT), source, original, source.substringAfterLast('.', "").lowercase(Locale.ROOT), original, position = position, annotation = annotation, epub = match, includedInBackup = match?.embeddedPath != null)
    }

    private fun readMrproNames(context: Context, uri: Uri): List<String> {
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("_names.list", true)) {
                        return readBounded(zip, MAX_META_BYTES)?.toString(Charsets.UTF_8)?.lines()?.map { it.trim() } ?: emptyList()
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

    private fun readDatabase(dbFile: File, embedded: Map<String, EpubMatch>, positions: Map<String, MoonPosition>): List<BookItem> {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            val hasBooks = it.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND lower(name)='books' LIMIT 1", null).use { c -> c.moveToFirst() }
            if (!hasBooks) error(tr("Tabelle 'books' fehlt", "Missing 'books' table"))

            val notes = linkedMapOf<String, MutableList<AnnotationRecord>>()
            runCatching {
                it.rawQuery("SELECT * FROM notes", null).use { c ->
                    while (c.moveToNext()) {
                        val file = c.string("filename") ?: continue
                        notes.getOrPut(file.lowercase(Locale.ROOT)) { mutableListOf() }.add(
                            AnnotationRecord(
                                id = c.long("_id") ?: 0L,
                                chapter = c.int("lastChapter"), splitIndex = c.int("lastSplitIndex"), position = c.long("lastPosition"),
                                length = c.int("highlightLength"), color = c.int("highlightColor"), timestampMs = c.long("time"),
                                bookmark = (c.int("bookmark") ?: 0) != 0, note = c.string("note"), original = c.string("original"),
                            )
                        )
                    }
                }
            }
            val result = mutableListOf<BookItem>()
            it.rawQuery("SELECT * FROM books", null).use { c ->
                while (c.moveToNext()) {
                    val file = c.string("filename") ?: c.string("book") ?: continue
                    val lower = (c.string("lowerFilename") ?: file).lowercase(Locale.ROOT)
                    val match = embedded[lower] ?: embedded[file.substringAfterLast('/').lowercase(Locale.ROOT)]
                    val title = c.string("book")?.takeIf { value -> value.isNotBlank() } ?: file.substringBeforeLast('.')
                    val description = c.string("description")
                    result += BookItem(
                        key = lower, sourceFile = file, originalName = file.substringBeforeLast('.', file), extension = file.substringAfterLast('.', "").lowercase(Locale.ROOT),
                        title = title, author = c.string("author"), isbn = extractIsbn(description), position = positions[file] ?: positions[lower],
                        annotation = notes[lower]?.let { records -> AnnotationData(records = records) }, isCryptic = file.substringAfterLast('/').matches(Regex("^[0-9a-fA-F]{32}\\..+")),
                        epub = match, includedInBackup = match?.embeddedPath != null,
                    )
                }
            }
            return result.sortedBy { book -> sortTitle(book.title) }
        }
    }

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
                    if (!file.isNullOrBlank() && !pos.isNullOrBlank()) out[file] = parsePo(pos)
                }
                type = parser.next()
            }
        }
    }

    private data class EpubMeta(val title: String?, val author: String?, val isbn: String?, val cover: android.graphics.Bitmap?)

    private fun inspectEpubFile(file: File, name: String, md5: String): EpubMatch {
        val meta = file.inputStream().use { inspectEpubStream(it, name) }
        return EpubMatch(embeddedPath = file.absolutePath, fileName = name.substringAfterLast('/'), title = meta?.title, author = meta?.author, isbn = meta?.isbn, partialMd5 = md5, cover = meta?.cover, size = file.length())
    }

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

    private fun copyBookAndHash(input: java.io.InputStream, target: File, prefix: ByteArray = byteArrayOf()): String {
        val digestChunks = Array(PARTIAL_MD5_OFFSETS.size) { ByteArray(1024) }
        val counts = IntArray(PARTIAL_MD5_OFFSETS.size)
        var absolute = 0L

        fun consume(bytes: ByteArray, length: Int, out: FileOutputStream) {
            out.write(bytes, 0, length)
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
                System.arraycopy(bytes, src, digestChunks[i], dst, len)
                counts[i] = maxOf(counts[i], dst + len)
            }
            absolute += length
        }

        FileOutputStream(target).use { out ->
            if (prefix.isNotEmpty()) consume(prefix, prefix.size, out)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                consume(buf, n, out)
            }
        }
        val md = java.security.MessageDigest.getInstance("MD5")
        for (i in digestChunks.indices) if (counts[i] > 0) md.update(digestChunks[i], 0, counts[i])
        return md.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun Cursor.string(name: String): String? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
    private fun Cursor.int(name: String): Int? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) }
    private fun Cursor.long(name: String): Long? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
}

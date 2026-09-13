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

/**
 * Writes books directly into Readest's documented on-disk library layout.
 *
 * Readest keeps the shared shelf index in Books/library.json and managed books
 * under Books/<bookHash>/. bookHash is Readest's partialMD5, which intentionally
 * differs from KOReader's partialMD5 used by KOSync.
 *
 * nav.json is a derived cache. We deliberately do not fabricate it: current
 * Readest recomputes it from the EPUB when absent or stale. This keeps the
 * export compatible with future BOOK_NAV_VERSION changes.
 */
internal object ReadestDirectExporter {
    private const val CONFIG_SCHEMA_VERSION = 3
    private val READest_OFFSETS = longArrayOf(
        0L, 1024L, 4096L, 16384L, 65536L, 262144L,
        1048576L, 4194304L, 16777216L, 67108864L, 268435456L, 1073741824L,
    )

    internal data class Result(val exported: Int, val skipped: Int, val warnings: List<String>)

    suspend fun export(
        context: Context,
        targetTree: Uri,
        books: List<BookItem>,
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
            val source = book.epub ?: run { skipped++; continue }
            val ext = source.fileName.substringAfterLast('.', book.extension).lowercase(Locale.ROOT)
            if (ext !in setOf("epub", "pdf")) {
                skipped++
                warnings += tr("${book.title}: Direkt-Export derzeit nur EPUB/PDF", "${book.title}: direct export currently supports EPUB/PDF")
                continue
            }

            val readestHash = openBookSource(context, source) { readestPartialMd5(it) }
            val metaHash = metadataHash(book)
            val dir = booksRoot.findFile(readestHash)?.takeIf { it.isDirectory }
                ?: booksRoot.createDirectory(readestHash)
                ?: error(tr("Readest-Buchordner konnte nicht angelegt werden", "Could not create Readest book folder"))

            val targetName = "${safeName(book.title)}.$ext"
            var targetBook = dir.findFile(targetName)
            if (targetBook == null) {
                targetBook = dir.createFile(mimeFor(ext), targetName)
                    ?: error(tr("Readest-Buchdatei konnte nicht angelegt werden", "Could not create Readest book file"))
                context.contentResolver.openOutputStream(targetBook.uri, "w")?.use { output ->
                    openBookSource(context, source) { input -> input.copyTo(output, 64 * 1024) }
                } ?: error(tr("Readest-Buchdatei konnte nicht geschrieben werden", "Could not write Readest book file"))
            }

            val now = System.currentTimeMillis()
            val coverHash = writeCoverIfNeeded(context, dir, source.cover)
            val existingConfigFile = dir.findFile("config.json")
            val config = existingConfigFile?.let { file ->
                readText(context, file, 8 * 1024 * 1024)?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            } ?: JSONObject()
            mergeConfig(config, book, readestHash, metaHash, now)
            writeOrReplaceText(context, dir, "config.json", config.toString())

            // Keep the original Moon+ annotation payload as a lossless fallback.
            if (book.hasAnnotations) {
                writeOrReplaceText(context, dir, "moon-export.mrexpt", Exporter.mrexptFor(book))
            }

            val row = byHash[readestHash] ?: JSONObject().also { byHash[readestHash] = it }
            mergeLibraryRow(row, book, readestHash, metaHash, coverHash, now, ext)
            exported++
        }

        if (originalLibrary.isNotBlank()) {
            writeOrReplaceText(context, booksRoot, "library.json.bak", originalLibrary)
        }
        val merged = JSONArray()
        byHash.values.forEach { merged.put(it) }
        writeOrReplaceText(context, booksRoot, "library.json", merged.toString())
        booksRoot.findFile(".nomedia") ?: booksRoot.createFile("application/octet-stream", ".nomedia")

        Result(exported, skipped, warnings)
    }

    private fun mergeConfig(config: JSONObject, book: BookItem, bookHash: String, metaHash: String, now: Long) {
        config.put("schemaVersion", maxOf(CONFIG_SCHEMA_VERSION, config.optInt("schemaVersion", 0)))
        config.put("bookHash", bookHash)
        config.put("metaHash", metaHash)
        config.put("updatedAt", now)
        if (!config.has("viewSettings")) config.put("viewSettings", JSONObject())
        if (!config.has("searchConfig")) config.put("searchConfig", JSONObject())

        book.position?.percent?.let { percent ->
            val current = percent.coerceIn(0.0, 100.0).roundToInt()
            // Readest documents progress as [current,total]. Using a 100-unit
            // fallback preserves the Moon+ percentage without inventing a CFI.
            config.put("progress", JSONArray().put(current).put(100))
        }

        val existing = config.optJSONArray("booknotes") ?: JSONArray()
        val knownIds = mutableSetOf<String>()
        for (i in 0 until existing.length()) existing.optJSONObject(i)?.optString("id")?.let(knownIds::add)
        for (record in book.annotation?.records.orEmpty()) {
            val note = directNote(bookHash, metaHash, record) ?: continue
            if (knownIds.add(note.getString("id"))) existing.put(note)
        }
        if (existing.length() > 0) config.put("booknotes", existing)
    }

    private fun directNote(bookHash: String, metaHash: String, record: AnnotationRecord): JSONObject? {
        val chapter = record.chapter ?: return null
        if (chapter < 0 || chapter > 100000) return null
        val text = record.original?.trim().orEmpty()
        val noteText = record.note.orEmpty()
        if (text.isBlank() && noteText.isBlank()) return null
        // Conservative chapter-start CFI. We never invent an in-document text
        // path. Readest itself uses the same form as a fallback for Moon+ imports.
        val spineStep = 2 * (chapter + 1)
        val cfi = "epubcfi(/6/$spineStep!)"
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
        book: BookItem,
        hash: String,
        metaHash: String,
        coverHash: String?,
        now: Long,
        extension: String,
    ) {
        val format = extension.uppercase(Locale.ROOT)
        row.put("hash", hash)
        row.put("format", format)
        row.put("metaHash", metaHash)
        row.put("title", book.title)
        row.put("sourceTitle", book.title)
        row.put("primaryLanguage", row.optString("primaryLanguage").ifBlank { "en" })
        book.author?.takeIf { it.isNotBlank() }?.let { row.put("author", it) }

        val metadata = row.optJSONObject("metadata") ?: JSONObject()
        metadata.put("title", book.title)
        metadata.put("sortAs", book.title)
        book.author?.takeIf { it.isNotBlank() }?.let { metadata.put("author", it) }
        book.isbn?.takeIf { it.isNotBlank() }?.let {
            metadata.put("identifier", it)
            metadata.put("isbn", it)
            if (!metadata.has("altIdentifier")) metadata.put("altIdentifier", JSONArray().put(it))
        }
        row.put("metadata", metadata)
        if (!row.has("createdAt")) row.put("createdAt", now)
        if (!row.has("downloadedAt")) row.put("downloadedAt", now)
        row.put("updatedAt", now)
        row.put("metadataUpdatedAt", now)
        row.put("deletedAt", JSONObject.NULL)
        coverHash?.let { row.put("coverHash", it) }
        book.position?.percent?.let { row.put("progress", JSONArray().put(it.coerceIn(0.0, 100.0).roundToInt()).put(100)) }
    }

    private fun metadataHash(book: BookItem): String {
        val title = book.title.trim()
        val authors = book.author.orEmpty().trim()
        val identifiers = book.isbn.orEmpty().trim()
        return md5(Normalizer.normalize("$title|$authors|$identifiers", Normalizer.Form.NFC))
    }

    internal fun readestPartialMd5(input: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val chunks = Array(READest_OFFSETS.size) { ByteArray(1024) }
        val counts = IntArray(READest_OFFSETS.size)
        var absolute = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            val blockEnd = absolute + n
            for (i in READest_OFFSETS.indices) {
                val start = READest_OFFSETS[i]
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
            if (READest_OFFSETS[i] >= absolute) break
            if (counts[i] > 0) digest.update(chunks[i], 0, counts[i])
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
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
                        val clean = entry.name.replace('\\', '/').trimStart('/')
                        if (clean == wanted) return block(zip)
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

    private fun readText(context: Context, file: DocumentFile, maxBytes: Int): String? {
        return context.contentResolver.openInputStream(file.uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) error(tr("Readest-Metadatei ist zu groß", "Readest metadata file is too large"))
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    private fun writeOrReplaceText(context: Context, dir: DocumentFile, name: String, text: String) {
        val existing = dir.findFile(name)
        val file = existing ?: dir.createFile("application/json", name)
            ?: error(tr("Datei konnte nicht angelegt werden: $name", "Could not create file: $name"))
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: error(tr("Datei konnte nicht geschrieben werden: $name", "Could not write file: $name"))
    }

    private fun moonColor(color: Int?): String = when (color) {
        null -> "yellow"
        in Int.MIN_VALUE..-10000000 -> "blue"
        else -> "yellow"
    }

    private fun mimeFor(extension: String): String = when (extension) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it) }
}

package de.moonexporter.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Routes EPUB/PDF through the mature exporter and handles the remaining formats supported by
 * both Moon+ Reader and Readest. Non-EPUB highlights are preserved losslessly as .mrexpt because
 * Readest's native annotation location model is format-specific and must not be fabricated.
 */
internal object UniversalReadestExporter {
    suspend fun export(
        context: Context,
        targetTree: Uri,
        books: List<BookItem>,
        normalizeBookNames: Boolean,
        onProgress: (ExportProgress) -> Unit,
    ): ReadestDirectExporter.Result = withContext(Dispatchers.IO) {
        var exported = 0
        var skipped = 0
        val warnings = mutableListOf<String>()
        books.forEachIndexed { index, book ->
            coroutineContext.ensureActive()
            val ext = BookFormats.extension(book.epub?.fileName.orEmpty(), book.extension)
            if (ext !in BookFormats.readestCompatible) {
                skipped++
                warnings += tr("${book.title}: Format $ext wird von Readest nicht unterstützt", "${book.title}: format $ext is not supported by Readest")
                return@forEachIndexed
            }
            if (ext == "epub" || ext == "pdf") {
                val result = ReadestDirectExporter.export(context, targetTree, listOf(book), normalizeBookNames, onProgress)
                exported += result.exported
                skipped += result.skipped
                warnings += result.warnings
            } else {
                onProgress(ExportProgress(tr("Buch ${index + 1}/${books.size} · universeller Readest-Export · ${book.title}", "Book ${index + 1}/${books.size} · universal Readest export · ${book.title}"), 0.15f))
                exportGeneric(context, targetTree, book, ext, normalizeBookNames)
                exported++
                onProgress(ExportProgress(tr("Buch ${index + 1}/${books.size} · geprüft · ${book.title}", "Book ${index + 1}/${books.size} · verified · ${book.title}"), 1f))
            }
        }
        ReadestDirectExporter.Result(exported, skipped, warnings)
    }

    private fun exportGeneric(context: Context, targetTree: Uri, book: BookItem, ext: String, normalizeBookNames: Boolean) {
        val source = requireNotNull(book.epub) { tr("Keine Buchdatei zugeordnet", "No book file matched") }
        StorageSafety.ensureCacheCapacity(context, source.size)
        val root = DocumentFile.fromTreeUri(context, targetTree) ?: error(tr("Readest-Zielordner nicht verfügbar", "Readest target folder unavailable"))
        val booksRoot = when {
            root.name.equals("Books", true) -> root
            root.findFile("Books")?.isDirectory == true -> requireNotNull(root.findFile("Books"))
            else -> root.createDirectory("Books") ?: error(tr("Readest/Books konnte nicht angelegt werden", "Could not create Readest/Books"))
        }

        withPreparedSource(context, source, ext) { local ->
            val hash = local.inputStream().buffered().use(ReadestDirectExporter::readestPartialMd5)
            val dir = booksRoot.findFile(hash)?.takeIf { it.isDirectory } ?: booksRoot.createDirectory(hash)
                ?: error(tr("Readest-Buchordner konnte nicht angelegt werden", "Could not create Readest book folder"))
            val existing = dir.listFiles().firstOrNull { it.isFile && it.name?.let(BookFormats::isReadestCompatible) == true }
            if (existing == null) {
                val base = if (normalizeBookNames) safeName(book.title) else safeName(source.fileName.substringBeforeLast('.', source.fileName))
                val finalName = "$base.$ext"
                val partName = "$finalName.moon-exporter.part"
                dir.findFile(partName)?.delete()
                val part = dir.createFile(BookFormats.mime(ext), partName)
                    ?: error(tr("Temporäre Readest-Buchdatei konnte nicht angelegt werden", "Could not create temporary Readest book file"))
                try {
                    context.contentResolver.openOutputStream(part.uri, "w")?.use { out ->
                        local.inputStream().buffered(256 * 1024).use { input -> input.copyTo(out, 256 * 1024) }
                        out.flush()
                    } ?: error(tr("Readest-Buchdatei konnte nicht geschrieben werden", "Could not write Readest book file"))
                    if (source.size != null && source.size > 0 && part.length() != source.size) {
                        error(tr("Buchdatei wurde nicht vollständig geschrieben", "Book file was not written completely"))
                    }
                    if (!part.renameTo(finalName)) error(tr("Readest-Buchdatei konnte nicht finalisiert werden", "Could not finalize Readest book file"))
                } catch (t: Throwable) {
                    runCatching { part.delete() }
                    throw t
                }
            }

            val now = System.currentTimeMillis()
            val configFile = dir.findFile("config.json") ?: dir.createFile("application/json", "config.json")
                ?: error(tr("config.json konnte nicht angelegt werden", "Could not create config.json"))
            val oldConfig = readText(context, configFile, 8 * 1024 * 1024).orEmpty()
            val config = runCatching { if (oldConfig.isBlank()) JSONObject() else JSONObject(oldConfig) }.getOrElse { JSONObject() }
            config.put("schemaVersion", maxOf(3, config.optInt("schemaVersion", 0)))
            config.put("bookHash", hash)
            book.position?.percent?.let { config.put("progress", progressPair(it)) }
            if (!config.has("viewSettings")) config.put("viewSettings", JSONObject())
            if (!config.has("searchConfig")) config.put("searchConfig", JSONObject())
            config.put("updatedAt", now)
            writeText(context, configFile, config.toString())
            if (book.hasAnnotations) writeOrReplace(context, dir, "moon-export.mrexpt", "text/plain", Exporter.mrexptFor(book))

            val libraryFile = booksRoot.findFile("library.json") ?: booksRoot.createFile("application/json", "library.json")
                ?: error(tr("library.json konnte nicht angelegt werden", "Could not create library.json"))
            val oldLibrary = readText(context, libraryFile, 16 * 1024 * 1024).orEmpty()
            if (oldLibrary.isNotBlank() && booksRoot.findFile("library.moon-exporter.bak.json") == null) {
                writeOrReplace(context, booksRoot, "library.moon-exporter.bak.json", "application/json", oldLibrary)
            }
            val array = runCatching { if (oldLibrary.isBlank()) JSONArray() else JSONArray(oldLibrary) }.getOrElse { JSONArray() }
            var row: JSONObject? = null
            for (i in 0 until array.length()) {
                val candidate = array.optJSONObject(i) ?: continue
                if (candidate.optString("hash").equals(hash, true)) { row = candidate; break }
            }
            if (row == null) {
                row = JSONObject()
                array.put(row)
            }
            row.put("hash", hash)
            row.put("format", ext.uppercase(Locale.ROOT))
            row.put("title", book.title)
            row.put("sourceTitle", book.title)
            book.author?.takeIf { it.isNotBlank() }?.let { row.put("author", it) }
            val metadata = row.optJSONObject("metadata") ?: JSONObject()
            metadata.put("title", book.title)
            metadata.put("sortAs", book.title)
            book.author?.takeIf { it.isNotBlank() }?.let { metadata.put("author", it) }
            book.isbn?.takeIf { it.isNotBlank() }?.let { metadata.put("isbn", it) }
            row.put("metadata", metadata)
            if (!row.has("createdAt")) row.put("createdAt", now)
            if (!row.has("downloadedAt")) row.put("downloadedAt", now)
            row.put("updatedAt", now)
            row.put("metadataUpdatedAt", now)
            row.put("deletedAt", JSONObject.NULL)
            book.position?.percent?.let { percent ->
                row.put("progress", progressPair(percent))
                if (percent >= 99.95) {
                    row.put("readingStatus", "finished")
                    row.put("readingStatusUpdatedAt", now)
                }
            }
            writeText(context, libraryFile, array.toString())
            if (booksRoot.findFile(".nomedia") == null) booksRoot.createFile("application/octet-stream", ".nomedia")
        }
    }

    private inline fun <T> withPreparedSource(context: Context, source: EpubMatch, extension: String, block: (File) -> T): T {
        source.embeddedPath?.let { path -> File(path).takeIf { it.isFile }?.let { return block(it) } }
        val temp = File.createTempFile("readest-generic-", ".$extension", context.cacheDir)
        try {
            temp.outputStream().buffered(256 * 1024).use { out -> copySource(context, source, out) }
            return block(temp)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun copySource(context: Context, source: EpubMatch, output: OutputStream) {
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
                        if (!entry.isDirectory && entry.name.replace('\\', '/').trimStart('/') == wanted) {
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

    private fun progressPair(percent: Double): JSONArray = JSONArray().put(percent.coerceIn(0.0, 100.0).roundToInt()).put(100)

    private fun readText(context: Context, file: DocumentFile, max: Int): String? = context.contentResolver.openInputStream(file.uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var total = 0
        while (true) {
            val n = input.read(buffer); if (n < 0) break; total += n; if (total > max) return@use null; out.write(buffer, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }

    private fun writeText(context: Context, file: DocumentFile, text: String) {
        context.contentResolver.openOutputStream(file.uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer -> writer.write(text); writer.flush() }
            ?: error(tr("Datei konnte nicht geschrieben werden", "Could not write file"))
    }

    private fun writeOrReplace(context: Context, dir: DocumentFile, name: String, mime: String, text: String) {
        val file = dir.findFile(name) ?: dir.createFile(mime, name) ?: error(tr("Datei konnte nicht angelegt werden: $name", "Could not create file: $name"))
        writeText(context, file, text)
    }
}

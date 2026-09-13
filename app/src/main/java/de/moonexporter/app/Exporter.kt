package de.moonexporter.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal object Exporter {
    suspend fun export(
        context: Context,
        targetTree: android.net.Uri,
        books: List<BookItem>,
        mode: ExportMode,
        includeDiagnostics: Boolean,
        onProgress: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, targetTree) ?: error(tr("Exportziel nicht verfügbar", "Export destination unavailable"))
        val created = mutableListOf<DocumentFile>()
        try {
            books.forEachIndexed { index, book ->
                coroutineContext.ensureActive()
                onProgress(tr("Export ${index + 1}/${books.size}: ${book.title}", "Export ${index + 1}/${books.size}: ${book.title}"))
                if (book.hasAnnotations) {
                    val file = createUnique(root, "${safeName(book.title)}.mrexpt", "text/plain").also(created::add)
                    writeText(context, file, mrexptFor(book))
                }
                if (mode == ExportMode.FULL) exportBook(context, root, book, created)
            }
            if (includeDiagnostics) {
                val diagnostic = createUnique(root, "moon-exporter-diagnostic.json", "application/json").also(created::add)
                writeText(context, diagnostic, diagnosticJson(books, mode))
            }
        } catch (t: Throwable) {
            created.asReversed().forEach { runCatching { it.delete() } }
            throw t
        }
    }

    private fun exportBook(context: Context, root: DocumentFile, book: BookItem, created: MutableList<DocumentFile>) {
        val match = book.epub ?: return
        val extension = match.fileName.substringAfterLast('.', book.extension).ifBlank { book.extension }
        val target = createUnique(root, "${safeName(book.title)}.$extension", mimeFor(extension)).also(created::add)
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            when {
                match.uri != null -> context.contentResolver.openInputStream(match.uri)?.use { it.copyTo(output, 64 * 1024) }
                    ?: error(tr("Quelldatei konnte nicht geöffnet werden", "Could not open source file"))
                match.backupUri != null && !match.archiveEntryName.isNullOrBlank() -> copyArchiveEntry(context, match.backupUri, match.archiveEntryName, output)
                match.embeddedPath != null -> File(match.embeddedPath).takeIf { it.isFile }?.inputStream()?.use { it.copyTo(output, 64 * 1024) }
                    ?: error(tr("Temporäre Quelldatei fehlt", "Temporary source file is missing"))
                else -> error(tr("Keine Buchdatei zugeordnet", "No book file matched"))
            }
        } ?: error(tr("Zieldatei konnte nicht geöffnet werden", "Could not open target file"))
    }

    private fun copyArchiveEntry(context: Context, backupUri: android.net.Uri, archiveEntryName: String, output: java.io.OutputStream) {
        val wanted = archiveEntryName.replace('\\', '/').trimStart('/')
        var found = false
        context.contentResolver.openInputStream(backupUri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val clean = entry.name.replace('\\', '/').trimStart('/')
                    if (clean == wanted) {
                        zip.copyTo(output, 64 * 1024)
                        found = true
                        break
                    }
                }
            }
        } ?: error(tr("Backup konnte nicht erneut geöffnet werden", "Could not reopen backup"))
        if (!found) error(tr("Buchdatei wurde im Backup nicht wiedergefunden", "Book file was not found again in the backup"))
    }

    internal fun mrexptFor(book: BookItem): String {
        book.annotation?.originalMrexpt?.takeIf { it.isNotBlank() }?.let { return it }
        val records = book.annotation?.records.orEmpty()
        if (records.isEmpty()) return "0\nindent:false\ntrim:false\n"
        return buildString {
            appendLine("0")
            appendLine("indent:false")
            appendLine("trim:false")
            records.forEachIndexed { index, r ->
                if (index > 0) appendLine("#")
                appendLine(r.id)
                appendLine(book.title)
                appendLine(book.sourceFile)
                appendLine(book.sourceFile.lowercase())
                appendLine(r.chapter ?: 0)
                appendLine(r.splitIndex ?: 0)
                appendLine(r.position ?: 0)
                appendLine(r.length ?: 0)
                appendLine(r.color ?: -28160)
                appendLine(r.timestampMs ?: 0)
                appendLine("")
                appendLine(r.note.orEmpty())
                appendLine(r.original.orEmpty())
                appendLine(if (!r.note.isNullOrBlank()) 0 else 1)
                appendLine(0)
                appendLine(0)
            }
        }
    }

    private fun createUnique(root: DocumentFile, requested: String, mime: String): DocumentFile {
        val base = requested.substringBeforeLast('.', requested)
        val ext = requested.substringAfterLast('.', "").let { if (it == requested) "" else ".$it" }
        var candidate = requested
        var index = 2
        while (root.findFile(candidate) != null) candidate = "$base ($index)$ext".also { index++ }
        return root.createFile(mime, candidate) ?: error(tr("Datei konnte nicht angelegt werden: $candidate", "Could not create file: $candidate"))
    }

    private fun writeText(context: Context, file: DocumentFile, text: String) {
        context.contentResolver.openOutputStream(file.uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: error(tr("Datei konnte nicht geschrieben werden", "Could not write file"))
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun diagnosticJson(books: List<BookItem>, mode: ExportMode): String = buildString {
        append("{\n  \"format\":\"moon-exporter-diagnostic\",\n  \"mode\":${mode.name.jsonEscape()},\n  \"books\":[\n")
        books.forEachIndexed { index, b ->
            if (index > 0) append(",\n")
            append("    {\"title\":${b.title.jsonEscape()},\"source\":${b.sourceFile.jsonEscape()},\"progress\":${b.position?.percent ?: "null"},\"annotations\":${b.annotation?.count ?: 0},\"partialMd5\":${b.epub?.partialMd5?.jsonEscape() ?: "null"}}")
        }
        append("\n  ]\n}\n")
    }
}

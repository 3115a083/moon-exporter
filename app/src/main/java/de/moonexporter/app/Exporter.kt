package de.moonexporter.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal object Exporter {
    suspend fun export(
        context: Context,
        targetTree: android.net.Uri,
        books: List<BookItem>,
        mode: ExportMode,
        includeDiagnostics: Boolean,
        normalizeReadestNames: Boolean = true,
        onProgress: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (mode == ExportMode.FULL) {
            val result = ReadestDirectExporter.export(context, targetTree, books, normalizeReadestNames, onProgress)
            val warningSuffix = if (result.skipped > 0) tr(" · ${result.skipped} übersprungen", " · ${result.skipped} skipped") else ""
            onProgress(tr("Readest-Direktexport abgeschlossen: ${result.exported} Bücher$warningSuffix", "Readest direct export complete: ${result.exported} books$warningSuffix"))
            return@withContext
        }

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

    private fun diagnosticJson(books: List<BookItem>, mode: ExportMode): String = buildString {
        append("{\n  \"format\":\"moon-exporter-diagnostic\",\n  \"mode\":${mode.name.jsonEscape()},\n  \"books\":[\n")
        books.forEachIndexed { index, b ->
            if (index > 0) append(",\n")
            append("    {\"title\":${b.title.jsonEscape()},\"source\":${b.sourceFile.jsonEscape()},\"progress\":${b.position?.percent ?: "null"},\"annotations\":${b.annotation?.count ?: 0},\"partialMd5\":${b.epub?.partialMd5?.jsonEscape() ?: "null"}}")
        }
        append("\n  ]\n}\n")
    }
}

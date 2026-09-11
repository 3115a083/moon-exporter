package de.moonexporter.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
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
            val readme = createUnique(root, "README-Moon-Exporter.txt", "text/plain").also(created::add)
            writeText(context, readme, readmeText())
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
        val source: InputStream = when {
            match.uri != null -> context.contentResolver.openInputStream(match.uri) ?: return
            match.embeddedPath != null -> File(match.embeddedPath).takeIf { it.isFile }?.inputStream() ?: return
            else -> return
        }
        source.use { input ->
            val extension = match.fileName.substringAfterLast('.', book.extension).ifBlank { book.extension }
            val target = createUnique(root, "${safeName(book.title)}.$extension", mimeFor(extension)).also(created::add)
            context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> input.copyTo(output, 64 * 1024) }
                ?: error(tr("Zieldatei konnte nicht geöffnet werden", "Could not open target file"))
        }
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

    private fun readmeText(): String = """
Moon Exporter

DEUTSCH
Readest: Buch in Readest öffnen, Annotationen importieren, Moon+ Reader wählen und die passende .mrexpt-Datei auswählen.
Der aktuelle Lesefortschritt wird nicht durch .mrexpt übertragen. Dafür kann Moon Exporter optional KOSync oder Calibre-Web Automated verwenden.
Bei KOSync/CWA wird niemals die E-Book-Datei hochgeladen. Übertragen werden nur Dokument-ID (partialMD5) und Fortschrittsdaten.

ENGLISH
Readest: open the book in Readest, choose annotation import, select Moon+ Reader and pick the matching .mrexpt file.
Current reading progress is not carried by .mrexpt. Moon Exporter can optionally send progress through KOSync or Calibre-Web Automated.
KOSync/CWA never uploads the ebook file. Only the document id (partialMD5) and reading progress are sent.
""".trimIndent()
}

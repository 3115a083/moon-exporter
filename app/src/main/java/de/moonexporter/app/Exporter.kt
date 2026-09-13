package de.moonexporter.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class ExportProgress(val message: String, val fraction: Float?)

internal object Exporter {
    suspend fun export(
        context: Context,
        targetTree: android.net.Uri,
        books: List<BookItem>,
        mode: ExportMode,
        includeDiagnostics: Boolean,
        normalizeReadestNames: Boolean = true,
        onProgress: (ExportProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (mode == ExportMode.FULL) {
            val store = TransferStore(context.applicationContext)
            val sessionId = store.createSession(targetTree, normalizeReadestNames, books)
            val intent = Intent(context, ExportService::class.java)
                .setAction(ExportService.ACTION_START)
                .putExtra(ExportService.EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    val state = ExportState.state.value
                    if (state.sessionId == sessionId) onProgress(ExportProgress(state.status, state.fraction))
                    when (store.session(sessionId)?.status) {
                        "DONE" -> {
                            onProgress(ExportProgress(tr("Readest-Export abgeschlossen und geprüft.", "Readest export completed and verified."), 1f))
                            return@withContext
                        }
                        "INTERRUPTED" -> {
                            val snapshot = ExportState.state.value
                            error(snapshot.error ?: tr("Export unterbrochen. Der Auftrag bleibt gespeichert und kann fortgesetzt werden.", "Export interrupted. The saved job can be resumed."))
                        }
                    }
                    delay(350)
                }
            } finally { store.close() }
        }

        val root = DocumentFile.fromTreeUri(context, targetTree) ?: error(tr("Exportziel nicht verfügbar", "Export destination unavailable"))
        val created = mutableListOf<DocumentFile>()
        try {
            val total = books.size.coerceAtLeast(1)
            books.forEachIndexed { index, book ->
                coroutineContext.ensureActive()
                onProgress(ExportProgress(
                    tr("Buch ${index + 1}/${books.size}: Markierungen als .mrexpt schreiben · ${book.title}", "Book ${index + 1}/${books.size}: writing highlights as .mrexpt · ${book.title}"),
                    index.toFloat() / total,
                ))
                if (book.hasAnnotations) {
                    val file = createUnique(root, "${safeName(book.title)}.mrexpt", "text/plain").also(created::add)
                    writeText(context, file, mrexptFor(book))
                }
            }
            if (includeDiagnostics) {
                onProgress(ExportProgress(tr("Diagnosebericht schreiben", "Writing diagnostic report"), 0.95f))
                val diagnostic = createUnique(root, "moon-exporter-diagnostic.json", "application/json").also(created::add)
                writeText(context, diagnostic, diagnosticJson(books, mode))
            }
        } catch (t: Throwable) {
            created.asReversed().forEach { runCatching { it.delete() } }
            throw t
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

    private fun diagnosticJson(books: List<BookItem>, mode: ExportMode): String = buildString {
        append("{\n  \"format\":\"moon-exporter-diagnostic\",\n  \"mode\":${mode.name.jsonEscape()},\n  \"books\":[\n")
        books.forEachIndexed { index, b ->
            if (index > 0) append(",\n")
            append("    {\"title\":${b.title.jsonEscape()},\"source\":${b.sourceFile.jsonEscape()},\"progress\":${b.position?.percent ?: "null"},\"annotations\":${b.annotation?.count ?: 0},\"partialMd5\":${b.epub?.partialMd5?.jsonEscape() ?: "null"}}")
        }
        append("\n  ]\n}\n")
    }
}

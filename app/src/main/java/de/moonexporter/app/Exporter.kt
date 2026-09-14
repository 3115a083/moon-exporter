package de.moonexporter.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
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
        if (!includeDiagnostics) {
            val store = TransferStore(context.applicationContext)
            val pending = store.latestUnfinishedSession()
            val sessionId = when (ManualSessionPolicy.decide(pending?.targetUri?.toString(), targetTree.toString())) {
                ManualSessionDecision.CREATE_NEW -> store.createSession(targetTree, normalizeReadestNames, books, mode)
                ManualSessionDecision.SUPERSEDE_AND_CREATE -> {
                    requireNotNull(pending)
                    store.setSessionStatus(pending.id, "SUPERSEDED")
                    store.createSession(targetTree, normalizeReadestNames, books, mode)
                }
                ManualSessionDecision.BLOCK_OTHER_TARGET -> {
                    store.close()
                    error(tr("Es existiert noch ein unterbrochener Export für ein anderes Ziel. Öffne die App erneut mit Zugriff auf dieses Ziel oder beende/repariere zuerst diesen Auftrag.", "An interrupted export for another target still exists. Resume or repair it before starting a different target."))
                }
            }
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
                            onProgress(ExportProgress(tr("Export abgeschlossen und geprüft.", "Export completed and verified."), 1f))
                            return@withContext
                        }
                        "INTERRUPTED" -> {
                            val snapshot = ExportState.state.value
                            error(snapshot.error ?: tr("Export unterbrochen. Der Auftrag bleibt gespeichert und kann fortgesetzt werden.", "Export interrupted. The saved job can be resumed."))
                        }
                    }
                    delay(350)
                }
            } catch (e: CancellationException) {
                val activity = context as? Activity
                if (activity != null && !activity.isFinishing && !activity.isChangingConfigurations) {
                    runCatching { context.startService(Intent(context, ExportService::class.java).setAction(ExportService.ACTION_CANCEL)) }
                }
                throw e
            } finally { store.close() }
        }

        // Diagnostics are intentionally an explicit foreground/UI operation and are never part of
        // normal user exports. Keeping this fallback avoids persisting diagnostic-only data.
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
            onProgress(ExportProgress(tr("Diagnosebericht schreiben", "Writing diagnostic report"), 0.95f))
            val diagnostic = createUnique(root, "moon-exporter-diagnostic.json", "application/json").also(created::add)
            writeText(context, diagnostic, diagnosticJson(books, mode))
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

    internal fun markingsFileName(book: BookItem): String =
        "${safeName(book.title)}-${TransferStore.stableSuffix(book.key)}.mrexpt"

    internal fun writeMarkingsFile(context: Context, root: DocumentFile, book: BookItem) {
        if (!book.hasAnnotations) return
        val name = markingsFileName(book)
        val file = root.findFile(name) ?: root.createFile("text/plain", name)
            ?: error(tr("Datei konnte nicht angelegt werden: $name", "Could not create file: $name"))
        writeText(context, file, mrexptFor(book))
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
        context.contentResolver.openOutputStream(file.uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(text)
            writer.flush()
        } ?: error(tr("Datei konnte nicht geschrieben werden", "Could not write file"))
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

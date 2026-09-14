package de.moonexporter.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Second-pass recovery for embedded formats that older MoonImporter revisions did not index.
 * It never extracts the book. KOReader partialMD5 is calculated directly from the archive stream.
 */
internal object EmbeddedBookRecovery {
    private const val MAX_ENTRIES = 40_000
    private const val MAX_NAMES_BYTES = 4 * 1024 * 1024
    private const val MAX_BOOK_BYTES = 2L * 1024 * 1024 * 1024

    suspend fun attach(context: Context, backupUri: Uri, books: List<BookItem>, onProgress: (String) -> Unit = {}): List<BookItem> =
        withContext(Dispatchers.IO) {
            if (books.isEmpty()) return@withContext books
            val wanted = books.filter { !it.hasBookFile }.flatMap { book ->
                listOf(book.sourceFile, book.sourceFile.substringAfterLast('/'), "${book.originalName}.${book.extension}")
            }.map(::normalizePath).filter { it.isNotBlank() }.toSet()
            if (wanted.isEmpty()) return@withContext books

            val names = readNames(context, backupUri)
            val matches = linkedMapOf<String, EpubMatch>()
            context.contentResolver.openInputStream(backupUri)?.buffered()?.use { raw ->
                ZipInputStream(raw).use { zip ->
                    var count = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        if (++count > MAX_ENTRIES) throw IOException(tr("Backup enthält zu viele Dateien", "Backup contains too many files"))
                        val clean = normalizePath(entry.name)
                        if (clean.contains("../") || clean.startsWith("..")) continue

                        val candidates = MoonImporter.mrproLogicalCandidates(clean, names)
                            .map(::normalizePath)
                            .distinct()
                        val logical = candidates.firstOrNull { candidate ->
                            BookFormats.extension(candidate) in BookFormats.readestCompatible &&
                                (candidate in wanted || candidate.substringAfterLast('/') in wanted)
                        } ?: continue
                        val fileName = logical.substringAfterLast('/')
                        val ext = BookFormats.extension(fileName)
                        if (ext !in BookFormats.readestCompatible) continue

                        onProgress(tr("Buchformat wird geprüft: $fileName", "Checking book format: $fileName"))
                        val bounded = CountingBoundedInputStream(zip, MAX_BOOK_BYTES)
                        val hash = partialMd5(bounded)
                        val match = EpubMatch(
                            backupUri = backupUri,
                            archiveEntryName = entry.name,
                            fileName = fileName,
                            title = fileName.substringBeforeLast('.', fileName).takeUnless(ProgressRecovery::looksOpaque),
                            partialMd5 = hash,
                            size = bounded.count,
                        )
                        candidates.forEach { candidate ->
                            matches[candidate] = match
                            matches[candidate.substringAfterLast('/')] = match
                        }
                    }
                }
            } ?: return@withContext books

            books.map { book ->
                if (book.hasBookFile) book else {
                    val path = normalizePath(book.sourceFile)
                    val base = path.substringAfterLast('/')
                    val match = matches[path] ?: matches[base]
                    if (match == null) book else book.copy(
                        epub = match,
                        includedInBackup = true,
                        title = match.title?.takeIf { it.isNotBlank() } ?: book.title,
                    )
                }
            }
        }

    private fun readNames(context: Context, backupUri: Uri): List<String> {
        context.contentResolver.openInputStream(backupUri)?.buffered()?.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.substringAfterLast('/').equals("_names.list", true)) continue
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_NAMES_BYTES) return emptyList()
                        out.write(buffer, 0, n)
                    }
                    return out.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF").lines().map(String::trim)
                }
            }
        }
        return emptyList()
    }

    private fun normalizePath(value: String): String = value.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)

    private class CountingBoundedInputStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) add(1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) add(n.toLong())
            return n
        }

        private fun add(n: Long) {
            count += n
            if (count > max) throw IOException(tr("Buchdatei im Backup ist ungewöhnlich groß", "Book file in backup is unusually large"))
        }
    }
}

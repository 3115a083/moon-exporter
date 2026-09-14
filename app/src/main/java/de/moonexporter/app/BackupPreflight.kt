package de.moonexporter.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

internal object BackupPreflight {
    private const val MAX_ENTRIES = 40_000
    private const val MAX_ANNOTATION_COMPRESSED = 32 * 1024 * 1024
    private const val MAX_ANNOTATION_INFLATED = 16 * 1024 * 1024
    private const val MAX_BOOK_BYTES = 2L * 1024 * 1024 * 1024

    suspend fun validate(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.buffered()?.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entries = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (++entries > MAX_ENTRIES) throw IOException(tr("Backup enthält zu viele Dateien", "Backup contains too many files"))
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    if (name.contains("../") || name.startsWith("..")) continue
                    val lower = name.lowercase(Locale.ROOT)
                    if (lower.endsWith(".an")) {
                        val compressedPayload = readBounded(zip, MAX_ANNOTATION_COMPRESSED)
                            ?: throw IOException(tr("Moon+-Markierungsdatei ist ungewöhnlich groß", "Moon+ annotation file is unusually large"))
                        validateInflatedAnnotation(compressedPayload)
                    } else if (BookFormats.extension(lower) in BookFormats.readestCompatible) {
                        if (entry.size > MAX_BOOK_BYTES) throw IOException(tr("Buchdatei im Backup ist größer als 2 GiB", "Book file in backup is larger than 2 GiB"))
                        if (entry.size < 0) drainBounded(zip, MAX_BOOK_BYTES)
                    }
                }
            }
        } ?: throw IOException(tr("Backup konnte nicht geöffnet werden", "Could not open backup"))
    }

    internal fun validateInflatedAnnotation(bytes: ByteArray) {
        runCatching {
            InflaterInputStream(ByteArrayInputStream(bytes)).use { input -> drainBounded(input, MAX_ANNOTATION_INFLATED.toLong()) }
        }.getOrElse { cause ->
            if (cause is IOException && cause.message?.contains("zu groß", true) == true) throw cause
            // Some Moon+ annotation variants are not zlib payloads. MoonImporter already treats
            // those as unreadable. They are not a decompression bomb, so preflight may continue.
        }
    }

    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun drainBounded(input: java.io.InputStream, max: Long) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw IOException(tr("Dekomprimierte Backup-Daten sind ungewöhnlich groß", "Decompressed backup data is unusually large"))
        }
    }
}

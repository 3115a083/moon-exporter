package de.moonexporter.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Session-local cache for Moon+ backup archives.
 *
 * SAF-backed .mrpro files are otherwise sequential ZIP streams. Reopening and scanning the
 * complete backup once per exported book is extremely expensive. This cache copies each backup
 * once into the app cache so individual book entries can be opened through ZipFile random access.
 */
internal object BackupArchiveCache {
    private val files = linkedMapOf<String, File>()

    @Synchronized
    fun prepare(context: Context, books: List<BookItem>) {
        val uris = books.mapNotNull { it.epub?.backupUri }.distinctBy(Uri::toString)
        for (uri in uris) ensureCached(context, uri)
    }

    @Synchronized
    fun copyEntry(source: EpubMatch, output: OutputStream): Boolean {
        val backupUri = source.backupUri ?: return false
        val entryName = source.archiveEntryName ?: return false
        val file = files[backupUri.toString()]?.takeIf(File::isFile) ?: return false
        ZipFile(file).use { zip ->
            val direct = zip.getEntry(entryName)
            val entry = direct ?: zip.entries().asSequence().firstOrNull {
                normalize(it.name) == normalize(entryName)
            } ?: return false
            zip.getInputStream(entry).buffered(256 * 1024).use { input ->
                input.copyTo(output, 256 * 1024)
            }
        }
        return true
    }

    @Synchronized
    fun clear() {
        files.values.forEach { runCatching { it.delete() } }
        files.clear()
    }

    private fun ensureCached(context: Context, uri: Uri): File? {
        files[uri.toString()]?.takeIf(File::isFile)?.let { return it }
        val suffix = sha256(uri.toString()).take(16)
        val file = File(context.cacheDir, "moon-export-backup-$suffix.mrpro")
        runCatching { file.delete() }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.buffered(1024 * 1024)?.use { input ->
                file.outputStream().buffered(1024 * 1024).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.flush()
                }
            } ?: return null
            files[uri.toString()] = file
            file
        }.getOrElse {
            runCatching { file.delete() }
            null
        }
    }

    private fun normalize(value: String): String = value.replace('\\', '/').trimStart('/')

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

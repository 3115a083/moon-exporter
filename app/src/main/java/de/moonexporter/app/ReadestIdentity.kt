package de.moonexporter.app

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Resolves the exact Readest book hash from the original matched ebook source.
 * Used only as a recovery fallback when folder/library heuristics cannot identify an interrupted export.
 */
internal object ReadestIdentity {
    fun expectedHash(context: Context, book: BookItem): String? {
        val source = book.epub ?: return null
        if (!book.hasBookFile) return null
        val ext = source.fileName.substringAfterLast('.', book.extension).lowercase(Locale.ROOT)
        if (ext !in setOf("epub", "pdf")) return null

        source.embeddedPath?.let { path ->
            File(path).takeIf { it.isFile }?.inputStream()?.buffered()?.use {
                return ReadestDirectExporter.readestPartialMd5(it)
            }
        }

        source.uri?.let { uri ->
            return context.contentResolver.openInputStream(uri)?.buffered()?.use {
                ReadestDirectExporter.readestPartialMd5(it)
            }
        }

        if (source.backupUri != null && !source.archiveEntryName.isNullOrBlank()) {
            val wanted = source.archiveEntryName.replace('\\', '/').trimStart('/')
            return context.contentResolver.openInputStream(source.backupUri)?.buffered()?.use { raw ->
                ZipInputStream(raw).use zipUse@ { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        if (entry.name.replace('\\', '/').trimStart('/') == wanted) {
                            return@zipUse ReadestDirectExporter.readestPartialMd5(zip)
                        }
                    }
                    null
                }
            }
        }
        return null
    }
}

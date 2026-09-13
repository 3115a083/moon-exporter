package de.moonexporter.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Exact Readest identity fallback used when an already-existing target folder cannot be rediscovered by metadata. */
internal object ReadestIdentity {
    suspend fun sourceHash(context: Context, source: EpubMatch): String? = withContext(Dispatchers.IO) {
        runCatching {
            source.embeddedPath?.let { path ->
                File(path).takeIf { it.isFile }?.inputStream()?.buffered()?.use(ReadestDirectExporter::readestPartialMd5)
                    ?.let { return@withContext it }
            }

            source.uri?.let { uri ->
                context.contentResolver.openInputStream(uri)?.buffered()?.use(ReadestDirectExporter::readestPartialMd5)
                    ?.let { return@withContext it }
            }

            val backupUri = source.backupUri
            val entryName = source.archiveEntryName
            if (backupUri != null && !entryName.isNullOrBlank()) {
                val wanted = entryName.replace('\\', '/').trimStart('/')
                val temp = File.createTempFile("readest-id-", ".${source.fileName.substringAfterLast('.', "bin")}", context.cacheDir)
                try {
                    var found = false
                    context.contentResolver.openInputStream(backupUri)?.buffered()?.use { raw ->
                        ZipInputStream(raw).use { zip ->
                            temp.outputStream().buffered(256 * 1024).use { output ->
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    if (entry.isDirectory) continue
                                    if (entry.name.replace('\\', '/').trimStart('/') == wanted) {
                                        zip.copyTo(output, 256 * 1024)
                                        found = true
                                        break
                                    }
                                }
                            }
                        }
                    }
                    if (!found) return@runCatching null
                    temp.inputStream().buffered().use(ReadestDirectExporter::readestPartialMd5)
                } finally {
                    runCatching { temp.delete() }
                }
            } else null
        }.getOrNull()
    }
}

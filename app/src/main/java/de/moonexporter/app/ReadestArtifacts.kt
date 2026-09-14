package de.moonexporter.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** Normalizes Moon+ annotation sidecars created through SAF.
 * Some document providers append .txt when createFile(text/plain, "moon-export.mrexpt") is used.
 * Keep exactly one extensionless moon-export.mrexpt file and remove legacy duplicates.
 */
internal object ReadestArtifacts {
    private const val EXACT_NAME = "moon-export.mrexpt"
    private const val MIME = "application/x-moon-reader-export"

    fun normalizeMoonExport(context: Context, targetTree: Uri, bookHash: String) {
        val root = DocumentFile.fromTreeUri(context, targetTree) ?: return
        val booksRoot = when {
            root.name.equals("Books", true) -> root
            else -> root.findFile("Books")?.takeIf { it.isDirectory } ?: return
        }
        val dir = booksRoot.findFile(bookHash)?.takeIf { it.isDirectory } ?: return
        val candidates = dir.listFiles().filter { file ->
            file.isFile && file.name?.lowercase()?.startsWith(EXACT_NAME) == true
        }
        if (candidates.isEmpty()) return

        var exact = candidates.firstOrNull { it.name == EXACT_NAME }
        if (exact == null) {
            val source = candidates.first()
            if (source.renameTo(EXACT_NAME)) exact = dir.findFile(EXACT_NAME)
            if (exact == null) {
                val created = dir.createFile(MIME, EXACT_NAME)
                if (created != null) {
                    context.contentResolver.openInputStream(source.uri)?.use { input ->
                        context.contentResolver.openOutputStream(created.uri, "w")?.use { output ->
                            input.copyTo(output, 64 * 1024)
                            output.flush()
                        }
                    }
                    exact = created
                }
            }
        }

        if (exact != null) {
            dir.listFiles().filter { file ->
                file.isFile && file.uri != exact.uri && file.name?.lowercase()?.startsWith(EXACT_NAME) == true
            }.forEach { runCatching { it.delete() } }
        }
    }
}

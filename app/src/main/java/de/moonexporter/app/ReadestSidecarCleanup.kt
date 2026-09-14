package de.moonexporter.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

internal object ReadestSidecarCleanup {
    fun normalizeMrexpt(context: Context, targetTree: Uri, hash: String) {
        val selected = DocumentFile.fromTreeUri(context, targetTree) ?: return
        val booksRoot = when {
            selected.name.equals("Books", true) -> selected
            selected.findFile("Books")?.isDirectory == true -> selected.findFile("Books") ?: return
            else -> return
        }
        val dir = booksRoot.findFile(hash)?.takeIf { it.isDirectory } ?: return
        val prefix = "moon-export.mrexpt"
        val candidates = dir.listFiles().filter { file ->
            file.isFile && file.name?.lowercase()?.startsWith(prefix) == true
        }
        if (candidates.isEmpty()) return

        var canonical = candidates.firstOrNull { it.name.equals(prefix, true) }
        if (canonical == null) {
            val source = candidates.maxByOrNull { it.lastModified() }
            if (source != null && source.renameTo(prefix)) canonical = source
        }
        candidates.filter { it != canonical }.forEach { runCatching { it.delete() } }
    }
}

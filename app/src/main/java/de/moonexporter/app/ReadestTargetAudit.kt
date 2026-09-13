package de.moonexporter.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

internal object ReadestTargetAudit {
    internal data class Snapshot(val bookHashes: Set<String>)
    internal data class Validation(val complete: Boolean, val reason: String? = null)

    fun snapshot(context: Context, targetTree: Uri): Snapshot = Snapshot(bookHashes(context, targetTree))

    fun discoverSingleNewHash(before: Snapshot, context: Context, targetTree: Uri): String? {
        val added = bookHashes(context, targetTree) - before.bookHashes
        return added.singleOrNull()
    }

    fun validateKnownBook(context: Context, targetTree: Uri, targetHash: String, expectedSize: Long?): Validation {
        val root = booksRoot(context, targetTree) ?: return Validation(false, "Readest/Books fehlt")
        val dir = root.findFile(targetHash)?.takeIf { it.isDirectory } ?: return Validation(false, "Buchordner fehlt")
        cleanupPartFiles(dir)
        val book = dir.listFiles().firstOrNull { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in setOf("epub", "pdf") }
            ?: return Validation(false, "Buchdatei fehlt")
        if (expectedSize != null && expectedSize > 0L && book.length() != expectedSize) {
            runCatching { book.delete() }
            return Validation(false, "Unvollständige Buchdatei entfernt")
        }
        val config = dir.findFile("config.json") ?: return Validation(false, "config.json fehlt")
        val configText = readText(context, config, 8 * 1024 * 1024) ?: return Validation(false, "config.json nicht lesbar")
        if (runCatching { JSONObject(configText) }.isFailure) return Validation(false, "config.json ungültig")
        val library = root.findFile("library.json") ?: return Validation(false, "library.json fehlt")
        val libraryText = readText(context, library, 16 * 1024 * 1024) ?: return Validation(false, "library.json nicht lesbar")
        val arr = runCatching { JSONArray(libraryText) }.getOrNull() ?: return Validation(false, "library.json ungültig")
        var present = false
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("hash") == targetHash) { present = true; break }
        if (!present) return Validation(false, "Bibliothekseintrag fehlt")
        return Validation(true)
    }

    fun repairKnownInterrupted(context: Context, targetTree: Uri, targetHash: String?, expectedSize: Long?): Validation {
        if (targetHash.isNullOrBlank()) return Validation(false, "Zielbuch noch nicht eindeutig ermittelt")
        return validateKnownBook(context, targetTree, targetHash, expectedSize)
    }

    fun cleanupTargetParts(context: Context, targetTree: Uri) {
        val root = booksRoot(context, targetTree) ?: return
        root.listFiles().filter { it.isFile && isPart(it.name) }.forEach { runCatching { it.delete() } }
        root.listFiles().filter { it.isDirectory }.forEach(::cleanupPartFiles)
    }

    private fun cleanupPartFiles(dir: DocumentFile) {
        dir.listFiles().filter { it.isFile && isPart(it.name) }.forEach { runCatching { it.delete() } }
    }

    private fun isPart(name: String?): Boolean = name?.endsWith(".moon-exporter.part", true) == true || name?.contains(".moon-exporter.part.", true) == true

    private fun bookHashes(context: Context, targetTree: Uri): Set<String> {
        val root = booksRoot(context, targetTree) ?: return emptySet()
        val rx = Regex("^[0-9a-fA-F]{32}$")
        return root.listFiles().filter { it.isDirectory && it.name?.matches(rx) == true }.mapNotNull { it.name?.lowercase() }.toSet()
    }

    private fun booksRoot(context: Context, targetTree: Uri): DocumentFile? {
        val selected = DocumentFile.fromTreeUri(context, targetTree) ?: return null
        return when {
            selected.name.equals("Books", true) -> selected
            selected.findFile("Books")?.isDirectory == true -> selected.findFile("Books")
            else -> null
        }
    }

    private fun readText(context: Context, file: DocumentFile, max: Int): String? = context.contentResolver.openInputStream(file.uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var total = 0
        while (true) {
            val n = input.read(buffer); if (n < 0) break; total += n; if (total > max) return@use null; out.write(buffer, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }
}

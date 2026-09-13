package de.moonexporter.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object ReadestTargetAudit {
    internal data class Snapshot(val bookHashes: Set<String>)
    internal data class Validation(val complete: Boolean, val reason: String? = null)

    fun snapshot(context: Context, targetTree: Uri): Snapshot = Snapshot(bookHashes(context, targetTree))

    fun discoverSingleNewHash(before: Snapshot, context: Context, targetTree: Uri): String? {
        val added = bookHashes(context, targetTree) - before.bookHashes
        return added.singleOrNull()
    }

    /**
     * Finds the exact folder touched by the single-book direct exporter even when that
     * Readest hash directory already existed before this attempt. The exporter always
     * refreshes config.updatedAt and writes config.bookHash, so this is stronger than
     * title/ISBN guessing and does not require re-reading the source ebook.
     */
    fun findRecentlyCommittedHash(context: Context, targetTree: Uri, startedAtMs: Long): String? {
        val root = booksRoot(context, targetTree) ?: return null
        repairLibraryIfNeeded(context, root)
        val libraryText = root.findFile("library.json")?.let { readText(context, it, 16 * 1024 * 1024) } ?: return null
        val library = runCatching { JSONArray(libraryText) }.getOrNull() ?: return null
        val libraryHashes = buildSet {
            for (i in 0 until library.length()) {
                library.optJSONObject(i)?.optString("hash")
                    ?.takeIf { it.matches(Regex("^[0-9a-fA-F]{32}$")) }
                    ?.lowercase(Locale.ROOT)
                    ?.let(::add)
            }
        }
        val candidates = mutableListOf<String>()
        for (dir in root.listFiles()) {
            val hash = dir.name?.takeIf { dir.isDirectory && it.matches(Regex("^[0-9a-fA-F]{32}$")) }?.lowercase(Locale.ROOT) ?: continue
            if (hash !in libraryHashes) continue
            val config = dir.findFile("config.json") ?: continue
            val text = readText(context, config, 8 * 1024 * 1024) ?: continue
            val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (!json.optString("bookHash").equals(hash, true)) continue
            val updatedAt = json.optLong("updatedAt", 0L)
            // Allow small clock/write ordering tolerance but never accept an old untouched folder.
            if (updatedAt >= startedAtMs - 2_000L) candidates += hash
        }
        return candidates.distinct().singleOrNull()
    }

    fun findLikelyHash(context: Context, targetTree: Uri, book: BookItem): String? {
        val root = booksRoot(context, targetTree) ?: return null
        repairLibraryIfNeeded(context, root)
        val text = root.findFile("library.json")?.let { readText(context, it, 16 * 1024 * 1024) } ?: return null
        val library = runCatching { JSONArray(text) }.getOrNull() ?: return null
        val wantedTitle = normalize(book.epub?.title ?: book.title)
        val wantedIsbn = normalizeIsbn(book.epub?.isbn ?: book.isbn)
        val matches = mutableListOf<String>()
        for (i in 0 until library.length()) {
            val row = library.optJSONObject(i) ?: continue
            val hash = row.optString("hash").takeIf { it.matches(Regex("^[0-9a-fA-F]{32}$")) } ?: continue
            val metadata = row.optJSONObject("metadata")
            if (wantedIsbn != null) {
                val ids = buildList {
                    metadata?.optString("isbn")?.takeIf { it.isNotBlank() }?.let(::add)
                    metadata?.optString("identifier")?.takeIf { it.isNotBlank() }?.let(::add)
                    metadata?.optJSONArray("altIdentifier")?.let { arr -> for (j in 0 until arr.length()) arr.optString(j).takeIf { it.isNotBlank() }?.let(::add) }
                }
                if (ids.any { normalizeIsbn(it) == wantedIsbn }) { matches += hash.lowercase(Locale.ROOT); continue }
            }
            val rowTitle = normalize(row.optString("title").ifBlank { metadata?.optString("title").orEmpty() })
            if (wantedTitle.isNotBlank() && rowTitle == wantedTitle) matches += hash.lowercase(Locale.ROOT)
        }
        return matches.distinct().singleOrNull()
    }

    fun validateKnownBook(context: Context, targetTree: Uri, targetHash: String, expectedSize: Long?): Validation {
        val root = booksRoot(context, targetTree) ?: return Validation(false, "Readest/Books fehlt")
        repairLibraryIfNeeded(context, root)
        val dir = root.findFile(targetHash)?.takeIf { it.isDirectory } ?: return Validation(false, "Buchordner fehlt: $targetHash")
        cleanupPartFiles(dir)
        val book = dir.listFiles().firstOrNull { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in setOf("epub", "pdf") }
            ?: return Validation(false, "Buchdatei fehlt")
        if (expectedSize != null && expectedSize > 0L && book.length() != expectedSize) {
            runCatching { book.delete() }
            return Validation(false, "Buchdatei war unvollständig und wurde zur Reparatur entfernt")
        }
        val targetBookHash = runCatching {
            context.contentResolver.openInputStream(book.uri)?.buffered()?.use(ReadestDirectExporter::readestPartialMd5)
        }.getOrNull()
        if (targetBookHash == null || !targetBookHash.equals(targetHash, true)) {
            runCatching { book.delete() }
            return Validation(false, "Buchdatei hatte nicht den erwarteten Readest-Hash und wurde entfernt")
        }

        val config = dir.findFile("config.json") ?: return Validation(false, "config.json fehlt")
        var configText = readText(context, config, 8 * 1024 * 1024)
        if (configText == null || runCatching { JSONObject(configText) }.isFailure) {
            val repairedFromBackup = restoreBackup(context, dir, "config.moon-exporter.bak.json", config)
            if (!repairedFromBackup && dir.findFile("config.moon-exporter.bak.json") == null) {
                overwriteText(context, config, "{}")
            }
            configText = readText(context, config, 8 * 1024 * 1024)
            if (configText == null || runCatching { JSONObject(configText) }.isFailure) return Validation(false, "config.json ungültig")
            return Validation(false, if (repairedFromBackup) "Unterbrochene config.json aus Sicherung repariert" else "Unterbrochene erste config.json zurückgesetzt")
        }
        val configJson = runCatching { JSONObject(configText) }.getOrNull() ?: return Validation(false, "config.json ungültig")
        val configHash = configJson.optString("bookHash")
        if (configHash.isNotBlank() && !configHash.equals(targetHash, true)) return Validation(false, "config.json gehört zu einem anderen Readest-Buchhash")

        val library = root.findFile("library.json") ?: return Validation(false, "library.json fehlt")
        val libraryText = readText(context, library, 16 * 1024 * 1024) ?: return Validation(false, "library.json nicht lesbar")
        val arr = runCatching { JSONArray(libraryText) }.getOrNull() ?: return Validation(false, "library.json ungültig")
        var present = false
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("hash")?.equals(targetHash, true) == true) { present = true; break }
        if (!present) return Validation(false, "Bibliothekseintrag für $targetHash fehlt")
        return Validation(true)
    }

    fun cleanupTargetParts(context: Context, targetTree: Uri) {
        val root = booksRoot(context, targetTree) ?: return
        repairLibraryIfNeeded(context, root)
        root.listFiles().filter { it.isFile && isPart(it.name) }.forEach { runCatching { it.delete() } }
        root.listFiles().filter { it.isDirectory }.forEach(::cleanupPartFiles)
    }

    fun cleanupRecoveryArtifacts(context: Context, targetTree: Uri) {
        val root = booksRoot(context, targetTree) ?: return
        root.listFiles().filter { it.isFile && (isPart(it.name) || it.name.equals("library.moon-exporter.bak.json", true)) }
            .forEach { runCatching { it.delete() } }
        root.listFiles().filter { it.isDirectory }.forEach { dir ->
            dir.listFiles().filter { file -> file.isFile && (isPart(file.name) || file.name.equals("config.moon-exporter.bak.json", true)) }
                .forEach { runCatching { it.delete() } }
        }
    }

    private fun repairLibraryIfNeeded(context: Context, root: DocumentFile) {
        val library = root.findFile("library.json") ?: return
        val current = readText(context, library, 16 * 1024 * 1024)
        if (current != null && runCatching { JSONArray(current) }.isSuccess) return
        val repairedFromBackup = restoreBackup(context, root, "library.moon-exporter.bak.json", library)
        if (!repairedFromBackup && root.findFile("library.moon-exporter.bak.json") == null) {
            overwriteText(context, library, "[]")
        }
    }

    private fun restoreBackup(context: Context, dir: DocumentFile, backupName: String, target: DocumentFile): Boolean {
        val backup = dir.findFile(backupName) ?: return false
        val bytes = context.contentResolver.openInputStream(backup.uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024); var total = 0
            while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > 16 * 1024 * 1024) return@use null; out.write(buf, 0, n) }
            out.toByteArray()
        } ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(target.uri, "w")?.use { it.write(bytes); it.flush() } ?: return false
            true
        }.getOrDefault(false)
    }

    private fun overwriteText(context: Context, target: DocumentFile, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(target.uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text); it.flush() } ?: return false
        true
    }.getOrDefault(false)

    private fun cleanupPartFiles(dir: DocumentFile) {
        dir.listFiles().filter { it.isFile && isPart(it.name) }.forEach { runCatching { it.delete() } }
    }

    private fun isPart(name: String?): Boolean = name?.endsWith(".moon-exporter.part", true) == true || name?.contains(".moon-exporter.part.", true) == true

    private fun bookHashes(context: Context, targetTree: Uri): Set<String> {
        val root = booksRoot(context, targetTree) ?: return emptySet()
        val rx = Regex("^[0-9a-fA-F]{32}$")
        return root.listFiles().filter { it.isDirectory && it.name?.matches(rx) == true }.mapNotNull { it.name?.lowercase(Locale.ROOT) }.toSet()
    }

    private fun booksRoot(context: Context, targetTree: Uri): DocumentFile? {
        val selected = DocumentFile.fromTreeUri(context, targetTree) ?: return null
        return when {
            selected.name.equals("Books", true) -> selected
            selected.findFile("Books")?.isDirectory == true -> selected.findFile("Books")
            else -> null
        }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    private fun normalizeIsbn(value: String?): String? = value?.uppercase(Locale.ROOT)?.filter { it.isDigit() || it == 'X' }?.takeIf { it.length == 10 || it.length == 13 }

    private fun readText(context: Context, file: DocumentFile, max: Int): String? = context.contentResolver.openInputStream(file.uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var total = 0
        while (true) {
            val n = input.read(buffer); if (n < 0) break; total += n; if (total > max) return@use null; out.write(buffer, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }
}

package de.moonexporter.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object ReadestLibraryStatus {
    internal data class Result(val finished: Int, val ambiguous: Int)

    fun markFinished(context: Context, targetTree: Uri, books: List<BookItem>): Result {
        val completed = books.filter { (it.position?.percent ?: -1.0) >= 99.999 }
        if (completed.isEmpty()) return Result(0, 0)

        val selectedRoot = DocumentFile.fromTreeUri(context, targetTree) ?: return Result(0, completed.size)
        val booksRoot = when {
            selectedRoot.name.equals("Books", true) -> selectedRoot
            selectedRoot.findFile("Books")?.isDirectory == true -> selectedRoot.findFile("Books")!!
            else -> return Result(0, completed.size)
        }
        val libraryFile = booksRoot.findFile("library.json") ?: return Result(0, completed.size)
        val text = context.contentResolver.openInputStream(libraryFile.uri)?.use { input ->
            val bytes = input.readBytesBounded(8 * 1024 * 1024) ?: return@use null
            bytes.toString(Charsets.UTF_8)
        } ?: return Result(0, completed.size)
        val library = runCatching { JSONArray(text) }.getOrNull() ?: return Result(0, completed.size)

        var finished = 0
        var ambiguous = 0
        val usedRows = mutableSetOf<Int>()
        val now = System.currentTimeMillis()

        for (book in completed) {
            val matches = findMatches(library, book).filterNot { it in usedRows }
            if (matches.size != 1) {
                ambiguous++
                continue
            }
            val index = matches.single()
            val row = library.optJSONObject(index) ?: continue
            val existingStatus = row.optString("readingStatus")
            if (existingStatus != "finished") {
                row.put("readingStatus", "finished")
                row.put("readingStatusUpdatedAt", book.position?.timestampMs?.takeIf { it > 0 } ?: now)
                row.put("updatedAt", maxOf(row.optLong("updatedAt", 0L), now))
            }
            usedRows += index
            finished++
        }

        if (finished > 0) {
            context.contentResolver.openOutputStream(libraryFile.uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(library.toString())
            } ?: return Result(0, completed.size)
        }
        return Result(finished, ambiguous)
    }

    private fun findMatches(library: JSONArray, book: BookItem): List<Int> {
        val isbn = normalizeIsbn(book.isbn ?: book.epub?.isbn)
        if (isbn != null) {
            val isbnMatches = mutableListOf<Int>()
            for (i in 0 until library.length()) {
                val row = library.optJSONObject(i) ?: continue
                if (rowIdentifiers(row).any { normalizeIsbn(it) == isbn }) isbnMatches += i
            }
            if (isbnMatches.isNotEmpty()) return isbnMatches
        }

        val titles = listOfNotNull(book.epub?.title, book.title)
            .map(::normalizeText).filter { it.isNotBlank() }.toSet()
        val authors = listOfNotNull(book.epub?.author, book.author)
            .flatMap { it.split(',', ';', '&') }
            .map(::normalizeText).filter { it.isNotBlank() }.toSet()
        val matches = mutableListOf<Int>()
        for (i in 0 until library.length()) {
            val row = library.optJSONObject(i) ?: continue
            val rowTitle = normalizeText(row.optString("title").ifBlank {
                row.optJSONObject("metadata")?.optString("title").orEmpty()
            })
            if (rowTitle !in titles) continue
            if (authors.isNotEmpty()) {
                val rowAuthor = normalizeText(row.optString("author").ifBlank {
                    row.optJSONObject("metadata")?.optString("author").orEmpty()
                })
                if (rowAuthor.isNotBlank() && authors.none { rowAuthor.contains(it) || it.contains(rowAuthor) }) continue
            }
            matches += i
        }
        return matches
    }

    private fun rowIdentifiers(row: JSONObject): List<String> {
        val metadata = row.optJSONObject("metadata") ?: return emptyList()
        val out = mutableListOf<String>()
        for (key in listOf("isbn", "identifier")) metadata.optString(key).takeIf { it.isNotBlank() }?.let(out::add)
        val alt = metadata.optJSONArray("altIdentifier")
        if (alt != null) for (i in 0 until alt.length()) alt.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
        return out
    }

    private fun normalizeIsbn(value: String?): String? = value
        ?.uppercase(Locale.ROOT)
        ?.filter { it.isDigit() || it == 'X' }
        ?.takeIf { it.length == 10 || it.length == 13 }

    private fun normalizeText(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

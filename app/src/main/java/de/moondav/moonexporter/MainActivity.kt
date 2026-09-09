package de.moondav.moonexporter

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MAX_FILES = 20_000
private const val MAX_PO_BYTES = 16 * 1024
private const val MAX_AN_BYTES = 16 * 1024 * 1024
private const val MAX_EPUB_BYTES = 32 * 1024 * 1024

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MoonExporterApp(this) }
    }
}

private data class MoonPosition(
    val raw: String,
    val timestampMs: Long?,
    val chapterOrPage: Int?,
    val section: Int?,
    val offset: Long?,
    val percent: Double?,
)

private data class AnnotationData(val text: String, val title: String?, val count: Int)
private data class EpubMatch(val uri: Uri, val fileName: String, val title: String?, val partialMd5: String?, val cover: Bitmap?)

private data class BookItem(
    val sourceFile: String,
    val originalName: String,
    val extension: String,
    val position: MoonPosition?,
    val annotation: AnnotationData?,
    val isCryptic: Boolean,
    val reconstructedTitle: String?,
    val editedTitle: String,
    val epub: EpubMatch?,
) {
    val displayTitle: String get() = editedTitle.ifBlank { reconstructedTitle ?: originalName }
    val hasAnnotations: Boolean get() = annotation != null && annotation.count > 0
}

@Composable
private fun MoonExporterApp(context: Context) {
    var books by remember { mutableStateOf<List<BookItem>>(emptyList()) }
    var status by remember { mutableStateOf("Wähle den Moon+ Ordner oder einen WebDAV-Save.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scope.launch {
            busy = true
            status = "Analysiere Moon+ Daten..."
            runCatching { scanMoonFolder(context, uri) }
                .onSuccess {
                    books = it
                    status = "${it.size} Bücher gefunden. ${it.count { b -> b.hasAnnotations }} mit Annotationen."
                }
                .onFailure { status = "Scan fehlgeschlagen: ${it.message}" }
            busy = false
        }
    }

    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        scope.launch {
            busy = true
            status = "Lese EPUB-Metadaten..."
            val epubs = withContext(Dispatchers.IO) { uris.mapNotNull { inspectEpub(context, it) } }
            books = autoMatchEpubs(books, epubs)
            status = "${epubs.size} EPUBs gelesen. ${books.count { it.epub != null }} Bücher gematcht."
            busy = false
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "Erzeuge Export..."
            runCatching { exportZip(context, uri, books) }
                .onSuccess { status = "Export fertig." }
                .onFailure { status = "Export fehlgeschlagen: ${it.message}" }
            busy = false
        }
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Moon Exporter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Lokaler Konverter für Moon+ Reader nach Readest, KOReader und CWA.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { treePicker.launch(null) }, enabled = !busy) { Text("Moon+ Ordner") }
                    OutlinedButton(onClick = { epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, enabled = !busy && books.isNotEmpty()) { Text("EPUBs matchen") }
                    Button(onClick = { exportPicker.launch("moon-exporter.zip") }, enabled = !busy && books.isNotEmpty()) { Text("Export") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status, style = MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(books, key = { it.sourceFile }) { item ->
                        BookRow(item) { title -> books = books.map { if (it.sourceFile == item.sourceFile) it.copy(editedTitle = title.trim()) else it } }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: BookItem, onRename: (String) -> Unit) {
    var title by remember(book.sourceFile, book.editedTitle) { mutableStateOf(book.displayTitle) }
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val cover = book.epub?.cover
            if (cover != null) {
                Image(cover.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp))
            } else {
                Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text(book.extension.uppercase(Locale.ROOT).take(4))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRename(title) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = {}, label = { Text(book.position?.percent?.let { "%.1f %%".format(Locale.ROOT, it) } ?: "keine Position") })
                    AssistChip(onClick = {}, label = { Text(if (book.hasAnnotations) "${book.annotation?.count} Annotationen" else "keine Annotationen") })
                    if (book.isCryptic) AssistChip(onClick = {}, label = { Text(if (book.reconstructedTitle != null) "Hash erkannt" else "manuell") })
                    if (book.epub != null) AssistChip(onClick = {}, label = { Text("EPUB") })
                }
                Text(book.sourceFile, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private suspend fun scanMoonFolder(context: Context, uri: Uri): List<BookItem> = withContext(Dispatchers.IO) {
    val root = DocumentFile.fromTreeUri(context, uri) ?: return@withContext emptyList()
    val positions = mutableMapOf<String, MoonPosition>()
    val annotations = mutableMapOf<String, AnnotationData>()
    val hints = mutableMapOf<String, String>()
    var seen = 0

    fun visit(dir: DocumentFile, depth: Int) {
        if (depth > 8 || seen > MAX_FILES) return
        for (child in dir.listFiles()) {
            seen++
            val name = child.name ?: continue
            if (child.isDirectory) {
                visit(child, depth + 1)
                continue
            }
            val lower = name.lowercase(Locale.ROOT)
            when {
                lower == "_names.list" || lower == "recent.list" -> readSmallText(context, child.uri, 512 * 1024)?.let { parseNameHints(it, hints) }
                lower.endsWith(".po") -> readSmallText(context, child.uri, MAX_PO_BYTES)?.let { positions[name.removeSuffix(".po")] = parsePo(it) }
                lower.endsWith(".an") -> readBytes(context, child.uri, MAX_AN_BYTES)?.let { decompressMoonAnnotation(it)?.also { data -> annotations[name.removeSuffix(".an")] = data } }
            }
        }
    }
    visit(root, 0)

    (positions.keys + annotations.keys).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).map { source ->
        val extension = source.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val original = source.substringBeforeLast('.', source)
        val cryptic = source.matches(Regex("^[0-9a-fA-F]{32}\\.(epub|pdf|mobi|azw3|cbr)$"))
        val hash = source.substringBefore('.', "").lowercase(Locale.ROOT)
        val title = annotations[source]?.title ?: hints[source.lowercase(Locale.ROOT)] ?: hints[hash]
        BookItem(source, original, extension, positions[source], annotations[source], cryptic, title, title ?: if (cryptic) "" else original, null)
    }
}

private fun parsePo(raw: String): MoonPosition {
    val text = raw.trim()
    Regex("^(\\d+)\\*(\\d+)@(\\d+)#(\\d+):([0-9.]+)%$").matchEntire(text)?.let {
        return MoonPosition(text, it.groupValues[1].toLong(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toLong(), it.groupValues[5].toDoubleOrNull())
    }
    Regex("^(\\d+)\\*(\\d+):([0-9.]+)%$").matchEntire(text)?.let {
        return MoonPosition(text, it.groupValues[1].toLong(), it.groupValues[2].toInt(), null, null, it.groupValues[3].toDoubleOrNull())
    }
    return MoonPosition(text, Regex("^(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull(), null, null, null, Regex("([0-9.]+)%").find(text)?.groupValues?.get(1)?.toDoubleOrNull())
}

private fun decompressMoonAnnotation(bytes: ByteArray): AnnotationData? = runCatching {
    val text = InflaterInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).readText()
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val marker = lines.indexOf("#")
    val title = if (marker >= 0 && marker + 2 < lines.size) lines[marker + 2].trim().ifBlank { null } else null
    AnnotationData(text, title, text.split("\n#\n").size.coerceAtLeast(1) - 1)
}.getOrNull()

private suspend fun inspectEpub(context: Context, uri: Uri): EpubMatch? = withContext(Dispatchers.IO) {
    val name = displayName(context, uri) ?: return@withContext null
    val bytes = readBytes(context, uri, MAX_EPUB_BYTES) ?: return@withContext EpubMatch(uri, name, name.substringBeforeLast('.'), partialMd5(context, uri), null)
    val entries = unzipSmall(bytes)
    EpubMatch(uri, name, extractEpubTitle(entries) ?: name.substringBeforeLast('.'), partialMd5(context, uri), extractEpubCover(entries))
}

private fun unzipSmall(bytes: ByteArray): Map<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.replace('\\', '/').trimStart('/')
            if (entry.isDirectory || name.contains("../") || name.startsWith(".") || out.size >= 512) continue
            val sink = ByteArrayOutputStream()
            zip.copyTo(sink, 64 * 1024)
            if (sink.size() <= 8 * 1024 * 1024) out[name] = sink.toByteArray()
        }
    }
    return out
}

private fun extractEpubTitle(entries: Map<String, ByteArray>): String? {
    val opf = entries.entries.firstOrNull { it.key.endsWith(".opf", true) }?.value?.toString(Charsets.UTF_8) ?: return null
    return Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.get(1)?.htmlUnescape()?.trim()
}

private fun extractEpubCover(entries: Map<String, ByteArray>): Bitmap? {
    val opfEntry = entries.entries.firstOrNull { it.key.endsWith(".opf", true) } ?: return null
    val opf = opfEntry.value.toString(Charsets.UTF_8)
    val coverId = Regex("<meta[^>]+name=[\"']cover[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.get(1)
    val href = if (coverId != null) Regex("<item[^>]+id=[\"']${Regex.escape(coverId)}[\"'][^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.get(1) else Regex("<item[^>]+properties=[\"'][^\"']*cover-image[^\"']*[\"'][^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.get(1)
    val cleanHref = href?.replace('\\', '/')?.trimStart('/') ?: return null
    val base = opfEntry.key.substringBeforeLast('/', "")
    val full = listOf(base, cleanHref).filter { it.isNotBlank() }.joinToString("/")
    if (full.contains("../")) return null
    val image = entries[full] ?: entries.entries.firstOrNull { it.key.endsWith(cleanHref) }?.value ?: return null
    return BitmapFactory.decodeByteArray(image, 0, image.size)
}

private fun autoMatchEpubs(books: List<BookItem>, epubs: List<EpubMatch>): List<BookItem> = books.map { book ->
    if (book.epub != null) book else {
        val byFile = epubs.firstOrNull { normalize(it.fileName.substringBeforeLast('.')) == normalize(book.originalName) }
        val wanted = normalize(book.displayTitle)
        val byTitle = epubs.firstOrNull { normalize(it.title.orEmpty()) == wanted } ?: epubs.firstOrNull { wanted.isNotBlank() && normalize(it.title.orEmpty()).contains(wanted) }
        book.copy(epub = byFile ?: byTitle)
    }
}

private suspend fun exportZip(context: Context, uri: Uri, books: List<BookItem>) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
        ZipOutputStream(output).use { zip ->
            zip.addText("README.txt", exportReadme())
            zip.addText("all_reading_positions.csv", positionsCsv(books))
            zip.addText("cryptic_books_manual_match.csv", positionsCsv(books.filter { it.isCryptic }))
            zip.addText("migration_manifest.json", manifestJson(books))
            zip.addText("kosync_progress.json", kosyncJson(books))
            books.forEach { book ->
                val safe = safeName(book.displayTitle)
                book.annotation?.let { zip.addText("mrexpt/$safe.mrexpt", it.text) }
                readestProgressJson(book)?.let { zip.addText("readest-progress/$safe.readest-annotations.json", it) }
                if (book.isCryptic && book.reconstructedTitle != null) {
                    book.position?.let { zip.addText("recovered-hash-books/$safe.${book.extension}.po", it.raw) }
                    book.annotation?.let { zip.addText("recovered-hash-books/$safe.mrexpt", it.text) }
                }
            }
        }
    } ?: error("Export-Ziel konnte nicht geöffnet werden")
}

private fun readestProgressJson(book: BookItem): String? {
    val position = book.position ?: return null
    val percent = position.percent ?: return null
    if (book.extension != "epub" || position.chapterOrPage == null) return null
    val cfi = "epubcfi(/6/${(position.chapterOrPage + 1) * 2}!)"
    val now = System.currentTimeMillis()
    val ts = position.timestampMs ?: now
    return """
{
  "${'$'}format": "readest-annotations",
  "version": 1,
  "exportedAt": $now,
  "book": { "title": ${book.displayTitle.json()}, "author": "", "format": "EPUB" },
  "progress": [${percent.toInt().coerceIn(0, 100)}, 100],
  "location": ${cfi.json()},
  "annotations": [
    { "id": ${("moon-position-" + safeName(book.displayTitle)).json()}, "type": "bookmark", "cfi": ${cfi.json()}, "note": ${("Imported Moon+ progress: %.1f%%".format(Locale.ROOT, percent)).json()}, "createdAt": $ts, "updatedAt": $ts }
  ]
}
""".trimIndent()
}

private fun kosyncJson(books: List<BookItem>): String = buildString {
    append("[\n")
    books.filter { it.position?.percent != null && it.epub?.partialMd5 != null }.forEachIndexed { index, book ->
        if (index > 0) append(",\n")
        val percent = book.position?.percent ?: 0.0
        append("  {\"document\":${book.epub?.partialMd5.orEmpty().json()},\"title\":${book.displayTitle.json()},\"percentage\":${"%.6f".format(Locale.ROOT, percent / 100.0)},\"progress\":${("%.2f%%".format(Locale.ROOT, percent)).json()},\"device\":\"Moon Exporter\",\"timestamp\":${(book.position?.timestampMs ?: System.currentTimeMillis()) / 1000}}")
    }
    append("\n]\n")
}

private fun positionsCsv(books: List<BookItem>): String = buildString {
    appendLine("book,source_file,timestamp_ms,timestamp_utc,chapter_or_page,section,character_offset,percent,raw_position,cryptic_filename,title_reconstructed,matched_epub")
    books.forEach { b ->
        val p = b.position
        appendLine(listOf(
            b.displayTitle,
            b.sourceFile,
            p?.timestampMs?.toString().orEmpty(),
            p?.timestampMs?.let { Instant.ofEpochMilli(it).toString() }.orEmpty(),
            p?.chapterOrPage?.toString().orEmpty(),
            p?.section?.toString().orEmpty(),
            p?.offset?.toString().orEmpty(),
            p?.percent?.toString().orEmpty(),
            p?.raw.orEmpty(),
            b.isCryptic.toString(),
            (b.reconstructedTitle != null).toString(),
            (b.epub != null).toString(),
        ).joinToString(",") { it.csv() })
    }
}

private fun manifestJson(books: List<BookItem>): String = """
{
  "format": "moon-exporter-bundle",
  "version": 1,
  "exportedAt": ${System.currentTimeMillis()},
  "books": ${books.size},
  "positions": ${books.count { it.position != null }},
  "withAnnotations": ${books.count { it.hasAnnotations }},
  "crypticFiles": ${books.count { it.isCryptic }},
  "matchedEpubs": ${books.count { it.epub != null }}
}
""".trimIndent()

private fun exportReadme(): String = """
Moon Exporter bundle

mrexpt/: Readest-kompatible Moon+ Annotationsexporte.
readest-progress/: Readest JSON mit konservativer Kapitelstart-Bookmark.
kosync_progress.json: KOReader/CWA/BookLore-kompatible Prozentdaten, soweit EPUBs gematcht wurden.
all_reading_positions.csv: vollständiger Moon+ .po Report.
cryptic_books_manual_match.csv: Hash-benannte Dateien zum manuellen Abgleich.

Dieser Export enthält keine Ebook-Dateien.
""".trimIndent()

private fun readSmallText(context: Context, uri: Uri, max: Int): String? = readBytes(context, uri, max)?.toString(Charsets.UTF_8)

private fun readBytes(context: Context, uri: Uri, max: Int): ByteArray? = context.contentResolver.openInputStream(uri)?.use { input ->
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buf, 0, n)
    }
    out.toByteArray()
}

private fun partialMd5(context: Context, uri: Uri): String? = runCatching {
    val data = readBytes(context, uri, MAX_EPUB_BYTES) ?: return null
    val digest = MessageDigest.getInstance("MD5")
    for (i in -1..10) {
        val offset = ((1024L shl ((2 * i) and 0x1f)) and 0xffffffffL).toInt()
        if (offset >= data.size) break
        digest.update(data, offset, (1024).coerceAtMost(data.size - offset))
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}.getOrNull()

private fun displayName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) return cursor.getString(0)
    }
    return uri.lastPathSegment
}

private fun parseNameHints(text: String, out: MutableMap<String, String>) {
    text.lines().forEach { line ->
        val parts = line.split('\t', '|', '=', limit = 2).map { it.trim() }
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) out[parts[0].lowercase(Locale.ROOT)] = parts[1]
    }
}

private fun ZipOutputStream.addText(path: String, text: String) {
    val safe = path.split('/').joinToString("/") { safeName(it) }
    putNextEntry(ZipEntry(safe))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun String.htmlUnescape(): String = replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
private fun String.csv(): String = "\"" + replace("\"", "\"\"") + "\""
private fun String.json(): String = buildString { append('"'); for (ch in this@json) when (ch) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(ch) }; append('"') }
private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD).replace(Regex("[^a-z0-9]+"), "").take(80)
private fun safeName(value: String): String = value.replace(Regex("[\\x00-\\x1f/\\\\:*?\"<>|]+"), "_").trim().take(160).ifBlank { "unknown" }

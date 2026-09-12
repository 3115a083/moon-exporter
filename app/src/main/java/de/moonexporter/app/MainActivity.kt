package de.moonexporter.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MoonExporterApp(this) }
    }
}

@Composable
private fun MoonExporterApp(context: Context) {
    var books by remember { mutableStateOf<List<BookItem>>(emptyList()) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var filter by remember { mutableStateOf(BookFilter.ALL) }
    var status by remember { mutableStateOf(tr("Moon+ Backup-Datei auswählen.", "Choose a Moon+ backup file.")) }
    var busy by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var exportMode by remember { mutableStateOf(ExportMode.FULL) }
    var diagnostic by remember { mutableStateOf(false) }
    var serverType by remember { mutableStateOf(ServerType.CALIBRE_WEB_AUTOMATED) }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("MoonExporter") }
    val scope = rememberCoroutineScope()

    fun setBooks(newBooks: List<BookItem>) {
        books = newBooks.sortedBy { sortTitle(it.title) }
        selected.clear()
        newBooks.forEach { selected[it.key] = true }
    }

    fun launchWork(block: suspend () -> Unit, cancelled: String) {
        activeJob = scope.launch {
            busy = true
            try { block() }
            catch (_: CancellationException) { status = cancelled; throw CancellationException() }
            catch (t: Throwable) { status = t.message ?: tr("Vorgang fehlgeschlagen", "Operation failed") }
            finally { busy = false; activeJob = null }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        launchWork({
            status = tr("Moon+ Ordner wird eingelesen…", "Reading Moon+ folder…")
            val result = MoonImporter.scanFolder(context, uri) { status = it }
            setBooks(result)
            status = if (result.isEmpty()) {
                tr("Keine Bücher erkannt. Prüfe Quelle oder Diagnose.", "No books detected. Check source or diagnostics.")
            } else tr("${result.size} Bücher gefunden.", "${result.size} books found.")
        }, tr("Einlesen abgebrochen", "Import cancelled"))
    }

    val mrproPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        launchWork({
            status = tr("Backup wird streamend analysiert…", "Streaming backup analysis…")
            val result = MoonImporter.scanMrpro(context, uri) { status = it }
            setBooks(result)
            status = tr("${result.size} Bücher aus dem Backup geladen.", "Loaded ${result.size} books from backup.")
        }, tr("Einlesen abgebrochen", "Import cancelled"))
    }

    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        launchWork({
            val epubs = mutableListOf<EpubMatch>()
            uris.forEachIndexed { index, uri ->
                status = tr("EPUB ${index + 1}/${uris.size} wird analysiert…", "Inspecting EPUB ${index + 1}/${uris.size}…")
                MoonImporter.inspectSelectedEpub(context, uri)?.let(epubs::add)
            }
            setBooks(MoonImporter.autoMatch(books, epubs))
            status = tr("${epubs.size} EPUBs analysiert.", "${epubs.size} EPUBs inspected.")
        }, tr("Einlesen abgebrochen", "Import cancelled"))
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val chosen = books.filter { selected[it.key] == true }
        launchWork({
            Exporter.export(context, uri, chosen, exportMode, diagnostic) { status = it }
            status = tr("Export abgeschlossen.", "Export complete.")
        }, tr("Export abgebrochen", "Export cancelled"))
    }

    val visibleBooks by remember(books, filter) {
        derivedStateOf {
            books.filter {
                when (filter) {
                    BookFilter.ALL -> true
                    BookFilter.WITH_PROGRESS -> it.position?.percent != null
                    BookFilter.WITHOUT_PROGRESS -> it.position?.percent == null
                    BookFilter.WITH_BOOK -> it.hasBookFile
                }
            }
        }
    }
    val selectedCount = selected.count { it.value }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Moon Exporter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(tr("Moon+ Migration für Readest und KOSync/CWA", "Moon+ migration for Readest and KOSync/CWA"), style = MaterialTheme.typography.bodySmall)
                    }
                    if (busy) TextButton(onClick = { activeJob?.cancel() }) { Text(tr("Abbrechen", "Cancel")) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { mrproPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) { Text(tr("Backup öffnen", "Open backup")) }
                    OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !busy) { Text(tr("Ordner", "Folder")) }
                    OutlinedButton(onClick = { epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, enabled = !busy && books.isNotEmpty()) { Text(tr("EPUBs zuordnen", "Match EPUBs")) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status, style = MaterialTheme.typography.bodySmall)

                if (books.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(filter == BookFilter.ALL, { filter = BookFilter.ALL }, { Text(tr("Alle", "All")) })
                        FilterChip(filter == BookFilter.WITH_PROGRESS, { filter = BookFilter.WITH_PROGRESS }, { Text(tr("Mit Fortschritt", "Progress")) })
                        FilterChip(filter == BookFilter.WITHOUT_PROGRESS, { filter = BookFilter.WITHOUT_PROGRESS }, { Text(tr("Ohne Fortschritt", "No progress")) })
                        FilterChip(filter == BookFilter.WITH_BOOK, { filter = BookFilter.WITH_BOOK }, { Text(tr("Mit Buch", "With book")) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { visibleBooks.forEach { selected[it.key] = true } },
                            modifier = Modifier.height(40.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) { Text(tr("Sichtbare auswählen", "Select visible")) }
                        OutlinedButton(
                            onClick = { selected.clear() },
                            modifier = Modifier.height(40.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) { Text(tr("Auswahl leeren", "Clear selection")) }
                        Text("$selectedCount", modifier = Modifier.align(Alignment.CenterVertically))
                    }

                    val listState = rememberLazyListState()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visibleBooks, key = { it.key }) { book ->
                                BookCard(book, selected[book.key] == true) { checked -> selected[book.key] = checked }
                            }
                        }
                        FastScroller(visibleBooks, listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(24.dp))
                    }
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            tr("Noch keine Bücher geladen. Export- und Servereinstellungen bleiben erreichbar.", "No books loaded yet. Export and server settings remain available."),
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                ExportControls(exportMode, { exportMode = it }, diagnostic, { diagnostic = it }, enabled = !busy && selectedCount > 0) {
                    exportPicker.launch(null)
                }
                SyncControls(
                    serverType, { serverType = it }, serverUrl, { serverUrl = it }, username, { username = it }, password, { password = it }, deviceId, { deviceId = it }, busy,
                    canSend = selectedCount > 0,
                    onTest = {
                        launchWork({
                            status = KoSyncClient.authenticate(SyncConfig(serverType, serverUrl, username, password, deviceId))
                        }, tr("Übertragung abgebrochen", "Transfer cancelled"))
                    },
                    onSend = {
                        val chosen = books.filter { selected[it.key] == true }
                        launchWork({
                            KoSyncClient.uploadProgress(SyncConfig(serverType, serverUrl, username, password, deviceId), chosen) { status = it }
                            status = tr("Fortschritt übertragen.", "Progress sent.")
                        }, tr("Übertragung abgebrochen", "Transfer cancelled"))
                    },
                )
            }
        }
    }
}

@Composable
private fun BookCard(book: BookItem, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(checked = checked, onCheckedChange = onChecked)
            Cover(book.epub?.cover, book.extension)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                book.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AssistChip(onClick = {}, label = { Text(book.position?.percent?.let { "%.1f%%".format(Locale.ROOT, it) } ?: "0.0%") })
                    AssistChip(onClick = {}, label = { Text("${book.annotation?.count ?: 0} ${tr("Mark.", "marks")}") })
                    AssistChip(onClick = {}, label = { Text(if (book.hasBookFile) tr("Buch vorhanden", "Book available") else tr("Buch fehlt", "Book missing")) })
                }
                val details = listOfNotNull(book.isbn?.let { "ISBN $it" }, book.sourceFile).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, extension: String) {
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)))
    else Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text(extension.uppercase(Locale.ROOT).take(4)) }
}

@Composable
private fun FastScroller(books: List<BookItem>, listState: LazyListState, modifier: Modifier = Modifier) {
    if (books.size < 2) return
    val scope = rememberCoroutineScope()
    var trackHeight by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragSection by remember { mutableStateOf("#") }
    val currentIndex by remember { derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, books.lastIndex) } }
    val currentSection = sectionFor(books[currentIndex].title)
    val visibleCount by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) } }
    val thumbFraction = (visibleCount.toFloat() / books.size.toFloat()).coerceIn(0.08f, 1f)
    val progress = (currentIndex.toFloat() / books.lastIndex.toFloat()).coerceIn(0f, 1f)

    fun seek(y: Float) {
        val fraction = (y / trackHeight).coerceIn(0f, 1f)
        val index = (fraction * books.lastIndex).toInt().coerceIn(0, books.lastIndex)
        dragSection = sectionFor(books[index].title)
        scope.launch { listState.scrollToItem(index) }
    }

    Box(
        modifier.onGloballyPositioned { trackHeight = it.size.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(books.size) {
                detectDragGestures(
                    onDragStart = { dragging = true; seek(it.y) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onDrag = { change, _ -> change.consume(); seek(change.position.y) },
                )
            }
    ) {
        Box(Modifier.align(Alignment.Center).width(5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)))
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = ((trackHeight * (1f - thumbFraction)) * progress).coerceAtLeast(0f).dp)
                .width(12.dp).height((trackHeight * thumbFraction).coerceAtLeast(36f).dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
        )
        Text(if (dragging) dragSection else currentSection, modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface).padding(horizontal = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ExportControls(
    mode: ExportMode,
    setMode: (ExportMode) -> Unit,
    diagnostic: Boolean,
    setDiagnostic: (Boolean) -> Unit,
    enabled: Boolean,
    onExport: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(mode == ExportMode.FULL, { setMode(ExportMode.FULL) }, { Text(tr("Vollständig", "Full")) })
        FilterChip(mode == ExportMode.MARKINGS_ONLY, { setMode(ExportMode.MARKINGS_ONLY) }, { Text(tr("Nur Markierungen", "Marks only")) })
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(diagnostic, setDiagnostic); Text(tr("Diagnose", "Diagnostics")) }
        Spacer(Modifier.weight(1f))
        Button(onClick = onExport, enabled = enabled) { Text(tr("Exportieren", "Export")) }
    }
}

@Composable
private fun SyncControls(
    type: ServerType, setType: (ServerType) -> Unit,
    url: String, setUrl: (String) -> Unit,
    user: String, setUser: (String) -> Unit,
    password: String, setPassword: (String) -> Unit,
    deviceId: String, setDeviceId: (String) -> Unit,
    busy: Boolean,
    canSend: Boolean,
    onTest: () -> Unit,
    onSend: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Lesefortschritt senden", "Send reading progress"), fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(type == ServerType.STANDARD_KOSYNC, { setType(ServerType.STANDARD_KOSYNC) }, { Text("KOSync") })
                FilterChip(type == ServerType.CALIBRE_WEB_AUTOMATED, { setType(ServerType.CALIBRE_WEB_AUTOMATED) }, { Text("Calibre-Web Automated") })
            }
            OutlinedTextField(url, setUrl, label = { Text(tr("Server-URL (HTTPS)", "Server URL (HTTPS)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(user, setUser, label = { Text(tr("Benutzer", "Username")) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(password, setPassword, label = { Text(tr("Passwort", "Password")) }, singleLine = true, modifier = Modifier.weight(1f), visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(deviceId, setDeviceId, label = { Text("Device ID") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text(tr("Es werden nur partialMD5 und Fortschritt übertragen, niemals die E-Book-Datei.", "Only partialMD5 and progress are sent, never the ebook file."), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = !busy && url.isNotBlank() && user.isNotBlank()) { Text(tr("Verbindung testen", "Test connection")) }
                Button(onClick = onSend, enabled = !busy && canSend && url.isNotBlank() && user.isNotBlank()) { Text(tr("Auswahl übertragen", "Send selection")) }
            }
        }
    }
}

package de.moonexporter.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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

private enum class TransferTarget { READEST, KOSYNC, CWA, BOOKLORE }

@Composable
private fun MoonExporterApp(context: Context) {
    var books by remember { mutableStateOf<List<BookItem>>(emptyList()) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var filter by remember { mutableStateOf(BookFilter.ALL) }
    var target by remember { mutableStateOf(TransferTarget.READEST) }
    var exportMode by remember { mutableStateOf(ExportMode.MARKINGS_ONLY) }
    var status by remember { mutableStateOf(tr("Wähle eine Moon+ Backup-Datei.", "Choose a Moon+ backup file.")) }
    var busy by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun setBooks(newBooks: List<BookItem>, resetSelection: Boolean = true) {
        books = newBooks.sortedBy { sortTitle(it.title) }
        if (resetSelection) {
            selected.clear()
            books.forEach { selected[it.key] = true }
        } else {
            books.forEach { if (it.key !in selected) selected[it.key] = true }
        }
    }

    fun launchWork(cancelled: String, block: suspend () -> Unit) {
        activeJob = scope.launch {
            busy = true
            try { block() }
            catch (_: CancellationException) { status = cancelled; throw CancellationException() }
            catch (t: Throwable) { status = t.message ?: tr("Vorgang fehlgeschlagen", "Operation failed") }
            finally { busy = false; activeJob = null }
        }
    }

    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        launchWork(tr("Import abgebrochen", "Import cancelled")) {
            status = tr("Backup wird analysiert…", "Analyzing backup…")
            val result = MoonImporter.scanMrpro(context, uri) { status = it }
            setBooks(result)
            status = tr("${result.size} Bücher gefunden. Cover und Metadaten werden ergänzt…", "${result.size} books found. Loading covers and metadata…")
            val enriched = BackupMetadata.enrich(context, result) { status = it }
            setBooks(enriched, resetSelection = false)
            status = tr("${enriched.size} Bücher analysiert.", "${enriched.size} books analyzed.")
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        launchWork(tr("Import abgebrochen", "Import cancelled")) {
            status = tr("Moon+ Ordner wird analysiert…", "Analyzing Moon+ folder…")
            val result = MoonImporter.scanFolder(context, uri) { status = it }
            setBooks(result)
            status = tr("${result.size} Bücher analysiert.", "${result.size} books analyzed.")
        }
    }

    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        launchWork(tr("Zuordnung abgebrochen", "Matching cancelled")) {
            val matches = mutableListOf<EpubMatch>()
            uris.forEachIndexed { index, uri ->
                status = tr("Buchdatei ${index + 1}/${uris.size} wird geprüft…", "Checking book file ${index + 1}/${uris.size}…")
                MoonImporter.inspectSelectedEpub(context, uri)?.let(matches::add)
            }
            setBooks(MoonImporter.autoMatch(books, matches), resetSelection = false)
            status = tr("${matches.size} Buchdateien geprüft.", "${matches.size} book files checked.")
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val chosen = books.filter { selected[it.key] == true }
        launchWork(tr("Export abgebrochen", "Export cancelled")) {
            Exporter.export(context, uri, chosen, exportMode, false) { status = it }
            status = tr("Readest-Export abgeschlossen.", "Readest export complete.")
        }
    }

    val visibleBooks = books.filter {
        when (filter) {
            BookFilter.ALL -> true
            BookFilter.WITH_PROGRESS -> it.position?.percent != null
            BookFilter.WITHOUT_PROGRESS -> it.position?.percent == null
            BookFilter.WITH_BOOK -> it.hasBookFile
        }
    }
    val selectedCount = selected.count { it.value }
    val selectedWithProgress = books.count { selected[it.key] == true && it.position?.percent != null }
    val selectedWithMarks = books.count { selected[it.key] == true && it.hasAnnotations }
    val missingBookFiles = books.count { selected[it.key] == true && it.epub?.partialMd5.isNullOrBlank() }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Moon Exporter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(tr("Moon+ Daten prüfen und zu Readest oder KOSync übertragen", "Review Moon+ data and transfer it to Readest or KOSync"), style = MaterialTheme.typography.bodySmall)
                        }
                        if (busy) TextButton(onClick = { activeJob?.cancel() }) { Text(tr("Abbrechen", "Cancel")) }
                    }
                }

                item {
                    StepCard("1", tr("Backup auswählen", "Choose backup")) {
                        Button(onClick = { backupPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Moon+ Backup-Datei auswählen", "Choose Moon+ backup file"))
                        }
                        TextButton(onClick = { folderPicker.launch(null) }, enabled = !busy) { Text(tr("Alternativ Moon+ Ordner verwenden", "Use Moon+ folder instead")) }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    StepCard("2", tr("Bücher prüfen und auswählen", "Review and select books")) {
                        if (books.isEmpty()) {
                            Text(
                                if (busy) tr("Die Buchliste erscheint hier, sobald die ersten Analysedaten verfügbar sind.", "The book list appears here as soon as analysis data is available.")
                                else tr("Noch keine Bücher analysiert. Nach dem Import sind hier alle erkannten Bücher einzeln auswählbar.", "No books analyzed yet. After import, every detected book can be selected here individually."),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text(tr("$selectedCount von ${books.size} ausgewählt · $selectedWithProgress mit Fortschritt · $selectedWithMarks mit Markierungen", "$selectedCount of ${books.size} selected · $selectedWithProgress with progress · $selectedWithMarks with highlights"), style = MaterialTheme.typography.bodySmall)
                            FilterDropdown(filter, { filter = it })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { visibleBooks.forEach { selected[it.key] = true } }, modifier = Modifier.weight(1f)) { Text(tr("Sichtbare wählen", "Select visible")) }
                                OutlinedButton(onClick = { visibleBooks.forEach { selected[it.key] = false } }, modifier = Modifier.weight(1f)) { Text(tr("Sichtbare abwählen", "Deselect visible")) }
                            }
                            if (missingBookFiles > 0) {
                                Text(tr("$missingBookFiles ausgewählte Bücher haben keine sichere KOSync-ID. Für Serverziele kann eine passende Buchdatei zugeordnet werden.", "$missingBookFiles selected books have no reliable KOSync ID. For server destinations, a matching book file can be assigned."), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { epubPicker.launch(arrayOf("application/epub+zip", "application/pdf", "application/octet-stream")) }, enabled = !busy) { Text(tr("Buchdateien zuordnen", "Match book files")) }
                            }
                        }
                    }
                }

                if (books.isNotEmpty()) {
                    items(visibleBooks, key = { it.key }) { book ->
                        BookCard(book, selected[book.key] == true) { selected[book.key] = it }
                    }
                }

                item {
                    StepCard("3", tr("Ziel und Exportart", "Destination and export type")) {
                        Text(tr("Ziel auswählen", "Select destination"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        TransferTarget.entries.forEach { option ->
                            SelectionRow(selected = target == option, label = targetLabel(option), onClick = { target = option })
                        }
                        when (target) {
                            TransferTarget.READEST -> ReadestTarget(exportMode, { exportMode = it }, enabled = !busy && selectedCount > 0) { exportPicker.launch(null) }
                            else -> ServerTarget(
                                target = target,
                                url = serverUrl,
                                setUrl = { serverUrl = it },
                                user = username,
                                setUser = { username = it },
                                password = password,
                                setPassword = { password = it },
                                busy = busy,
                                canSend = selectedCount > 0,
                                onTest = {
                                    val config = SyncConfig(serverType(target), serverUrl, username, password)
                                    launchWork(tr("Verbindungstest abgebrochen", "Connection test cancelled")) { status = KoSyncClient.authenticate(config) }
                                },
                                onSend = {
                                    val chosen = books.filter { selected[it.key] == true }
                                    val config = SyncConfig(serverType(target), serverUrl, username, password)
                                    launchWork(tr("Übertragung abgebrochen", "Transfer cancelled")) {
                                        KoSyncClient.uploadProgress(config, chosen) { status = it }
                                        status = tr("Lesefortschritt übertragen.", "Reading progress transferred.")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("$number. $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SelectionRow(selected: Boolean, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FilterDropdown(filter: BookFilter, onFilter: (BookFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("Buchfilter", "Book filter"), style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("${filterLabel(filter)}  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BookFilter.entries.forEach { value -> DropdownMenuItem(text = { Text(filterLabel(value)) }, onClick = { onFilter(value); open = false }) }
        }
    }
}

@Composable
private fun ReadestTarget(mode: ExportMode, setMode: (ExportMode) -> Unit, enabled: Boolean, onExport: () -> Unit) {
    Text(tr("Exportart auswählen", "Select export type"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    SelectionRow(mode == ExportMode.MARKINGS_ONLY, tr("Nur Markierungen für Readest", "Highlights only for Readest")) { setMode(ExportMode.MARKINGS_ONLY) }
    SelectionRow(mode == ExportMode.FULL, tr("Markierungen + Buchdateien", "Highlights + book files")) { setMode(ExportMode.FULL) }
    Text(tr("Die eigentliche Aktion ist erst der folgende Export-Button.", "The actual action is the export button below."), style = MaterialTheme.typography.bodySmall)
    Button(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(tr("Ausgewählte Bücher exportieren", "Export selected books")) }
}

@Composable
private fun ServerTarget(
    target: TransferTarget, url: String, setUrl: (String) -> Unit, user: String, setUser: (String) -> Unit,
    password: String, setPassword: (String) -> Unit, busy: Boolean, canSend: Boolean, onTest: () -> Unit, onSend: () -> Unit,
) {
    val hint = when (target) {
        TransferTarget.CWA -> tr("CWA-Serveradresse. /kosync wird automatisch ergänzt.", "CWA server address. /kosync is added automatically.")
        TransferTarget.BOOKLORE -> tr("BookLore-Serveradresse. /api/koreader wird automatisch ergänzt.", "BookLore server address. /api/koreader is added automatically.")
        else -> tr("Adresse des KOSync-kompatiblen Servers.", "Address of the KOSync-compatible server.")
    }
    Text(tr("Du kannst die Zugangsdaten bereits während der Backup-Analyse eingeben. Übertragen wird erst nach deiner Buchauswahl.", "You can enter credentials while the backup is still being analyzed. Transfer starts only after you select books."), style = MaterialTheme.typography.bodySmall)
    Text(hint, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(url, setUrl, label = { Text(tr("Server-URL (HTTPS)", "Server URL (HTTPS)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedTextField(user, setUser, label = { Text(tr("Benutzername", "Username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, setPassword, label = { Text(tr("Passwort", "Password")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onTest, enabled = !busy && url.isNotBlank() && user.isNotBlank(), modifier = Modifier.weight(1f)) { Text(tr("Verbindung testen", "Test connection")) }
        Button(onClick = onSend, enabled = !busy && canSend && url.isNotBlank() && user.isNotBlank(), modifier = Modifier.weight(1f)) { Text(tr("Fortschritt senden", "Send progress")) }
    }
}

@Composable
private fun BookCard(book: BookItem, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(checked = checked, onCheckedChange = onChecked)
            Cover(book.epub?.cover, book.extension)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                val progress = book.position?.percent?.let { "%.1f%%".format(Locale.ROOT, it) } ?: tr("kein Fortschritt", "no progress")
                Text(tr("Lesefortschritt: $progress", "Reading progress: $progress"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(tr("Positionsart: ${positionMethod(book.position)}", "Position method: ${positionMethod(book.position)}"), style = MaterialTheme.typography.bodySmall)
                Text(tr("Markierungen: ${book.annotation?.count ?: 0}", "Highlights: ${book.annotation?.count ?: 0}"), style = MaterialTheme.typography.bodySmall)
                val identity = book.epub?.partialMd5?.takeIf { it.isNotBlank() }?.let { "partialMD5 ${it.take(10)}…" } ?: tr("keine sichere KOSync-ID", "no reliable KOSync ID")
                Text(tr("Buch-ID: $identity", "Book ID: $identity"), style = MaterialTheme.typography.bodySmall)
                Text(tr("Quelle: ${book.sourceFile}", "Source: ${book.sourceFile}"), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, extension: String) {
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
    else Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(extension.uppercase(Locale.ROOT).take(4), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun positionMethod(position: MoonPosition?): String = when {
    position == null -> tr("nicht vorhanden", "not available")
    position.chapterOrPage != null && position.section != null && position.offset != null -> tr("Moon+ Kapitel / Abschnitt / Zeichenposition", "Moon+ chapter / section / character offset")
    position.chapterOrPage != null && position.offset == null -> tr("Moon+ Seite + Prozent", "Moon+ page + percentage")
    position.percent != null -> tr("Prozent-Fallback", "percentage fallback")
    else -> tr("Rohwert erhalten, nicht aufgelöst", "raw value preserved, unresolved")
}

private fun serverType(target: TransferTarget): ServerType = when (target) {
    TransferTarget.CWA -> ServerType.CALIBRE_WEB_AUTOMATED
    TransferTarget.BOOKLORE -> ServerType.BOOKLORE
    else -> ServerType.STANDARD_KOSYNC
}

@Composable
private fun filterLabel(value: BookFilter): String = when (value) {
    BookFilter.ALL -> tr("Alle Bücher", "All books")
    BookFilter.WITH_PROGRESS -> tr("Mit Lesefortschritt", "With reading progress")
    BookFilter.WITHOUT_PROGRESS -> tr("Ohne Lesefortschritt", "Without reading progress")
    BookFilter.WITH_BOOK -> tr("Mit zugeordneter Buchdatei", "With matched book file")
}

@Composable
private fun targetLabel(value: TransferTarget): String = when (value) {
    TransferTarget.READEST -> tr("Readest als Datei-Export", "Readest file export")
    TransferTarget.KOSYNC -> tr("KOSync-kompatibler Server", "KOSync-compatible server")
    TransferTarget.CWA -> "Calibre-Web Automated"
    TransferTarget.BOOKLORE -> "BookLore"
}

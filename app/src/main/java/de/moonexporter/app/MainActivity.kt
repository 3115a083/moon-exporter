package de.moonexporter.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent { MoonExporterApp(this) }
    }
}

private enum class TransferTarget { READEST, KOSYNC, CWA, BOOKLORE }

@Composable
private fun MoonExporterApp(context: Context) {
    val analysis by AnalysisState.state.collectAsStateWithLifecycle()
    var books by remember { mutableStateOf<List<BookItem>>(emptyList()) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var filter by remember { mutableStateOf(BookFilter.ALL) }
    var target by remember { mutableStateOf(TransferTarget.READEST) }
    var exportMode by remember { mutableStateOf(ExportMode.MARKINGS_ONLY) }
    var normalizeReadestNames by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf(tr("Wähle eine oder mehrere Moon+ Backup-Dateien.", "Choose one or more Moon+ backup files.")) }
    var exportProgress by remember { mutableStateOf<Float?>(null) }
    var exportFailed by remember { mutableStateOf(false) }
    var localBusy by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var connectionFeedback by remember { mutableStateOf<String?>(null) }
    var pendingBookKey by remember { mutableStateOf<String?>(null) }
    var pendingExportKeys by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var lastSession by remember { mutableStateOf(0L) }
    var lastRevision by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val busy = localBusy || analysis.running

    fun setBooks(newBooks: List<BookItem>, resetSelection: Boolean = true) {
        books = newBooks.sortedBy { sortTitle(it.title) }
        if (resetSelection) {
            selected.clear()
            books.forEach { book ->
                selected[book.key] = book.hasBookFile || book.position != null || book.hasAnnotations
            }
        } else books.forEach { if (it.key !in selected) selected[it.key] = true }
    }

    LaunchedEffect(analysis.sessionId, analysis.revision) {
        if (analysis.sessionId != 0L && analysis.revision != lastRevision) {
            val newSession = analysis.sessionId != lastSession
            setBooks(analysis.books, resetSelection = newSession)
            status = analysis.status
            lastSession = analysis.sessionId
            lastRevision = analysis.revision
        }
    }
    LaunchedEffect(analysis.status) {
        if (analysis.sessionId != 0L) status = analysis.status
    }

    fun launchWork(cancelled: String, block: suspend () -> Unit) {
        activeJob = scope.launch {
            localBusy = true
            try { block() }
            catch (_: CancellationException) { status = cancelled; throw CancellationException() }
            catch (t: Throwable) { status = t.message ?: tr("Vorgang fehlgeschlagen", "Operation failed") }
            finally { localBusy = false; activeJob = null }
        }
    }

    fun startAnalysis(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val intent = Intent(context, AnalysisService::class.java).setAction(AnalysisService.ACTION_START)
        if (uris.size == 1) {
            intent.putExtra(AnalysisService.EXTRA_URI, uris.single().toString())
        } else {
            intent.putStringArrayListExtra(AnalysisService.EXTRA_URIS, ArrayList(uris.map(android.net.Uri::toString)))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        exportProgress = null
        exportFailed = false
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startAnalysis(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportProgress = null
        exportFailed = false
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        launchWork(tr("Import abgebrochen", "Import cancelled")) {
            status = tr("Moon+ Ordner wird analysiert…", "Analyzing Moon+ folder…")
            val result = MoonImporter.scanFolder(context, uri) { status = it }
            setBooks(ProgressRecovery.reconstructTitles(result))
            status = tr("${result.size} Bücher analysiert.", "${result.size} books analyzed.")
        }
    }
    val multiBookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        launchWork(tr("Zuordnung abgebrochen", "Matching cancelled")) {
            val matches = mutableListOf<EpubMatch>()
            uris.forEachIndexed { index, uri ->
                status = tr("Buchdatei ${index + 1}/${uris.size} wird geprüft…", "Checking book file ${index + 1}/${uris.size}…")
                BookFileInspector.inspect(context, uri)?.let(matches::add)
            }
            setBooks(ProgressRecovery.reconstructTitles(MoonImporter.autoMatch(books, matches)), resetSelection = false)
            status = tr("${matches.size} Buchdateien geprüft.", "${matches.size} book files checked.")
        }
    }
    val singleBookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = pendingBookKey
        pendingBookKey = null
        if (uri == null || key == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        launchWork(tr("Zuordnung abgebrochen", "Matching cancelled")) {
            status = tr("Buchdatei wird geprüft…", "Checking book file…")
            val match = BookFileInspector.inspect(context, uri) ?: error(tr("Buchdatei konnte nicht gelesen werden oder das Format wird von Readest nicht unterstützt", "Book file could not be read or its format is not supported by Readest"))
            books = books.map { book ->
                if (book.key != key) book else {
                    val metadataTitle = match.title?.takeIf { it.isNotBlank() && !ProgressRecovery.looksOpaque(it) }
                    book.copy(epub = match, title = metadataTitle ?: book.title, author = book.author ?: match.author, isbn = book.isbn ?: match.isbn)
                }
            }.sortedBy { sortTitle(it.title) }
            status = tr("Buchdatei zugeordnet. Der Moon+-Lesefortschritt bleibt erhalten.", "Book file matched. Moon+ reading progress was preserved.")
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            pendingExportKeys = null
            return@rememberLauncherForActivityResult
        }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val chosenKeys = pendingExportKeys?.toSet().orEmpty()
        pendingExportKeys = null
        val sourceBooks = books.ifEmpty { analysis.books }
        val chosen = sourceBooks.filter { it.key in chosenKeys }
        if (chosen.isEmpty()) {
            exportFailed = true
            status = tr("Keine ausgewählten Bücher für den Export gefunden.", "No selected books were found for export.")
            return@rememberLauncherForActivityResult
        }
        exportProgress = 0f
        exportFailed = false
        launchWork(tr("Export abgebrochen", "Export cancelled")) {
            try {
                Exporter.export(context, uri, chosen, exportMode, false, normalizeReadestNames) { progress ->
                    status = progress.message
                    exportProgress = progress.fraction
                }
                status = tr("Readest-Export abgeschlossen.", "Readest export complete.")
                exportProgress = 1f
            } catch (t: Throwable) {
                exportFailed = true
                throw t
            }
        }
    }

    val visibleBooks = books.filter {
        when (filter) {
            BookFilter.ALL -> true
            BookFilter.WITH_PROGRESS -> it.position?.percent != null
            BookFilter.WITHOUT_PROGRESS -> it.position?.percent == null
            BookFilter.WITH_BOOK -> it.hasBookFile
            BookFilter.WITHOUT_BOOK -> !it.hasBookFile
        }
    }
    val selectedCount = selected.count { it.value }
    val selectedWithProgress = books.count { selected[it.key] == true && it.position?.percent != null }
    val selectedWithMarks = books.count { selected[it.key] == true && it.hasAnnotations }
    val selectedCryptic = books.count {
        selected[it.key] == true && (it.isCryptic || ProgressRecovery.looksOpaque(it.epub?.fileName ?: it.sourceFile))
    }
    val missingBookFiles = books.count { selected[it.key] == true && it.position != null && it.epub?.partialMd5.isNullOrBlank() }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 12.dp, end = if (visibleBooks.isNotEmpty()) 42.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Moon Exporter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text(tr("Moon+ Lesefortschritt und Markierungen übertragen", "Transfer Moon+ reading progress and highlights"), style = MaterialTheme.typography.bodySmall)
                            }
                            if (busy) TextButton(onClick = {
                                if (analysis.running) context.startService(Intent(context, AnalysisService::class.java).setAction(AnalysisService.ACTION_CANCEL)) else activeJob?.cancel()
                            }) { Text(tr("Abbrechen", "Cancel")) }
                        }
                    }
                    item {
                        StepCard("1", tr("Backup analysieren", "Analyze backup")) {
                            Button(onClick = { backupPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Moon+ Backup(s) auswählen", "Choose Moon+ backup(s)")) }
                            Text(tr("Mehrere .mrpro aus /.Moon+/Backup können gemeinsam gewählt werden. Moon Exporter sortiert sie nach Backup-Datum und führt Fortschritt und Markierungen konservativ zusammen.", "You can select multiple .mrpro files from /.Moon+/Backup together. Moon Exporter sorts them by backup date and conservatively consolidates progress and highlights."), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { folderPicker.launch(null) }, enabled = !busy) { Text(tr("Alternativ Moon+ Ordner verwenden", "Use Moon+ folder instead")) }
                            if (analysis.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(status, style = MaterialTheme.typography.bodySmall)
                            if (analysis.running) Text(tr("Die Analyse läuft als Android-Hintergrundaufgabe weiter. Der Fortschritt ist auch in der Benachrichtigung sichtbar.", "Analysis continues as an Android foreground task. Progress is also visible in the notification."), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    item {
                        StepCard("2", tr("Bücher prüfen und auswählen", "Review and select books")) {
                            if (books.isEmpty()) {
                                Text(if (analysis.running) tr("Die Buchliste erscheint nach der Datenauswertung. Du kannst bereits zu Schritt 3 scrollen und das Ziel konfigurieren.", "The book list appears after data extraction. You can already scroll to step 3 and configure the destination.") else tr("Noch keine Bücher analysiert. Nach dem Import werden hier alle erkannten Bücher mit Fortschritt und Berechnungsart angezeigt.", "No books analyzed yet. Detected books will appear here with progress and calculation method."), style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(tr("$selectedCount von ${books.size} ausgewählt · $selectedWithProgress mit Fortschritt · $selectedWithMarks mit Markierungen", "$selectedCount of ${books.size} selected · $selectedWithProgress with progress · $selectedWithMarks with highlights"), style = MaterialTheme.typography.bodySmall)
                                Text(tr("Rechts: Pfeil nach oben/unten für Anfang/Ende, Buchstaben antippen oder ziehen.", "Right: use the arrows for top/bottom, or tap and drag letters."), style = MaterialTheme.typography.labelSmall)
                                FilterDropdown(filter) { filter = it }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { visibleBooks.forEach { selected[it.key] = true } }, modifier = Modifier.weight(1f)) { Text(tr("Sichtbare wählen", "Select visible")) }
                                    OutlinedButton(onClick = { visibleBooks.forEach { selected[it.key] = false } }, modifier = Modifier.weight(1f)) { Text(tr("Abwählen", "Deselect")) }
                                }
                                if (missingBookFiles > 0) {
                                    Text(tr("$missingBookFiles ausgewählte Bücher haben Fortschritt, aber noch keine sichere Buchdatei für die Zielberechnung. Sie können direkt in der jeweiligen Buchkarte zugeordnet werden.", "$missingBookFiles selected books have progress but no reliable book file for target calculation. They can be matched directly in each book card."), style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = { multiBookPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(tr("Mehrere Buchdateien automatisch zuordnen", "Auto-match multiple book files")) }
                                }
                            }
                        }
                    }
                    if (books.isNotEmpty()) items(visibleBooks, key = { it.key }) { book ->
                        BookCard(book, selected[book.key] == true, { selected[book.key] = it }) {
                            pendingBookKey = book.key
                            singleBookPicker.launch(arrayOf("*/*"))
                        }
                    }
                    item {
                        StepCard("3", tr("Ziel auswählen", "Choose destination")) {
                            Text(tr("Exportziel", "Export destination"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            TransferTarget.entries.forEach { option -> SelectionRow(target == option, targetLabel(option)) { target = option; connectionFeedback = null } }
                            when (target) {
                                TransferTarget.READEST -> ReadestTarget(
                                    exportMode,
                                    { exportMode = it },
                                    normalizeReadestNames,
                                    { normalizeReadestNames = it },
                                    selectedCryptic,
                                    enabled = !busy && selectedCount > 0,
                                ) {
                                    pendingExportKeys = ArrayList(books.filter { selected[it.key] == true }.map { it.key })
                                    exportPicker.launch(null)
                                }
                                else -> ServerTarget(
                                    target, serverUrl, { serverUrl = it; connectionFeedback = null }, username, { username = it; connectionFeedback = null }, password, { password = it; connectionFeedback = null }, busy, selectedCount > 0, connectionFeedback,
                                    onTest = {
                                        val config = SyncConfig(serverType(target), serverUrl, username, password)
                                        launchWork(tr("Verbindungstest abgebrochen", "Connection test cancelled")) {
                                            connectionFeedback = tr("Verbindung wird getestet…", "Testing connection…")
                                            try {
                                                val result = KoSyncClient.authenticate(config)
                                                connectionFeedback = result
                                                status = result
                                            } catch (t: Throwable) {
                                                connectionFeedback = t.message ?: tr("Verbindung fehlgeschlagen", "Connection failed")
                                                throw t
                                            }
                                        }
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
                    if (exportProgress != null) item {
                        StepCard("4", tr("Exportfortschritt", "Export progress")) {
                            Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            LinearProgressIndicator(
                                progress = { exportProgress?.coerceIn(0f, 1f) ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                when {
                                    localBusy -> tr("Der aktuelle Teilschritt steht oben. Der Export läuft im Foreground-Service weiter, auch wenn du die App-Oberfläche schließt.", "The current sub-step is shown above. Export continues in the foreground service even if you close the app UI.")
                                    exportFailed -> tr("Export unterbrochen oder fehlgeschlagen.", "Export interrupted or failed.")
                                    exportProgress != null && exportProgress!! >= 1f -> tr("Export erfolgreich beendet.", "Export completed successfully.")
                                    else -> tr("Export unterbrochen.", "Export interrupted.")
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                if (visibleBooks.isNotEmpty()) {
                    AlphabetRail(
                        books = visibleBooks,
                        modifier = Modifier.align(Alignment.CenterEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(end = 4.dp),
                        onBookIndex = { bookIndex -> scope.launch { listState.animateScrollToItem(3 + bookIndex) } },
                        onTop = { scope.launch { listState.animateScrollToItem(0) } },
                        onBottom = { scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                    )
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
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
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
private fun ReadestTarget(
    mode: ExportMode,
    setMode: (ExportMode) -> Unit,
    normalizeNames: Boolean,
    setNormalizeNames: (Boolean) -> Unit,
    crypticCount: Int,
    enabled: Boolean,
    onExport: () -> Unit,
) {
    Text(tr("Exportart", "Export type"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    SelectionRow(mode == ExportMode.MARKINGS_ONLY, tr("Nur Markierungen (.mrexpt)", "Highlights only (.mrexpt)")) { setMode(ExportMode.MARKINGS_ONLY) }
    SelectionRow(mode == ExportMode.FULL, tr("Direkt in Readest-Bibliothek", "Directly into Readest library")) { setMode(ExportMode.FULL) }
    if (mode == ExportMode.FULL) {
        Text(tr("Schreibt EPUB, PDF, MOBI/AZW/AZW3, FB2, CBZ/ZIP, TXT und Markdown direkt nach Readest/Books. Native EPUB-Markierungen werden nur bei sicher auflösbarer CFI geschrieben; andere Markierungen bleiben verlustfrei als .mrexpt erhalten.", "Writes EPUB, PDF, MOBI/AZW/AZW3, FB2, CBZ/ZIP, TXT and Markdown directly into Readest/Books. Native EPUB highlights are written only when a reliable CFI can be resolved; other highlights remain losslessly preserved as .mrexpt."), style = MaterialTheme.typography.labelSmall)
        if (crypticCount > 0) {
            Text(tr("$crypticCount ausgewählte Moon+-Bücher haben kryptische Datei-/Hashnamen. Titel, Autor und ISBN werden soweit möglich aus EPUB, Moon+-Datenbank und Backup-Metadaten rekonstruiert.", "$crypticCount selected Moon+ books have cryptic file/hash names. Title, author and ISBN are reconstructed where possible from the EPUB, Moon+ database and backup metadata."), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { setNormalizeNames(!normalizeNames) }.padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = normalizeNames, onCheckedChange = setNormalizeNames)
                Column(Modifier.weight(1f)) {
                    Text(tr("Rekonstruierte Dateinamen verwenden", "Use reconstructed filenames"), fontWeight = FontWeight.Medium)
                    Text(tr("Beispiel: 7f3a9c….epub → Ermittelter Buchtitel.epub. Die Moon+-Originaldatei wird nicht umbenannt.", "Example: 7f3a9c….epub → Recovered Book Title.epub. The original Moon+ file is not renamed."), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    } else {
        Text(tr("Readest kann Moon+-Markierungen aus .mrexpt importieren. Dieser Modus verändert keine Readest-Bibliotheksdateien.", "Readest can import Moon+ highlights from .mrexpt. This mode does not modify Readest library files."), style = MaterialTheme.typography.labelSmall)
    }
    Button(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(tr("Ausgewählte Bücher exportieren", "Export selected books")) }
}

@Composable
private fun ServerTarget(
    target: TransferTarget, url: String, setUrl: (String) -> Unit, user: String, setUser: (String) -> Unit,
    password: String, setPassword: (String) -> Unit, busy: Boolean, canSend: Boolean, feedback: String?, onTest: () -> Unit, onSend: () -> Unit,
) {
    val hint = when (target) {
        TransferTarget.CWA -> tr("CWA-Serveradresse. /kosync wird automatisch ergänzt.", "CWA server address. /kosync is added automatically.")
        TransferTarget.BOOKLORE -> tr("BookLore-Serveradresse. /api/koreader wird automatisch ergänzt.", "BookLore server address. /api/koreader is added automatically.")
        else -> tr("Adresse des KOSync-kompatiblen Servers.", "Address of the KOSync-compatible server.")
    }
    Text(tr("HTTPS wird empfohlen. HTTP ist nur für lokale Heimnetz-Adressen erlaubt.", "HTTPS is recommended. HTTP is only allowed for local-network addresses."), style = MaterialTheme.typography.bodySmall)
    Text(hint, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(url, setUrl, label = { Text(tr("Server-URL (HTTPS oder lokales HTTP)", "Server URL (HTTPS or local HTTP)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedTextField(user, setUser, label = { Text(tr("Benutzername", "Username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, setPassword, label = { Text(tr("Passwort", "Password")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onTest, enabled = !busy && url.isNotBlank() && user.isNotBlank(), modifier = Modifier.weight(1f)) { Text(tr("Verbindung testen", "Test connection")) }
        Button(onClick = onSend, enabled = !busy && canSend && url.isNotBlank() && user.isNotBlank(), modifier = Modifier.weight(1f)) { Text(tr("Fortschritt senden", "Send progress")) }
    }
    feedback?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun BookCard(book: BookItem, checked: Boolean, onChecked: (Boolean) -> Unit, onChooseBook: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onChecked(!checked) }, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Checkbox(checked = checked, onCheckedChange = onChecked, modifier = Modifier.size(38.dp))
            Cover(book.epub?.cover, book.extension)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (book.isCryptic || ProgressRecovery.looksOpaque(book.epub?.fileName ?: book.sourceFile)) {
                    Text(tr("Kryptischer Moon+-Dateiname erkannt. Rekonstruierter Titel: ${book.title}", "Cryptic Moon+ filename detected. Recovered title: ${book.title}"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                }
                val percent = book.position?.percent
                val progressText = percent?.let { "%.1f%%".format(Locale.ROOT, it) } ?: tr("kein Fortschritt", "no progress")
                Text(tr("Lesefortschritt: $progressText", "Reading progress: $progressText"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (percent != null) LinearProgressIndicator(progress = { (percent / 100.0).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(tr("Berechnungsart: ${positionMethod(book.position)}", "Calculation: ${positionMethod(book.position)}"), style = MaterialTheme.typography.bodySmall)
                Text(tr("Markierungen: ${book.annotation?.count ?: 0}", "Highlights: ${book.annotation?.count ?: 0}"), style = MaterialTheme.typography.bodySmall)
                val identity = book.epub?.partialMd5?.takeIf { it.isNotBlank() }?.let { "partialMD5 ${it.take(10)}…" } ?: tr("noch keine sichere KOSync-ID", "no reliable KOSync ID yet")
                Text(tr("Buch-ID: $identity", "Book ID: $identity"), style = MaterialTheme.typography.bodySmall)
                if (book.position != null && book.epub?.partialMd5.isNullOrBlank()) {
                    OutlinedButton(onClick = onChooseBook, modifier = Modifier.fillMaxWidth()) { Text(tr("Passende Buchdatei auswählen", "Choose matching book file")) }
                    Text(tr("Der gefundene Moon+-Fortschritt bleibt erhalten. Die Buchdatei wird nur für Titel, Cover und Zielposition/ID verwendet.", "The recovered Moon+ progress is preserved. The book file is only used for title, cover and target position/ID."), style = MaterialTheme.typography.labelSmall)
                }
                if (book.position?.raw?.isNotBlank() == true) Text(tr("Moon+-Rohposition erhalten", "Moon+ raw position preserved"), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Cover(bitmap: Bitmap?, extension: String) {
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = tr("Buchcover", "Book cover"), contentScale = ContentScale.Crop, modifier = Modifier.size(width = 56.dp, height = 82.dp).clip(RoundedCornerShape(8.dp)))
    else Box(Modifier.size(width = 56.dp, height = 82.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text(extension.uppercase(Locale.ROOT).take(4).ifBlank { "BOOK" }, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun positionMethod(position: MoonPosition?): String = when {
    position == null -> tr("nicht vorhanden", "not available")
    position.chapterOrPage != null && position.section != null && position.offset != null -> tr("Kapitel + Abschnitt + Zeichenposition", "chapter + section + character offset")
    position.chapterOrPage != null && position.offset == null -> tr("Seite/Kapitel + Prozent", "page/chapter + percentage")
    position.percent != null -> tr("Prozent-Fallback", "percentage fallback")
    else -> tr("Rohwert erhalten, noch nicht aufgelöst", "raw value preserved, unresolved")
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
    BookFilter.WITH_BOOK -> tr("Mit Buchdatei", "With book file")
    BookFilter.WITHOUT_BOOK -> tr("Ohne Buchdatei", "Without book file")
}

@Composable
private fun targetLabel(value: TransferTarget): String = when (value) {
    TransferTarget.READEST -> tr("Readest Datei-Export", "Readest file export")
    TransferTarget.KOSYNC -> tr("KOSync-kompatibler Server", "KOSync-compatible server")
    TransferTarget.CWA -> "Calibre-Web Automated"
    TransferTarget.BOOKLORE -> "BookLore"
}

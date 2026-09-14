package de.moonexporter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AnalysisSnapshot(
    val sessionId: Long = 0L,
    val revision: Int = 0,
    val running: Boolean = false,
    val status: String = tr("Noch keine Analyse gestartet.", "No analysis started yet."),
    val books: List<BookItem> = emptyList(),
    val error: String? = null,
)

internal object AnalysisState {
    private val mutable = MutableStateFlow(AnalysisSnapshot())
    val state: StateFlow<AnalysisSnapshot> = mutable.asStateFlow()

    fun start(sessionId: Long) {
        mutable.value = AnalysisSnapshot(sessionId, mutable.value.revision + 1, true, tr("Backup wird analysiert…", "Analyzing backup…"))
    }

    fun status(text: String) { mutable.value = mutable.value.copy(status = text, running = true) }
    fun books(items: List<BookItem>, text: String) { mutable.value = mutable.value.copy(books = items, revision = mutable.value.revision + 1, status = text, running = true, error = null) }
    fun complete(items: List<BookItem>, text: String) { mutable.value = mutable.value.copy(books = items, revision = mutable.value.revision + 1, status = text, running = false, error = null) }
    fun failed(text: String) { mutable.value = mutable.value.copy(status = text, running = false, error = text) }
    fun cancelled() { mutable.value = mutable.value.copy(status = tr("Analyse abgebrochen", "Analysis cancelled"), running = false) }
}

class AnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analysisJob: Job? = null
    private lateinit var store: TransferStore

    override fun onCreate() {
        super.onCreate()
        store = TransferStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { analysisJob?.cancel(); return START_NOT_STICKY }
            ACTION_START -> {
                val multi = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty().map(Uri::parse)
                val single = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                val uris = if (multi.isNotEmpty()) multi else listOfNotNull(single)
                if (uris.isNotEmpty()) startAnalysis(uris)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startAnalysis(inputUris: List<Uri>) {
        analysisJob?.cancel()
        val sessionId = System.currentTimeMillis()
        AnalysisState.start(sessionId)
        startForeground(NOTIFICATION_ID, notification(tr("Backup wird geprüft…", "Checking backup…"), true))
        analysisJob = scope.launch {
            try {
                if (inputUris.size == 1) {
                    val uri = inputUris.single()
                    val cached = store.loadAnalysis(this@AnalysisService, uri)
                    if (!cached.isNullOrEmpty()) {
                        val text = tr("${cached.size} Bücher aus unverändertem letzten Einlesen wiederhergestellt.", "${cached.size} books restored from the unchanged previous scan.")
                        AnalysisState.complete(cached, text)
                        notify(text, false)
                        return@launch
                    }
                }

                val ordered = inputUris.map { uri -> Triple(uri, displayName(uri), BackupConsolidator.describe(displayName(uri))) }
                    .sortedWith(compareBy<Triple<Uri, String, MoonBackupDescriptor>> { it.third.date ?: java.time.LocalDate.MIN }.thenBy { it.second })
                val scans = mutableListOf<List<BookItem>>()
                ordered.forEachIndexed { index, (uri, name, _) ->
                    update(tr("Backup ${index + 1}/${ordered.size} wird analysiert: $name", "Analyzing backup ${index + 1}/${ordered.size}: $name"))
                    val scanned = MoonImporter.scanMrpro(this@AnalysisService, uri) { update(it) }
                    val withAllFormats = EmbeddedBookRecovery.attach(this@AnalysisService, uri, scanned) { update(it) }
                    val recovered = ProgressRecovery.recover(this@AnalysisService, uri, withAllFormats) { update(it) }
                    scans += ProgressRecovery.reconstructTitles(recovered)
                }

                val consolidated = if (scans.size == 1) scans.single() else BackupConsolidator.consolidate(scans)
                val mergeText = if (ordered.size > 1) {
                    tr("${ordered.size} Backups zusammengeführt, ${consolidated.size} Bücher konsolidiert. Cover und Metadaten werden ergänzt…", "Merged ${ordered.size} backups into ${consolidated.size} consolidated books. Loading covers and metadata…")
                } else {
                    tr("${consolidated.size} Bücher gefunden. Cover und Metadaten werden ergänzt…", "${consolidated.size} books found. Loading covers and metadata…")
                }
                AnalysisState.books(consolidated, mergeText)
                update(mergeText)
                val enriched = BackupMetadata.enrich(this@AnalysisService, consolidated) { update(it) }
                val finalBooks = ProgressRecovery.reconstructTitles(enriched)
                if (inputUris.size == 1) store.saveAnalysis(this@AnalysisService, inputUris.single(), finalBooks)
                val complete = if (ordered.size > 1) {
                    tr("${finalBooks.size} Bücher aus ${ordered.size} Moon+-Backups konsolidiert.", "${finalBooks.size} books consolidated from ${ordered.size} Moon+ backups.")
                } else {
                    tr("${finalBooks.size} Bücher analysiert und lokal zwischengespeichert.", "${finalBooks.size} books analyzed and cached locally.")
                }
                AnalysisState.complete(finalBooks, complete)
                notify(complete, false)
            } catch (_: CancellationException) {
                AnalysisState.cancelled(); notify(AnalysisState.state.value.status, false)
            } catch (t: Throwable) {
                val message = StorageSafety.userMessage(t) ?: t.message ?: tr("Analyse fehlgeschlagen", "Analysis failed")
                AnalysisState.failed(message); notify(message, false)
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "backup.mrpro"

    private fun update(text: String) { AnalysisState.status(text); notify(text, true) }
    private fun notify(text: String, running: Boolean) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, running)) }

    private fun notification(text: String, running: Boolean): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Moon+ Backup-Analyse", "Moon+ backup analysis"))
            .setContentText(text).setContentIntent(pending).setOnlyAlertOnce(true).setOngoing(running)
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PRIVATE)
        if (running) builder.setProgress(100, 0, true) else builder.setProgress(0, 0, false)
        return builder.build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Backup-Analyse", "Backup analysis"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("Fortschritt der lokalen Moon+ Backup-Analyse", "Progress of local Moon+ backup analysis"); setShowBadge(false)
        })
    }

    override fun onDestroy() {
        analysisJob?.cancel(); scope.cancel(); store.close(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "de.moonexporter.app.action.ANALYZE"
        const val ACTION_CANCEL = "de.moonexporter.app.action.CANCEL_ANALYSIS"
        const val EXTRA_URI = "backup_uri"
        const val EXTRA_URIS = "backup_uris"
        private const val CHANNEL_ID = "backup_analysis"
        private const val NOTIFICATION_ID = 4101
    }
}

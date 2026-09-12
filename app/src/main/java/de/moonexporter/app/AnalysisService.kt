package de.moonexporter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
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
        mutable.value = AnalysisSnapshot(
            sessionId = sessionId,
            revision = mutable.value.revision + 1,
            running = true,
            status = tr("Backup wird analysiert…", "Analyzing backup…"),
        )
    }

    fun status(text: String) {
        mutable.value = mutable.value.copy(status = text, running = true)
    }

    fun books(items: List<BookItem>, text: String) {
        mutable.value = mutable.value.copy(
            books = items,
            revision = mutable.value.revision + 1,
            status = text,
            running = true,
            error = null,
        )
    }

    fun complete(items: List<BookItem>, text: String) {
        mutable.value = mutable.value.copy(
            books = items,
            revision = mutable.value.revision + 1,
            status = text,
            running = false,
            error = null,
        )
    }

    fun failed(text: String) {
        mutable.value = mutable.value.copy(status = text, running = false, error = text)
    }

    fun cancelled() {
        mutable.value = mutable.value.copy(status = tr("Analyse abgebrochen", "Analysis cancelled"), running = false)
    }
}

class AnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analysisJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                analysisJob?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val raw = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                startAnalysis(Uri.parse(raw))
            }
        }
        return START_NOT_STICKY
    }

    private fun startAnalysis(uri: Uri) {
        analysisJob?.cancel()
        val sessionId = System.currentTimeMillis()
        AnalysisState.start(sessionId)
        startForeground(NOTIFICATION_ID, notification(tr("Backup wird analysiert…", "Analyzing backup…"), true))
        analysisJob = scope.launch {
            try {
                val scanned = MoonImporter.scanMrpro(this@AnalysisService, uri) { update(it) }
                val recovered = ProgressRecovery.recover(this@AnalysisService, uri, scanned) { update(it) }
                val withTitles = ProgressRecovery.reconstructTitles(recovered)
                AnalysisState.books(
                    withTitles,
                    tr("${withTitles.size} Bücher gefunden. Cover und Metadaten werden ergänzt…", "${withTitles.size} books found. Loading covers and metadata…"),
                )
                update(AnalysisState.state.value.status)
                val enriched = BackupMetadata.enrich(this@AnalysisService, withTitles) { update(it) }
                val finalBooks = ProgressRecovery.reconstructTitles(enriched)
                val complete = tr("${finalBooks.size} Bücher analysiert.", "${finalBooks.size} books analyzed.")
                AnalysisState.complete(finalBooks, complete)
                notify(complete, false)
            } catch (_: CancellationException) {
                AnalysisState.cancelled()
                notify(AnalysisState.state.value.status, false)
            } catch (t: Throwable) {
                val message = t.message ?: tr("Analyse fehlgeschlagen", "Analysis failed")
                AnalysisState.failed(message)
                notify(message, false)
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun update(text: String) {
        AnalysisState.status(text)
        notify(text, true)
    }

    private fun notify(text: String, running: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, running))
    }

    private fun notification(text: String, running: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Moon+ Backup-Analyse", "Moon+ backup analysis"))
            .setContentText(text)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (running) builder.setProgress(100, 0, true) else builder.setProgress(0, 0, false)
        return builder.build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            tr("Backup-Analyse", "Backup analysis"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = tr("Fortschritt der lokalen Moon+ Backup-Analyse", "Progress of local Moon+ backup analysis")
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        analysisJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "de.moonexporter.app.action.ANALYZE"
        const val ACTION_CANCEL = "de.moonexporter.app.action.CANCEL_ANALYSIS"
        const val EXTRA_URI = "backup_uri"
        private const val CHANNEL_ID = "backup_analysis"
        private const val NOTIFICATION_ID = 4101
    }
}

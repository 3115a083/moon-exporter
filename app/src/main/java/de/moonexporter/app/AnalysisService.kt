package de.moonexporter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analysisJob: Job? = null
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("Backup-Analyse", "Backup analysis"), NotificationManager.IMPORTANCE_LOW).apply {
                    description = tr("Zeigt den Fortschritt der Moon+ Backup-Analyse", "Shows Moon+ backup analysis progress")
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                analysisJob?.cancel()
                return START_NOT_STICKY
            }
            ACTION_ANALYZE -> {
                if (analysisJob?.isActive == true) return START_NOT_STICKY
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return START_NOT_STICKY
                AnalysisStore.start()
                startForeground(NOTIFICATION_ID, notification(0, tr("Backup wird vorbereitet…", "Preparing backup…"), true))
                analysisJob = scope.launch { analyze(uri) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun analyze(uri: Uri) {
        try {
            val totalEntries = MoonImporter.countMrproEntries(this, uri).coerceAtLeast(1)
            val books = MoonImporter.scanMrpro(
                context = this,
                uri = uri,
                onProgress = { message -> update(5, message) },
                onEntryProgress = { done ->
                    val percent = (5 + (done.toDouble() / totalEntries.toDouble() * 65.0)).toInt().coerceIn(5, 70)
                    update(percent, tr("Backup wird analysiert: $done/$totalEntries", "Analyzing backup: $done/$totalEntries"))
                },
            )
            val reconstructed = MoonImporter.reconstructTitles(books)
            AnalysisStore.publishBooks(
                reconstructed,
                72,
                tr("${reconstructed.size} Bücher gefunden. Cover und Metadaten werden ergänzt…", "${reconstructed.size} books found. Loading covers and metadata…"),
            )
            update(72, tr("Bücher gefunden. Cover werden geladen…", "Books found. Loading covers…"))
            val enriched = BackupMetadata.enrich(this, reconstructed) { done, total, message ->
                val percent = if (total <= 0) 95 else (72 + (done.toDouble() / total.toDouble() * 26.0)).toInt().coerceIn(72, 98)
                update(percent, message)
            }
            val finalBooks = MoonImporter.reconstructTitles(enriched)
            val withProgress = finalBooks.count { it.position?.percent != null }
            val doneText = tr(
                "${finalBooks.size} Bücher analysiert, $withProgress mit Lesefortschritt.",
                "${finalBooks.size} books analyzed, $withProgress with reading progress.",
            )
            AnalysisStore.complete(finalBooks, doneText)
            notifications.notify(NOTIFICATION_ID, notification(100, doneText, false))
        } catch (_: CancellationException) {
            AnalysisStore.cancel()
            notifications.notify(NOTIFICATION_ID, notification(0, tr("Analyse abgebrochen", "Analysis cancelled"), false))
        } catch (t: Throwable) {
            val message = t.message ?: tr("Analyse fehlgeschlagen", "Analysis failed")
            AnalysisStore.fail(message)
            notifications.notify(NOTIFICATION_ID, notification(0, message, false))
        } finally {
            stopForeground(false)
            stopSelf()
        }
    }

    private fun update(percent: Int, text: String) {
        AnalysisStore.progress(percent, text)
        notifications.notify(NOTIFICATION_ID, notification(percent, text, true))
    }

    private fun notification(progress: Int, text: String, running: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        builder
            .setSmallIcon(if (running) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle("Moon Exporter")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setProgress(100, progress.coerceIn(0, 100), false)
        if (running) {
            val cancelIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, AnalysisService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, tr("Abbrechen", "Cancel"), cancelIntent).build())
        }
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ANALYZE = "de.moonexporter.app.action.ANALYZE"
        const val ACTION_CANCEL = "de.moonexporter.app.action.CANCEL_ANALYSIS"
        const val EXTRA_URI = "backup_uri"
        private const val CHANNEL_ID = "backup_analysis"
        private const val NOTIFICATION_ID = 1105
    }
}

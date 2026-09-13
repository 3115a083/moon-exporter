package de.moonexporter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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

internal data class ExportSnapshot(
    val running: Boolean = false,
    val sessionId: Long = 0L,
    val status: String = tr("Noch kein Export gestartet.", "No export started yet."),
    val fraction: Float? = null,
    val completedBooks: Int = 0,
    val totalBooks: Int = 0,
    val error: String? = null,
)

internal object ExportState {
    private val mutable = MutableStateFlow(ExportSnapshot())
    val state: StateFlow<ExportSnapshot> = mutable.asStateFlow()
    fun update(value: ExportSnapshot) { mutable.value = value }
}

class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exportJob: Job? = null
    private lateinit var store: TransferStore

    override fun onCreate() {
        super.onCreate()
        store = TransferStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                exportJob?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
                if (id > 0) startSession(id)
            }
            ACTION_RESUME -> store.latestUnfinishedSession()?.let { startSession(it.id) }
            null -> store.latestUnfinishedSession()?.let { startSession(it.id) }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSession(sessionId: Long) {
        if (exportJob?.isActive == true && ExportState.state.value.sessionId == sessionId) return
        exportJob?.cancel()
        val session = store.session(sessionId) ?: return
        val items = store.items(sessionId)
        if (items.isEmpty()) return
        store.setSessionStatus(sessionId, "RUNNING")
        startForeground(NOTIFICATION_ID, notification(tr("Readest-Export wird vorbereitet…", "Preparing Readest export…"), 0, items.size, true))
        exportJob = scope.launch {
            var done = 0
            ExportState.update(ExportSnapshot(true, sessionId, tr("Readest-Ziel wird geprüft…", "Checking Readest target…"), 0f, 0, items.size))
            try {
                ReadestTargetAudit.cleanupTargetParts(this@ExportService, session.targetUri)
                for (item in items) {
                    val currentDoneHash = store.completedHash(session.targetUri, item) ?: item.targetHash
                    if (!currentDoneHash.isNullOrBlank()) {
                        val validation = ReadestTargetAudit.validateKnownBook(this@ExportService, session.targetUri, currentDoneHash, item.book.epub?.size)
                        if (validation.complete) {
                            store.setItemState(item.id, "DONE", currentDoneHash)
                            done++
                            publish(sessionId, done, items.size, tr("Bereits vollständig: ${item.book.title}", "Already complete: ${item.book.title}"))
                            continue
                        }
                    }

                    store.setItemState(item.id, "RUNNING", currentDoneHash)
                    val before = ReadestTargetAudit.snapshot(this@ExportService, session.targetUri)
                    publish(sessionId, done, items.size, tr("Prüfe/Repariere ${item.book.title}", "Checking/repairing ${item.book.title}"))
                    try {
                        ReadestDirectExporter.export(this@ExportService, session.targetUri, listOf(item.book), session.normalizeNames) { p ->
                            val base = done.toFloat() / items.size
                            val local = p.fraction ?: 0f
                            ExportState.update(ExportSnapshot(true, sessionId, p.message, (base + local / items.size).coerceIn(0f, 1f), done, items.size))
                            notifyProgress(p.message, done, items.size)
                        }
                        val targetHash = ReadestTargetAudit.discoverSingleNewHash(before, this@ExportService, session.targetUri)
                            ?: ReadestTargetAudit.findLikelyHash(this@ExportService, session.targetUri, item.book)
                        val validation = targetHash?.let { ReadestTargetAudit.validateKnownBook(this@ExportService, session.targetUri, it, item.book.epub?.size) }
                        if (targetHash == null || validation?.complete != true) {
                            error(validation?.reason ?: tr("Readest-Ziel konnte nach dem Export nicht eindeutig validiert werden", "Readest target could not be validated after export"))
                        }
                        store.setItemState(item.id, "DONE", targetHash)
                        store.rememberCompleted(session.targetUri, item, targetHash)
                        done++
                        publish(sessionId, done, items.size, tr("Gesichert: ${item.book.title}", "Committed: ${item.book.title}"))
                    } catch (t: Throwable) {
                        val candidate = ReadestTargetAudit.discoverSingleNewHash(before, this@ExportService, session.targetUri)
                            ?: ReadestTargetAudit.findLikelyHash(this@ExportService, session.targetUri, item.book)
                        store.setItemState(item.id, "INTERRUPTED", candidate, t.message)
                        throw t
                    }
                }
                ReadestTargetAudit.cleanupRecoveryArtifacts(this@ExportService, session.targetUri)
                store.setSessionStatus(sessionId, "DONE")
                val text = tr("Readest-Export abgeschlossen und geprüft.", "Readest export completed and verified.")
                ExportState.update(ExportSnapshot(false, sessionId, text, 1f, items.size, items.size))
                notifyProgress(text, items.size, items.size, false)
            } catch (_: CancellationException) {
                store.setSessionStatus(sessionId, "INTERRUPTED")
                val text = tr("Export unterbrochen. Er wird beim nächsten Start fortgesetzt.", "Export interrupted. It will resume on the next start.")
                ExportState.update(ExportSnapshot(false, sessionId, text, done.toFloat() / items.size, done, items.size))
                notifyProgress(text, done, items.size, false)
            } catch (t: Throwable) {
                store.setSessionStatus(sessionId, "INTERRUPTED")
                val text = t.message ?: tr("Export unterbrochen", "Export interrupted")
                ExportState.update(ExportSnapshot(false, sessionId, text, done.toFloat() / items.size, done, items.size, text))
                notifyProgress(text, done, items.size, false)
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun publish(sessionId: Long, done: Int, total: Int, text: String) {
        val fraction = if (total == 0) 1f else done.toFloat() / total
        ExportState.update(ExportSnapshot(true, sessionId, text, fraction, done, total))
        notifyProgress(text, done, total)
    }

    private fun notifyProgress(text: String, done: Int, total: Int, running: Boolean = true) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, done, total, running))
    }

    private fun notification(text: String, done: Int, total: Int, running: Boolean): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Readest-Export", "Readest export"))
            .setContentText(text)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (running && total > 0) builder.setProgress(total, done.coerceIn(0, total), false) else builder.setProgress(0, 0, false)
        return builder.build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, tr("Readest-Export", "Readest export"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("Zuverlässiger Readest-Export im Hintergrund", "Reliable Readest export in the background")
            setShowBadge(false)
        })
    }

    override fun onDestroy() {
        exportJob?.cancel()
        scope.cancel()
        store.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "de.moonexporter.app.action.START_EXPORT"
        const val ACTION_RESUME = "de.moonexporter.app.action.RESUME_EXPORT"
        const val ACTION_CANCEL = "de.moonexporter.app.action.CANCEL_EXPORT"
        const val EXTRA_SESSION_ID = "session_id"
        private const val CHANNEL_ID = "readest_export"
        private const val NOTIFICATION_ID = 4102
    }
}

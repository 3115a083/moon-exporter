package de.moonexporter.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

class MoonExporterApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var resumeChecked = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        val store = TransferStore(this)
        try {
            val pending = store.latestUnfinishedSession()
            val pendingItems = pending?.let { store.items(it.id) }.orEmpty()
            if (pending != null && pendingItems.isNotEmpty()) {
                val processed = pendingItems.count { it.state == "DONE" || it.state.startsWith("SKIPPED") }
                val exported = pendingItems.count { it.state == "DONE" }
                val total = pendingItems.size
                val fraction = processed.toFloat() / total.toFloat()
                val text = when (pending.status) {
                    "INTERRUPTED" -> tr(
                        "Unterbrochener Readest-Export wiederhergestellt: $processed/$total verarbeitet.",
                        "Restored interrupted Readest export: $processed/$total processed.",
                    )
                    else -> tr(
                        "Laufender Readest-Export wiederhergestellt: $processed/$total verarbeitet.",
                        "Restored running Readest export: $processed/$total processed.",
                    )
                }
                AnalysisState.complete(pendingItems.map { it.book }, text)
                ExportState.update(
                    ExportSnapshot(
                        running = pending.status != "INTERRUPTED",
                        sessionId = pending.id,
                        status = text,
                        fraction = fraction,
                        completedBooks = exported,
                        totalBooks = total,
                        error = pendingItems.firstNotNullOfOrNull { it.error },
                    ),
                )
            } else {
                store.loadLatestAnalysis(this)?.takeIf { it.isNotEmpty() }?.let { books ->
                    AnalysisState.complete(books, tr("Letztes unverändertes Einlesen wiederhergestellt.", "Restored the last unchanged scan."))
                }
            }
        } finally { store.close() }
    }

    override fun onActivityResumed(activity: Activity) {
        if (resumeChecked) return
        resumeChecked = true
        val store = TransferStore(this)
        try {
            val pending = store.latestUnfinishedSession()
            val sourceReady = pending?.let { session ->
                if (session.mode == ExportMode.MARKINGS_ONLY) listOf(true)
                else store.items(session.id).map { it.book.hasBookFile }
            }.orEmpty()
            if (pending != null && ManualSessionPolicy.canAutoResume(sourceReady)) {
                runCatching {
                    ContextCompat.startForegroundService(this, Intent(this, ExportService::class.java).setAction(ExportService.ACTION_RESUME))
                }
            }
        } finally { store.close() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

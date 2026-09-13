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
            store.loadLatestAnalysis(this)?.takeIf { it.isNotEmpty() }?.let { books ->
                AnalysisState.complete(books, tr("Letztes unverändertes Einlesen wiederhergestellt.", "Restored the last unchanged scan."))
            }
        } finally { store.close() }
    }

    override fun onActivityResumed(activity: Activity) {
        if (resumeChecked) return
        resumeChecked = true
        val store = TransferStore(this)
        try {
            if (store.latestUnfinishedSession() != null) {
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

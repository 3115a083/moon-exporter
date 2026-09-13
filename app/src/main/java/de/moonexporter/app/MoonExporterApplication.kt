package de.moonexporter.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat

class MoonExporterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = TransferStore(this)
        try {
            store.loadLatestAnalysis(this)?.takeIf { it.isNotEmpty() }?.let { books ->
                AnalysisState.complete(books, tr("Letztes unverändertes Einlesen wiederhergestellt.", "Restored the last unchanged scan."))
            }
            if (store.latestUnfinishedSession() != null) {
                runCatching {
                    ContextCompat.startForegroundService(this, Intent(this, ExportService::class.java).setAction(ExportService.ACTION_RESUME))
                }
            }
        } finally { store.close() }
    }
}

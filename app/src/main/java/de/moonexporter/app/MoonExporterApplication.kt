package de.moonexporter.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat

class MoonExporterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val pending = TransferStore(this).useStore { it.latestUnfinishedSession() }
        if (pending != null) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, ExportService::class.java).setAction(ExportService.ACTION_RESUME))
            }
        }
    }

    private inline fun <T> TransferStore.useStore(block: (TransferStore) -> T): T = try { block(this) } finally { close() }
}

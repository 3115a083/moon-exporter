package de.moonexporter.app

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.IOException

internal object StorageSafety {
    private const val MIN_FREE_RESERVE = 64L * 1024 * 1024

    /**
     * Readest export may need one complete temporary source copy in app cache.
     * Keep a reserve so Android and SQLite still have room to complete/rollback bookkeeping.
     */
    fun ensureCacheCapacity(context: Context, sourceBytes: Long?) {
        val expected = sourceBytes?.coerceAtLeast(0L) ?: 0L
        val required = expected + MIN_FREE_RESERVE
        val available = context.cacheDir.usableSpace
        if (available in 1 until required) {
            throw IOException(tr(
                "Zu wenig freier Gerätespeicher für den Export. Benötigt werden mindestens ${humanBytes(required)}, verfügbar sind ${humanBytes(available)}.",
                "Not enough free device storage for export. At least ${humanBytes(required)} is required, ${humanBytes(available)} is available.",
            ))
        }
    }

    fun userMessage(t: Throwable): String? {
        val chain = generateSequence(t as Throwable?) { it.cause }.toList()
        val noSpace = chain.any { cause ->
            (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) ||
                cause.message?.contains("ENOSPC", ignoreCase = true) == true ||
                cause.message?.contains("No space left", ignoreCase = true) == true
        }
        return if (noSpace) tr(
            "Gerätespeicher oder Zielmedium ist voll. Der Export wurde sicher unterbrochen und kann nach dem Freigeben von Speicher fortgesetzt werden.",
            "Device storage or the destination is full. Export was safely interrupted and can resume after space is freed.",
        ) else null
    }

    internal fun humanBytes(bytes: Long): String {
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mib < 1024.0) "%.0f MiB".format(mib) else "%.2f GiB".format(mib / 1024.0)
    }
}

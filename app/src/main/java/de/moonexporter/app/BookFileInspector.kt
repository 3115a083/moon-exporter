package de.moonexporter.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object BookFileInspector {
    suspend fun inspect(context: Context, uri: Uri): EpubMatch? = withContext(Dispatchers.IO) {
        val name = displayName(context, uri) ?: return@withContext null
        if (name.endsWith(".epub", true)) return@withContext MoonImporter.inspectSelectedEpub(context, uri)
        val hash = context.contentResolver.openInputStream(uri)?.use { partialMd5(it.buffered()) } ?: return@withContext null
        val inferred = name.substringBeforeLast('.', name).takeUnless { ProgressRecovery.looksOpaque(it) }
        EpubMatch(
            uri = uri,
            fileName = name,
            title = inferred,
            partialMd5 = hash,
            size = querySize(context, uri),
        )
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun querySize(context: Context, uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return null
    }
}

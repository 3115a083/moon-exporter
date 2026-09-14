package de.moonexporter.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class StoredTransferItem(
    val id: Long,
    val sessionId: Long,
    val ordinal: Int,
    val book: BookItem,
    val fingerprint: String,
    val state: String,
    val targetHash: String?,
    val error: String?,
)

internal data class StoredTransferSession(
    val id: Long,
    val targetUri: Uri,
    val normalizeNames: Boolean,
    val status: String,
    val mode: ExportMode,
)

/** App-private checkpoint DB. Android removes it automatically when the app is uninstalled. */
internal class TransferStore(context: Context) : SQLiteOpenHelper(context, "moon_exporter_state.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE transfer_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, target_uri TEXT NOT NULL, normalize_names INTEGER NOT NULL, mode TEXT NOT NULL DEFAULT 'FULL', status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE transfer_items (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, ordinal INTEGER NOT NULL, book_key TEXT NOT NULL, fingerprint TEXT NOT NULL, payload_json TEXT NOT NULL, state TEXT NOT NULL, target_hash TEXT, error TEXT, updated_at INTEGER NOT NULL, UNIQUE(session_id, book_key))")
        db.execSQL("CREATE INDEX idx_transfer_items_session ON transfer_items(session_id, ordinal)")
        db.execSQL("CREATE TABLE completed_books (target_uri TEXT NOT NULL, book_key TEXT NOT NULL, fingerprint TEXT NOT NULL, target_hash TEXT, completed_at INTEGER NOT NULL, PRIMARY KEY(target_uri, book_key))")
        db.execSQL("CREATE TABLE analysis_cache (slot INTEGER PRIMARY KEY CHECK(slot=1), source_uri TEXT NOT NULL, source_fingerprint TEXT NOT NULL, books_json TEXT NOT NULL, updated_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE transfer_sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'FULL'")
    }

    fun createSession(targetUri: Uri, normalizeNames: Boolean, books: List<BookItem>, mode: ExportMode = ExportMode.FULL): Long {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("target_uri", targetUri.toString())
                put("normalize_names", if (normalizeNames) 1 else 0)
                put("mode", mode.name)
                put("status", "PENDING")
                put("created_at", now)
                put("updated_at", now)
            }
            val sessionId = db.insertOrThrow("transfer_sessions", null, values)
            books.forEachIndexed { index, book ->
                val json = bookToJson(book).toString()
                db.insertOrThrow("transfer_items", null, ContentValues().apply {
                    put("session_id", sessionId)
                    put("ordinal", index)
                    put("book_key", book.key)
                    put("fingerprint", sha256(json))
                    put("payload_json", json)
                    put("state", "PENDING")
                    put("updated_at", now)
                })
            }
            db.setTransactionSuccessful()
            return sessionId
        } finally { db.endTransaction() }
    }

    fun latestUnfinishedSession(): StoredTransferSession? {
        readableDatabase.rawQuery("SELECT id,target_uri,normalize_names,status,mode FROM transfer_sessions WHERE status IN ('PENDING','RUNNING','INTERRUPTED') ORDER BY id DESC LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) return null
            return sessionFromCursor(c)
        }
    }

    fun session(id: Long): StoredTransferSession? {
        readableDatabase.rawQuery("SELECT id,target_uri,normalize_names,status,mode FROM transfer_sessions WHERE id=?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return sessionFromCursor(c)
        }
    }

    private fun sessionFromCursor(c: android.database.Cursor): StoredTransferSession = StoredTransferSession(
        id = c.getLong(0),
        targetUri = Uri.parse(c.getString(1)),
        normalizeNames = c.getInt(2) != 0,
        status = c.getString(3),
        mode = runCatching { ExportMode.valueOf(c.getString(4)) }.getOrDefault(ExportMode.FULL),
    )

    fun items(sessionId: Long): List<StoredTransferItem> {
        val out = mutableListOf<StoredTransferItem>()
        readableDatabase.rawQuery("SELECT id,session_id,ordinal,payload_json,fingerprint,state,target_hash,error FROM transfer_items WHERE session_id=? ORDER BY ordinal", arrayOf(sessionId.toString())).use { c ->
            while (c.moveToNext()) {
                val book = runCatching { bookFromJson(JSONObject(c.getString(3))) }.getOrNull() ?: continue
                out += StoredTransferItem(c.getLong(0), c.getLong(1), c.getInt(2), book, c.getString(4), c.getString(5), c.getString(6), c.getString(7))
            }
        }
        return out
    }

    fun setSessionStatus(id: Long, status: String) {
        writableDatabase.update("transfer_sessions", ContentValues().apply { put("status", status); put("updated_at", System.currentTimeMillis()) }, "id=?", arrayOf(id.toString()))
    }

    fun setItemState(id: Long, state: String, targetHash: String? = null, error: String? = null) {
        writableDatabase.update("transfer_items", ContentValues().apply {
            put("state", state)
            if (targetHash != null) put("target_hash", targetHash)
            if (error == null) putNull("error") else put("error", error.take(500))
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun rememberCompleted(targetUri: Uri, item: StoredTransferItem, targetHash: String?) {
        writableDatabase.insertWithOnConflict("completed_books", null, ContentValues().apply {
            put("target_uri", targetUri.toString())
            put("book_key", item.book.key)
            put("fingerprint", item.fingerprint)
            put("target_hash", targetHash)
            put("completed_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun completedHash(targetUri: Uri, item: StoredTransferItem): String? {
        readableDatabase.rawQuery("SELECT target_hash,fingerprint FROM completed_books WHERE target_uri=? AND book_key=?", arrayOf(targetUri.toString(), item.book.key)).use { c ->
            if (!c.moveToFirst() || c.getString(1) != item.fingerprint) return null
            return c.getString(0)
        }
    }

    fun saveAnalysis(context: Context, sourceUri: Uri, books: List<BookItem>) {
        val fp = sourceFingerprint(context, sourceUri) ?: return
        val arr = JSONArray(); books.forEach { arr.put(bookToJson(it)) }
        writableDatabase.insertWithOnConflict("analysis_cache", null, ContentValues().apply {
            put("slot", 1); put("source_uri", sourceUri.toString()); put("source_fingerprint", fp); put("books_json", arr.toString()); put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadAnalysis(context: Context, sourceUri: Uri): List<BookItem>? {
        val fp = sourceFingerprint(context, sourceUri) ?: return null
        readableDatabase.rawQuery("SELECT source_fingerprint,books_json FROM analysis_cache WHERE slot=1 AND source_uri=?", arrayOf(sourceUri.toString())).use { c ->
            if (!c.moveToFirst() || c.getString(0) != fp) return null
            return decodeBooks(c.getString(1))
        }
    }

    fun loadLatestAnalysis(context: Context): List<BookItem>? {
        readableDatabase.rawQuery("SELECT source_uri,source_fingerprint,books_json FROM analysis_cache WHERE slot=1", null).use { c ->
            if (!c.moveToFirst()) return null
            val uri = runCatching { Uri.parse(c.getString(0)) }.getOrNull() ?: return null
            if (sourceFingerprint(context, uri) != c.getString(1)) return null
            return decodeBooks(c.getString(2))
        }
    }

    private fun decodeBooks(json: String): List<BookItem>? {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        return buildList { for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { runCatching { bookFromJson(it) }.getOrNull()?.let(::add) } }
    }

    companion object {
        private fun sourceFingerprint(context: Context, uri: Uri): String? {
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
            return "${uri}|${doc.length()}|${doc.lastModified()}"
        }

        internal fun stableSuffix(value: String): String = sha256(value).take(10)

        private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun bookToJson(b: BookItem): JSONObject = JSONObject().apply {
            put("key", b.key); put("sourceFile", b.sourceFile); put("originalName", b.originalName); put("extension", b.extension); put("title", b.title)
            put("author", b.author); put("isbn", b.isbn); put("isCryptic", b.isCryptic); put("includedInBackup", b.includedInBackup)
            b.position?.let { p -> put("position", JSONObject().apply { put("raw", p.raw); put("timestampMs", p.timestampMs); put("chapterOrPage", p.chapterOrPage); put("section", p.section); put("offset", p.offset); put("percent", p.percent) }) }
            b.annotation?.let { a -> put("annotation", JSONObject().apply {
                put("originalMrexpt", a.originalMrexpt); val records = JSONArray(); a.records.forEach { r -> records.put(JSONObject().apply {
                    put("id", r.id); put("chapter", r.chapter); put("splitIndex", r.splitIndex); put("position", r.position); put("length", r.length); put("color", r.color); put("timestampMs", r.timestampMs); put("bookmark", r.bookmark); put("note", r.note); put("original", r.original)
                }) }; put("records", records)
            }) }
            b.epub?.let { e -> put("epub", JSONObject().apply {
                put("uri", e.uri?.toString()); put("backupUri", e.backupUri?.toString()); put("archiveEntryName", e.archiveEntryName); put("embeddedPath", e.embeddedPath); put("fileName", e.fileName); put("title", e.title); put("author", e.author); put("isbn", e.isbn); put("partialMd5", e.partialMd5); put("size", e.size)
            }) }
        }

        private fun bookFromJson(o: JSONObject): BookItem {
            val p = o.optJSONObject("position")?.let { MoonPosition(it.optString("raw"), it.optLongOrNull("timestampMs"), it.optIntOrNull("chapterOrPage"), it.optIntOrNull("section"), it.optLongOrNull("offset"), it.optDoubleOrNull("percent")) }
            val a = o.optJSONObject("annotation")?.let { ao ->
                val arr = ao.optJSONArray("records") ?: JSONArray(); val records = buildList { for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { r -> add(AnnotationRecord(r.optLong("id"), r.optIntOrNull("chapter"), r.optIntOrNull("splitIndex"), r.optLongOrNull("position"), r.optIntOrNull("length"), r.optIntOrNull("color"), r.optLongOrNull("timestampMs"), r.optBoolean("bookmark"), r.optNullableString("note"), r.optNullableString("original"))) } }
                AnnotationData(ao.optNullableString("originalMrexpt"), records)
            }
            val e = o.optJSONObject("epub")?.let { EpubMatch(it.optNullableString("uri")?.let(Uri::parse), it.optNullableString("backupUri")?.let(Uri::parse), it.optNullableString("archiveEntryName"), it.optNullableString("embeddedPath"), it.getString("fileName"), it.optNullableString("title"), it.optNullableString("author"), it.optNullableString("isbn"), it.optNullableString("partialMd5"), null, it.optLongOrNull("size")) }
            return BookItem(o.getString("key"), o.getString("sourceFile"), o.getString("originalName"), o.getString("extension"), o.getString("title"), o.optNullableString("author"), o.optNullableString("isbn"), p, a, o.optBoolean("isCryptic"), e, o.optBoolean("includedInBackup"))
        }

        private fun JSONObject.optNullableString(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
        private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
        private fun JSONObject.optIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
        private fun JSONObject.optDoubleOrNull(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key)
    }
}

package de.moonexporter.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal data class SyncConfig(
    val serverType: ServerType,
    val baseUrl: String,
    val username: String,
    val password: String,
    val deviceId: String,
)

internal object KoSyncClient {
    suspend fun authenticate(config: SyncConfig): String = withContext(Dispatchers.IO) {
        val endpoint = when (config.serverType) {
            ServerType.CALIBRE_WEB_AUTOMATED -> "${normalizeBaseUrl(config.baseUrl, config.serverType)}/kosync/users/auth"
            ServerType.STANDARD_KOSYNC -> "${normalizeBaseUrl(config.baseUrl, config.serverType)}/users/auth"
        }
        val response = request("GET", endpoint, config, null)
        if (response.code !in 200..299) throw syncError(response.code, response.body)
        tr("Verbindung erfolgreich", "Connection successful")
    }

    suspend fun uploadProgress(config: SyncConfig, books: List<BookItem>, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (config.serverType == ServerType.CALIBRE_WEB_AUTOMATED) authenticate(config)
        val endpoint = when (config.serverType) {
            ServerType.CALIBRE_WEB_AUTOMATED -> "${normalizeBaseUrl(config.baseUrl, config.serverType)}/kosync/syncs/progress"
            ServerType.STANDARD_KOSYNC -> "${normalizeBaseUrl(config.baseUrl, config.serverType)}/syncs/progress"
        }
        val syncable = books.filter { it.position?.percent != null && !it.epub?.partialMd5.isNullOrBlank() }
        syncable.forEachIndexed { index, book ->
            coroutineContext.ensureActive()
            onProgress(tr("Übertragung ${index + 1}/${syncable.size}: ${book.title}", "Sending ${index + 1}/${syncable.size}: ${book.title}"))
            val rawPercent = book.position?.percent ?: 0.0
            val percent = (rawPercent / 100.0).coerceIn(0.0, 1.0)
            val document = requireNotNull(book.epub?.partialMd5)
            val body = """{"document":${document.jsonEscape()},"progress":${("%.2f%%".format(Locale.ROOT, rawPercent)).jsonEscape()},"percentage":${"%.6f".format(Locale.ROOT, percent)},"device":"KOReader","device_id":${config.deviceId.ifBlank { "MoonExporter" }.jsonEscape()}}"""
            val response = request("PUT", endpoint, config, body)
            if (response.code !in 200..299) throw syncError(response.code, response.body)
        }
    }

    internal fun normalizeBaseUrl(raw: String, type: ServerType): String {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { tr("Server-URL fehlt", "Server URL is missing") }
        val uri = URI(trimmed)
        require(uri.scheme.equals("https", true)) { tr("Nur HTTPS ist erlaubt", "HTTPS is required") }
        require(!uri.host.isNullOrBlank()) { tr("Ungültige Server-URL", "Invalid server URL") }
        val noTrailing = trimmed.trimEnd('/')
        return if (type == ServerType.CALIBRE_WEB_AUTOMATED && noTrailing.endsWith("/kosync", true)) noTrailing.dropLast(7).trimEnd('/') else noTrailing
    }

    private data class Response(val code: Int, val body: String)

    private fun request(method: String, endpoint: String, config: SyncConfig, json: String?): Response {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            when (config.serverType) {
                ServerType.CALIBRE_WEB_AUTOMATED -> {
                    val token = Base64.encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $token")
                }
                ServerType.STANDARD_KOSYNC -> {
                    setRequestProperty("X-Auth-User", config.username)
                    setRequestProperty("X-Auth-Key", md5(config.password))
                }
            }
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (json != null) connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(16_384) }.orEmpty()
        connection.disconnect()
        return Response(code, body)
    }

    private fun syncError(code: Int, body: String): IllegalStateException {
        val lower = body.lowercase(Locale.ROOT)
        val message = when {
            code == 401 -> tr("HTTP 401: Zugangsdaten prüfen", "HTTP 401: check credentials")
            code == 503 && "koreader sync is disabled" in lower -> tr("HTTP 503: KOSync ist in Calibre-Web Automated deaktiviert", "HTTP 503: KOSync is disabled in Calibre-Web Automated")
            body.trimStart().startsWith("<") -> tr("HTTP $code: HTML/Login-Seite erhalten. Reverse Proxy, Weiterleitung oder Serverpfad prüfen", "HTTP $code: HTML/login page received. Check reverse proxy, redirects or server path")
            else -> tr("HTTP $code: Server hat die Fortschrittsübertragung abgelehnt", "HTTP $code: server rejected progress update")
        }
        return IllegalStateException(message)
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }
}

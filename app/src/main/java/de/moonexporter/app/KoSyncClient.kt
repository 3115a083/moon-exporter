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
    val deviceId: String = "MoonExporter",
)

internal object KoSyncClient {
    suspend fun authenticate(config: SyncConfig): String = withContext(Dispatchers.IO) {
        val response = request("GET", "${endpointRoot(config)}/users/auth", config, null)
        if (response.code !in 200..299) throw syncError(response.code, response.body, config.serverType)
        tr("Verbindung erfolgreich", "Connection successful")
    }

    suspend fun uploadProgress(config: SyncConfig, books: List<BookItem>, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        authenticate(config)
        val syncable = books.filter { it.position?.percent != null && !it.epub?.partialMd5.isNullOrBlank() }
        if (syncable.isEmpty()) error(tr(
            "Kein ausgewähltes Buch hat gleichzeitig Lesefortschritt und eine passende Buchdatei für die KOSync-ID.",
            "No selected book has both reading progress and a matched book file for the KOSync document ID.",
        ))
        val endpoint = "${endpointRoot(config)}/syncs/progress"
        syncable.forEachIndexed { index, book ->
            coroutineContext.ensureActive()
            onProgress(tr("Übertragung ${index + 1}/${syncable.size}: ${book.title}", "Sending ${index + 1}/${syncable.size}: ${book.title}"))
            val rawPercent = requireNotNull(book.position?.percent).coerceIn(0.0, 100.0)
            val percentage = rawPercent / 100.0
            val document = requireNotNull(book.epub?.partialMd5)
            val body = """{"document":${document.jsonEscape()},"progress":${("%.2f%%".format(Locale.ROOT, rawPercent)).jsonEscape()},"percentage":${"%.6f".format(Locale.ROOT, percentage)},"device":"Moon Exporter","device_id":${config.deviceId.ifBlank { "MoonExporter" }.jsonEscape()}}"""
            val response = request("PUT", endpoint, config, body)
            if (response.code !in 200..299) throw syncError(response.code, response.body, config.serverType)
        }
    }

    internal fun normalizeBaseUrl(raw: String, type: ServerType): String {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { tr("Server-URL fehlt", "Server URL is missing") }
        val uri = URI(trimmed)
        require(uri.scheme.equals("https", true)) { tr("Nur HTTPS ist erlaubt", "HTTPS is required") }
        require(!uri.host.isNullOrBlank()) { tr("Ungültige Server-URL", "Invalid server URL") }
        return when (type) {
            ServerType.CALIBRE_WEB_AUTOMATED -> if (trimmed.endsWith("/kosync", true)) trimmed.dropLast(7).trimEnd('/') else trimmed
            ServerType.BOOKLORE -> if (trimmed.endsWith("/api/koreader", true)) trimmed.dropLast(13).trimEnd('/') else trimmed
            ServerType.STANDARD_KOSYNC -> trimmed
        }
    }

    internal fun endpointRoot(config: SyncConfig): String {
        val base = normalizeBaseUrl(config.baseUrl, config.serverType)
        return when (config.serverType) {
            ServerType.CALIBRE_WEB_AUTOMATED -> "$base/kosync"
            ServerType.BOOKLORE -> "$base/api/koreader"
            ServerType.STANDARD_KOSYNC -> base
        }
    }

    private data class Response(val code: Int, val body: String)

    private fun request(method: String, endpoint: String, config: SyncConfig, json: String?): Response {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/vnd.koreader.v1+json")
            when (config.serverType) {
                ServerType.CALIBRE_WEB_AUTOMATED -> {
                    val token = Base64.encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $token")
                }
                ServerType.STANDARD_KOSYNC, ServerType.BOOKLORE -> {
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

    private fun syncError(code: Int, body: String, type: ServerType): IllegalStateException {
        val lower = body.lowercase(Locale.ROOT)
        val server = when (type) {
            ServerType.CALIBRE_WEB_AUTOMATED -> "CWA"
            ServerType.BOOKLORE -> "BookLore"
            ServerType.STANDARD_KOSYNC -> "KOSync"
        }
        val message = when {
            code == 401 -> tr("HTTP 401: $server-Zugangsdaten prüfen", "HTTP 401: check $server credentials")
            code == 503 && "koreader sync is disabled" in lower -> tr("HTTP 503: KOSync ist in CWA deaktiviert", "HTTP 503: KOSync is disabled in CWA")
            code in 300..399 -> tr("HTTP $code: Server leitet um. Server-URL oder Reverse Proxy prüfen", "HTTP $code: server redirects. Check server URL or reverse proxy")
            body.trimStart().startsWith("<") -> tr("HTTP $code: HTML/Login-Seite erhalten. Serverpfad prüfen", "HTTP $code: HTML/login page received. Check server path")
            else -> tr("HTTP $code: $server hat die Fortschrittsübertragung abgelehnt", "HTTP $code: $server rejected progress update")
        }
        return IllegalStateException(message)
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }
}

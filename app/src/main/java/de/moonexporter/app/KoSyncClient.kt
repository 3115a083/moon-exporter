package de.moonexporter.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Reader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
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
    private const val MAX_RESPONSE_CHARS = 16_384

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
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { tr("Server-URL muss HTTP oder HTTPS verwenden", "Server URL must use HTTP or HTTPS") }
        require(!uri.host.isNullOrBlank()) { tr("Ungültige Server-URL", "Invalid server URL") }
        require(uri.userInfo == null) { tr("Zugangsdaten dürfen nicht in der Server-URL stehen", "Credentials must not be embedded in the server URL") }
        if (scheme == "http") {
            require(isLocalNetworkHost(uri.host)) {
                tr(
                    "HTTP ist nur für lokale Heimnetz-Adressen erlaubt. Für öffentliche Server ist HTTPS erforderlich.",
                    "HTTP is only allowed for local-network addresses. Public servers require HTTPS.",
                )
            }
        }
        return when (type) {
            ServerType.CALIBRE_WEB_AUTOMATED -> if (trimmed.endsWith("/kosync", true)) trimmed.dropLast(7).trimEnd('/') else trimmed
            ServerType.BOOKLORE -> if (trimmed.endsWith("/api/koreader", true)) trimmed.dropLast(13).trimEnd('/') else trimmed
            ServerType.STANDARD_KOSYNC -> trimmed
        }
    }

    internal fun isLocalNetworkHost(host: String): Boolean {
        val normalized = host.trim().trim('[', ']').lowercase(Locale.ROOT)
        if (normalized == "localhost" || normalized.endsWith(".local")) return true
        parseIpv4(normalized)?.let { return isPrivateAddress(it) }
        if (':' in normalized) return runCatching { InetAddress.getByName(normalized) }.getOrNull()?.let(::isPrivateAddress) == true

        // Hostnames, including single-label LAN names, are trusted only when every resolved
        // address is private/local. This prevents public DNS names or rebinding targets from
        // silently receiving credentials over cleartext HTTP.
        val resolved = runCatching { InetAddress.getAllByName(normalized).toList() }.getOrElse { return false }
        return resolved.isNotEmpty() && resolved.all(::isPrivateAddress)
    }

    private fun parseIpv4(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        if (values.any { it !in 0..255 }) return null
        return InetAddress.getByAddress(values.map { it.toByte() }.toByteArray())
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        return when (address) {
            is Inet4Address -> {
                val b = address.address.map { it.toInt() and 0xff }
                b[0] == 10 || b[0] == 127 || (b[0] == 172 && b[1] in 16..31) ||
                    (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
            }
            is Inet6Address -> {
                val first = address.address[0].toInt() and 0xff
                first == 0xfc || first == 0xfd || address.isLinkLocalAddress || address.isLoopbackAddress
            }
            else -> false
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
        return try {
            if (json != null) connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { readBounded(it, MAX_RESPONSE_CHARS) }.orEmpty()
            Response(code, body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun readBounded(reader: Reader, maxChars: Int): String {
        val out = StringBuilder(minOf(maxChars, 4096))
        val buffer = CharArray(2048)
        while (out.length < maxChars) {
            val wanted = minOf(buffer.size, maxChars - out.length)
            val count = reader.read(buffer, 0, wanted)
            if (count < 0) break
            out.append(buffer, 0, count)
        }
        return out.toString()
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

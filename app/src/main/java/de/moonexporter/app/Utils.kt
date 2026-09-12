package de.moonexporter.app

import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal val PARTIAL_MD5_OFFSETS = longArrayOf(
    1L shl 9, 1L shl 11, 1L shl 13, 1L shl 15, 1L shl 17, 1L shl 19,
    1L shl 21, 1L shl 23, 1L shl 25, 1L shl 27, 1L shl 29, 1L shl 31,
)

internal fun partialMd5(input: InputStream): String {
    val digest = MessageDigest.getInstance("MD5")
    val chunks = Array(PARTIAL_MD5_OFFSETS.size) { ByteArray(1024) }
    val counts = IntArray(PARTIAL_MD5_OFFSETS.size)
    val buffer = ByteArray(64 * 1024)
    var absolute = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        for (i in PARTIAL_MD5_OFFSETS.indices) {
            val start = PARTIAL_MD5_OFFSETS[i]
            val end = start + 1024
            val blockStart = absolute
            val blockEnd = absolute + n
            if (blockEnd <= start || blockStart >= end) continue
            val from = maxOf(blockStart, start)
            val to = minOf(blockEnd, end)
            val src = (from - blockStart).toInt()
            val dst = (from - start).toInt()
            val len = (to - from).toInt()
            System.arraycopy(buffer, src, chunks[i], dst, len)
            counts[i] = maxOf(counts[i], dst + len)
        }
        absolute += n
    }
    for (i in chunks.indices) if (counts[i] > 0) digest.update(chunks[i], 0, counts[i])
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
}

internal fun parsePo(raw: String): MoonPosition {
    val text = raw.trim()
    Regex("^(\\d+)\\*(\\d+)@(\\d+)#(\\d+):([0-9.]+)%$").matchEntire(text)?.let {
        return MoonPosition(text, it.groupValues[1].toLongOrNull(), it.groupValues[2].toIntOrNull(), it.groupValues[3].toIntOrNull(), it.groupValues[4].toLongOrNull(), it.groupValues[5].toDoubleOrNull())
    }
    Regex("^(\\d+)\\*(\\d+):([0-9.]+)%$").matchEntire(text)?.let {
        return MoonPosition(text, it.groupValues[1].toLongOrNull(), it.groupValues[2].toIntOrNull(), null, null, it.groupValues[3].toDoubleOrNull())
    }
    return MoonPosition(
        raw = text,
        timestampMs = Regex("^(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull(),
        percent = Regex("([0-9.]+)%").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull(),
    )
}

private val LEADING_ARTICLE = Regex("^(the|a|an|der|die|das|ein|eine|le|la|el)\\s+", RegexOption.IGNORE_CASE)
internal fun sortTitle(value: String): String = normalize(value.replace(LEADING_ARTICLE, ""))
internal fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD).replace(Regex("[^a-z0-9]+"), "").take(160)
internal fun safeName(value: String): String = value.replace(Regex("[\\x00-\\x1f/\\\\:*?\"<>|]+"), "_").trim().take(160).ifBlank { "unknown" }
internal fun String.jsonEscape(): String = buildString {
    append('"')
    for (ch in this@jsonEscape) when (ch) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(ch)
    }
    append('"')
}

internal fun sectionFor(title: String): String {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return "#"
    return when (val c = trimmed.first().uppercaseChar()) {
        in 'A'..'Z' -> c.toString()
        'Ä' -> "Ä"
        'Ö' -> "Ö"
        'Ü' -> "Ü"
        else -> "#"
    }
}

internal fun tr(de: String, en: String): String = if (Locale.getDefault().language.equals("de", true)) de else en

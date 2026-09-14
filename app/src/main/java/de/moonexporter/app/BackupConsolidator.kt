package de.moonexporter.app

import java.time.LocalDate
import java.util.Locale

internal data class MoonBackupDescriptor(
    val displayName: String,
    val date: LocalDate?,
    val deviceName: String?,
)

/**
 * Consolidates multiple Moon+ backups before transfer. Inputs must be ordered from oldest to newest.
 * Newer records win only when they are actually newer; annotations are unioned losslessly.
 */
internal object BackupConsolidator {
    private val backupName = Regex("^(\\d{4}-\\d{2}-\\d{2})\\s+(.+?)\\s+Backup(?:\\s*\\([^)]*\\))?\\.mrpro$", RegexOption.IGNORE_CASE)

    fun describe(displayName: String): MoonBackupDescriptor {
        val match = backupName.matchEntire(displayName.trim())
        val date = match?.groupValues?.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val device = match?.groupValues?.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
        return MoonBackupDescriptor(displayName, date, device)
    }

    fun consolidate(backupsOldestToNewest: List<List<BookItem>>): List<BookItem> {
        val merged = linkedMapOf<String, BookItem>()
        backupsOldestToNewest.forEach { books ->
            books.forEach { incoming ->
                val identity = identity(incoming)
                merged[identity] = merged[identity]?.let { combine(it, incoming) } ?: incoming
            }
        }
        return merged.values.sortedBy { sortTitle(it.title) }
    }

    internal fun identity(book: BookItem): String {
        book.epub?.partialMd5?.takeIf { it.isNotBlank() }?.let { return "hash:${it.lowercase(Locale.ROOT)}" }
        val source = book.sourceFile.substringAfterLast('/').substringBeforeLast('.').normalizeIdentity()
        if (source.isNotBlank() && !ProgressRecovery.looksOpaque(source)) return "file:$source"
        val title = book.title.normalizeIdentity()
        val author = book.author.orEmpty().normalizeIdentity()
        return "meta:$title|$author"
    }

    private fun combine(old: BookItem, newer: BookItem): BookItem {
        val position = newerPosition(old.position, newer.position)
        val annotations = mergeAnnotations(old.annotation, newer.annotation)
        val source = when {
            newer.hasBookFile -> newer.epub
            old.hasBookFile -> old.epub
            newer.epub != null -> newer.epub
            else -> old.epub
        }
        val preferredTitle = listOf(newer.title, old.title, source?.title.orEmpty())
            .firstOrNull { it.isNotBlank() && !ProgressRecovery.looksOpaque(it) }
            ?: newer.title.ifBlank { old.title }
        return newer.copy(
            key = old.key,
            title = preferredTitle,
            author = newer.author?.takeIf { it.isNotBlank() } ?: old.author,
            isbn = newer.isbn?.takeIf { it.isNotBlank() } ?: old.isbn,
            position = position,
            annotation = annotations,
            epub = source,
            includedInBackup = old.includedInBackup || newer.includedInBackup,
        )
    }

    private fun newerPosition(old: MoonPosition?, newer: MoonPosition?): MoonPosition? {
        if (old == null) return newer
        if (newer == null) return old
        val oldTs = old.timestampMs ?: Long.MIN_VALUE
        val newTs = newer.timestampMs ?: Long.MIN_VALUE
        return when {
            newTs > oldTs -> newer
            oldTs > newTs -> old
            else -> if ((newer.percent ?: -1.0) >= (old.percent ?: -1.0)) newer else old
        }
    }

    private fun mergeAnnotations(old: AnnotationData?, newer: AnnotationData?): AnnotationData? {
        if (old == null) return newer
        if (newer == null) return old
        val records = linkedMapOf<String, AnnotationRecord>()
        (old.records + newer.records).forEach { record ->
            val key = listOf(record.id, record.chapter, record.position, record.length, record.original, record.note).joinToString("|")
            val existing = records[key]
            if (existing == null || (record.timestampMs ?: 0L) >= (existing.timestampMs ?: 0L)) records[key] = record
        }
        val raw = listOfNotNull(old.originalMrexpt?.takeIf { it.isNotBlank() }, newer.originalMrexpt?.takeIf { it.isNotBlank() })
            .distinct().joinToString("\n# moon-exporter merged backup boundary\n").takeIf { it.isNotBlank() }
        return AnnotationData(raw, records.values.toList())
    }

    private fun String.normalizeIdentity(): String = lowercase(Locale.ROOT)
        .replace(Regex("\\.[a-z0-9]{1,5}$"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

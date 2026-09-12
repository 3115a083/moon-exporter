package de.moonexporter.app

import android.graphics.Bitmap
import android.net.Uri

internal data class MoonPosition(
    val raw: String,
    val timestampMs: Long? = null,
    val chapterOrPage: Int? = null,
    val section: Int? = null,
    val offset: Long? = null,
    val percent: Double? = null,
)

internal data class AnnotationRecord(
    val id: Long = 0,
    val chapter: Int? = null,
    val splitIndex: Int? = null,
    val position: Long? = null,
    val length: Int? = null,
    val color: Int? = null,
    val timestampMs: Long? = null,
    val bookmark: Boolean = false,
    val note: String? = null,
    val original: String? = null,
)

internal data class AnnotationData(
    val originalMrexpt: String? = null,
    val records: List<AnnotationRecord> = emptyList(),
) {
    val count: Int get() = if (records.isNotEmpty()) records.size else originalMrexpt?.split("\n#\n")?.size?.minus(1)?.coerceAtLeast(0) ?: 0
}

internal data class EpubMatch(
    val uri: Uri? = null,
    val backupUri: Uri? = null,
    val archiveEntryName: String? = null,
    val embeddedPath: String? = null,
    val fileName: String,
    val title: String? = null,
    val author: String? = null,
    val isbn: String? = null,
    val partialMd5: String? = null,
    val cover: Bitmap? = null,
    val size: Long? = null,
)

internal data class BookItem(
    val key: String,
    val sourceFile: String,
    val originalName: String,
    val extension: String,
    val title: String,
    val author: String? = null,
    val isbn: String? = null,
    val position: MoonPosition? = null,
    val annotation: AnnotationData? = null,
    val isCryptic: Boolean = false,
    val epub: EpubMatch? = null,
    val includedInBackup: Boolean = false,
) {
    val hasAnnotations: Boolean get() = annotation?.records?.isNotEmpty() == true || !annotation?.originalMrexpt.isNullOrBlank()
    val hasBookFile: Boolean get() = epub?.uri != null || epub?.embeddedPath != null || (epub?.backupUri != null && !epub.archiveEntryName.isNullOrBlank())
}

internal enum class BookFilter { ALL, WITH_PROGRESS, WITHOUT_PROGRESS, WITH_BOOK }
internal enum class ExportMode { FULL, MARKINGS_ONLY }
internal enum class ServerType { STANDARD_KOSYNC, CALIBRE_WEB_AUTOMATED, BOOKLORE }

package de.moonexporter.app

import java.util.Locale

/** Formats currently supported by both Moon+ Reader and Readest. */
internal object BookFormats {
    val readestCompatible = setOf("epub", "pdf", "mobi", "azw", "azw3", "fb2", "cbz", "zip", "txt", "md")

    fun extension(fileName: String, fallback: String = ""): String =
        fileName.substringAfterLast('.', fallback).lowercase(Locale.ROOT)

    fun isReadestCompatible(fileName: String, fallback: String = ""): Boolean =
        extension(fileName, fallback) in readestCompatible

    fun mime(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "mobi" -> "application/x-mobipocket-ebook"
        "azw", "azw3" -> "application/vnd.amazon.ebook"
        "fb2" -> "application/x-fictionbook+xml"
        "cbz" -> "application/vnd.comicbook+zip"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        else -> "application/octet-stream"
    }
}

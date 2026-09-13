package de.moonexporter.app

internal object DirectExportPolicy {
    fun shouldSkipForMissingSource(book: BookItem): Boolean = !book.hasBookFile

    fun missingSourceHasUserData(book: BookItem): Boolean =
        book.position != null || book.hasAnnotations
}

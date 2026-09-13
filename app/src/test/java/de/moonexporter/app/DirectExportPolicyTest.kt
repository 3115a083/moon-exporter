package de.moonexporter.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectExportPolicyTest {
    @Test
    fun sourceLessBookWithoutProgressOrAnnotationsIsSkippable() {
        val book = BookItem(
            key = "no-source-no-data",
            sourceFile = "missing.epub",
            originalName = "missing.epub",
            extension = "epub",
            title = "Synthetic missing source",
        )

        assertTrue(DirectExportPolicy.shouldSkipForMissingSource(book))
        assertFalse(DirectExportPolicy.missingSourceHasUserData(book))
    }

    @Test
    fun sourceLessBookWithProgressIsStillNonFatalButCarriesUserData() {
        val book = BookItem(
            key = "no-source-with-progress",
            sourceFile = "missing.epub",
            originalName = "missing.epub",
            extension = "epub",
            title = "Synthetic progress source",
            position = MoonPosition(raw = "1@0#10:42%", chapterOrPage = 1, section = 0, offset = 10, percent = 42.0),
        )

        assertTrue(DirectExportPolicy.shouldSkipForMissingSource(book))
        assertTrue(DirectExportPolicy.missingSourceHasUserData(book))
    }
}

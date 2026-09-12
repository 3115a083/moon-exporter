package de.moonexporter.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class AnalysisUiState(
    val running: Boolean = false,
    val progress: Int = 0,
    val message: String = tr("Wähle eine Moon+ Backup-Datei.", "Choose a Moon+ backup file."),
    val books: List<BookItem> = emptyList(),
    val error: String? = null,
)

internal object AnalysisStore {
    private val mutable = MutableStateFlow(AnalysisUiState())
    val state: StateFlow<AnalysisUiState> = mutable.asStateFlow()

    fun start() {
        mutable.value = AnalysisUiState(
            running = true,
            progress = 0,
            message = tr("Backup wird vorbereitet…", "Preparing backup…"),
        )
    }

    fun progress(percent: Int, message: String) {
        mutable.update { it.copy(running = true, progress = percent.coerceIn(0, 99), message = message, error = null) }
    }

    fun publishBooks(books: List<BookItem>, percent: Int, message: String) {
        mutable.update { it.copy(running = true, progress = percent.coerceIn(0, 99), message = message, books = books, error = null) }
    }

    fun replaceBooks(books: List<BookItem>) {
        mutable.update { it.copy(books = books) }
    }

    fun complete(books: List<BookItem>, message: String) {
        mutable.value = AnalysisUiState(running = false, progress = 100, message = message, books = books)
    }

    fun fail(message: String) {
        mutable.update { it.copy(running = false, message = message, error = message) }
    }

    fun cancel() {
        mutable.update { it.copy(running = false, message = tr("Analyse abgebrochen", "Analysis cancelled")) }
    }
}

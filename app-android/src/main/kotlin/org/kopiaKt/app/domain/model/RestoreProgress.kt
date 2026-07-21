package org.kopiaKt.app.domain.model

data class RestoreProgress(
    val state: RestoreState,
    val totalFiles: Long,
    val restoredFiles: Long,
    val totalBytes: Long,
    val restoredBytes: Long,
    val currentFile: String?,
    val errorMessage: String?,
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) {
            (restoredBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
}

enum class RestoreState {
    IDLE,
    PREPARING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
}

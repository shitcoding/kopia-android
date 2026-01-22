package org.kopiaKt.app.domain.model

import java.time.Instant

data class FileEntry(
    val name: String,
    val type: FileEntryType,
    val size: Long,
    val modTime: Instant?,
    val permissions: Int,
    val objectId: String?
)

enum class FileEntryType {
    FILE,
    DIRECTORY,
    SYMLINK,
    UNKNOWN
}

data class DirectorySummary(
    val totalSize: Long,
    val fileCount: Long,
    val dirCount: Long
)

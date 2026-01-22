package org.kopiaKt.app.domain.usecase

import org.kopiaKt.app.domain.model.FileEntry
import org.kopiaKt.app.domain.model.FileEntryType
import org.kopiaKt.app.domain.repository.SnapshotRepository
import javax.inject.Inject

class BrowseSnapshotUseCase @Inject constructor(
    private val snapshotRepository: SnapshotRepository
) {
    suspend operator fun invoke(snapshotId: String, path: String): List<FileEntry> {
        return snapshotRepository.browseDirectory(snapshotId, path)
            .sortedWith(
                compareBy<FileEntry> { it.type != FileEntryType.DIRECTORY }
                    .thenBy { it.name.lowercase() }
            )
    }
}

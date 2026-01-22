package org.kopiaKt.app.domain.usecase

import org.kopiaKt.app.domain.model.SnapshotInfo
import org.kopiaKt.app.domain.model.SourceInfo
import org.kopiaKt.app.domain.repository.SnapshotRepository
import javax.inject.Inject

class ListSnapshotsUseCase @Inject constructor(
    private val snapshotRepository: SnapshotRepository
) {
    suspend operator fun invoke(source: SourceInfo? = null): List<SnapshotInfo> {
        return snapshotRepository.listSnapshots(source)
            .sortedByDescending { it.startTime }
    }
}

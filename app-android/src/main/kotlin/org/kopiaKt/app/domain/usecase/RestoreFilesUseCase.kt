package org.kopiaKt.app.domain.usecase

import kotlinx.coroutines.flow.Flow
import org.kopiaKt.app.domain.model.RestoreProgress
import org.kopiaKt.app.domain.repository.RestoreOptions
import org.kopiaKt.app.domain.repository.SnapshotRepository
import javax.inject.Inject

class RestoreFilesUseCase @Inject constructor(
    private val snapshotRepository: SnapshotRepository
) {
    operator fun invoke(
        snapshotId: String,
        sourcePath: String,
        destinationUri: String,
        options: RestoreOptions = RestoreOptions()
    ): Flow<RestoreProgress> {
        return snapshotRepository.restore(snapshotId, sourcePath, destinationUri, options)
    }

    fun cancel() {
        snapshotRepository.cancelRestore()
    }
}

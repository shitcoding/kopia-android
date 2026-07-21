package org.kopiaKt.app.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.kopiaKt.android.worker.BackupWorker
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custom WorkerFactory that creates BackupWorker instances with injected dependencies.
 *
 * This replaces the default WorkerFactory so that BackupWorker can access
 * the KopiaRepositoryManager through dependency injection instead of relying
 * on static state (the companion object repositoryProvider pattern).
 */
class KopiaWorkerFactory @Inject constructor(
    private val repositoryManager: KopiaRepositoryManager,
) : WorkerFactory() {

    init {
        BackupWorker.repositoryProvider = { _ -> repositoryManager.getRepository() }
    }

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        BackupWorker::class.java.name -> BackupWorker(appContext, workerParameters)
        else -> null
    }
}

/**
 * Hilt module that provides the custom WorkerFactory for dependency injection.
 *
 * The WorkerFactory is provided as a singleton so that all worker creation
 * goes through a single factory instance with consistent dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object HiltWorkerModule {

    @Provides
    @Singleton
    fun provideWorkerFactory(
        repositoryManager: KopiaRepositoryManager,
    ): WorkerFactory = KopiaWorkerFactory(repositoryManager)
}

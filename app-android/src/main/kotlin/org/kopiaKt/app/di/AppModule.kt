package org.kopiaKt.app.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.kopiaKt.android.worker.BackupSourceManager
import org.kopiaKt.android.worker.TaskManager
import org.kopiaKt.app.data.repository.EncryptedCredentialRepository
import org.kopiaKt.app.data.repository.KopiaRepositoryManagerImpl
import org.kopiaKt.app.data.repository.SnapshotRepositoryImpl
import org.kopiaKt.app.domain.repository.CredentialRepository
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.app.domain.repository.SnapshotRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindKopiaRepositoryManager(
        impl: KopiaRepositoryManagerImpl,
    ): KopiaRepositoryManager

    @Binds
    @Singleton
    abstract fun bindSnapshotRepository(
        impl: SnapshotRepositoryImpl,
    ): SnapshotRepository

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(
        impl: EncryptedCredentialRepository,
    ): CredentialRepository

    companion object {
        /**
         * Backup state outlives any one screen: the bridge is rebuilt per `MainActivity`, and
         * `BackupWorker` must reach the same instances the Tasks screen reads.
         */
        @Provides
        @Singleton
        fun provideTaskManager(): TaskManager = TaskManager()

        @Provides
        @Singleton
        fun provideBackupSourceManager(): BackupSourceManager = BackupSourceManager()
    }
}

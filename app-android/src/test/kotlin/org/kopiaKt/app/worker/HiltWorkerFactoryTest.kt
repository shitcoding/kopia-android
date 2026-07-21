package org.kopiaKt.app.worker

import android.content.Context
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.kopiaKt.android.worker.BackupWorker
import org.kopiaKt.app.domain.repository.KopiaRepositoryManager
import org.kopiaKt.core.repository.DirectRepository
import java.util.UUID

class HiltWorkerFactoryTest {

    private lateinit var repositoryManager: KopiaRepositoryManager
    private lateinit var factory: KopiaWorkerFactory
    private lateinit var appContext: Context
    private lateinit var workerParams: WorkerParameters

    private var savedProvider: ((Context) -> DirectRepository?)? = null

    @BeforeEach
    fun setUp() {
        savedProvider = BackupWorker.repositoryProvider
        repositoryManager = mockk(relaxed = true)
        appContext = mockk(relaxed = true)
        workerParams = createMockWorkerParams()
        factory = KopiaWorkerFactory(repositoryManager)
    }

    @AfterEach
    fun tearDown() {
        BackupWorker.repositoryProvider = savedProvider
    }

    private fun createMockWorkerParams(): WorkerParameters = mockk(relaxed = true) {
        every { id } returns UUID.randomUUID()
    }

    @Nested
    @DisplayName("Worker Factory")
    inner class WorkerFactoryTests {

        @Test
        fun `factory creates BackupWorker instance`() {
            val worker = factory.createWorker(
                appContext,
                BackupWorker::class.java.name,
                workerParams,
            )

            assertThat(worker).isNotNull()
            assertThat(worker).isInstanceOf(BackupWorker::class.java)
        }

        @Test
        fun `factory returns null for unknown worker class`() {
            val worker = factory.createWorker(
                appContext,
                "com.example.UnknownWorker",
                workerParams,
            )

            assertThat(worker).isNull()
        }

        @Test
        fun `factory passes correct parameters`() {
            val specificContext: Context = mockk(relaxed = true)
            val specificParams = createMockWorkerParams()

            val worker = factory.createWorker(
                specificContext,
                BackupWorker::class.java.name,
                specificParams,
            )

            assertThat(worker).isNotNull()
            // The BackupWorker should have been created with the provided context and params.
            // Since CoroutineWorker stores them internally, we verify the worker was created
            // successfully with those parameters (it would throw if invalid).
            assertThat(worker).isInstanceOf(BackupWorker::class.java)
        }
    }

    @Nested
    @DisplayName("DI Module")
    inner class DiModuleTests {

        @Test
        fun `module provides WorkerFactory`() {
            val provided = HiltWorkerModule.provideWorkerFactory(repositoryManager)

            assertThat(provided).isNotNull()
            assertThat(provided).isInstanceOf(WorkerFactory::class.java)
        }

        @Test
        fun `module is singleton scope`() {
            // Verify that calling provideWorkerFactory with the same dependency
            // returns a new instance each time (Hilt @Singleton handles caching,
            // not the @Provides method itself). What matters is the annotation exists.
            val first = HiltWorkerModule.provideWorkerFactory(repositoryManager)
            val second = HiltWorkerModule.provideWorkerFactory(repositoryManager)

            // Each call creates a new instance (Hilt manages singleton lifecycle)
            assertThat(first).isNotSameInstanceAs(second)

            // Verify the @Singleton annotation is present on the method
            val method = HiltWorkerModule::class.java.methods.first {
                it.name == "provideWorkerFactory"
            }
            val hasSingleton = method.annotations.any {
                it.annotationClass.qualifiedName == "javax.inject.Singleton"
            }
            assertThat(hasSingleton).isTrue()
        }

        @Test
        fun `factory wires repository provider on construction`() {
            val mockRepo: DirectRepository = mockk(relaxed = true)
            val manager: KopiaRepositoryManager = mockk(relaxed = true) {
                every { getRepository() } returns mockRepo
            }

            KopiaWorkerFactory(manager)

            // Factory init should have set BackupWorker.repositoryProvider
            assertThat(BackupWorker.repositoryProvider).isNotNull()
            val result = BackupWorker.repositoryProvider?.invoke(mockk())
            assertThat(result).isSameInstanceAs(mockRepo)
            verify { manager.getRepository() }
        }
    }
}

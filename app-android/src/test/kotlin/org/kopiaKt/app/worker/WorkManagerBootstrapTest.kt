package org.kopiaKt.app.worker

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.kopiaKt.android.worker.BackupWorker
import org.kopiaKt.app.KopiaApp
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * WorkManager only reaches [KopiaWorkerFactory] if the application supplies it as the on-demand
 * configuration, and [BackupWorker] only reaches the repository if that factory's hook is installed.
 * Both were missing, so every backup failed with "Repository not configured" -- at runtime, with
 * nothing at build time to notice. This drives the real application through Robolectric so the whole
 * chain (Hilt injection -> configuration -> hook) is exercised rather than asserted piecemeal.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class WorkManagerBootstrapTest {

    private val app get() = RuntimeEnvironment.getApplication() as KopiaApp

    @Test
    fun `application supplies the Kopia worker factory to WorkManager`() {
        assertThat(app.workManagerConfiguration.workerFactory)
            .isInstanceOf(KopiaWorkerFactory::class.java)
    }

    @Test
    fun `startup installs the repository hook the worker reads`() {
        assertThat(app).isNotNull()

        assertThat(BackupWorker.repositoryProvider).isNotNull()
    }
}

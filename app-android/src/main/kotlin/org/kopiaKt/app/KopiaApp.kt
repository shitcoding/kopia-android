package org.kopiaKt.app

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.kopiaKt.app.worker.KopiaWorkerFactory
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class KopiaApp :
    Application(),
    Configuration.Provider {

    @Inject
    lateinit var workerFactory: KopiaWorkerFactory

    /**
     * On-demand WorkManager initialization. Paired with the manifest removing
     * `androidx.work.WorkManagerInitializer`: without that removal WorkManager self-initializes
     * through `androidx.startup` and this factory is silently ignored.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        installBouncyCastleProvider()
        workerFactory.install()
    }

    /**
     * Replace Android's built-in stripped BouncyCastle provider with the full
     * bcprov-jdk18on provider. Android ships a subset of BC that lacks algorithms
     * required by SSHJ (e.g., X25519 for Curve25519 key exchange). By removing
     * the built-in provider and inserting the full one at highest priority, all
     * crypto operations (SSH, TLS, etc.) will use the complete algorithm set.
     */
    private fun installBouncyCastleProvider() {
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}

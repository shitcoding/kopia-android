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
}

/**
 * Replaces Android's built-in stripped BouncyCastle provider with the full bcprov-jdk18on one.
 *
 * Android ships a subset of BC that lacks algorithms SSHJ needs (X25519 for Curve25519 key
 * exchange), so the full provider has to be registered. It is **appended**, not inserted first.
 *
 * It used to be inserted at position 1, which made it win every *unqualified* `getInstance` in the
 * process -- and BouncyCastle is pure Java where the platform's Conscrypt is backed by ARMv8 crypto
 * instructions. Profiled on a Nothing Phone (2) during a real 1.4 GB backup (`simpleperf`,
 * 48k samples): `AESEngine.shift` 17.2% and `AESEngine.encryptBlock` 12.4% of all CPU, ~37% once
 * the GCM tables and `Pack`/`GCMUtil` helpers are counted -- for AES-256-GCM, which this SoC
 * implements in hardware. Every unqualified lookup this codebase makes is a standard algorithm
 * (`AES/GCM/NoPadding`, `HmacSHA256`, `SHA-256`, and `X.509` / `KeyStore` / `TrustManagerFactory` /
 * `TLS` in `TlsTrust`) that Conscrypt provides and accelerates, while everything genuinely
 * BouncyCastle-only -- BLAKE2b, BLAKE3, key derivation -- instantiates BC classes directly and does
 * not consult the provider list at all. Appending therefore keeps the complete algorithm set
 * reachable (by name, which is how SSHJ asks for it) and stops it displacing accelerated
 * implementations. See task-66.
 *
 * TLS itself is unaffected either way: only `bcprov`, `bcpkix` and `bcutil` are on the classpath,
 * and none of them registers an `SSLContext` or `TrustManagerFactory` service (that is `bctls`,
 * which is not a dependency), so `TlsTrust.socketFactory` has always resolved to the platform. What
 * does move is `CertificateFactory.getInstance("X.509")` for a user-supplied root CA, from BC to
 * the platform -- which also stops handing a BC-parsed certificate to a platform
 * `TrustManagerFactory`. `TlsTrustTest` covers that path with real PEMs.
 */
internal fun installBouncyCastleProvider() {
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.addProvider(BouncyCastleProvider())
}

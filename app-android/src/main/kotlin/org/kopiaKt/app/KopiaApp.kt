package org.kopiaKt.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

@HiltAndroidApp
class KopiaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installBouncyCastleProvider()
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

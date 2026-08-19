package org.kopiaKt.app

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import java.security.Security
import javax.crypto.Cipher

/**
 * The full BouncyCastle provider has to be registered -- SSHJ needs algorithms Android's stripped
 * copy does not carry -- but it must not be registered *ahead* of the platform's own providers.
 *
 * It was, for a long time, and the cost was measured rather than argued: profiling a real 1.4 GB
 * backup on a Nothing Phone (2) put `AESEngine.shift` and `AESEngine.encryptBlock` at ~30% of all
 * CPU on their own, because every unqualified `Cipher.getInstance("AES/GCM/NoPadding")` in the
 * process resolved to pure-Java BouncyCastle instead of the hardware-backed platform provider.
 *
 * Nothing else pins this, and "put BC first so SSH works" is a plausible-looking regression, so
 * these tests hold the line.
 */
class CryptoProviderOrderTest {

    @Test
    fun `the full BouncyCastle provider is registered`() {
        installBouncyCastleProvider()

        val bc = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        assertThat(bc).isNotNull()
        // X25519 is the algorithm Android's subset lacks and SSHJ needs, so it is what makes this
        // the FULL provider rather than merely a provider called "BC".
        assertThat(bc.getService("KeyAgreement", "X25519")).isNotNull()
    }

    @Test
    fun `BouncyCastle does not displace the platform for algorithms the platform accelerates`() {
        installBouncyCastleProvider()

        assertThat(Security.getProviders().first().name).isNotEqualTo(BouncyCastleProvider.PROVIDER_NAME)
        // The three unqualified lookups this codebase actually makes. On Android each resolves to
        // Conscrypt, which uses the SoC's AES and SHA instructions; on this JVM, to the platform
        // provider. Either way: not BouncyCastle.
        assertThat(Cipher.getInstance("AES/GCM/NoPadding").provider.name)
            .isNotEqualTo(BouncyCastleProvider.PROVIDER_NAME)
        assertThat(javax.crypto.Mac.getInstance("HmacSHA256").provider.name)
            .isNotEqualTo(BouncyCastleProvider.PROVIDER_NAME)
        assertThat(java.security.MessageDigest.getInstance("SHA-256").provider.name)
            .isNotEqualTo(BouncyCastleProvider.PROVIDER_NAME)
    }
}

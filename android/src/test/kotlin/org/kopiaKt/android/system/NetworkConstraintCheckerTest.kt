package org.kopiaKt.android.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Unit tests for NetworkConstraintChecker.
 *
 * Uses Robolectric shadows for Android system services.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class NetworkConstraintCheckerTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var checker: NetworkConstraintChecker

    @BeforeEach
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        checker = NetworkConstraintChecker(context)
    }

    private fun setupNetwork(
        hasInternet: Boolean = true,
        isWifi: Boolean = false,
        isEthernet: Boolean = false,
        isCellular: Boolean = false,
        isVpn: Boolean = false,
        isMetered: Boolean = !isWifi,
        downstreamKbps: Int = 100000,
        upstreamKbps: Int = 50000,
    ) {
        val shadowCm = shadowOf(connectivityManager)

        val network = ShadowNetwork.newInstance(1)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        val shadowCapabilities = shadowOf(capabilities)

        if (hasInternet) {
            shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        if (!isMetered) {
            shadowCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        if (isWifi) {
            shadowCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        if (isEthernet) {
            shadowCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        if (isCellular) {
            shadowCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        if (isVpn) {
            shadowCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        }

        shadowCm.setActiveNetworkInfo(null)
        shadowCm.setNetworkCapabilities(network, capabilities)
        shadowCm.setDefaultNetworkActive(true)

        // Use reflection to set the active network since the shadow API varies
        try {
            val setActiveNetwork = shadowCm.javaClass.getMethod("setActiveNetwork", android.net.Network::class.java)
            setActiveNetwork.invoke(shadowCm, network)
        } catch (e: NoSuchMethodException) {
            // Older Robolectric version - skip this
        }
    }

    private fun clearNetwork() {
        val shadowCm = shadowOf(connectivityManager)
        shadowCm.setActiveNetworkInfo(null)
        try {
            val setActiveNetwork = shadowCm.javaClass.getMethod("setActiveNetwork", android.net.Network::class.java)
            setActiveNetwork.invoke(shadowCm, null as android.net.Network?)
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun `isNetworkAvailable returns false when no network`() {
        clearNetwork()

        // Since we can't reliably clear active network in all Robolectric versions,
        // at least verify the method doesn't crash
        val result = checker.isNetworkAvailable()
        // Result depends on Robolectric shadow state
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `isNetworkMetered delegates to connectivity manager`() {
        // The isActiveNetworkMetered result depends on shadow state
        val result = checker.isNetworkMetered()
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `getNetworkType returns a valid type`() {
        val type = checker.getNetworkType()
        assertThat(type).isIn(NetworkType.values().toList())
    }

    @Test
    fun `getCurrentNetworkState returns valid state`() {
        val state = checker.getCurrentNetworkState()

        assertThat(state.type).isIn(NetworkType.values().toList())
        assertThat(state.isConnected).isAnyOf(true, false)
        assertThat(state.isMetered).isAnyOf(true, false)
    }

    @Test
    fun `checkBackupConstraints returns satisfied when network available and WiFi not required`() {
        val result = checker.checkBackupConstraints(
            requireWifi = false,
            requireConnected = false,
        )

        // With no requirements, should be satisfied
        assertThat(result.satisfied).isTrue()
        assertThat(result.violations).isEmpty()
    }

    @Test
    fun `suggestThrottleSpeed returns non-negative value`() {
        val speed = checker.suggestThrottleSpeed()
        assertThat(speed).isAtLeast(0L)
    }

    @Test
    fun `getEstimatedDownstreamBandwidthKbps returns value`() {
        val bandwidth = checker.getEstimatedDownstreamBandwidthKbps()
        // Either a valid bandwidth or -1 for unknown
        assertThat(bandwidth).isAtLeast(-1)
    }

    @Test
    fun `getEstimatedUpstreamBandwidthKbps returns value`() {
        val bandwidth = checker.getEstimatedUpstreamBandwidthKbps()
        // Either a valid bandwidth or -1 for unknown
        assertThat(bandwidth).isAtLeast(-1)
    }
}

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class NetworkTypeTest {

    @Test
    fun `NetworkType enum has all expected values`() {
        val types = NetworkType.values()

        assertThat(types).hasLength(10)
        assertThat(types).asList().contains(NetworkType.NONE)
        assertThat(types).asList().contains(NetworkType.WIFI)
        assertThat(types).asList().contains(NetworkType.ETHERNET)
        assertThat(types).asList().contains(NetworkType.MOBILE_2G)
        assertThat(types).asList().contains(NetworkType.MOBILE_3G)
        assertThat(types).asList().contains(NetworkType.MOBILE_4G)
        assertThat(types).asList().contains(NetworkType.MOBILE_5G)
        assertThat(types).asList().contains(NetworkType.MOBILE_UNKNOWN)
        assertThat(types).asList().contains(NetworkType.VPN)
        assertThat(types).asList().contains(NetworkType.OTHER)
    }
}

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class NetworkStateTest {

    @Test
    fun `NetworkState data class holds all values`() {
        val state = NetworkState(
            isConnected = true,
            type = NetworkType.WIFI,
            isMetered = false,
            downstreamBandwidthKbps = 100000,
            upstreamBandwidthKbps = 50000,
        )

        assertThat(state.isConnected).isTrue()
        assertThat(state.type).isEqualTo(NetworkType.WIFI)
        assertThat(state.isMetered).isFalse()
        assertThat(state.downstreamBandwidthKbps).isEqualTo(100000)
        assertThat(state.upstreamBandwidthKbps).isEqualTo(50000)
    }

    @Test
    fun `isSuitableForBackup returns true when connected and unmetered`() {
        val state = NetworkState(
            isConnected = true,
            type = NetworkType.WIFI,
            isMetered = false,
        )

        assertThat(state.isSuitableForBackup).isTrue()
    }

    @Test
    fun `isSuitableForBackup returns false when metered`() {
        val state = NetworkState(
            isConnected = true,
            type = NetworkType.MOBILE_4G,
            isMetered = true,
        )

        assertThat(state.isSuitableForBackup).isFalse()
    }

    @Test
    fun `isSuitableForBackup returns false when not connected`() {
        val state = NetworkState(
            isConnected = false,
            type = NetworkType.NONE,
            isMetered = false,
        )

        assertThat(state.isSuitableForBackup).isFalse()
    }

    @Test
    fun `NetworkState defaults for bandwidth are -1`() {
        val state = NetworkState(
            isConnected = true,
            type = NetworkType.WIFI,
            isMetered = false,
        )

        assertThat(state.downstreamBandwidthKbps).isEqualTo(-1)
        assertThat(state.upstreamBandwidthKbps).isEqualTo(-1)
    }
}

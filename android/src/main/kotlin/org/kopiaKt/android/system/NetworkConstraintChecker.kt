package org.kopiaKt.android.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Helper class for checking network connectivity and constraints.
 *
 * Provides utilities for:
 * - Checking current network connectivity
 * - Determining if network is metered (mobile data)
 * - Monitoring network state changes
 * - Checking bandwidth estimates
 */
class NetworkConstraintChecker(private val context: Context) {

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService<ConnectivityManager>()
    }

    /**
     * Checks if any network is available.
     *
     * @return true if connected to any network
     */
    fun isNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /**
     * Checks if the current network is WiFi (unmetered).
     *
     * @return true if connected via WiFi
     */
    fun isWifiConnected(): Boolean {
        val cm = connectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
    }

    /**
     * Checks if the current network is metered (typically mobile data).
     *
     * @return true if the network is metered
     */
    fun isNetworkMetered(): Boolean = connectivityManager?.isActiveNetworkMetered == true

    /**
     * Checks if connected to unmetered (WiFi) network.
     *
     * @return true if connected to unmetered network
     */
    fun isUnmeteredNetworkAvailable(): Boolean {
        val cm = connectivityManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } else {
            isWifiConnected()
        }
    }

    /**
     * Gets the current network type.
     *
     * @return NetworkType representing the current connection
     */
    fun getNetworkType(): NetworkType {
        val cm = connectivityManager ?: return NetworkType.NONE

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return NetworkType.NONE
            val capabilities = cm.getNetworkCapabilities(network) ?: return NetworkType.NONE

            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    getMobileNetworkType()
                }
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
                else -> NetworkType.OTHER
            }
        } else {
            @Suppress("DEPRECATION")
            when (cm.activeNetworkInfo?.type) {
                ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                ConnectivityManager.TYPE_MOBILE -> getMobileNetworkType()
                ConnectivityManager.TYPE_ETHERNET -> NetworkType.ETHERNET
                ConnectivityManager.TYPE_VPN -> NetworkType.VPN
                else -> NetworkType.NONE
            }
        }
    }

    private fun getMobileNetworkType(): NetworkType {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return NetworkType.MOBILE_UNKNOWN

        return when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            -> NetworkType.MOBILE_2G

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            -> NetworkType.MOBILE_3G

            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_IWLAN,
            -> NetworkType.MOBILE_4G

            TelephonyManager.NETWORK_TYPE_NR -> NetworkType.MOBILE_5G

            else -> NetworkType.MOBILE_UNKNOWN
        }
    }

    /**
     * Gets estimated downstream bandwidth in Kbps.
     *
     * @return Estimated bandwidth in Kbps, or -1 if unknown
     */
    fun getEstimatedDownstreamBandwidthKbps(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cm = connectivityManager ?: return -1
            val network = cm.activeNetwork ?: return -1
            val capabilities = cm.getNetworkCapabilities(network) ?: return -1
            return capabilities.linkDownstreamBandwidthKbps
        }
        return -1
    }

    /**
     * Gets estimated upstream bandwidth in Kbps.
     *
     * @return Estimated bandwidth in Kbps, or -1 if unknown
     */
    fun getEstimatedUpstreamBandwidthKbps(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cm = connectivityManager ?: return -1
            val network = cm.activeNetwork ?: return -1
            val capabilities = cm.getNetworkCapabilities(network) ?: return -1
            return capabilities.linkUpstreamBandwidthKbps
        }
        return -1
    }

    /**
     * Observes network connectivity changes.
     *
     * @return Flow emitting NetworkState on each change
     */
    fun observeNetworkState(): Flow<NetworkState> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(NetworkState(isConnected = false, type = NetworkType.NONE, isMetered = true))
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getCurrentNetworkState())
            }

            override fun onLost(network: Network) {
                trySend(NetworkState(isConnected = false, type = NetworkType.NONE, isMetered = true))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(getCurrentNetworkState())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, callback)

        // Emit initial state
        trySend(getCurrentNetworkState())

        awaitClose {
            cm.unregisterNetworkCallback(callback)
        }
    }

    /**
     * Gets the current network state.
     *
     * @return NetworkState representing current connectivity
     */
    fun getCurrentNetworkState(): NetworkState = NetworkState(
        isConnected = isNetworkAvailable(),
        type = getNetworkType(),
        isMetered = isNetworkMetered(),
        downstreamBandwidthKbps = getEstimatedDownstreamBandwidthKbps(),
        upstreamBandwidthKbps = getEstimatedUpstreamBandwidthKbps(),
    )

    /**
     * Checks if network constraints are satisfied for backup.
     *
     * @param requireWifi Whether WiFi (unmetered) network is required
     * @param requireConnected Whether any network connection is required
     * @return ConstraintCheckResult indicating if constraints are met
     */
    fun checkBackupConstraints(
        requireWifi: Boolean = true,
        requireConnected: Boolean = true,
    ): ConstraintCheckResult {
        val violations = mutableListOf<String>()

        if (requireConnected && !isNetworkAvailable()) {
            violations.add("No network connection available")
        }

        if (requireWifi && !isUnmeteredNetworkAvailable()) {
            val networkType = getNetworkType()
            violations.add("WiFi required, but connected via $networkType")
        }

        return ConstraintCheckResult(
            satisfied = violations.isEmpty(),
            violations = violations,
        )
    }

    /**
     * Suggests a throttle speed based on network type.
     *
     * @return Suggested upload speed limit in bytes per second, or 0 for no limit
     */
    fun suggestThrottleSpeed(): Long = when (getNetworkType()) {
        NetworkType.WIFI, NetworkType.ETHERNET -> 0L // No limit
        NetworkType.MOBILE_5G -> 50 * 1024 * 1024L // 50 MB/s
        NetworkType.MOBILE_4G -> 10 * 1024 * 1024L // 10 MB/s
        NetworkType.MOBILE_3G -> 1 * 1024 * 1024L // 1 MB/s
        NetworkType.MOBILE_2G -> 100 * 1024L // 100 KB/s
        NetworkType.MOBILE_UNKNOWN -> 5 * 1024 * 1024L // 5 MB/s conservative
        NetworkType.VPN -> 0L // No limit, VPN handles its own throttling
        NetworkType.OTHER -> 5 * 1024 * 1024L // 5 MB/s conservative
        NetworkType.NONE -> 0L
    }
}

/**
 * Network connection types.
 */
enum class NetworkType {
    NONE,
    WIFI,
    ETHERNET,
    MOBILE_2G,
    MOBILE_3G,
    MOBILE_4G,
    MOBILE_5G,
    MOBILE_UNKNOWN,
    VPN,
    OTHER,
}

/**
 * Current network state.
 */
data class NetworkState(
    /** Whether any network is connected */
    val isConnected: Boolean,
    /** Type of network connection */
    val type: NetworkType,
    /** Whether the network is metered (mobile data) */
    val isMetered: Boolean,
    /** Estimated downstream bandwidth in Kbps (-1 if unknown) */
    val downstreamBandwidthKbps: Int = -1,
    /** Estimated upstream bandwidth in Kbps (-1 if unknown) */
    val upstreamBandwidthKbps: Int = -1,
) {
    /**
     * Whether this network is suitable for backup without user confirmation.
     */
    val isSuitableForBackup: Boolean
        get() = isConnected && !isMetered
}

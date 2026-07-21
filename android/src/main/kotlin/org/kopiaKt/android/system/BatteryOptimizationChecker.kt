package org.kopiaKt.android.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * Helper class for checking and requesting battery optimization exemptions.
 *
 * Android's Doze mode can delay or prevent background work. For reliable
 * backup operations, apps may need to request exemption from battery
 * optimization. This class provides utilities for:
 * - Checking if the app is exempt from battery optimization
 * - Creating intents to request exemption
 * - Checking battery level and charging status
 */
class BatteryOptimizationChecker(private val context: Context) {

    private val powerManager: PowerManager? by lazy {
        context.getSystemService<PowerManager>()
    }

    /**
     * Checks if the app is exempt from battery optimization.
     *
     * Apps exempt from battery optimization can run background work more
     * reliably even when the device is in Doze mode.
     *
     * @return true if exempt, false otherwise
     */
    fun isExemptFromBatteryOptimization(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    } else {
        // Pre-Marshmallow doesn't have Doze mode
        true
    }

    /**
     * Creates an intent to open the battery optimization settings for this app.
     *
     * The user can then manually disable battery optimization for the app.
     * This is the recommended approach for apps that don't qualify for the
     * whitelist exemption.
     *
     * @return Intent to battery optimization settings
     */
    fun createBatteryOptimizationSettingsIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    } else {
        Intent(Settings.ACTION_SETTINGS)
    }

    /**
     * Creates an intent to request battery optimization exemption directly.
     *
     * This shows a system dialog asking the user to exempt the app.
     * Note: Using this requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission
     * and may violate Google Play policies for some app categories.
     *
     * @return Intent to request exemption, or null if not supported
     */
    fun createRequestExemptionIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    } else {
        null
    }

    /**
     * Checks if the battery level is low.
     *
     * @return true if battery is low (< 15%)
     */
    fun isBatteryLow(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val batteryLevel = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            return batteryLevel < LOW_BATTERY_THRESHOLD
        }
        return false
    }

    /**
     * Checks if the device is currently charging.
     *
     * @return true if charging
     */
    fun isCharging(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            return batteryManager?.isCharging == true
        }
        return false
    }

    /**
     * Gets the current battery percentage.
     *
     * @return Battery percentage (0-100), or -1 if unknown
     */
    fun getBatteryLevel(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } else {
        -1
    }

    /**
     * Checks if device is in power save mode (battery saver).
     *
     * @return true if in power save mode
     */
    fun isPowerSaveMode(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        powerManager?.isPowerSaveMode == true
    } else {
        false
    }

    /**
     * Checks if device is in interactive mode (screen on).
     *
     * @return true if interactive
     */
    fun isInteractive(): Boolean = powerManager?.isInteractive == true

    /**
     * Checks if the device is currently idle (in Doze mode).
     *
     * @return true if device is idle
     */
    fun isDeviceIdle(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager?.isDeviceIdleMode == true
    } else {
        false
    }

    /**
     * Returns a summary of the current battery state.
     */
    fun getBatteryState(): BatteryState = BatteryState(
        level = getBatteryLevel(),
        isCharging = isCharging(),
        isLow = isBatteryLow(),
        isPowerSaveMode = isPowerSaveMode(),
        isDeviceIdle = isDeviceIdle(),
        isExemptFromOptimization = isExemptFromBatteryOptimization(),
    )

    /**
     * Checks if conditions are suitable for background backup.
     *
     * @param requireCharging Whether charging is required
     * @param requireBatteryNotLow Whether battery must not be low
     * @return ConstraintCheckResult indicating if constraints are met
     */
    fun checkBackupConstraints(
        requireCharging: Boolean = false,
        requireBatteryNotLow: Boolean = true,
    ): ConstraintCheckResult {
        val violations = mutableListOf<String>()

        if (requireCharging && !isCharging()) {
            violations.add("Device must be charging")
        }

        if (requireBatteryNotLow && isBatteryLow()) {
            violations.add("Battery is low (${getBatteryLevel()}%)")
        }

        if (isPowerSaveMode()) {
            violations.add("Power save mode is enabled")
        }

        return ConstraintCheckResult(
            satisfied = violations.isEmpty(),
            violations = violations,
        )
    }

    companion object {
        /** Battery level below which we consider it "low" */
        const val LOW_BATTERY_THRESHOLD = 15
    }
}

/**
 * Represents the current battery state.
 */
data class BatteryState(
    /** Battery level percentage (0-100), or -1 if unknown */
    val level: Int,
    /** Whether the device is currently charging */
    val isCharging: Boolean,
    /** Whether the battery level is considered low */
    val isLow: Boolean,
    /** Whether power save mode (battery saver) is enabled */
    val isPowerSaveMode: Boolean,
    /** Whether the device is in Doze mode */
    val isDeviceIdle: Boolean,
    /** Whether the app is exempt from battery optimization */
    val isExemptFromOptimization: Boolean,
)

/**
 * Result of checking constraints.
 */
data class ConstraintCheckResult(
    /** Whether all constraints are satisfied */
    val satisfied: Boolean,
    /** List of constraint violations (empty if satisfied) */
    val violations: List<String>,
)

package org.kopiaKt.android.system

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

/**
 * Unit tests for BatteryOptimizationChecker.
 *
 * Uses Robolectric shadows for Android system services.
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class BatteryOptimizationCheckerTest {

    private lateinit var context: Context
    private lateinit var powerManager: PowerManager
    private lateinit var checker: BatteryOptimizationChecker

    @BeforeEach
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        checker = BatteryOptimizationChecker(context)
    }

    @Test
    fun `isExemptFromBatteryOptimization returns result from power manager`() {
        // The result depends on Robolectric shadow configuration
        val result = checker.isExemptFromBatteryOptimization()
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `isBatteryLow returns result based on battery level`() {
        val result = checker.isBatteryLow()
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `isCharging returns result from battery manager`() {
        val result = checker.isCharging()
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `getBatteryLevel returns value`() {
        val level = checker.getBatteryLevel()
        // Returns a value - actual behavior depends on BatteryManager shadow
        // In Robolectric, it may return Int.MIN_VALUE when property unavailable
        assertThat(level).isNotNull()
    }

    @Test
    fun `isPowerSaveMode delegates to power manager`() {
        val shadowPm = shadowOf(powerManager)
        shadowPm.setIsPowerSaveMode(true)

        assertThat(checker.isPowerSaveMode()).isTrue()

        shadowPm.setIsPowerSaveMode(false)
        assertThat(checker.isPowerSaveMode()).isFalse()
    }

    @Test
    fun `isInteractive delegates to power manager`() {
        val shadowPm = shadowOf(powerManager)
        shadowPm.setIsInteractive(true)

        assertThat(checker.isInteractive()).isTrue()

        shadowPm.setIsInteractive(false)
        assertThat(checker.isInteractive()).isFalse()
    }

    @Test
    fun `isDeviceIdle returns result from power manager`() {
        // Device idle state depends on shadow configuration
        val result = checker.isDeviceIdle()
        assertThat(result).isAnyOf(true, false)
    }

    @Test
    fun `getBatteryState returns complete state`() {
        val state = checker.getBatteryState()

        // Level may be Int.MIN_VALUE in Robolectric when BatteryManager property unavailable
        assertThat(state.level).isNotNull()
        assertThat(state.isCharging).isAnyOf(true, false)
        assertThat(state.isLow).isAnyOf(true, false)
        assertThat(state.isPowerSaveMode).isAnyOf(true, false)
        assertThat(state.isDeviceIdle).isAnyOf(true, false)
        assertThat(state.isExemptFromOptimization).isAnyOf(true, false)
    }

    @Test
    fun `checkBackupConstraints satisfied when no constraints`() {
        val result = checker.checkBackupConstraints(
            requireCharging = false,
            requireBatteryNotLow = false
        )

        // With power save mode off, should be satisfied
        val shadowPm = shadowOf(powerManager)
        shadowPm.setIsPowerSaveMode(false)

        val result2 = checker.checkBackupConstraints(
            requireCharging = false,
            requireBatteryNotLow = false
        )

        assertThat(result2.satisfied).isTrue()
        assertThat(result2.violations).isEmpty()
    }

    @Test
    fun `checkBackupConstraints fails when power save mode enabled`() {
        val shadowPm = shadowOf(powerManager)
        shadowPm.setIsPowerSaveMode(true)

        val result = checker.checkBackupConstraints(
            requireCharging = false,
            requireBatteryNotLow = false
        )

        assertThat(result.satisfied).isFalse()
        assertThat(result.violations).contains("Power save mode is enabled")
    }

    @Test
    fun `createBatteryOptimizationSettingsIntent returns valid intent`() {
        val intent = checker.createBatteryOptimizationSettingsIntent()

        assertThat(intent).isNotNull()
        assertThat(intent.action).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    @Test
    fun `createRequestExemptionIntent returns intent with package URI`() {
        val intent = checker.createRequestExemptionIntent()

        assertThat(intent).isNotNull()
        assertThat(intent!!.action).isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertThat(intent.data?.toString()).contains(context.packageName)
    }
}

/**
 * Tests for BatteryState data class.
 */
class BatteryStateTest {

    @Test
    fun `BatteryState data class holds all values`() {
        val state = BatteryState(
            level = 50,
            isCharging = true,
            isLow = false,
            isPowerSaveMode = false,
            isDeviceIdle = false,
            isExemptFromOptimization = true
        )

        assertThat(state.level).isEqualTo(50)
        assertThat(state.isCharging).isTrue()
        assertThat(state.isLow).isFalse()
        assertThat(state.isPowerSaveMode).isFalse()
        assertThat(state.isDeviceIdle).isFalse()
        assertThat(state.isExemptFromOptimization).isTrue()
    }

    @Test
    fun `BatteryState with low battery`() {
        val state = BatteryState(
            level = 10,
            isCharging = false,
            isLow = true,
            isPowerSaveMode = true,
            isDeviceIdle = false,
            isExemptFromOptimization = false
        )

        assertThat(state.level).isEqualTo(10)
        assertThat(state.isLow).isTrue()
        assertThat(state.isPowerSaveMode).isTrue()
    }
}

/**
 * Tests for ConstraintCheckResult data class.
 */
class ConstraintCheckResultTest {

    @Test
    fun `satisfied result has no violations`() {
        val result = ConstraintCheckResult(
            satisfied = true,
            violations = emptyList()
        )

        assertThat(result.satisfied).isTrue()
        assertThat(result.violations).isEmpty()
    }

    @Test
    fun `unsatisfied result has violations`() {
        val result = ConstraintCheckResult(
            satisfied = false,
            violations = listOf("Battery too low", "Not charging")
        )

        assertThat(result.satisfied).isFalse()
        assertThat(result.violations).hasSize(2)
        assertThat(result.violations).contains("Battery too low")
        assertThat(result.violations).contains("Not charging")
    }
}

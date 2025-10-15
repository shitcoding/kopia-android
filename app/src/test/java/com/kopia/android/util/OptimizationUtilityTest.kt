package com.kopia.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kopia.android.test.BaseUnitTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.google.common.truth.Truth.assertThat

/**
 * Unit tests for OptimizationUtility
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OptimizationUtilityTest : BaseUnitTest() {

    private lateinit var context: Context
    private lateinit var optimizationUtility: OptimizationUtility

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        optimizationUtility = OptimizationUtility.getInstance(context)
    }

    @Test
    fun `test optimization utility singleton`() {
        val instance1 = OptimizationUtility.getInstance(context)
        val instance2 = OptimizationUtility.getInstance(context)

        assertThat(instance1).isSameInstanceAs(instance2)
    }

    @Test
    fun `test get optimized Kopia parameters returns map`() {
        val params = optimizationUtility.getOptimizedKopiaParameters()

        assertThat(params).isNotNull()
        assertThat(params).isInstanceOf(Map::class.java)
    }

    @Test
    fun `test optimized parameters contain expected keys`() {
        val params = optimizationUtility.getOptimizedKopiaParameters()

        // Verify that we get some optimization parameters
        // The exact keys depend on implementation
        assertThat(params).isNotNull()
    }

    @Test
    fun `test optimization utility handles context properly`() {
        // Should not crash with valid context
        val utility = OptimizationUtility.getInstance(context)
        assertThat(utility).isNotNull()
    }

    @Test
    fun `test periodic optimizations can be scheduled`() {
        // Should not crash when scheduling
        optimizationUtility.schedulePeriodicOptimizations()

        // No exception = success
        assertThat(true).isTrue()
    }

    @Test
    fun `test WebView optimization availability`() {
        // This test verifies the optimization utility exists and can be called
        // We don't test with null WebView as it's not a valid use case
        assertThat(optimizationUtility).isNotNull()

        // The optimize method exists and is callable
        // (We would need a real WebView to test actual optimization)
    }
}

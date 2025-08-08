package com.kopia.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ActivityTestRule
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Base class for instrumented tests in the Kopia Android app.
 * Provides common setup for UI testing with Espresso and UI Automator.
 */
@RunWith(AndroidJUnit4::class)
abstract class BaseInstrumentedTest {
    
    // Get the context of the app under test
    protected val appContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
    
    // UI Automator device instance for system interactions
    protected lateinit var device: UiDevice
    
    @Before
    fun setupDevice() {
        // Initialize UiDevice instance
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }
    
    /**
     * Helper method to grant storage permissions programmatically
     * This is useful for tests that require Storage Access Framework
     */
    protected fun grantStoragePermissions() {
        // Use UiAutomator to grant permissions if needed
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${appContext.packageName} android.permission.READ_EXTERNAL_STORAGE"
            ).close()
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${appContext.packageName} android.permission.WRITE_EXTERNAL_STORAGE"
            ).close()
        }
    }
}

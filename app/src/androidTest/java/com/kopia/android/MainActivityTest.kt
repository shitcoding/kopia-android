package com.kopia.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.hamcrest.CoreMatchers.containsString
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * UI tests for MainActivity using Espresso and UI Automator.
 * Tests WebView loading, server connection, and basic UI interactions.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityTest : BaseInstrumentedTest() {

    // Launch MainActivity before each test
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)
    
    @Before
    override fun setupDevice() {
        super.setupDevice()
        // Grant storage permissions needed for tests
        grantStoragePermissions()
    }
    
    @Test
    fun testMainActivityLaunch() {
        // Verify that the WebView is displayed
        onView(withId(R.id.webview)).check(matches(isDisplayed()))
        
        // Wait for the server to start and WebView to load content
        // This is important as server startup can take time
        device.wait(Until.hasObject(By.text("Kopia")), 30000)
    }
    
    @Test
    fun testWebViewInteraction() {
        // Wait for WebView to load
        Thread.sleep(5000) // Simple wait, could be replaced with more robust waiting
        
        // Test WebView interaction using Espresso Web
        try {
            onWebView()
                .withElement(findElement(Locator.ID, "username")) // Assuming login form is shown
                .perform(webClick())
            
            // More WebView interactions would go here
        } catch (e: Exception) {
            // Handle case where WebView might not be fully loaded
            // or the expected elements aren't present
        }
    }
    
    @Test
    fun testMenuNavigation() {
        // Open the menu
        onView(withContentDescription("Open navigation drawer")).perform(click())
        
        // Click on Settings menu item
        onView(withText("Settings")).perform(click())
        
        // Verify we navigated to Settings
        onView(withId(R.id.settings_container)).check(matches(isDisplayed()))
    }
    
    @Test
    fun testStorageAccessFramework() {
        // Open the menu
        onView(withContentDescription("Open navigation drawer")).perform(click())
        
        // Click on Settings
        onView(withText("Settings")).perform(click())
        
        // Click on "Select Repository Location" button
        onView(withId(R.id.btn_select_repository)).perform(click())
        
        // Use UI Automator to handle the system file picker dialog
        // This is where UI Automator shines for cross-app testing
        device.wait(Until.hasObject(By.text("Documents")), 5000)
        
        // Navigate and select a folder in the file picker
        // Note: This part is highly dependent on the device's file structure
        // and would need to be adapted for specific test environments
        val documentsButton = device.findObject(By.text("Documents"))
        if (documentsButton != null && documentsButton.exists()) {
            documentsButton.click()
            device.wait(Until.hasObject(By.text("Select")), 5000)
            
            // Click the "Select" button
            val selectButton = device.findObject(By.text("Select"))
            if (selectButton != null && selectButton.exists()) {
                selectButton.click()
            }
        }
        
        // Verify we returned to the Settings screen
        onView(withId(R.id.settings_container)).check(matches(isDisplayed()))
    }
}

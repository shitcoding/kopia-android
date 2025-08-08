package com.kopia.android.diagnostic

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.kopia.android.BaseInstrumentedTest
import com.kopia.android.R
import org.hamcrest.CoreMatchers.containsString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for DiagnosticActivity using Espresso.
 * Tests diagnostic data collection, workflow validation, and report generation.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DiagnosticActivityTest : BaseInstrumentedTest() {

    @get:Rule
    val activityRule = ActivityScenarioRule(DiagnosticActivity::class.java)

    @Test
    fun testDeviceInfoCollection() {
        // Verify that device info is displayed
        onView(withId(R.id.device_info_text))
            .check(matches(isDisplayed()))
            .check(matches(withText(containsString("Device:"))))
    }

    @Test
    fun testPerformanceDataCollection() {
        // Click the collect performance data button
        onView(withId(R.id.btn_collect_performance))
            .perform(scrollTo(), click())

        // Wait for data collection to complete
        Thread.sleep(3000)

        // Verify that performance data is displayed
        onView(withId(R.id.performance_data_text))
            .check(matches(isDisplayed()))
            .check(matches(withText(containsString("Memory:"))))
    }

    @Test
    fun testRunTests() {
        // Click the run tests button
        onView(withId(R.id.btn_run_tests))
            .perform(scrollTo(), click())

        // Wait for tests to complete
        Thread.sleep(5000)

        // Verify that test results are displayed
        onView(withId(R.id.test_results_text))
            .check(matches(isDisplayed()))
            .check(matches(withText(containsString("Test Results:"))))
    }

    @Test
    fun testWorkflowValidation() {
        // Click the validate workflows button
        onView(withId(R.id.btn_validate_workflows))
            .perform(scrollTo(), click())

        // Wait for validation to complete
        Thread.sleep(5000)

        // Verify that workflow validation results are displayed
        onView(withId(R.id.workflow_validation_text))
            .check(matches(isDisplayed()))
            .check(matches(withText(containsString("Workflow Validation:"))))
    }

    @Test
    fun testShareReport() {
        // First collect some data
        onView(withId(R.id.btn_collect_performance))
            .perform(scrollTo(), click())
        Thread.sleep(3000)

        // Click the share report button
        onView(withId(R.id.btn_share_report))
            .perform(scrollTo(), click())

        // Verify that the share intent is launched
        // This is challenging to test in an automated way, as it involves system UI
        // We could use UI Automator to verify the share sheet appears
        device.wait(Until.hasObject(By.text("Share")), 3000)
        
        // Note: In a real test, we might need to handle the share dialog differently
        // or mock the sharing functionality
    }
}

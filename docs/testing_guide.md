# Kopia Android Testing Guide

This document provides comprehensive information about the automated testing framework for the Kopia Android app, including how to run tests, interpret results, and extend the testing framework.

## Table of Contents

1. [Testing Framework Overview](#testing-framework-overview)
2. [Test Types](#test-types)
3. [Running Tests](#running-tests)
4. [Test Reports](#test-reports)
5. [CI Integration](#ci-integration)
6. [Adding New Tests](#adding-new-tests)
7. [Testing Best Practices](#testing-best-practices)
8. [Troubleshooting](#troubleshooting)

## Testing Framework Overview

The Kopia Android app uses a multi-layered testing approach with the following components:

- **JUnit**: Base framework for all tests
- **Mockito**: For mocking dependencies in unit tests
- **Espresso**: For UI testing within the app
- **UI Automator**: For system-level interactions (especially SAF)
- **Custom Test Runner**: For configuring the test environment

The testing framework is designed to validate all aspects of the app, including:

- Core functionality (server startup, repository management)
- UI interactions (WebView, navigation)
- System interactions (Storage Access Framework)
- Error handling and recovery
- Performance and resource usage

## Test Types

### Unit Tests

Located in `app/src/test/java/com/kopia/android/`, these tests validate individual components in isolation. They include:

- **KopiaServerServiceTest**: Tests server initialization, startup, and shutdown
- **WorkflowValidatorTest**: Tests validation of all key user workflows

Unit tests run on the JVM and do not require an Android device or emulator.

### Instrumented Tests

Located in `app/src/androidTest/java/com/kopia/android/`, these tests validate app behavior on a real device or emulator. They include:

- **MainActivityTest**: Tests WebView loading, server connection, and basic UI interactions
- **DiagnosticActivityTest**: Tests diagnostic data collection and reporting

Instrumented tests require an Android device or emulator to run.

## Running Tests

### Using the Test Script

The easiest way to run all tests is using the provided script:

```bash
./run_tests.sh
```

This script will:
1. Clean the project
2. Run unit tests
3. Start an emulator if needed
4. Run instrumented tests
5. Generate test reports

### Using Gradle

You can also run tests directly with Gradle:

```bash
# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Generate combined test report
./gradlew testReport
```

### Using Android Studio

1. Right-click on a test class or method and select "Run"
2. To run all tests, right-click on the test directory and select "Run Tests"

## Test Reports

Test reports are generated in HTML format and can be found at:

- **Unit Tests**: `app/build/reports/tests/testDebugUnitTest/index.html`
- **Instrumented Tests**: `app/build/reports/androidTests/connected/index.html`
- **Combined Report**: `app/build/reports/combined-tests/index.html`

The combined report provides links to both unit and instrumented test reports for easy access.

## CI Integration

The project includes GitHub Actions workflows for continuous integration testing:

- **Unit Tests**: Run on every push and pull request
- **Instrumented Tests**: Run on every push and pull request using an Android emulator
- **Build**: Creates a debug APK after tests pass

The workflow configuration is located in `.github/workflows/android-ci.yml`.

## Adding New Tests

### Adding a Unit Test

1. Create a new test class in `app/src/test/java/com/kopia/android/`
2. Extend `BaseUnitTest` for common setup
3. Use JUnit annotations (`@Test`, `@Before`, etc.)
4. Use Mockito for mocking dependencies

Example:

```kotlin
@RunWith(RobolectricTestRunner::class)
class MyComponentTest : BaseUnitTest() {
    @Test
    fun `test my functionality`() {
        // Test code here
    }
}
```

### Adding an Instrumented Test

1. Create a new test class in `app/src/androidTest/java/com/kopia/android/`
2. Extend `BaseInstrumentedTest` for common setup
3. Use Espresso for UI interactions
4. Use UI Automator for system interactions

Example:

```kotlin
@RunWith(AndroidJUnit4::class)
class MyActivityTest : BaseInstrumentedTest() {
    @get:Rule
    val activityRule = ActivityScenarioRule(MyActivity::class.java)
    
    @Test
    fun testMyFeature() {
        // Test code here using Espresso
        onView(withId(R.id.my_button)).perform(click())
    }
}
```

## Testing Best Practices

1. **Test One Thing Per Test**: Each test method should validate a single aspect of functionality
2. **Use Descriptive Test Names**: Name tests to clearly describe what they're testing
3. **Mock External Dependencies**: Use Mockito to isolate the component being tested
4. **Handle Asynchronous Operations**: Use `IdlingResource` or explicit waits for async operations
5. **Clean Up After Tests**: Reset state in `@After` methods to avoid test interdependencies
6. **Test Edge Cases**: Include tests for error conditions and edge cases
7. **Keep Tests Fast**: Minimize setup and teardown time

## Troubleshooting

### Common Issues

1. **Tests Fail on CI but Pass Locally**
   - Check for device-specific issues
   - Ensure tests don't depend on specific screen sizes or Android versions
   - Add more logging to identify the issue

2. **Flaky Tests**
   - Add proper synchronization (IdlingResources, explicit waits)
   - Avoid depending on timing or external resources
   - Isolate tests from each other

3. **WebView Testing Issues**
   - Use `onWebView()` for WebView interactions
   - Add proper waits for WebView content to load
   - Use JavaScript evaluation for complex WebView testing

4. **Storage Access Framework Issues**
   - Use UI Automator for SAF interactions
   - Mock SAF interactions in unit tests
   - Add proper error handling for permission issues

### Getting Help

If you encounter issues with the testing framework, check:
- Android Studio's logcat output
- Test reports for detailed error information
- Stack traces in the CI logs

For more complex issues, consult the Android testing documentation or open an issue in the project repository.

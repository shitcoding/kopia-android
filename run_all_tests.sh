#!/bin/bash
# Comprehensive test script for Kopia Android
# Runs all tests: unit, instrumented, integration, and build verification

# Set colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Verbose mode flag
VERBOSE=0
if [[ "$1" == "--verbose" ]]; then
    VERBOSE=1
fi

# Function to print section header
print_header() {
    echo
    echo -e "${BLUE}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║  $1${NC}"
    echo -e "${BLUE}╚════════════════════════════════════════════════════════════╝${NC}"
    echo
}

# Function to print test result
print_result() {
    local test_name=$1
    local result=$2
    if [ $result -eq 0 ]; then
        echo -e "${GREEN}✓ $test_name: PASSED${NC}"
    else
        echo -e "${RED}✗ $test_name: FAILED${NC}"
    fi
}

# Start
clear
print_header "KOPIA ANDROID - COMPREHENSIVE TEST SUITE"
echo -e "${CYAN}Started at: $(date)${NC}"
echo -e "${CYAN}Test Mode: $([ $VERBOSE -eq 1 ] && echo "VERBOSE" || echo "NORMAL")${NC}"
echo

# Directory of this script
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$DIR"

# Check if Android SDK is properly set up
if [ -z "$ANDROID_HOME" ]; then
    if [ -f "local.properties" ]; then
        SDK_DIR=$(grep "sdk.dir" local.properties | cut -d'=' -f2)
        if [ -n "$SDK_DIR" ]; then
            export ANDROID_HOME="$SDK_DIR"
        fi
    fi

    if [ -z "$ANDROID_HOME" ]; then
        echo -e "${RED}Error: ANDROID_HOME not set and not found in local.properties${NC}"
        exit 1
    fi
fi

echo -e "${CYAN}Android SDK: $ANDROID_HOME${NC}"
echo

# Initialize result tracking
UNIT_TEST_RESULT=1
INSTRUMENTED_TEST_RESULT=1
BUILD_TEST_RESULT=1
STARTED_EMULATOR=0
EMULATOR_PID=""

# ==============================================================================
# PHASE 1: PROJECT CLEANUP
# ==============================================================================
print_header "PHASE 1: PROJECT CLEANUP"
echo -e "${YELLOW}Cleaning project...${NC}"

if [ $VERBOSE -eq 1 ]; then
    ./gradlew clean
else
    ./gradlew clean > /dev/null 2>&1
fi

if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Clean failed!${NC}"
    exit 1
else
    echo -e "${GREEN}✓ Project cleaned successfully${NC}"
fi

# ==============================================================================
# PHASE 2: UNIT TESTS
# ==============================================================================
print_header "PHASE 2: UNIT TESTS (JVM)"
echo -e "${YELLOW}Running unit tests on JVM...${NC}"
echo -e "  - RepositoryManagerTest"
echo -e "  - FileUtilsTest"
echo -e "  - OptimizationUtilityTest"
echo -e "  - Existing unit tests"
echo

if [ $VERBOSE -eq 1 ]; then
    ./gradlew test
else
    ./gradlew test --quiet
fi

UNIT_TEST_RESULT=$?
if [ $UNIT_TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}✓ Unit tests PASSED${NC}"
else
    echo -e "${RED}✗ Unit tests FAILED${NC}"
    echo -e "${YELLOW}  See report: app/build/reports/tests/testDebugUnitTest/index.html${NC}"
fi

# ==============================================================================
# PHASE 3: EMULATOR SETUP
# ==============================================================================
print_header "PHASE 3: EMULATOR SETUP"

# Check if emulator is already running
EMULATOR_RUNNING=$(adb devices | grep -v "List" | grep "emulator" | wc -l)
if [ $EMULATOR_RUNNING -gt 0 ]; then
    echo -e "${GREEN}✓ Emulator already running${NC}"
    adb devices
else
    echo -e "${YELLOW}Starting Pixel_9 emulator...${NC}"

    # Start emulator
    EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
    if [ ! -f "$EMULATOR_BIN" ]; then
        echo -e "${RED}✗ Emulator binary not found at $EMULATOR_BIN${NC}"
        exit 1
    fi

    # Start emulator in background
    $EMULATOR_BIN -avd Pixel_9 -no-audio -no-boot-anim -no-snapshot -no-window -gpu swiftshader_indirect > /dev/null 2>&1 &
    EMULATOR_PID=$!
    STARTED_EMULATOR=1

    echo -e "  Emulator PID: $EMULATOR_PID"
    echo -e "${YELLOW}  Waiting for emulator to boot (this may take a few minutes)...${NC}"

    # Wait for device
    adb wait-for-device

    # Wait for boot to complete
    BOOT_COMPLETED=""
    WAIT_COUNT=0
    while [ "$BOOT_COMPLETED" != "1" ] && [ $WAIT_COUNT -lt 120 ]; do
        BOOT_COMPLETED=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | tr -d '\n')
        if [ "$BOOT_COMPLETED" != "1" ]; then
            echo -ne "  Booting... ${WAIT_COUNT}s\r"
            sleep 2
            WAIT_COUNT=$((WAIT_COUNT + 2))
        fi
    done
    echo

    if [ "$BOOT_COMPLETED" == "1" ]; then
        echo -e "${GREEN}✓ Emulator booted successfully${NC}"

        # Give it a bit more time to stabilize
        echo -e "${YELLOW}  Waiting for system to stabilize...${NC}"
        sleep 10
    else
        echo -e "${RED}✗ Emulator failed to boot within timeout${NC}"
        if [ $STARTED_EMULATOR -eq 1 ] && [ -n "$EMULATOR_PID" ]; then
            kill $EMULATOR_PID 2>/dev/null
        fi
        exit 1
    fi
fi

echo -e "\n${CYAN}Emulator Details:${NC}"
adb devices -l

# ==============================================================================
# PHASE 4: INSTRUMENTED TESTS
# ==============================================================================
print_header "PHASE 4: INSTRUMENTED TESTS (ON DEVICE)"
echo -e "${YELLOW}Running instrumented tests on emulator...${NC}"
echo -e "  - FilesystemAccessTest"
echo -e "  - KopiaServerTest"
echo -e "  - MainActivityTest"
echo -e "  - IntegrationTestSuite"
echo -e "  - Existing instrumented tests"
echo

# Grant permissions beforehand
echo -e "${YELLOW}Granting required permissions...${NC}"
adb shell pm grant com.kopia.android android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
adb shell pm grant com.kopia.android android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null
adb shell pm grant com.kopia.android android.permission.POST_NOTIFICATIONS 2>/dev/null
echo -e "${GREEN}✓ Permissions granted${NC}"
echo

# Run instrumented tests
if [ $VERBOSE -eq 1 ]; then
    ./gradlew connectedAndroidTest
else
    ./gradlew connectedAndroidTest --quiet
fi

INSTRUMENTED_TEST_RESULT=$?
if [ $INSTRUMENTED_TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}✓ Instrumented tests PASSED${NC}"
else
    echo -e "${RED}✗ Instrumented tests FAILED${NC}"
    echo -e "${YELLOW}  See report: app/build/reports/androidTests/connected/index.html${NC}"
fi

# ==============================================================================
# PHASE 5: BUILD & SIGN TESTS
# ==============================================================================
print_header "PHASE 5: BUILD & SIGN VERIFICATION"
echo -e "${YELLOW}Running build and signing tests...${NC}"
echo

if [ -f "./test_build_and_sign.sh" ]; then
    if [ $VERBOSE -eq 1 ]; then
        ./test_build_and_sign.sh
    else
        ./test_build_and_sign.sh > /tmp/build_test.log 2>&1
    fi

    BUILD_TEST_RESULT=$?

    if [ $BUILD_TEST_RESULT -eq 0 ]; then
        echo -e "${GREEN}✓ Build and sign tests PASSED${NC}"
    else
        echo -e "${RED}✗ Build and sign tests FAILED${NC}"
        if [ $VERBOSE -eq 0 ]; then
            echo -e "${YELLOW}  See log: /tmp/build_test.log${NC}"
        fi
    fi
else
    echo -e "${YELLOW}⚠ Build test script not found, skipping...${NC}"
    BUILD_TEST_RESULT=2  # Skipped
fi

# ==============================================================================
# PHASE 6: CLEANUP
# ==============================================================================
print_header "PHASE 6: CLEANUP"

# Shutdown emulator if we started it
if [ $STARTED_EMULATOR -eq 1 ] && [ -n "$EMULATOR_PID" ]; then
    echo -e "${YELLOW}Shutting down emulator (PID: $EMULATOR_PID)...${NC}"

    # Try graceful shutdown first
    adb emu kill 2>/dev/null
    sleep 2

    # Force kill if still running
    if ps -p $EMULATOR_PID > /dev/null 2>&1; then
        kill $EMULATOR_PID 2>/dev/null
        sleep 2
        if ps -p $EMULATOR_PID > /dev/null 2>&1; then
            kill -9 $EMULATOR_PID 2>/dev/null
        fi
    fi

    echo -e "${GREEN}✓ Emulator shut down${NC}"
else
    echo -e "${CYAN}ℹ Emulator was already running, leaving it running${NC}"
fi

# ==============================================================================
# PHASE 7: GENERATE REPORTS
# ==============================================================================
print_header "PHASE 7: GENERATE COMBINED REPORT"
echo -e "${YELLOW}Generating test reports...${NC}"

./gradlew testReport > /dev/null 2>&1 || true

echo -e "${GREEN}✓ Reports generated${NC}"
echo
echo -e "${CYAN}Test Report Locations:${NC}"
echo -e "  Unit Tests:        file://$DIR/app/build/reports/tests/testDebugUnitTest/index.html"
echo -e "  Instrumented Tests: file://$DIR/app/build/reports/androidTests/connected/index.html"
echo -e "  Combined Report:    file://$DIR/app/build/reports/combined-tests/index.html"

# ==============================================================================
# FINAL SUMMARY
# ==============================================================================
print_header "TEST SUMMARY"

echo -e "${CYAN}Test Results:${NC}"
print_result "Unit Tests            " $UNIT_TEST_RESULT
print_result "Instrumented Tests    " $INSTRUMENTED_TEST_RESULT

if [ $BUILD_TEST_RESULT -eq 0 ]; then
    print_result "Build & Sign Tests    " $BUILD_TEST_RESULT
elif [ $BUILD_TEST_RESULT -eq 2 ]; then
    echo -e "${YELLOW}⚠ Build & Sign Tests   : SKIPPED${NC}"
else
    print_result "Build & Sign Tests    " $BUILD_TEST_RESULT
fi

echo
echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"

# Calculate overall result
if [ $UNIT_TEST_RESULT -eq 0 ] && [ $INSTRUMENTED_TEST_RESULT -eq 0 ] && ([ $BUILD_TEST_RESULT -eq 0 ] || [ $BUILD_TEST_RESULT -eq 2 ]); then
    echo -e "${GREEN}║           ALL TESTS PASSED ✓                                ║${NC}"
    OVERALL_RESULT=0
else
    echo -e "${RED}║           SOME TESTS FAILED ✗                               ║${NC}"
    OVERALL_RESULT=1
fi

echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo
echo -e "${CYAN}Completed at: $(date)${NC}"
echo

exit $OVERALL_RESULT

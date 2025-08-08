#!/bin/bash
# Script to run all tests for the Kopia Android app

# Set colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}=== Kopia Android Test Runner ===${NC}"
echo "Starting tests at $(date)"
echo

# Check if Android SDK is properly set up
if [ -z "$ANDROID_HOME" ]; then
  echo -e "${RED}Error: ANDROID_HOME environment variable is not set.${NC}"
  echo "Please set ANDROID_HOME to your Android SDK location."
  exit 1
fi

# Directory of this script
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$DIR"

# Clean project
echo -e "${YELLOW}Cleaning project...${NC}"
./gradlew clean
if [ $? -ne 0 ]; then
  echo -e "${RED}Clean failed!${NC}"
  exit 1
fi
echo -e "${GREEN}Clean successful.${NC}"
echo

# Run unit tests
echo -e "${YELLOW}Running unit tests...${NC}"
./gradlew test
TEST_RESULT=$?
if [ $TEST_RESULT -ne 0 ]; then
  echo -e "${RED}Unit tests failed!${NC}"
  # Continue with other tests even if unit tests fail
else
  echo -e "${GREEN}Unit tests passed.${NC}"
fi
echo

# Check if an emulator is running
EMULATOR_RUNNING=$(adb devices | grep -v "List" | grep "emulator" | wc -l)
if [ $EMULATOR_RUNNING -eq 0 ]; then
  echo -e "${YELLOW}No emulator detected. Starting emulator...${NC}"
  # Start an emulator in the background
  # Note: You may need to adjust this command based on your AVD name
  emulator -avd Pixel_API_30 -no-audio -no-boot-anim -no-window &
  EMULATOR_PID=$!
  
  # Wait for emulator to boot
  echo "Waiting for emulator to boot..."
  adb wait-for-device
  
  # Additional wait to ensure device is fully booted
  BOOT_COMPLETED=0
  while [ $BOOT_COMPLETED -eq 0 ]; do
    BOOT_COMPLETED=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    sleep 2
  done
  echo -e "${GREEN}Emulator is ready.${NC}"
else
  echo -e "${GREEN}Emulator is already running.${NC}"
fi
echo

# Run instrumented tests
echo -e "${YELLOW}Running instrumented tests...${NC}"
./gradlew connectedAndroidTest
INSTRUMENTED_RESULT=$?
if [ $INSTRUMENTED_RESULT -ne 0 ]; then
  echo -e "${RED}Instrumented tests failed!${NC}"
else
  echo -e "${GREEN}Instrumented tests passed.${NC}"
fi
echo

# Kill emulator if we started it
if [ -n "$EMULATOR_PID" ]; then
  echo -e "${YELLOW}Shutting down emulator...${NC}"
  kill $EMULATOR_PID
  echo -e "${GREEN}Emulator shut down.${NC}"
fi

# Generate test reports
echo -e "${YELLOW}Generating test reports...${NC}"
./gradlew testReport
echo -e "${GREEN}Test reports generated.${NC}"
echo

# Show test report locations
echo -e "${YELLOW}Test Reports:${NC}"
echo "Unit Test Report: $DIR/app/build/reports/tests/testDebugUnitTest/index.html"
echo "Instrumented Test Report: $DIR/app/build/reports/androidTests/connected/index.html"
echo

# Determine overall test result
if [ $TEST_RESULT -eq 0 ] && [ $INSTRUMENTED_RESULT -eq 0 ]; then
  echo -e "${GREEN}All tests passed!${NC}"
  exit 0
else
  echo -e "${RED}Some tests failed. Check the reports for details.${NC}"
  exit 1
fi

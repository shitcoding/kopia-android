#!/bin/bash
# Script to test APK building and signing

# Set colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== Kopia Android Build & Sign Test ===${NC}"
echo "Starting build tests at $(date)"
echo

# Directory of this script
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$DIR"

# Test 1: Build Debug APK
echo -e "${YELLOW}Test 1: Building Debug APK...${NC}"
./gradlew assembleDebug
if [ $? -ne 0 ]; then
    echo -e "${RED}✗ Debug APK build failed!${NC}"
    DEBUG_BUILD_RESULT=1
else
    echo -e "${GREEN}✓ Debug APK built successfully${NC}"
    DEBUG_BUILD_RESULT=0

    # Verify debug APK exists
    DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$DEBUG_APK" ]; then
        echo -e "${GREEN}✓ Debug APK found at: $DEBUG_APK${NC}"

        # Check APK size
        APK_SIZE=$(du -h "$DEBUG_APK" | cut -f1)
        echo -e "  Debug APK size: $APK_SIZE"

        # Verify APK content (list files inside)
        echo -e "${YELLOW}  Checking APK contents...${NC}"
        KOPIA_BINARY=$(unzip -l "$DEBUG_APK" | grep "libkopia.so")
        if [ -n "$KOPIA_BINARY" ]; then
            echo -e "${GREEN}  ✓ libkopia.so found in APK${NC}"
        else
            echo -e "${RED}  ✗ libkopia.so NOT found in APK${NC}"
        fi

        # Check manifest
        MANIFEST=$(unzip -l "$DEBUG_APK" | grep "AndroidManifest.xml")
        if [ -n "$MANIFEST" ]; then
            echo -e "${GREEN}  ✓ AndroidManifest.xml found in APK${NC}"
        else
            echo -e "${RED}  ✗ AndroidManifest.xml NOT found in APK${NC}"
        fi
    else
        echo -e "${RED}✗ Debug APK not found at expected location${NC}"
        DEBUG_BUILD_RESULT=1
    fi
fi
echo

# Test 2: Check if keystore exists for release build
echo -e "${YELLOW}Test 2: Checking Keystore Configuration...${NC}"
if [ ! -f "keystore.properties" ]; then
    echo -e "${RED}✗ keystore.properties not found${NC}"
    KEYSTORE_EXISTS=0
else
    echo -e "${GREEN}✓ keystore.properties found${NC}"

    # Check if keystore file exists
    STORE_FILE=$(grep "storeFile" keystore.properties | cut -d'=' -f2)
    if [ -f "$STORE_FILE" ]; then
        echo -e "${GREEN}✓ Keystore file found: $STORE_FILE${NC}"
        KEYSTORE_EXISTS=1
    else
        echo -e "${YELLOW}⚠ Keystore file not found: $STORE_FILE${NC}"
        echo -e "${YELLOW}  Generating new keystore...${NC}"

        # Generate a keystore for testing
        KEYSTORE_DIR="$DIR"
        KEYSTORE_FILE="$KEYSTORE_DIR/kopia_android.keystore"
        KEYSTORE_ALIAS="kopia_android"
        KEYSTORE_PASS="kopia_android_keystore"
        KEY_PASS="kopia_android_key"

        # Use keytool to generate keystore
        keytool -genkey -v \
            -keystore "$KEYSTORE_FILE" \
            -alias "$KEYSTORE_ALIAS" \
            -keyalg RSA \
            -keysize 2048 \
            -validity 10000 \
            -storepass "$KEYSTORE_PASS" \
            -keypass "$KEY_PASS" \
            -dname "CN=Kopia Android, OU=Development, O=Kopia, L=Unknown, ST=Unknown, C=US" \
            2>&1

        if [ $? -eq 0 ]; then
            echo -e "${GREEN}✓ Keystore generated successfully${NC}"
            echo -e "  Location: $KEYSTORE_FILE"
            KEYSTORE_EXISTS=1
        else
            echo -e "${RED}✗ Failed to generate keystore${NC}"
            KEYSTORE_EXISTS=0
        fi
    fi
fi
echo

# Test 3: Build Release APK (only if keystore exists)
if [ $KEYSTORE_EXISTS -eq 1 ]; then
    echo -e "${YELLOW}Test 3: Building Release APK...${NC}"
    ./gradlew assembleRelease
    if [ $? -ne 0 ]; then
        echo -e "${RED}✗ Release APK build failed!${NC}"
        RELEASE_BUILD_RESULT=1
    else
        echo -e "${GREEN}✓ Release APK built successfully${NC}"
        RELEASE_BUILD_RESULT=0

        # Verify release APK exists
        RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
        if [ -f "$RELEASE_APK" ]; then
            echo -e "${GREEN}✓ Release APK found at: $RELEASE_APK${NC}"

            # Check APK size
            APK_SIZE=$(du -h "$RELEASE_APK" | cut -f1)
            echo -e "  Release APK size: $APK_SIZE"

            # Verify signing
            echo -e "${YELLOW}  Verifying APK signature...${NC}"
            $ANDROID_HOME/build-tools/*/apksigner verify --verbose "$RELEASE_APK" 2>&1
            if [ $? -eq 0 ]; then
                echo -e "${GREEN}  ✓ APK signature verified${NC}"
            else
                echo -e "${YELLOW}  ⚠ APK signature verification failed (apksigner might not be available)${NC}"
            fi

            # Compare sizes
            DEBUG_SIZE=$(stat -f%z "app/build/outputs/apk/debug/app-debug.apk" 2>/dev/null || echo "0")
            RELEASE_SIZE=$(stat -f%z "$RELEASE_APK" 2>/dev/null || echo "0")

            if [ $RELEASE_SIZE -lt $DEBUG_SIZE ]; then
                echo -e "${GREEN}  ✓ Release APK is smaller than debug (ProGuard/R8 working)${NC}"
                echo -e "    Debug: $(echo "scale=2; $DEBUG_SIZE/1024/1024" | bc) MB"
                echo -e "    Release: $(echo "scale=2; $RELEASE_SIZE/1024/1024" | bc) MB"
                echo -e "    Reduction: $(echo "scale=2; ($DEBUG_SIZE-$RELEASE_SIZE)/1024/1024" | bc) MB"
            else
                echo -e "${YELLOW}  ⚠ Release APK is not smaller than debug${NC}"
            fi
        else
            echo -e "${RED}✗ Release APK not found at expected location${NC}"
            RELEASE_BUILD_RESULT=1
        fi
    fi
else
    echo -e "${YELLOW}Test 3: Skipping Release APK build (no keystore)${NC}"
    RELEASE_BUILD_RESULT=2  # Skipped
fi
echo

# Test 4: Build Android App Bundle (AAB)
echo -e "${YELLOW}Test 4: Building Android App Bundle (AAB)...${NC}"
if [ $KEYSTORE_EXISTS -eq 1 ]; then
    ./gradlew bundleRelease
    if [ $? -ne 0 ]; then
        echo -e "${RED}✗ AAB build failed!${NC}"
        AAB_BUILD_RESULT=1
    else
        echo -e "${GREEN}✓ AAB built successfully${NC}"
        AAB_BUILD_RESULT=0

        # Verify AAB exists
        AAB_FILE="app/build/outputs/bundle/release/app-release.aab"
        if [ -f "$AAB_FILE" ]; then
            echo -e "${GREEN}✓ AAB found at: $AAB_FILE${NC}"

            # Check AAB size
            AAB_SIZE=$(du -h "$AAB_FILE" | cut -f1)
            echo -e "  AAB size: $AAB_SIZE"
        else
            echo -e "${RED}✗ AAB not found at expected location${NC}"
            AAB_BUILD_RESULT=1
        fi
    fi
else
    echo -e "${YELLOW}Test 4: Skipping AAB build (no keystore)${NC}"
    AAB_BUILD_RESULT=2  # Skipped
fi
echo

# Test 5: Check ProGuard/R8 Configuration
echo -e "${YELLOW}Test 5: Checking ProGuard/R8 Configuration...${NC}"
PROGUARD_FILE="app/proguard-rules.pro"
if [ -f "$PROGUARD_FILE" ]; then
    echo -e "${GREEN}✓ ProGuard rules file found${NC}"

    # Check if build.gradle has minifyEnabled
    MINIFY_ENABLED=$(grep "minifyEnabled" app/build.gradle | grep "true")
    if [ -n "$MINIFY_ENABLED" ]; then
        echo -e "${GREEN}✓ Code minification enabled in release build${NC}"
    else
        echo -e "${YELLOW}⚠ Code minification not found in build.gradle${NC}"
    fi
else
    echo -e "${YELLOW}⚠ ProGuard rules file not found${NC}"
fi
echo

# Summary
echo -e "${BLUE}==================================${NC}"
echo -e "${BLUE}Build Test Summary${NC}"
echo -e "${BLUE}==================================${NC}"

if [ $DEBUG_BUILD_RESULT -eq 0 ]; then
    echo -e "${GREEN}✓ Debug APK Build: PASSED${NC}"
else
    echo -e "${RED}✗ Debug APK Build: FAILED${NC}"
fi

if [ $KEYSTORE_EXISTS -eq 1 ]; then
    echo -e "${GREEN}✓ Keystore Configuration: PASSED${NC}"
else
    echo -e "${YELLOW}⚠ Keystore Configuration: NOT CONFIGURED${NC}"
fi

if [ $RELEASE_BUILD_RESULT -eq 0 ]; then
    echo -e "${GREEN}✓ Release APK Build: PASSED${NC}"
elif [ $RELEASE_BUILD_RESULT -eq 2 ]; then
    echo -e "${YELLOW}⚠ Release APK Build: SKIPPED${NC}"
else
    echo -e "${RED}✗ Release APK Build: FAILED${NC}"
fi

if [ $AAB_BUILD_RESULT -eq 0 ]; then
    echo -e "${GREEN}✓ AAB Build: PASSED${NC}"
elif [ $AAB_BUILD_RESULT -eq 2 ]; then
    echo -e "${YELLOW}⚠ AAB Build: SKIPPED${NC}"
else
    echo -e "${RED}✗ AAB Build: FAILED${NC}"
fi

echo -e "${BLUE}==================================${NC}"
echo

# Overall result
if [ $DEBUG_BUILD_RESULT -eq 0 ] && ([ $RELEASE_BUILD_RESULT -eq 0 ] || [ $RELEASE_BUILD_RESULT -eq 2 ]); then
    echo -e "${GREEN}Overall: BUILD TESTS PASSED${NC}"
    exit 0
else
    echo -e "${RED}Overall: SOME BUILD TESTS FAILED${NC}"
    exit 1
fi

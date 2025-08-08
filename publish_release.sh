#!/bin/bash
# Kopia Android App Publishing Script
# This script automates the process of building and preparing the app for distribution

# Color codes for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Print banner
echo -e "${BLUE}=================================${NC}"
echo -e "${BLUE}  KOPIA ANDROID RELEASE SCRIPT   ${NC}"
echo -e "${BLUE}=================================${NC}"
echo ""

# Check if keystore.properties exists
if [ ! -f "keystore.properties" ]; then
    echo -e "${RED}Error: keystore.properties file not found!${NC}"
    echo -e "Please create a keystore.properties file with the following content:"
    echo -e "storePassword=your_store_password"
    echo -e "keyPassword=your_key_password"
    echo -e "keyAlias=your_key_alias"
    echo -e "storeFile=path_to_keystore"
    exit 1
fi

# Check if keystore file exists
STORE_FILE=$(grep "storeFile" keystore.properties | cut -d'=' -f2)
if [ ! -f "$STORE_FILE" ]; then
    echo -e "${RED}Error: Keystore file $STORE_FILE not found!${NC}"
    echo -e "Please ensure the keystore file exists at the specified location."
    exit 1
fi

# Function to increment version code
increment_version_code() {
    echo -e "${YELLOW}Incrementing version code...${NC}"
    
    # Get current version code
    CURRENT_VERSION_CODE=$(grep "versionCode" app/build.gradle | grep -o '[0-9]\+')
    NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))
    
    # Update version code in build.gradle
    sed -i '' "s/versionCode $CURRENT_VERSION_CODE/versionCode $NEW_VERSION_CODE/g" app/build.gradle
    
    echo -e "${GREEN}Version code updated from $CURRENT_VERSION_CODE to $NEW_VERSION_CODE${NC}"
}

# Ask if version code should be incremented
read -p "Do you want to increment the version code? (y/n): " INCREMENT_VERSION
if [[ $INCREMENT_VERSION == "y" || $INCREMENT_VERSION == "Y" ]]; then
    increment_version_code
fi

# Ask for version name update
read -p "Do you want to update the version name? (y/n): " UPDATE_VERSION_NAME
if [[ $UPDATE_VERSION_NAME == "y" || $UPDATE_VERSION_NAME == "Y" ]]; then
    # Get current version name
    CURRENT_VERSION_NAME=$(grep "versionName" app/build.gradle | grep -o '"[^"]*"')
    
    # Ask for new version name
    read -p "Enter new version name (current is $CURRENT_VERSION_NAME): " NEW_VERSION_NAME
    
    # Update version name in build.gradle
    sed -i '' "s/versionName $CURRENT_VERSION_NAME/versionName \"$NEW_VERSION_NAME\"/g" app/build.gradle
    
    echo -e "${GREEN}Version name updated to $NEW_VERSION_NAME${NC}"
fi

# Clean project
echo -e "${YELLOW}Cleaning project...${NC}"
./gradlew clean
if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Failed to clean project!${NC}"
    exit 1
fi
echo -e "${GREEN}Project cleaned successfully.${NC}"

# Build release APK
echo -e "${YELLOW}Building release APK...${NC}"
./gradlew assembleRelease
if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Failed to build release APK!${NC}"
    exit 1
fi
echo -e "${GREEN}Release APK built successfully.${NC}"

# Build release Bundle (AAB)
echo -e "${YELLOW}Building Android App Bundle...${NC}"
./gradlew bundleRelease
if [ $? -ne 0 ]; then
    echo -e "${RED}Error: Failed to build Android App Bundle!${NC}"
    exit 1
fi
echo -e "${GREEN}Android App Bundle built successfully.${NC}"

# Create release directory if it doesn't exist
RELEASE_DIR="releases"
mkdir -p $RELEASE_DIR

# Get version information for naming
VERSION_CODE=$(grep "versionCode" app/build.gradle | grep -o '[0-9]\+')
VERSION_NAME=$(grep "versionName" app/build.gradle | grep -o '"[^"]*"' | tr -d '"')
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

# Copy APK and Bundle to release directory
APK_PATH="app/build/outputs/apk/release/app-release.apk"
AAB_PATH="app/build/outputs/bundle/release/app-release.aab"
RELEASE_APK="$RELEASE_DIR/kopia-android-$VERSION_NAME-$VERSION_CODE-$TIMESTAMP.apk"
RELEASE_AAB="$RELEASE_DIR/kopia-android-$VERSION_NAME-$VERSION_CODE-$TIMESTAMP.aab"

cp $APK_PATH $RELEASE_APK
cp $AAB_PATH $RELEASE_AAB

# Generate SHA-256 checksums
echo -e "${YELLOW}Generating checksums...${NC}"
shasum -a 256 $RELEASE_APK > "$RELEASE_APK.sha256"
shasum -a 256 $RELEASE_AAB > "$RELEASE_AAB.sha256"

echo -e "${GREEN}Release files prepared:${NC}"
echo -e "  APK: $RELEASE_APK"
echo -e "  AAB: $RELEASE_AAB"

# Ask if user wants to create a GitHub release
read -p "Do you want to prepare a GitHub release? (y/n): " CREATE_GITHUB_RELEASE
if [[ $CREATE_GITHUB_RELEASE == "y" || $CREATE_GITHUB_RELEASE == "Y" ]]; then
    # Create release notes template
    RELEASE_NOTES="$RELEASE_DIR/release_notes_$VERSION_NAME.md"
    
    echo "# Kopia Android $VERSION_NAME (Build $VERSION_CODE)" > $RELEASE_NOTES
    echo "" >> $RELEASE_NOTES
    echo "## What's New" >> $RELEASE_NOTES
    echo "- " >> $RELEASE_NOTES
    echo "" >> $RELEASE_NOTES
    echo "## Bug Fixes" >> $RELEASE_NOTES
    echo "- " >> $RELEASE_NOTES
    echo "" >> $RELEASE_NOTES
    echo "## SHA-256 Checksums" >> $RELEASE_NOTES
    echo "\`\`\`" >> $RELEASE_NOTES
    cat "$RELEASE_APK.sha256" >> $RELEASE_NOTES
    cat "$RELEASE_AAB.sha256" >> $RELEASE_NOTES
    echo "\`\`\`" >> $RELEASE_NOTES
    
    echo -e "${GREEN}Release notes template created at $RELEASE_NOTES${NC}"
    echo -e "${YELLOW}Please edit the release notes before creating the GitHub release.${NC}"
fi

echo -e "${BLUE}=================================${NC}"
echo -e "${GREEN}Release preparation complete!${NC}"
echo -e "${BLUE}=================================${NC}"
echo ""
echo -e "Next steps:"
echo -e "1. Test the release APK on target devices"
echo -e "2. Upload to Google Play Console"
echo -e "3. Create GitHub release (if selected)"
echo -e "4. Update documentation"
echo ""

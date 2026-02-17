#!/bin/bash
# Setup script for restore Maestro tests
# Creates a clean empty directory for restore destination

# Remove any existing restore destination and create fresh empty directory
adb shell "rm -rf /sdcard/Download/_kopia_restore && mkdir -p /sdcard/Download/_kopia_restore"

# Verify directory was created
adb shell "ls -la /sdcard/Download/_kopia_restore"

echo "Restore destination directory created: /sdcard/Download/_kopia_restore"

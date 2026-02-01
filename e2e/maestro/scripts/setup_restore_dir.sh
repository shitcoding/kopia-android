#!/bin/bash
# Setup script for restore_files.yaml Maestro test
# Creates a clean empty directory for restore destination

# Remove any existing restore destination and create fresh empty directory
adb shell "rm -rf /sdcard/Download/restore_dest && mkdir -p /sdcard/Download/restore_dest"

# Verify directory was created
adb shell "ls -la /sdcard/Download/restore_dest"

echo "Restore destination directory created: /sdcard/Download/restore_dest"

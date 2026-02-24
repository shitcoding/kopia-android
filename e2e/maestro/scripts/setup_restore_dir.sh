#!/bin/bash
# Setup script for restore Maestro tests
# Creates a clean empty directory for restore destination

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Device serial: use first arg, or auto-detect if single device
SERIAL="${1:-}"
if [ -z "$SERIAL" ]; then
    DEVICE_COUNT=$(adb devices | grep -c 'device$')
    if [ "$DEVICE_COUNT" -gt 1 ]; then
        echo "ERROR: Multiple devices connected. Specify device serial as first argument."
        echo "Usage: $0 <device-serial>"
        echo "Available devices:"
        adb devices
        exit 1
    fi
    ADB="adb"
else
    ADB="adb -s $SERIAL"
fi

# Remove any existing restore destination and create fresh empty directory
$ADB shell "rm -rf /sdcard/Download/_kopia_restore && mkdir -p /sdcard/Download/_kopia_restore"

# Verify directory was created
$ADB shell "ls -la /sdcard/Download/_kopia_restore"

echo "Restore destination directory created: /sdcard/Download/_kopia_restore"

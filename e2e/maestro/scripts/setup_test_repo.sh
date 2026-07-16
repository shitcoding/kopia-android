#!/bin/bash
# Setup script for Maestro E2E tests
# Pushes test repositories to AVD and sets required permissions

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

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

echo "Setting up test repositories on AVD..."

# Clean up existing test repos. Force-stop the app FIRST: a connected app holds the repo blobs
# open and the FUSE rm then silently leaves its written blobs behind (observed 2026-07-17: stale
# session/index blobs from prior runs survived every "clean" and poisoned later runs).
echo "Cleaning existing test repos..."
$ADB shell am force-stop org.kopiaKt.app 2>/dev/null || true
$ADB shell "rm -rf /sdcard/testrepo /sdcard/v1repo /sdcard/Download/restore_dest /sdcard/KopiaTestRepo /sdcard/KopiaNegativeTestRepo"

# Push edge_case_repo
echo "Pushing edge_case_repo to /sdcard/testrepo..."
$ADB push "$REPO_ROOT/core/src/test/resources/fixtures/edge_case_repos/edge_case_repo" /sdcard/testrepo

# Push v1_test_repo
echo "Pushing v1_test_repo to /sdcard/v1repo..."
$ADB push "$REPO_ROOT/core/src/test/resources/fixtures/edge_case_repos/v1_test_repo" /sdcard/v1repo

# Create restore destination directory
echo "Creating restore destination directory..."
$ADB shell "mkdir -p /sdcard/Download/restore_dest"

# Grant storage permissions
echo "Granting storage permissions..."
$ADB shell appops set org.kopiaKt.app MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

echo ""
echo "Test repos ready:"
echo "  - Edge case repo: /sdcard/testrepo (password: test123)"
echo "  - V1 format repo: /sdcard/v1repo (password: test123)"
echo "  - Restore dest:   /sdcard/Download/restore_dest"
echo "  - Cleaned up:     /sdcard/KopiaTestRepo, /sdcard/KopiaNegativeTestRepo"

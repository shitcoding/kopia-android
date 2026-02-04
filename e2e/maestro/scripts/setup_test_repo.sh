#!/bin/bash
# Setup script for Maestro E2E tests
# Pushes test repositories to AVD and sets required permissions

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

echo "Setting up test repositories on AVD..."

# Clean up existing test repos
echo "Cleaning existing test repos..."
adb shell "rm -rf /sdcard/testrepo /sdcard/v1repo /sdcard/Download/restore_dest"

# Push edge_case_repo
echo "Pushing edge_case_repo to /sdcard/testrepo..."
adb push "$REPO_ROOT/core/src/test/resources/fixtures/edge_case_repos/edge_case_repo" /sdcard/testrepo

# Push v1_test_repo
echo "Pushing v1_test_repo to /sdcard/v1repo..."
adb push "$REPO_ROOT/core/src/test/resources/fixtures/edge_case_repos/v1_test_repo" /sdcard/v1repo

# Create restore destination directory
echo "Creating restore destination directory..."
adb shell "mkdir -p /sdcard/Download/restore_dest"

# Grant storage permissions
echo "Granting storage permissions..."
adb shell appops set org.kopiaKt.app MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

echo ""
echo "Test repos ready:"
echo "  - Edge case repo: /sdcard/testrepo (password: test123)"
echo "  - V1 format repo: /sdcard/v1repo (password: test123)"
echo "  - Restore dest:   /sdcard/Download/restore_dest"

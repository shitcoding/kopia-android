#!/usr/bin/env bash
# configure_avd.sh - Apply device policy settings for E2E testing
# Run after AVD boot, before installing APK or running tests.
# Usage: ./configure_avd.sh [device-serial]
#   e.g. ./configure_avd.sh emulator-5554
#   If no serial given, uses default adb device.
set -euo pipefail

DEVICE="${1:-}"
if [[ -n "$DEVICE" ]]; then
    ADB="adb -s $DEVICE"
else
    ADB="adb"
fi

echo "[$DEVICE] Waiting for device..."
$ADB wait-for-device
$ADB shell 'until [[ "$(getprop sys.boot_completed)" == "1" ]]; do sleep 1; done'
sleep 2

echo "[$DEVICE] Disabling animations..."
$ADB shell settings put global window_animation_scale 0
$ADB shell settings put global transition_animation_scale 0
$ADB shell settings put global animator_duration_scale 0

echo "[$DEVICE] Disabling WebView text input interference (API 34+)..."
$ADB shell settings put secure stylus_handwriting_enabled 0 2>/dev/null || true
$ADB shell settings put secure spell_checker_enabled 0
$ADB shell settings put secure show_ime_with_hard_keyboard 0 2>/dev/null || true
$ADB shell settings put secure autofill_service null 2>/dev/null || true

echo "[$DEVICE] Configuring screen stability..."
$ADB shell settings put system screen_off_timeout 2147483647
$ADB shell settings put global stay_on_while_plugged_in 3
$ADB shell settings put system font_scale 1.0

echo "[$DEVICE] Reducing noise (DND, accessibility, doze, blurs)..."
$ADB shell settings put global zen_mode 2
$ADB shell settings put secure enabled_accessibility_services null 2>/dev/null || true
# TalkBack's Select to Speak popup can appear mid-flow EVEN with enabled_accessibility_services
# null, and while active it CAPTURES ALL TAPS (to pick what to read aloud) - taps then "complete"
# in Maestro but never reach the app (observed 2026-07-16: select-all tap no-oped, flow failed).
# Test-only AVD: disable the whole package.
$ADB shell pm disable-user --user 0 com.google.android.marvin.talkback 2>/dev/null || true
$ADB shell dumpsys deviceidle disable 2>/dev/null || true
$ADB shell settings put global disable_window_blurs 1 2>/dev/null || true

echo "[$DEVICE] Unlocking screen..."
$ADB shell input keyevent 82

echo "[$DEVICE] Device configured for E2E testing."

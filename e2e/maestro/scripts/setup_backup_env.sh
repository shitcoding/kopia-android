#!/usr/bin/env bash
# setup_backup_env.sh <serial>
#
# Prepares the device for one ATTEMPT of a `backup`-category Maestro flow. Runs per attempt, not per
# flow: with a retry budget, a first attempt that completed a backup and then flaked would otherwise
# leave a snapshot behind, and the retry's "exactly one snapshot" assertion would fail for the wrong
# reason - turning every flake into a hard failure.
#
# Never skips. A missing prerequisite here means the flow would test nothing.
set -euo pipefail

SERIAL="${1:?usage: setup_backup_env.sh <serial>}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

APP_ID="org.kopiaKt.app"
STATE_DIR="$REPO_ROOT/e2e/maestro/.backup"
HOST_SOURCE="$STATE_DIR/source/phone_source"
DEVICE_SOURCE="/sdcard/Download/phone_source"
DEVICE_REPO="/sdcard/backup_e2e_repo"

adb() { command adb -s "$SERIAL" "$@"; }
fail() { echo "[setup_backup_env] ERROR: $*" >&2; exit 1; }

command -v adb >/dev/null 2>&1 || fail "adb not found"
adb get-state >/dev/null 2>&1 || fail "device $SERIAL is not available"

# 1. Stop the app FIRST. FUSE deletes fail silently while the app holds a path - the lesson
#    setup_test_repo.sh already carries.
adb shell am force-stop "$APP_ID" || true

# 2. Drop persisted sources, the persisted device identity and any SAF grants, so each flow starts
#    from the same state and flows stay order-independent. (This is `pm clear` from the host, not
#    Maestro's clearState, which breaks a live WebView DevTools session.)
adb shell pm clear "$APP_ID" >/dev/null || fail "pm clear failed"

# pm clear revokes these; re-grant or the app cannot read /sdcard at all.
adb shell appops set "$APP_ID" MANAGE_EXTERNAL_STORAGE allow || true
# A no-op today (nothing requests it at runtime yet), but it stops these flows breaking silently
# when the phase-4 runtime prompt lands.
adb shell pm grant "$APP_ID" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# 3. A WRITABLE repository of its own, CREATED BY GO. The committed fixtures (/sdcard/testrepo,
#    /sdcard/v1repo) are read-only reference data and must never be backed up into.
#
#    Go creates it rather than the app, for two reasons. The debug build deliberately uses fast
#    scrypt (N=1024) so tests are not dominated by key derivation, and Go refuses that parameter set
#    - a repository the debug app CREATES is one Go cannot open, so the oracle could never run. More
#    importantly this is the sharper test: the phone writing snapshots INTO a desktop-created
#    repository is exactly the cross-compatibility claim the project makes.
command -v kopia >/dev/null 2>&1 || fail "host 'kopia' not found - it creates the repository under test (brew install kopia)"
HOST_REPO="$STATE_DIR/repo"
rm -rf "$HOST_REPO" "$STATE_DIR/kopia"
mkdir -p "$STATE_DIR/kopia"
KOPIA_PASSWORD="testpassword123" kopia repository create filesystem \
    --path="$HOST_REPO" \
    --block-hash=BLAKE2B-256-128 \
    --encryption=AES256-GCM-HMAC-SHA256 \
    --object-splitter=FIXED-1M \
    --config-file="$STATE_DIR/kopia/setup.config" >/dev/null 2>&1 \
    || fail "Go kopia could not create the repository"
KOPIA_PASSWORD="testpassword123" kopia repository disconnect \
    --config-file="$STATE_DIR/kopia/setup.config" >/dev/null 2>&1 || true

adb shell "rm -rf $DEVICE_REPO" || fail "could not clear $DEVICE_REPO"
adb push "$HOST_REPO" "$DEVICE_REPO" >/dev/null || fail "could not push the repository"

# 4. The deterministic source tree, retained on the host so the Go-side verifier has an original to
#    compare against.
mkdir -p "$STATE_DIR/source"
"$SCRIPT_DIR/gen_backup_source.sh" "$HOST_SOURCE" >/dev/null || fail "could not generate the source tree"
adb shell "rm -rf $DEVICE_SOURCE" || fail "could not clear $DEVICE_SOURCE"
adb push "$HOST_SOURCE" "$DEVICE_SOURCE" >/dev/null || fail "could not push the source tree"

# 4b. A larger tree, for the flows that need a backup still running when they act on it: the small
#     one finishes in seconds, so there would be nothing left to cancel (backup_cancel) and nothing
#     in flight to rotate during (backup_task_survives_recreation).
LARGE_SOURCE="/sdcard/Download/phone_source_large"
adb shell "rm -rf $LARGE_SOURCE" || true
adb shell "mkdir -p $LARGE_SOURCE" || fail "could not create $LARGE_SOURCE"
# ~190 MB of incompressible data. Sized from measurement, not guesswork: 72 MB was hashed and
# uploaded before the Cancel tap could land, so the flow asserted the absence of a snapshot that had
# already been written.
for i in $(seq 1 16); do
    adb shell "dd if=/dev/urandom of=$LARGE_SOURCE/blob-$i.bin bs=1048576 count=12" >/dev/null 2>&1 \
        || fail "could not generate the large source"
done

# 5. Portrait. A flow that rotates the device to force an activity recreation cannot restore it
#    if it fails mid-way, and every later flow would then run sideways.
adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
adb shell settings put system user_rotation 0 >/dev/null 2>&1 || true

# 6. Nothing left over from a previous verification.
rm -rf "$STATE_DIR/pulled" "$STATE_DIR/restored"

echo "[setup_backup_env] ready: repo=$DEVICE_REPO source=$DEVICE_SOURCE"

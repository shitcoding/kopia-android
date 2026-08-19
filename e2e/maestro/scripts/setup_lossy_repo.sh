#!/usr/bin/env bash
# setup_lossy_repo.sh [serial]
#
# Builds the fixture `lossy_snapshot_warnings` needs: a repository holding one snapshot that is
# COMPLETE and yet lost part of its source. That is the shape task-63's six warning surfaces exist
# for, and the shape that had no device coverage at all (task-70).
#
# **Staged with permissions, not with timing.** task-70 described the recipe task-61 used to find the
# defect -- rename the source folder 6 s into a 16 s walk -- which is a race the flow does not
# control and would have to keep winning on every machine. A file the walk cannot open produces the
# identical manifest deterministically: Go's record-and-continue writes `numFailed` into the root
# directory summary and still marks the snapshot complete. Verified before this script was written:
#
#   incomplete: None
#   stats:      {'errorCount': 3, 'fileCount': 2}
#   root summ:  {'files': 2, 'numFailed': 3, 'errors': [...]}
#
# **Go creates it**, as with every other fixture here: the debug build uses fast scrypt (N=1024),
# which Go refuses, so a repository the app created could never be built or verified by the oracle --
# and a phone reading a desktop-written repository is the sharper test anyway.
set -euo pipefail

SERIAL="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
STATE_DIR="$REPO_ROOT/e2e/maestro/.lossy"
DEVICE_REPO="/sdcard/lossyrepo"
PASSWORD="test123"

if [ -n "$SERIAL" ]; then adb() { command adb -s "$SERIAL" "$@"; }; fi
fail() { echo "[setup_lossy_repo] ERROR: $*" >&2; exit 1; }

command -v kopia >/dev/null 2>&1 || fail "host 'kopia' not found — it creates the fixture (brew install kopia)"
adb get-state >/dev/null 2>&1 || fail "no device available"

# Root can read a mode-000 file, so a fixture built as root would silently be a HEALTHY snapshot and
# the flow would assert warnings that are correctly absent. Fail loudly instead.
[ "$(id -u)" -ne 0 ] || fail "refusing to build the fixture as root: chmod 000 would not make anything unreadable"

rm -rf "$STATE_DIR"
mkdir -p "$STATE_DIR/src/photos" "$STATE_DIR/cfg"

# Two readable files, so the snapshot has real content to browse and restore...
printf 'holiday\n'  > "$STATE_DIR/src/readable.txt"
printf 'portrait\n' > "$STATE_DIR/src/photos/keep.jpg"
# ...and three the walk cannot open. Three rather than one: it exercises the plural branch of the
# label ("3 items unreadable"), which is the case a real user meets, and gives the flow a string
# distinctive enough that it cannot match something else on the screen by accident.
for n in 1 2 3; do
    printf 'unreadable %s\n' "$n" > "$STATE_DIR/src/locked$n.bin"
    chmod 000 "$STATE_DIR/src/locked$n.bin"
done

KOPIA_PASSWORD="$PASSWORD" kopia repository create filesystem \
    --path="$STATE_DIR/repo" \
    --block-hash=BLAKE2B-256-128 \
    --encryption=AES256-GCM-HMAC-SHA256 \
    --object-splitter=FIXED-1M \
    --config-file="$STATE_DIR/cfg/k.config" >/dev/null 2>&1 \
    || fail "Go kopia could not create the repository"

# `snapshot create` exits non-zero when it hits fatal errors, which is exactly what we asked for.
KOPIA_PASSWORD="$PASSWORD" kopia snapshot create "$STATE_DIR/src" \
    --config-file="$STATE_DIR/cfg/k.config" >/dev/null 2>&1 || true

# Assert the fixture is what the flow needs BEFORE pushing it. A fixture that quietly came out
# healthy would turn the flow into one that asserts nothing and passes.
SUMMARY=$(KOPIA_PASSWORD="$PASSWORD" kopia snapshot list --all --json \
    --config-file="$STATE_DIR/cfg/k.config" 2>/dev/null | python3 -c "
import json, sys
snaps = json.load(sys.stdin)
if len(snaps) != 1:
    sys.exit('expected exactly one snapshot, got %d' % len(snaps))
s = snaps[0]
summ = s.get('rootEntry', {}).get('summ', {})
if s.get('incomplete'):
    sys.exit('the fixture snapshot is INCOMPLETE (%s); it must be complete-but-lossy' % s['incomplete'])
if summ.get('numFailed') != 3:
    sys.exit('expected numFailed=3, got %r' % summ.get('numFailed'))
print('numFailed=%d files=%d incomplete=None' % (summ['numFailed'], summ.get('files', 0)))
") || fail "the fixture is not complete-but-lossy: $SUMMARY"
echo "[setup_lossy_repo] fixture verified: $SUMMARY"

KOPIA_PASSWORD="$PASSWORD" kopia repository disconnect --config-file="$STATE_DIR/cfg/k.config" >/dev/null 2>&1 || true

adb shell am force-stop org.kopiaKt.app 2>/dev/null || true
adb shell "rm -rf $DEVICE_REPO" || fail "could not clear $DEVICE_REPO"
adb push "$STATE_DIR/repo" "$DEVICE_REPO" >/dev/null || fail "could not push the fixture"

# The mode-000 files would otherwise stay unreadable to the host user that generated them.
chmod -R u+rwX "$STATE_DIR/src"

echo "[setup_lossy_repo] ready: $DEVICE_REPO (password $PASSWORD)"

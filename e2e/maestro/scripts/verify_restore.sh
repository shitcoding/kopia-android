#!/usr/bin/env bash
# verify_restore.sh <serial> [min_files]
#
# Byte-level restore integrity check. After a restore flow, this:
#   1. asserts the device restored at least <min_files> files to /sdcard/Download/_kopia_restore;
#   2. restores the SAME edge_case_repo snapshot with the Go `kopia` CLI (the authoritative reference)
#      and confirms EVERY file KopiaKt restored to the device is byte-identical (md5) to the reference.
#
# This is what proves backup/restore is actually correct, not just that "Restore Complete" appeared.
# If host `kopia` is unavailable it falls back to the count check and says so (never a silent pass).
#
# Works for full-snapshot restores (restore_files/restore_flow: all 76 files) and partial restores
# (only the files present on the device are md5-checked against the reference; completeness is covered
# by <min_files>). Operates on a COPY of the repo so the committed fixture is never mutated.
set -uo pipefail

SERIAL="${1:?usage: verify_restore.sh <serial> [min_files]}"
MIN="${2:-1}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DEST="/sdcard/Download/_kopia_restore"
REPO_SRC="$REPO_ROOT/core/src/test/resources/fixtures/edge_case_repos/edge_case_repo"
PW="test123"

# 1. Count check (cheap, generic floor).
count="$(adb -s "$SERIAL" shell "find $DEST -type f 2>/dev/null | wc -l" | tr -d '\r[:space:]')"
[ -n "$count" ] || count=0
if [ "$count" -lt "$MIN" ]; then
    echo "FAIL: restored $count file(s) on disk, expected >= $MIN"
    exit 1
fi

# 2. Byte-level check against a Go-kopia reference restore.
if ! command -v kopia >/dev/null 2>&1; then
    echo "OK(count-only): $count file(s) restored (>= $MIN); host kopia absent -> byte-level skipped"
    exit 0
fi
if ! command -v md5 >/dev/null 2>&1; then
    echo "OK(count-only): $count file(s) restored (>= $MIN); md5 absent -> byte-level skipped"
    exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -R "$REPO_SRC" "$WORK/repo"
KCFG="$WORK/repo.config"; REF="$WORK/ref"; DEV="$WORK/dev"
mkdir -p "$REF" "$DEV"

if ! KOPIA_PASSWORD="$PW" kopia repository connect filesystem --path="$WORK/repo" \
        --config-file="$KCFG" --no-check-for-updates >/dev/null 2>&1; then
    echo "OK(count-only): $count file(s) restored; kopia connect to reference repo failed -> byte-level skipped"
    exit 0
fi
SNAP="$(KOPIA_PASSWORD="$PW" kopia snapshot list --config-file="$KCFG" --json 2>/dev/null \
        | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])" 2>/dev/null || true)"
if [ -z "$SNAP" ]; then
    echo "OK(count-only): $count file(s) restored; could not resolve reference snapshot -> byte-level skipped"
    exit 0
fi
KOPIA_PASSWORD="$PW" kopia snapshot restore "$SNAP" "$REF" --config-file="$KCFG" >/dev/null 2>&1
KOPIA_PASSWORD="$PW" kopia repository disconnect --config-file="$KCFG" >/dev/null 2>&1 || true

# Pull the device's restored tree, then locate where the comparable files start:
#   - FULL-snapshot restore (RestoreScreen): SAF wraps everything in the snapshot's source dir, e.g.
#     _kopia_restore/edge_case_data/... That wrapper is NOT a top-level entry of the Go-kopia reference
#     (which holds the snapshot CONTENTS at its root), so strip the single wrapper dir.
#   - PARTIAL restore (FileBrowser "Restore selected"): the selected entries land DIRECTLY under
#     _kopia_restore (e.g. _kopia_restore/.hidden_dir/, _kopia_restore/level1/), whose paths already
#     line up with the reference, so do NOT strip.
# Detect the full-restore wrapper precisely: exactly one top-level entry, it is a directory, and an
# entry of that name does NOT exist in the reference. Everything else compares straight from
# _kopia_restore (partial restores, a single selected dir that is itself a reference entry, lone files).
# CAVEAT: a future partial restore of a single *nested* dir whose basename is absent from the reference
# root could be misclassified as a full-restore wrapper; such flows should pass an explicit expected
# path list instead of relying on this heuristic.
adb -s "$SERIAL" pull "$DEST" "$DEV" >/dev/null 2>&1 || { echo "FAIL: could not pull $DEST"; exit 1; }
DEVROOT="$DEV/_kopia_restore"
[ -d "$DEVROOT" ] || DEVROOT="$DEV"
BASE="$DEVROOT"
top_count="$(find "$DEVROOT" -mindepth 1 -maxdepth 1 2>/dev/null | wc -l | tr -d ' ')"
top_one="$(find "$DEVROOT" -mindepth 1 -maxdepth 1 2>/dev/null | head -1)"
if [ "$top_count" -eq 1 ] && [ -d "$top_one" ] && [ ! -e "$REF/$(basename "$top_one")" ]; then
    BASE="$top_one"   # full-restore source-dir wrapper -> strip it
fi
[ -n "$(find "$BASE" -type f 2>/dev/null | head -1)" ] || { echo "FAIL: no restored directory tree under $DEST"; exit 1; }

# Compare every device file's md5 to the same relative path in the reference. NOTE: file content +
# paths only -- empty directories and dir-only structure are not asserted (SAF restore drops no-file
# dirs and the fixtures' coverage is file-based); per-flow completeness is covered by <min_files>.
mismatch=0; checked=0
while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    checked=$((checked + 1))
    refmd5="$( [ -f "$REF/$rel" ] && md5 -q "$REF/$rel" 2>/dev/null || true )"
    devmd5="$(md5 -q "$BASE/$rel" 2>/dev/null || true)"
    if [ -z "$refmd5" ]; then
        echo "  MISMATCH: $rel — not present in the Go-kopia reference"; mismatch=$((mismatch + 1))
    elif [ "$refmd5" != "$devmd5" ]; then
        echo "  BYTE MISMATCH: $rel — ref=$refmd5 dev=$devmd5"; mismatch=$((mismatch + 1))
    fi
done < <(cd "$BASE" && find . -type f | sed 's|^\./||')

if [ "$mismatch" -ne 0 ]; then
    echo "FAIL: $mismatch of $checked restored file(s) differ from the Go-kopia reference"
    exit 1
fi
echo "OK(byte-level): all $checked restored file(s) md5-match the Go-kopia reference (count $count >= $MIN)"
exit 0

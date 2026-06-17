#!/usr/bin/env bash
# setup_roundtrip_repo.sh <serial>
#
# Prerequisite for the backup/restore round-trip integrity flow (restore_roundtrip.yaml). It:
#   1. generates a deterministic source tree (the authoritative "original") and RETAINS it + a
#      path/size/sha256 manifest under e2e/maestro/.roundtrip/ for verify_roundtrip.sh;
#   2. backs it up with the Go `kopia` CLI into a fresh repo, using exact flags KopiaKt reads
#      (BLAKE2B-256-128 / AES256-GCM-HMAC-SHA256 / FIXED-1M splitter);
#   3. pushes that repo to the device and resets the restore destination.
#
# This test REQUIRES Go kopia — it LOUD-FAILS if the tooling is missing (no silent skip): the whole
# point is an independent original-vs-restored byte check, which can't be done without the backup.
set -euo pipefail

SERIAL="${1:?usage: setup_roundtrip_repo.sh <serial>}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

STATE_DIR="$REPO_ROOT/e2e/maestro/.roundtrip"
SRC="$STATE_DIR/source/roundtrip_source"   # fixed wrapper name -> known restored path on device
REPO="$STATE_DIR/repo"
KCFG="$STATE_DIR/kopia.config"
MANIFEST="$STATE_DIR/manifest"
DEVICE_REPO="/sdcard/roundtriprepo"
PW="test123"

command -v kopia  >/dev/null 2>&1 || { echo "ERROR: Go kopia CLI required (brew install kopia)" >&2; exit 1; }
command -v adb    >/dev/null 2>&1 || { echo "ERROR: adb required" >&2; exit 1; }
[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" = "device" ] || { echo "ERROR: device $SERIAL not online" >&2; exit 1; }

echo "[roundtrip] (re)generating deterministic source + manifest..."
rm -rf "$STATE_DIR"
mkdir -p "$STATE_DIR/source"
# Generator prints the path/size/sha256 manifest to stdout; retain it as the comparison authority.
"$SCRIPT_DIR/gen_roundtrip_source.sh" "$SRC" > "$MANIFEST"
echo "[roundtrip] source files: $(wc -l < "$MANIFEST" | tr -d ' ')  ($SRC retained for verification)"

echo "[roundtrip] creating kopia repo + snapshot via Go kopia ($(kopia --version 2>&1 | head -1))..."
KOPIA_PASSWORD="$PW" kopia repository create filesystem \
    --path="$REPO" \
    --block-hash=BLAKE2B-256-128 \
    --encryption=AES256-GCM-HMAC-SHA256 \
    --object-splitter=FIXED-1M \
    --config-file="$KCFG" 2>&1 | sed 's/^/    /'
KOPIA_PASSWORD="$PW" kopia snapshot create "$SRC" --config-file="$KCFG" 2>&1 | sed 's/^/    /'
KOPIA_PASSWORD="$PW" kopia repository disconnect --config-file="$KCFG" 2>&1 | sed 's/^/    /' || true

echo "[roundtrip] pushing repo to $SERIAL:$DEVICE_REPO + resetting restore dest..."
adb -s "$SERIAL" shell "rm -rf $DEVICE_REPO" 2>/dev/null || true
adb -s "$SERIAL" push "$REPO" "$DEVICE_REPO" 2>&1 | sed 's/^/    /'
adb -s "$SERIAL" shell appops set org.kopiaKt.app MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
"$SCRIPT_DIR/setup_restore_dir.sh" "$SERIAL" >/dev/null

echo "[roundtrip] ready: repo at $DEVICE_REPO (password '$PW'), source root 'roundtrip_source'."

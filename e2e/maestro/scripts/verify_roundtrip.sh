#!/usr/bin/env bash
# verify_roundtrip.sh <serial>
#
# STRICT, bidirectional byte-for-byte verification for the backup/restore round-trip. Compares the
# device's restored tree against the HELD-OUT ORIGINAL source that setup_roundtrip_repo.sh retained
# (the exact bytes Go kopia backed up) — NOT a Go-kopia re-restore. This is the independent
# "files aren't broken" guarantee.
#
# Unlike verify_restore.sh (which is permissive for partial restores), this verifier FAILS unless:
#   - the restored tree is exactly _kopia_restore/roundtrip_source with no unexpected siblings,
#   - every original file is restored and byte-identical (cmp),
#   - every restored file maps to an original (no extras).
# Any missing tooling / failed pull / missing retained source is a FAILURE, never a skip.
set -uo pipefail

SERIAL="${1:?usage: verify_roundtrip.sh <serial>}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

STATE_DIR="$REPO_ROOT/e2e/maestro/.roundtrip"
SRC="$STATE_DIR/source/roundtrip_source"
DEST="/sdcard/Download/_kopia_restore"

fail() { echo "FAIL: $*"; exit 1; }

command -v adb >/dev/null 2>&1 || fail "adb not found"
command -v cmp >/dev/null 2>&1 || fail "cmp not found"
[ -d "$SRC" ] || fail "retained original source missing at $SRC (run setup_roundtrip_repo.sh first)"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
PULLED="$WORK/pulled"; mkdir -p "$PULLED"
adb -s "$SERIAL" pull "$DEST" "$PULLED" >/dev/null 2>&1 || fail "could not pull $DEST from device"

DEVROOT="$PULLED/_kopia_restore"
[ -d "$DEVROOT" ] || fail "no restored tree pulled from $DEST"
WRAP="$DEVROOT/roundtrip_source"
[ -d "$WRAP" ] || fail "restored wrapper '_kopia_restore/roundtrip_source' is missing"

errors=0

# Exactly one top-level entry (roundtrip_source) — anything else is unexpected garbage.
while IFS= read -r -d '' entry; do
    base="$(basename "$entry")"
    [ "$base" = "roundtrip_source" ] || { echo "  UNEXPECTED top-level entry under $DEST: $base"; errors=$((errors + 1)); }
done < <(find "$DEVROOT" -mindepth 1 -maxdepth 1 -print0)

# Forward: every original file must be restored and byte-identical.
checked=0
while IFS= read -r -d '' rel; do
    rel="${rel#./}"
    checked=$((checked + 1))
    if [ ! -f "$WRAP/$rel" ]; then
        echo "  MISSING in restore: $rel"; errors=$((errors + 1)); continue
    fi
    if ! cmp -s "$SRC/$rel" "$WRAP/$rel"; then
        s_src="$( (shasum -a 256 "$SRC/$rel" 2>/dev/null || echo '?') | cut -d' ' -f1)"
        s_dev="$( (shasum -a 256 "$WRAP/$rel" 2>/dev/null || echo '?') | cut -d' ' -f1)"
        echo "  BYTE MISMATCH: $rel (orig=$s_src restored=$s_dev)"; errors=$((errors + 1))
    fi
done < <(cd "$SRC" && find . -type f -print0)

# Reverse: every restored file must correspond to an original (no extras).
while IFS= read -r -d '' rel; do
    rel="${rel#./}"
    [ -f "$SRC/$rel" ] || { echo "  UNEXPECTED extra restored file: $rel"; errors=$((errors + 1)); }
done < <(cd "$WRAP" && find . -type f -print0)

if [ "$errors" -ne 0 ]; then
    fail "$errors discrepancy(ies) between restored tree and the original source (checked $checked original files)"
fi
echo "OK(round-trip): all $checked original file(s) restored byte-identically (cmp) — no missing/extra/corrupt files"
exit 0

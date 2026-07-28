#!/usr/bin/env bash
# verify_backup.sh <serial> [--expect-snapshots N] [--expect-absent RELPATH]...
#
# Proves that what the PHONE wrote is a real Kopia repository by making the Go implementation read it:
# pull the repository off the device, connect with the host `kopia` binary, list the snapshots, restore
# the newest one, and compare it byte-for-byte against the original retained by setup_backup_env.sh.
#
# This is the sharpest assertion the suite can make about backup. An in-app restore would only verify
# the app against itself; Go is an independent oracle, and the only thing that can catch a
# phone-written repository that is subtly Go-incompatible.
#
# Missing host `kopia` is a hard FAILURE for this category, not a skip - the Go oracle IS the test.
set -uo pipefail

SERIAL="${1:?usage: verify_backup.sh <serial> [--expect-snapshots N] [--expect-absent RELPATH]...}"
shift

EXPECT_SNAPSHOTS=""
EXPECT_ABSENT=()
while [ $# -gt 0 ]; do
    case "$1" in
        --expect-snapshots) EXPECT_SNAPSHOTS="${2:?--expect-snapshots needs a number}"; shift 2 ;;
        --expect-absent)    EXPECT_ABSENT+=("${2:?--expect-absent needs a path}"); shift 2 ;;
        *) echo "verify_backup: unknown argument $1" >&2; exit 2 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

APP_ID="org.kopiaKt.app"
STATE_DIR="$REPO_ROOT/e2e/maestro/.backup"
ORIGINAL="$STATE_DIR/source/phone_source"
WORK="$STATE_DIR/pulled"
RESTORED="$STATE_DIR/restored"
KOPIA_STATE="$STATE_DIR/kopia"
DEVICE_REPO="/sdcard/backup_e2e_repo"
REPO_PASSWORD="testpassword123"

adb() { command adb -s "$SERIAL" "$@"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# Isolate the CLI completely. Without --config-file the verifier writes the DEFAULT repository.config,
# silently disconnecting whatever real Kopia setup the developer has and pointing it at a temp
# directory that is about to be deleted. Precedents: setup_roundtrip_repo.sh, KopiaCliRunner.kt.
kopia_cli() {
    KOPIA_PASSWORD="$REPO_PASSWORD" command kopia \
        --config-file="$KOPIA_STATE/repository.config" \
        --log-dir="$KOPIA_STATE/logs" \
        "$@"
}

command -v adb >/dev/null 2>&1 || fail "adb not found"
command -v kopia >/dev/null 2>&1 || fail "host 'kopia' not found - the Go oracle IS this test (brew install kopia)"
command -v cmp >/dev/null 2>&1 || fail "cmp not found"
[ -d "$ORIGINAL" ] || fail "retained original missing at $ORIGINAL (setup_backup_env.sh did not run)"

# The flow asserted completion before we got here, but force-stop anyway so nothing can be mid-write
# while we copy the repository off the device.
adb shell am force-stop "$APP_ID" || true

rm -rf "$WORK" "$RESTORED" "$KOPIA_STATE"
mkdir -p "$WORK" "$RESTORED" "$KOPIA_STATE"

adb shell "test -d $DEVICE_REPO" || fail "no repository at $DEVICE_REPO - the phone never created one"
adb pull "$DEVICE_REPO" "$WORK" >/dev/null 2>&1 || fail "could not pull $DEVICE_REPO"
# adb pull <dir> <dest> creates <dest>/<basename>; connect to that child, not to $WORK.
PULLED="$WORK/$(basename "$DEVICE_REPO")"
# Filesystem storage writes blobs with a .f suffix, as Go's does.
[ -f "$PULLED/kopia.repository.f" ] || fail "$PULLED has no kopia.repository blob - not a repository"

# Connecting at all is already a result: it proves the phone wrote a format blob Go understands.
kopia_cli repository connect filesystem --path="$PULLED" >"$KOPIA_STATE/connect.log" 2>&1 \
    || { cat "$KOPIA_STATE/connect.log" >&2; fail "Go kopia could not open the repository the phone wrote"; }

trap 'kopia_cli repository disconnect >/dev/null 2>&1 || true' EXIT

kopia_cli snapshot list --all --json >"$KOPIA_STATE/snapshots.json" 2>"$KOPIA_STATE/list.log" \
    || { cat "$KOPIA_STATE/list.log" >&2; fail "Go kopia could not list the phone's snapshots"; }

SNAPSHOT_COUNT="$(python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    print(len(json.load(f)))
' "$KOPIA_STATE/snapshots.json")"

echo "verify_backup: Go sees $SNAPSHOT_COUNT snapshot(s)"

if [ -n "$EXPECT_SNAPSHOTS" ] && [ "$SNAPSHOT_COUNT" != "$EXPECT_SNAPSHOTS" ]; then
    kopia_cli snapshot list --all >&2 || true
    fail "expected $EXPECT_SNAPSHOTS snapshot(s), Go sees $SNAPSHOT_COUNT"
fi
[ "$SNAPSHOT_COUNT" -gt 0 ] || fail "the phone wrote no snapshots"

# The source identity must be the unified one (task-30.6): user@android-<model>-<random>:<path>.
# A regex, not a literal - the suffix is per device.
SOURCE_ID="$(python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    snaps = json.load(f)
s = snaps[-1]["source"]
print("{}@{}:{}".format(s["userName"], s["host"], s["path"]))
' "$KOPIA_STATE/snapshots.json")"
echo "verify_backup: source identity = $SOURCE_ID"
echo "$SOURCE_ID" | grep -Eq '^[a-z0-9._-]+@android-[a-z0-9._-]+-[0-9a-f]{6}:' \
    || fail "unexpected source identity '$SOURCE_ID' (expected user@android-<model>-<random>:<path>)"

ROOT_ID="$(python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    snaps = json.load(f)
print(snaps[-1]["rootEntry"]["obj"])
' "$KOPIA_STATE/snapshots.json")"

kopia_cli snapshot restore "$ROOT_ID" "$RESTORED" >"$KOPIA_STATE/restore.log" 2>&1 \
    || { cat "$KOPIA_STATE/restore.log" >&2; fail "Go kopia could not restore the phone's snapshot"; }

# Strict, bidirectional comparison against the retained original, as verify_roundtrip.sh does.
# --expect-absent paths are EXEMPT from the forward check and hard-asserted absent instead: the
# original deliberately contains the file the ignore-rule flow excludes, so without the exemption
# that flow could never pass and the pressure would be to weaken the whole comparison.
python3 - "$ORIGINAL" "$RESTORED" "${EXPECT_ABSENT[@]+"${EXPECT_ABSENT[@]}"}" <<'PY'
import os, sys, filecmp

original, restored = sys.argv[1], sys.argv[2]
expect_absent = set(sys.argv[3:])

def tree(root):
    found = {}
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            full = os.path.join(dirpath, name)
            found[os.path.relpath(full, root)] = full
    return found

want, got = tree(original), tree(restored)
problems = []

for rel in sorted(expect_absent):
    if rel in got:
        problems.append(f"present but must be excluded: {rel}")
    if rel not in want:
        problems.append(f"--expect-absent {rel} is not in the original tree; the assertion is vacuous")

for rel, path in sorted(want.items()):
    if rel in expect_absent:
        continue
    if rel not in got:
        problems.append(f"MISSING from the restore: {rel}")
    elif not filecmp.cmp(path, got[rel], shallow=False):
        problems.append(f"CONTENT DIFFERS: {rel}")

for rel in sorted(got):
    if rel not in want:
        problems.append(f"EXTRA in the restore: {rel}")

if problems:
    print("FAIL: Go's restore of the phone's snapshot does not match the original:", file=sys.stderr)
    for p in problems:
        print("  - " + p, file=sys.stderr)
    sys.exit(1)

print(f"verify_backup: {len(want) - len(expect_absent)} file(s) restored byte-identical by Go kopia")
PY

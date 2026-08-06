#!/usr/bin/env bash
# Self-check for the failure classifier in host_health.sh (task-41).
#
# The bug this guards against: the runner labelled a flow "INFRA - emulator ANR/degradation, NOT a
# code failure" while its maestro log showed one specific assertion failing identically on every
# attempt and no ANR text anywhere. That made the whole backup category unable to report a red, and
# cost two sessions chasing a "degraded host" that was really a flow bug.
#
# Run: bash e2e/maestro/scripts/test_failure_classification.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
. "$SCRIPT_DIR/host_health.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
pass=0
fail=0

check() {
    local what="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        pass=$((pass + 1))
        echo "  ok   $what"
    else
        fail=$((fail + 1))
        echo "  FAIL $what"
        echo "         expected: '$expected'"
        echo "         actual:   '$actual'"
    fi
}

# A real failing maestro log: steps complete, then one names itself FAILED.
cat >"$TMP/assertion.log" <<'EOF'
Tap on ".*Edit Policy.*"... COMPLETED
Assert that "Save policy" is visible... COMPLETED
Assert that "10", id: keep-latest-input is visible... FAILED

Assertion is false: "10", id: keep-latest-input is visible
EOF

# A flow killed by an ANR: maestro itself saw the system dialog.
cat >"$TMP/anr.log" <<'EOF'
Launch app "org.kopiaKt.app"... COMPLETED
Assert that "KopiaKt" is visible... FAILED

Assertion is false: "KopiaKt" is visible
System UI shows: KopiaKt isn't responding
EOF

# A NESTED failure, copied from a real backup-flow artifact. Maestro unwinds outwards, so the
# assertion comes first and the wrapper last -- the shape every backup flow produces, and the one
# that made an earlier version of this helper report "Repeat 3 times" as the failure.
cat >"$TMP/nested.log" <<'EOF'
Run flows/_backup_prepare.yaml... COMPLETED
  Assert that ".*Backup Progress.*" is visible... FAILED
Repeat 3 times... FAILED

Assertion is false: ".*Backup Progress.*" is visible
EOF

# A selector that merely CONTAINS the failure marker must not be read as a verdict.
cat >"$TMP/decoy.log" <<'EOF'
Assert that ".*step ... FAILED banner.*" is visible... COMPLETED
EOF

# A clean pass.
cat >"$TMP/pass.log" <<'EOF'
Launch app "org.kopiaKt.app"... COMPLETED
Assert that "KopiaKt" is visible... COMPLETED
EOF

# Truncated because the emulator died mid-run: nothing declared itself failed.
cat >"$TMP/truncated.log" <<'EOF'
Launch app "org.kopiaKt.app"... COMPLETED
Assert that "Snapshots" is visible... COMPLETED
EOF

echo "hh_maestro_failing_step:"
check "names the failing assertion" \
    'Assert that "10", id: keep-latest-input is visible' \
    "$(hh_maestro_failing_step "$TMP/assertion.log")"
check "reports nothing for a clean pass" "" "$(hh_maestro_failing_step "$TMP/pass.log")"
check "reports nothing for a truncated log" "" "$(hh_maestro_failing_step "$TMP/truncated.log")"
check "reports nothing for a missing file" "" "$(hh_maestro_failing_step "$TMP/nope.log")"
check "names the innermost step, not the wrapper" \
    'Assert that ".*Backup Progress.*" is visible' \
    "$(hh_maestro_failing_step "$TMP/nested.log")"
check "ignores a marker inside a selector string" "" \
    "$(hh_maestro_failing_step "$TMP/decoy.log")"

echo "hh_flow_failed_deterministically:"
hh_flow_failed_deterministically "$TMP/assertion.log" && r=yes || r=no
check "an explicit assertion failure is deterministic" yes "$r"
hh_flow_failed_deterministically "$TMP/anr.log" && r=yes || r=no
check "an ANR dialog in the log is NOT deterministic" no "$r"
hh_flow_failed_deterministically "$TMP/truncated.log" && r=yes || r=no
check "a truncated log is NOT deterministic" no "$r"
hh_flow_failed_deterministically "$TMP/nope.log" && r=yes || r=no
check "a missing log is NOT deterministic" no "$r"
hh_flow_failed_deterministically "$TMP/nested.log" && r=yes || r=no
check "a nested assertion failure is deterministic" yes "$r"

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]

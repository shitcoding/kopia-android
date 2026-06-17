#!/usr/bin/env bash
# run_e2e.sh — per-flow Maestro E2E runner with prerequisites, state reset, and artifacts.
#
# Why a runner instead of `maestro test e2e/maestro/`:
#   - per-flow work queue: each flow runs in its own `maestro` process so one hang/crash
#     can't poison the rest, and we can reset state + capture artifacts between flows;
#   - explicit prerequisites per flow (fresh APK/bundle, test repos, restore-dir reset,
#     Docker backends) that are LOUDLY failed or VISIBLY skipped — never silently green;
#   - a mutation hook (--expect-fail) so a flow can be proven to FAIL on a broken build.
#
# This runner does NOT create/start AVDs (that's manage_avds.sh, which enforces the <=2 cap).
# Bring an emulator up first, then point this at its serial.
#
# Usage:
#   run_e2e.sh <serial> [flow ...]        Run the whole manifest, or only the named flows.
#   run_e2e.sh --list                     Print the flow manifest (name + category) and exit.
#   run_e2e.sh <serial> --no-remote ...   Skip remote (Docker) flows instead of running them.
#   run_e2e.sh <serial> --expect-fail F   Mutation test: run flow F and PASS only if it FAILS.
#   run_e2e.sh --help
#
# Flow args may be a bare name ("restore_files"), with or without ".yaml".
#
# Exit code: 0 only if every executed flow passed (skips don't fail the run unless every
# requested flow was skipped). Non-zero if any executed flow failed.

set -uo pipefail   # NOT -e: the per-flow loop must continue past a failing flow.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
FLOW_DIR="$REPO_ROOT/e2e/maestro"
APK="$REPO_ROOT/app-android/build/outputs/apk/debug/app-android-debug.apk"
DOCUMENTSUI="com.google.android.documentsui"

# Per-flow wall-clock timeout — a hung maestro/adb/WebView must not block the whole queue.
# macOS has no `timeout` by default; prefer coreutils timeout/gtimeout, else a bash watchdog.
FLOW_TIMEOUT="${E2E_FLOW_TIMEOUT:-600}"
TIMEOUT_BIN=""
command -v timeout  >/dev/null 2>&1 && TIMEOUT_BIN="timeout"
[ -z "$TIMEOUT_BIN" ] && command -v gtimeout >/dev/null 2>&1 && TIMEOUT_BIN="gtimeout"

# --------------------------------------------------------------------------- #
#  Flow manifest — explicit + ordered. category drives per-flow prerequisites:
#    local   : test repos pushed (default)
#    restore : local + reset /sdcard/Download/_kopia_restore before the flow
#    s3|webdav|sftp : local + Docker backend up & seeded (host `kopia` required)
#  flows/_connect_and_browse.yaml is intentionally excluded (dead/unused).
# --------------------------------------------------------------------------- #
MANIFEST=(
  "welcome_screen|local"
  "backup_welcome_create_repo|local"
  "backup_create_repo_local|local"
  "backup_create_repo_local_negative|local"
  "connect_local_repo|local"
  "connect_edge_case_repo|local"
  "connect_v1_repo|local"
  "connect_error_handling|local"
  "view_v1_snapshot|local"
  "browse_snapshots|local"
  "browse_files|local"
  "browse_files_detailed|local"
  "navigate_directories|local"
  "source_snapshots_screen|local"
  "backup_settings_navigation|local"
  "backup_sources_dashboard|local"
  "backup_policy_editor|local"
  "backup_add_source_and_run|local"
  "backup_add_source_and_run_negative|local"
  "backup_estimation_dialog|local"
  "backup_maintenance|local"
  "backup_task_list|local"
  "settings_disconnect|local"
  "exitdoor_disconnect_filebrowser|local"
  "exitdoor_disconnect_snapshots|local"
  "exitdoor_disconnect_source_snapshots|local"
  "restore_files|restore"
  "restore_flow|restore"
  "filebrowser_batch_select_restore_files|restore"
  "filebrowser_batch_select_all_restore|local"
  "filebrowser_restore_directory_preservation|restore"
  "full_e2e_flow|restore"
  "connect_s3_repo|s3"
  "connect_s3_errors|s3"
  "connect_webdav_repo|webdav"
  "connect_webdav_errors|webdav"
  "connect_sftp_repo|sftp"
  "connect_sftp_errors|sftp"
)

# --------------------------------------------------------------------------- #
#  Output helpers
# --------------------------------------------------------------------------- #
log()  { echo "[run_e2e] $*"; }
warn() { echo "[run_e2e] WARNING: $*" >&2; }
die()  { echo "[run_e2e] ERROR: $*" >&2; exit 1; }

# --------------------------------------------------------------------------- #
#  Manifest lookup
# --------------------------------------------------------------------------- #
manifest_category() {
    # echo the category for a flow base name, or "local" if not in the manifest.
    local name="$1" entry
    for entry in "${MANIFEST[@]}"; do
        if [ "${entry%%|*}" = "$name" ]; then
            echo "${entry##*|}"; return 0
        fi
    done
    echo "local"
}

print_manifest() {
    local entry
    printf '%-44s %s\n' "FLOW" "CATEGORY"
    for entry in "${MANIFEST[@]}"; do
        printf '%-44s %s\n' "${entry%%|*}.yaml" "${entry##*|}"
    done
}

# --------------------------------------------------------------------------- #
#  Prerequisites
# --------------------------------------------------------------------------- #

# Rebuild the APK if missing or if any source tree is newer, then (re)install it.
# build-or-fail: a stale or unbuildable APK is a hard error, never a silent stale run.
ensure_fresh_apk() {
    local serial="$1" need_build=0
    local src_dirs=( react-ui/src app-android/src core/src snapshot/src storage/src android/src )
    local d
    for d in "${src_dirs[@]}"; do
        [ -d "$REPO_ROOT/$d" ] || die "expected source dir missing: $d (broken checkout?)"
    done
    if [ ! -f "$APK" ]; then
        need_build=1
    else
        # Any source newer than the built APK? (dirs validated above, so a missing dir can't
        # silently hide staleness and let a stale APK look "fresh".)
        local newer
        newer="$(cd "$REPO_ROOT" && find "${src_dirs[@]}" -type f -newer "$APK" -print -quit 2>/dev/null)"
        if [ -n "$newer" ]; then
            log "Sources newer than the APK — rebuilding."
            need_build=1
        fi
    fi
    if [ "$need_build" -eq 1 ]; then
        log "Building React bundle (react-ui)…"
        ( cd "$REPO_ROOT/react-ui" && npm run build ) || die "react-ui build failed"
        log "Building debug APK…"
        ( cd "$REPO_ROOT" && ./gradlew :app-android:assembleDebug ) || die "assembleDebug failed"
        [ -f "$APK" ] || die "APK still missing after build: $APK"
    fi
    log "Installing APK on $serial…"
    adb -s "$serial" install -r -g "$APK" >/dev/null || die "APK install failed on $serial"
}

# Push the read-only test repos + grant storage. Done once per run (fixtures don't mutate).
ensure_test_repos() {
    local serial="$1"
    log "Pushing test repositories to $serial…"
    "$SCRIPT_DIR/setup_test_repo.sh" "$serial" >/dev/null || die "test repo setup failed on $serial"
}

# Reset the restore destination before a restore flow so leftovers can't fake a pass.
reset_restore_dir() {
    local serial="$1"
    "$SCRIPT_DIR/setup_restore_dir.sh" "$serial" >/dev/null || die "restore-dir reset failed on $serial"
}

# Bring up + seed Docker backends. Returns non-zero (without aborting) when the tooling is
# missing so remote flows become a VISIBLE skip rather than a silent green.
REMOTE_READY=-1   # -1 unknown, 0 unavailable, 1 ready
ensure_remote_backends() {
    [ "$REMOTE_READY" -ne -1 ] && return $(( REMOTE_READY == 1 ? 0 : 1 ))
    if ! command -v docker >/dev/null 2>&1; then
        warn "docker not found — remote (S3/WebDAV/SFTP) flows will be SKIPPED."
        REMOTE_READY=0; return 1
    fi
    if ! command -v kopia >/dev/null 2>&1; then
        warn "host 'kopia' CLI not found (brew install kopia) — remote flows will be SKIPPED (cannot seed)."
        REMOTE_READY=0; return 1
    fi
    log "Starting Docker storage backends…"
    if ! "$SCRIPT_DIR/start_storage_backends.sh" >/dev/null 2>&1; then
        warn "start_storage_backends.sh failed — remote flows will be SKIPPED."
        REMOTE_READY=0; return 1
    fi
    log "Seeding Docker storage backends…"
    if ! "$SCRIPT_DIR/seed_storage_backends.sh" >/dev/null 2>&1; then
        warn "seed_storage_backends.sh failed — remote flows will be SKIPPED."
        REMOTE_READY=0; return 1
    fi
    REMOTE_READY=1; return 0
}

# --------------------------------------------------------------------------- #
#  Artifacts
# --------------------------------------------------------------------------- #
ARTIFACT_DIR=""   # set in main once we know the run timestamp
capture_artifacts() {
    local serial="$1" name="$2"
    local dest="$ARTIFACT_DIR/$name"
    mkdir -p "$dest"
    # Resized screenshot (>2000px poisons the agent's context — keep <=1920px).
    if adb -s "$serial" exec-out screencap -p > "$dest/raw.png" 2>/dev/null && [ -s "$dest/raw.png" ]; then
        if command -v sips >/dev/null 2>&1; then
            sips --resampleHeightWidthMax 1920 "$dest/raw.png" --out "$dest/screen.png" >/dev/null 2>&1 \
                && rm -f "$dest/raw.png"
        fi
    else
        rm -f "$dest/raw.png"
    fi
    # Native view hierarchy (WebView internals won't show, but the chrome will).
    adb -s "$serial" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 \
        && adb -s "$serial" pull /sdcard/window_dump.xml "$dest/hierarchy.xml" >/dev/null 2>&1 || true
    log "  artifacts: $dest"
}

# --------------------------------------------------------------------------- #
#  Run one flow
# --------------------------------------------------------------------------- #
# Run `maestro test` with a wall-clock timeout. Returns the flow's exit code, or a
# timeout marker (124 from coreutils, 143 from the bash watchdog's SIGTERM).
run_flow_cmd() {
    local secs="$1" serial="$2" flow="$3" logf="$4"
    if [ -n "$TIMEOUT_BIN" ]; then
        "$TIMEOUT_BIN" "$secs" maestro --device "$serial" test "$flow" >"$logf" 2>&1
        return $?
    fi
    # Fallback watchdog (bash 3.2, no coreutils): background the flow, kill it if it overruns.
    maestro --device "$serial" test "$flow" >"$logf" 2>&1 &
    local cmd_pid=$!
    ( sleep "$secs"; kill -TERM "$cmd_pid" 2>/dev/null; sleep 5; kill -KILL "$cmd_pid" 2>/dev/null ) >/dev/null 2>&1 &
    local watch_pid=$!
    local rc=0
    wait "$cmd_pid" 2>/dev/null || rc=$?
    kill -TERM "$watch_pid" >/dev/null 2>&1 || true
    wait "$watch_pid" 2>/dev/null || true
    return "$rc"
}

RESULTS=()   # "name|STATUS|note"
run_one() {
    local serial="$1" name="$2"
    local flow="$FLOW_DIR/$name.yaml"
    local cat; cat="$(manifest_category "$name")"

    if [ ! -f "$flow" ]; then
        RESULTS+=("$name|SKIP|no such flow file"); warn "$name: no such flow file"; return
    fi

    # Per-flow state reset / prerequisites.
    adb -s "$serial" shell am force-stop "$DOCUMENTSUI" >/dev/null 2>&1 || true
    case "$cat" in
        restore) reset_restore_dir "$serial" ;;
        s3|webdav|sftp)
            if ! ensure_remote_backends; then
                RESULTS+=("$name|SKIP|remote backend unavailable ($cat)")
                log "SKIP $name ($cat backend unavailable)"; return
            fi
            ;;
    esac

    log "RUN  $name ($cat)…"
    local logf="$ARTIFACT_DIR/$name.maestro.log"
    local rc=0
    run_flow_cmd "$FLOW_TIMEOUT" "$serial" "$flow" "$logf" || rc=$?
    local tnote=""
    case "$rc" in 124|137|143) tnote=" (timed out after ${FLOW_TIMEOUT}s)" ;; esac

    if [ -n "$EXPECT_FAIL" ] && [ "$EXPECT_FAIL" = "$name" ]; then
        # Mutation mode: the feature is deliberately broken, so we WANT this flow to fail.
        # Run a clean baseline pass FIRST — a device/infra error also yields rc!=0, so always
        # capture artifacts and verify the failure happened for the right reason.
        if [ "$rc" -ne 0 ]; then
            RESULTS+=("$name|MUT-OK|failed on broken build$tnote — verify reason in artifacts")
            capture_artifacts "$serial" "$name"
            log "MUT-OK $name — flow FAILED on the broken build (confirm the failure reason in artifacts)."
        else
            RESULTS+=("$name|MUT-LEAK|passed on broken build (FALSE PASS!)")
            capture_artifacts "$serial" "$name"
            warn "MUT-LEAK $name — flow PASSED on a broken build; it cannot detect this regression."
        fi
        return
    fi

    if [ "$rc" -eq 0 ]; then
        RESULTS+=("$name|PASS|")
        log "PASS $name"
    else
        RESULTS+=("$name|FAIL|rc=$rc$tnote; see $logf")
        capture_artifacts "$serial" "$name"
        warn "FAIL $name (rc=$rc)$tnote — log: $logf"
    fi
}

# --------------------------------------------------------------------------- #
#  Summary
# --------------------------------------------------------------------------- #
print_summary() {
    local r name status note pass=0 fail=0 skip=0 other=0
    echo ""
    echo "==================== E2E SUMMARY ===================="
    printf '%-44s %-9s %s\n' "FLOW" "STATUS" "NOTE"
    for r in "${RESULTS[@]:-}"; do
        [ -n "$r" ] || continue
        name="${r%%|*}"; status="$(echo "$r" | cut -d'|' -f2)"; note="$(echo "$r" | cut -d'|' -f3-)"
        printf '%-44s %-9s %s\n' "$name" "$status" "$note"
        case "$status" in
            PASS|MUT-OK) pass=$((pass+1)) ;;
            FAIL|MUT-LEAK) fail=$((fail+1)) ;;
            SKIP) skip=$((skip+1)) ;;
            *) other=$((other+1)) ;;
        esac
    done
    echo "----------------------------------------------------"
    echo "pass=$pass  fail=$fail  skip=$skip  other=$other"
    echo "artifacts: $ARTIFACT_DIR"
    echo "===================================================="
    [ "$fail" -eq 0 ] || return 1
    # If literally everything was skipped, that's not a real green.
    if [ "$pass" -eq 0 ] && [ "$skip" -gt 0 ]; then
        warn "every requested flow was SKIPPED — nothing actually ran."
        return 2
    fi
    return 0
}

# --------------------------------------------------------------------------- #
#  Main
# --------------------------------------------------------------------------- #
NO_REMOTE=0
EXPECT_FAIL=""
SERIAL=""
REQUESTED=()

# Parse args.
while [ $# -gt 0 ]; do
    case "$1" in
        --help|-h) sed -n '2,30p' "$0"; exit 0 ;;
        --list) print_manifest; exit 0 ;;
        --no-remote) NO_REMOTE=1 ;;
        --expect-fail) shift; EXPECT_FAIL="${1:-}"
                       case "$EXPECT_FAIL" in ""|--*) die "--expect-fail needs a flow name" ;; esac
                       EXPECT_FAIL="${EXPECT_FAIL%.yaml}" ;;
        --*) die "unknown flag: $1" ;;
        *)
            if [ -z "$SERIAL" ]; then SERIAL="$1"; else REQUESTED+=("${1%.yaml}"); fi
            ;;
    esac
    shift
done

[ -n "$SERIAL" ] || die "missing <serial> (e.g. emulator-5554). See --help."

# Validate explicitly named flows first (cheap; a typo must be a LOUD error, not a silent SKIP
# that could still exit 0) — before touching the device or building.
for name in ${REQUESTED[@]+"${REQUESTED[@]}"}; do
    [ -f "$FLOW_DIR/$name.yaml" ] || die "no such flow: $name (see --list)"
done
[ -n "$EXPECT_FAIL" ] && { [ -f "$FLOW_DIR/$EXPECT_FAIL.yaml" ] || die "no such flow: $EXPECT_FAIL (see --list)"; }

command -v maestro >/dev/null 2>&1 || die "maestro not found (need >=2.2.0 for API 36)."
[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" = "device" ] || die "device $SERIAL is not online (start it via manage_avds.sh)."

# Build the queue: requested flows, or the whole manifest in order.
QUEUE=()
if [ "${#REQUESTED[@]}" -gt 0 ]; then
    QUEUE=("${REQUESTED[@]}")
else
    for entry in "${MANIFEST[@]}"; do QUEUE+=("${entry%%|*}"); done
fi
# In mutation mode, only the target flow runs.
if [ -n "$EXPECT_FAIL" ]; then QUEUE=("$EXPECT_FAIL"); fi

# Drop remote flows if --no-remote.
if [ "$NO_REMOTE" -eq 1 ]; then
    FILTERED=()
    for name in "${QUEUE[@]}"; do
        case "$(manifest_category "$name")" in
            s3|webdav|sftp) RESULTS+=("$name|SKIP|--no-remote") ;;
            *) FILTERED+=("$name") ;;
        esac
    done
    QUEUE=(${FILTERED[@]+"${FILTERED[@]}"})
fi

ARTIFACT_DIR="$REPO_ROOT/e2e/maestro/artifacts/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$ARTIFACT_DIR" || die "cannot create artifact dir: $ARTIFACT_DIR"

log "serial=$SERIAL  flows=${#QUEUE[@]}  artifacts=$ARTIFACT_DIR"

# Global prerequisites (once per run).
ensure_fresh_apk "$SERIAL"
ensure_test_repos "$SERIAL"

# Per-flow work queue.
for name in ${QUEUE[@]+"${QUEUE[@]}"}; do
    run_one "$SERIAL" "$name"
done

print_summary
exit $?

#!/usr/bin/env bash
# run_e2e.sh - per-flow Maestro E2E runner with prerequisites, state reset, and artifacts.
#
# Why a runner instead of `maestro test e2e/maestro/`:
#   - per-flow work queue: each flow runs in its own `maestro` process so one hang/crash
#     can't poison the rest, and we can reset state + capture artifacts between flows;
#   - explicit prerequisites per flow (fresh APK/bundle, test repos, restore-dir reset,
#     Docker backends) that are LOUDLY failed or VISIBLY skipped - never silently green;
#   - a mutation hook (--expect-fail) so a flow can be proven to FAIL on a broken build.
#
# This runner does NOT create/start AVDs (that's manage_avds.sh, which enforces the <=2 cap).
# Bring an emulator up first, then point this at its serial.
#
# Usage:
#   run_e2e.sh <serial> [flow ...]        Run the whole manifest, or only the named flows (default:
#                                         per-flow queue with prereqs/reset/retry/artifacts).
#   run_e2e.sh --list                     Print the flow manifest (name + category) and exit.
#   run_e2e.sh <serial> --no-remote ...   Skip remote (Docker) flows instead of running them.
#   run_e2e.sh <serial> --force ...       Override the pre-flight host-memory HARD gate (task-19).
#   run_e2e.sh <serial> --expect-fail F   Mutation test: run flow F and PASS only if it FAILS.
#   run_e2e.sh --shard-split s1,s2        FAST mode: native maestro sharding across devices, NO
#                                         per-flow reset/retry/artifacts (mutually exclusive w/ above).
#   run_e2e.sh --help
#
# Flow args may be a bare name ("restore_files"), with or without ".yaml".
# Env: E2E_FLOW_TIMEOUT (per-flow seconds, default 600); E2E_RETRY_MAX (auto-retries/flow, default 1);
#      E2E_MAX_RECOVERIES (auto cold-boots on emulator ANR, default 3); E2E_RESTART_EVERY (proactive
#      cold-boot every N flows, default 0=off); E2E_BOOT_TIMEOUT (boot wait seconds, default 120).
#      A pre-flight health gate (swap/compressor, not the misleading "free %") WARNs, or in a HARD-low
#      state aborts unless --force.
#
# Exit code: 0 only if every executed flow passed (skips don't fail the run unless every
# requested flow was skipped). Non-zero if any executed flow failed.

set -uo pipefail   # NOT -e: the per-flow loop must continue past a failing flow.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
FLOW_DIR="$REPO_ROOT/e2e/maestro"
APK="$REPO_ROOT/app-android/build/outputs/apk/debug/app-android-debug.apk"
DOCUMENTSUI="com.google.android.documentsui"

# Emulator binary (for mid-run recovery cold-boots; matches manage_avds.sh).
EMULATOR="$HOME/Library/Android/sdk/emulator/emulator"

# Shared host/emulator health helpers: pre-flight gate, ANR detection, robust stop (task-19).
# shellcheck source=host_health.sh
. "$SCRIPT_DIR/host_health.sh"

# --force overrides the pre-flight HARD memory gate. Recovery knobs (env-overridable):
FORCE=0
# Auto-recover a wedged (ANR'd) emulator by cold-boot + re-setup, at most this many times per run.
E2E_MAX_RECOVERIES="${E2E_MAX_RECOVERIES:-3}"
# Proactively cold-boot the AVD every N flows on long runs (0 = disabled; reactive ANR recovery is the
# safety net regardless).
E2E_RESTART_EVERY="${E2E_RESTART_EVERY:-0}"
BOOT_TIMEOUT_S="${E2E_BOOT_TIMEOUT:-120}"
RECOVERIES_DONE=0
HEALTH_LOG=""   # set in main once ARTIFACT_DIR exists
case "$E2E_MAX_RECOVERIES" in ''|*[!0-9]*) echo "[run_e2e] ERROR: E2E_MAX_RECOVERIES must be a non-negative integer" >&2; exit 1 ;; esac
case "$E2E_RESTART_EVERY"  in ''|*[!0-9]*) echo "[run_e2e] ERROR: E2E_RESTART_EVERY must be a non-negative integer" >&2; exit 1 ;; esac
case "$BOOT_TIMEOUT_S"     in ''|*[!0-9]*) echo "[run_e2e] ERROR: E2E_BOOT_TIMEOUT must be a non-negative integer" >&2; exit 1 ;; esac

# Per-flow wall-clock timeout - a hung maestro/adb/WebView must not block the whole queue.
# macOS has no `timeout` by default; prefer coreutils timeout/gtimeout, else a bash watchdog.
FLOW_TIMEOUT="${E2E_FLOW_TIMEOUT:-600}"
TIMEOUT_BIN=""
command -v timeout  >/dev/null 2>&1 && TIMEOUT_BIN="timeout"
[ -z "$TIMEOUT_BIN" ] && command -v gtimeout >/dev/null 2>&1 && TIMEOUT_BIN="gtimeout"

# Flake policy: auto-retry a failing flow up to this many times before recording FAIL.
RETRY_MAX="${E2E_RETRY_MAX:-1}"
# Both knobs must be non-negative integers. A negative/garbage RETRY_MAX would make attempts=0 -> the
# run loop never executes -> rc stays 0 -> every flow false-greens. Abort loudly instead.
case "$FLOW_TIMEOUT" in ''|*[!0-9]*) echo "[run_e2e] ERROR: E2E_FLOW_TIMEOUT must be a non-negative integer, got '$FLOW_TIMEOUT'" >&2; exit 1 ;; esac
case "$RETRY_MAX"    in ''|*[!0-9]*) echo "[run_e2e] ERROR: E2E_RETRY_MAX must be a non-negative integer, got '$RETRY_MAX'" >&2; exit 1 ;; esac

# --------------------------------------------------------------------------- #
#  Flow manifest - explicit + ordered. category drives per-flow prerequisites:
#    local   : test repos pushed (default)
#    restore : local + reset /sdcard/Download/_kopia_restore before the flow
#    backup  : local + a wiped app, a writable repo path and a fresh deterministic source tree,
#              reset before EVERY attempt; verified afterwards by Go kopia (host `kopia` required)
#    s3|webdav|sftp : local + Docker backend up & seeded (host `kopia` required)
#  (the dead flows/_connect_and_browse.yaml was removed in Phase 5.)
# --------------------------------------------------------------------------- #
MANIFEST=(
  "welcome_screen_smoke|local"
  "create_repo_screen_smoke|local"
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
  "settings_navigation_smoke|local"
  "sources_dashboard_smoke|local"
  "policy_editor|local"
  "backup_add_source|local"
  "backup_add_source_validation|local"
  "add_source_form_smoke|local"
  "maintenance_screen_smoke|local"
  "task_list_screen_smoke|local"
  "settings_disconnect|local"
  "exitdoor_disconnect_filebrowser|local"
  "exitdoor_disconnect_snapshots|local"
  "exitdoor_disconnect_source_snapshots|local"
  "restore_files|restore"
  "restore_flow|restore"
  "filebrowser_batch_select_restore_files|restore"
  "filebrowser_batch_select_all|local"
  "filebrowser_restore_directory_preservation|restore"
  "full_e2e_flow|restore"
  "restore_roundtrip|roundtrip"
  "backup_run_local|backup"
  "backup_policy_ignore|backup"
  "backup_retention|backup"
  "backup_task_survives_recreation|backup"
  "backup_cancel|backup"
  "backup_saf_source|backup"
  "backup_source_snapshots_back|backup"
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

# Require maestro present and >= 2.2.0 (needed for API 36). Warn (don't block) on an unparseable
# version so a format change upstream doesn't wedge the runner.
require_maestro() {
    command -v maestro >/dev/null 2>&1 || die "maestro not found (need >=2.2.0 for API 36)."
    local v major minor
    # Extract the first dotted version number anywhere in the output - handles bare "2.2.0" and
    # labeled forms like "Maestro 2.2.0". The grep guarantees major/minor are numeric.
    v="$(maestro --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1)"
    if [ -z "$v" ]; then
        warn "could not parse maestro version (got: '$(maestro --version 2>/dev/null | head -1)'); need >=2.2.0"
        return 0
    fi
    major="${v%%.*}"
    minor="$(printf '%s' "${v#*.}" | cut -d. -f1)"
    if [ "$major" -lt 2 ] || { [ "$major" -eq 2 ] && [ "$minor" -lt 2 ]; }; then
        die "maestro $v is too old; need >=2.2.0 (API 36). Upgrade maestro."
    fi
}

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

# Manifest integrity: every manifest flow must have a YAML file. A mismatch is a runner bug, not a
# skippable condition, so fail loudly before running anything.
validate_manifest() {
    local entry
    for entry in "${MANIFEST[@]}"; do
        [ -f "$FLOW_DIR/${entry%%|*}.yaml" ] || die "manifest references a missing flow: ${entry%%|*}.yaml (runner bug)"
    done
}

# --------------------------------------------------------------------------- #
#  Prerequisites
# --------------------------------------------------------------------------- #

# Rebuild the APK if missing or if any source tree is newer, then (re)install it.
# build-or-fail: a stale or unbuildable APK is a hard error, never a silent stale run.
# Rebuild the APK (React bundle + debug APK) if it's missing or any source is newer. build-or-fail:
# a stale or unbuildable APK is a hard error, never a silent stale run. Builds at most once per run.
build_apk_if_stale() {
    local need_build=0
    local src_dirs=( react-ui/src app-android/src core/src snapshot/src storage/src android/src )
    local d
    for d in "${src_dirs[@]}"; do
        [ -d "$REPO_ROOT/$d" ] || die "expected source dir missing: $d (broken checkout?)"
    done
    if [ ! -f "$APK" ]; then
        need_build=1
    else
        # Watch source trees AND build inputs (gradle/npm/vite) - a build-file change can also make
        # the APK stale. (src_dirs validated above, so a missing dir can't hide staleness.)
        local watch=( "${src_dirs[@]}" ) f
        for f in build.gradle.kts settings.gradle.kts gradle/libs.versions.toml app-android/build.gradle.kts \
                 react-ui/package.json react-ui/package-lock.json react-ui/vite.config.ts react-ui/index.html; do
            [ -e "$REPO_ROOT/$f" ] && watch+=("$f")
        done
        local newer
        newer="$(cd "$REPO_ROOT" && find "${watch[@]}" -type f -newer "$APK" -print -quit 2>/dev/null)"
        if [ -n "$newer" ]; then
            log "Sources or build inputs newer than the APK - rebuilding."
            need_build=1
        fi
    fi
    if [ "$need_build" -eq 1 ]; then
        log "Building React bundle (react-ui)..."
        ( cd "$REPO_ROOT/react-ui" && npm run build ) || die "react-ui build failed"
        log "Building debug APK..."
        ( cd "$REPO_ROOT" && ./gradlew :app-android:assembleDebug ) || die "assembleDebug failed"
        [ -f "$APK" ] || die "APK still missing after build: $APK"
    fi
}

install_apk() {
    local serial="$1"
    log "Installing APK on $serial..."
    adb -s "$serial" install -r -g "$APK" >/dev/null || die "APK install failed on $serial"
}

# Single-device convenience: build (if stale) then install.
ensure_fresh_apk() {
    build_apk_if_stale
    install_apk "$1"
}

# Apply device-stability settings (animations/IME/autofill/doze off, screen on). The runner is a
# valid entry point on its own, so don't assume `manage_avds.sh setup` already configured the device.
ensure_device_configured() {
    local serial="$1"
    log "Configuring $serial for E2E stability..."
    "$SCRIPT_DIR/configure_avd.sh" "$serial" >/dev/null 2>&1 || warn "configure_avd.sh reported issues on $serial"
}

# Push the read-only test repos + grant storage. Done once per run (fixtures don't mutate).
ensure_test_repos() {
    local serial="$1"
    log "Pushing test repositories to $serial..."
    "$SCRIPT_DIR/setup_test_repo.sh" "$serial" >/dev/null || die "test repo setup failed on $serial"
}

# Reset the restore destination before a restore flow so leftovers can't fake a pass.
reset_restore_dir() {
    local serial="$1"
    "$SCRIPT_DIR/setup_restore_dir.sh" "$serial" >/dev/null || die "restore-dir reset failed on $serial"
}

# Minimum number of files a restore flow must leave on disk. A UI "Restore Complete" is not proof —
# the writer could no-op. Per-flow exact counts where known; otherwise the generic floor (>=1) still
# catches the gross false-pass (restore claimed complete but wrote nothing). The dir is reset before
# the flow, so the count reflects only this flow's output.
restore_min_files() {
    case "$1" in
        restore_files|restore_flow) echo 76 ;;   # whole edge_case_repo snapshot root
        # Partial restore of two selected folders (.hidden_dir + level1), each holding exactly one
        # file. Require both so a silently-dropped selection fails the count check (not just MIN=1).
        filebrowser_batch_select_restore_files) echo 2 ;;
        *) echo 1 ;;                              # generic: at least one file must land
    esac
}

# Bring up + seed Docker backends. Returns non-zero (without aborting) when the tooling is
# missing so remote flows become a VISIBLE skip rather than a silent green.
REMOTE_READY=-1   # -1 unknown, 0 unavailable, 1 ready
ensure_remote_backends() {
    [ "$REMOTE_READY" -ne -1 ] && return $(( REMOTE_READY == 1 ? 0 : 1 ))
    if ! command -v docker >/dev/null 2>&1; then
        warn "docker not found - remote (S3/WebDAV/SFTP) flows will be SKIPPED."
        REMOTE_READY=0; return 1
    fi
    if ! command -v kopia >/dev/null 2>&1; then
        warn "host 'kopia' CLI not found (brew install kopia) - remote flows will be SKIPPED (cannot seed)."
        REMOTE_READY=0; return 1
    fi
    log "Starting Docker storage backends..."
    if ! "$SCRIPT_DIR/start_storage_backends.sh" >/dev/null 2>&1; then
        warn "start_storage_backends.sh failed - remote flows will be SKIPPED."
        REMOTE_READY=0; return 1
    fi
    log "Seeding Docker storage backends..."
    if ! "$SCRIPT_DIR/seed_storage_backends.sh" >/dev/null 2>&1; then
        warn "seed_storage_backends.sh failed - remote flows will be SKIPPED."
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
    # Resized screenshot (>2000px poisons the agent's context - keep <=1920px).
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
#  Emulator recovery (task-19)
# --------------------------------------------------------------------------- #
# Append a one-line host/emulator health snapshot to the run's health log.
log_health() {
    local serial="$1" tag="$2"
    [ -n "$HEALTH_LOG" ] || return 0
    printf '%s  %-44s %s\n' "$(date +%H:%M:%S)" "$tag" "$(hh_snapshot "$serial")" >> "$HEALTH_LOG" 2>/dev/null || true
}

# Recover a wedged/degraded emulator: robustly stop it, cold-boot it fresh, wait for boot, re-run device
# setup. The relaunched emulator's output is redirected to a file so the detached qemu can't inherit (and
# pin open) the runner's stdout pipe. Returns 0 on success. task-19.
restart_avd() {
    local serial="$1" reason="${2:-}" port idx avd elapsed=0 booted=""
    port="${serial##*-}"
    idx=$(( (port - 5554) / 2 + 1 ))
    avd="e2e_avd_${idx}"
    warn "RECOVER $serial${reason:+ ($reason)} - cold-booting $avd. $(hh_snapshot "$serial")"
    log_health "$serial" "recover-before:$reason"
    hh_robust_stop "$serial" || warn "  could not confirm the old emulator exited; relaunching anyway"
    "$EMULATOR" -avd "$avd" -port "$port" \
        -no-snapshot-load -no-snapshot-save -no-boot-anim -no-audio -gpu swiftshader_indirect \
        >"$ARTIFACT_DIR/emu-recover-$idx.log" 2>&1 &
    # Bounded boot wait: poll boot_completed (each adb call time-boxed when a timeout binary exists) up
    # to BOOT_TIMEOUT_S. No unbounded `adb wait-for-device` - it can hang forever if the device never
    # reappears; the poll loop returns failure within the timeout instead.
    while [ "$elapsed" -lt "$BOOT_TIMEOUT_S" ]; do
        # shellcheck disable=SC2086  # intentional word-split: empty when no timeout binary is present
        booted="$(${TIMEOUT_BIN:+$TIMEOUT_BIN 10} adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
        [ "$booted" = "1" ] && break
        sleep 2; elapsed=$((elapsed + 2))
    done
    if [ "$booted" != "1" ]; then
        warn "  $serial did not boot within ${BOOT_TIMEOUT_S}s after recovery"
        return 1
    fi
    log "  $serial re-booted in ~${elapsed}s; re-running device setup..."
    ensure_device_configured "$serial"
    # Tolerant setup (NOT the die-ing install_apk/ensure_test_repos): a recovery-time install/push
    # failure must degrade to a failed recovery (caller records INFRA / continues), never abort the run.
    if ! adb -s "$serial" install -r -g "$APK" >/dev/null 2>&1; then
        warn "  recovery: APK reinstall failed on $serial"; return 1
    fi
    if ! "$SCRIPT_DIR/setup_test_repo.sh" "$serial" >/dev/null 2>&1; then
        warn "  recovery: test-repo push failed on $serial"; return 1
    fi
    log_health "$serial" "recover-after:$reason"
    # NOTE: the recovery-budget counter (RECOVERIES_DONE) is incremented by the ANR-recovery caller, not
    # here, so proactive (E2E_RESTART_EVERY) cold-boots don't consume the ANR budget.
    return 0
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
RETRIED=()   # names that were retried at least once
run_one() {
    local serial="$1" name="$2"
    local flow="$FLOW_DIR/$name.yaml"
    local cat; cat="$(manifest_category "$name")"

    if [ ! -f "$flow" ]; then
        RESULTS+=("$name|SKIP|no such flow file"); warn "$name: no such flow file"; return
    fi

    # Remote backends: one-time availability gate; unavailable -> VISIBLE skip, never a silent green.
    case "$cat" in
        s3|webdav|sftp)
            if ! ensure_remote_backends; then
                RESULTS+=("$name|SKIP|remote backend unavailable ($cat)")
                log "SKIP $name ($cat backend unavailable)"; return
            fi
            ;;
    esac

    # Arguments for verify_backup.sh, per flow. Each backup flow asserts a different repository state, so
# a single fixed invocation could not tell "exactly one snapshot" from "exactly two".
backup_verify_args() {
    case "$1" in
        backup_run_local|backup_task_survives_recreation) echo "--expect-snapshots 1" ;;
        backup_saf_source)   echo "--expect-snapshots 2" ;;
        backup_policy_ignore) echo "--expect-snapshots 1 --expect-absent excluded/secret.txt" ;;
        backup_retention)    echo "--expect-snapshots 2" ;;
        # backup_cancel asserts NO complete snapshot; verify_backup would fail on an empty repo, so
        # the flow's own assertions are the check there.
        *) echo "" ;;
    esac
}

# Round-trip flows back up a deterministic source with Go kopia and push that repo (loud-fail,
    # never skip — the whole point is an independent original-vs-restored byte check).
    if [ "$cat" = "roundtrip" ]; then
        if ! "$SCRIPT_DIR/setup_roundtrip_repo.sh" "$serial" >"$ARTIFACT_DIR/$name.setup.log" 2>&1; then
            RESULTS+=("$name|FAIL|round-trip setup failed (Go kopia required); see $name.setup.log")
            warn "FAIL $name - setup_roundtrip_repo.sh failed; see $ARTIFACT_DIR/$name.setup.log"
            return
        fi
    fi

    # Flake policy: up to RETRY_MAX auto-retries, then fail. Mutation mode never retries (we want the
    # single deliberate result).
    local attempts=$(( RETRY_MAX + 1 ))
    if [ -n "$EXPECT_FAIL" ] && [ "$EXPECT_FAIL" = "$name" ]; then attempts=1; fi

    local rc=0 i retried=0 logf="" anr_infra=0
    log_health "$serial" "$name"
    for (( i = 1; i <= attempts; i++ )); do
        # Reset volatile state before EVERY attempt (including retries) so a retry starts clean.
        adb -s "$serial" shell am force-stop "$DOCUMENTSUI" >/dev/null 2>&1 || true
        case "$cat" in
            restore|roundtrip) reset_restore_dir "$serial" ;;
            backup)
                if ! "$SCRIPT_DIR/setup_backup_env.sh" "$serial" >"$ARTIFACT_DIR/$name.setup.log" 2>&1; then
                    RESULTS+=("$name|FAIL|backup env setup failed; see $name.setup.log")
                    warn "FAIL $name - setup_backup_env.sh failed; see $ARTIFACT_DIR/$name.setup.log"
                    return
                fi
                ;;
        esac

        if [ "$i" -eq 1 ]; then log "RUN  $name ($cat)..."; else log "RETRY $name (attempt $i/$attempts)..."; retried=1; fi
        rc=0
        # Per-attempt log so a pass-on-retry doesn't erase the failing attempt's evidence.
        logf="$ARTIFACT_DIR/$name.attempt-$i.maestro.log"
        run_flow_cmd "$FLOW_TIMEOUT" "$serial" "$flow" "$logf" || rc=$?
        if [ "$rc" -eq 0 ]; then anr_infra=0; break; fi
        # Failed attempt. Classify THIS attempt (anr_infra is per-attempt, NOT sticky): the GUEST is
        # wedged (ANR -> infra) if the maestro log shows the ANR dialog OR a live probe says so. Keeping
        # it per-attempt means a genuine regression on the FINAL attempt is reported as FAIL even if an
        # earlier attempt ANR'd. Cold-boot before the next retry (within the ANR recovery budget).
        anr_infra=0
        # A maestro log that names the step it failed on is a VERDICT ABOUT THE APP, and it outranks
        # every ANR heuristic. Without this the classifier could - and did - relabel a flow that
        # failed the same assertion on every attempt as "not a code failure", which left the whole
        # backup category unable to report a red (task-41).
        if hh_flow_failed_deterministically "$logf"; then
            log "  $name failed at: $(hh_maestro_failing_step "$logf")"
        elif [ -z "$EXPECT_FAIL" ] && { grep -qiE "isn't responding" "$logf" 2>/dev/null || hh_detect_anr "$serial"; }; then
            anr_infra=1
        elif [ -z "$EXPECT_FAIL" ] && hh_host_unresponsive "$serial"; then
            # The guest is not answering at all, but nothing identified it as an ANR of THIS app, so
            # the result stays whatever the evidence says (usually a timed-out FAIL). Recovery is a
            # separate decision from classification: without a cold-boot here every remaining flow
            # runs against the same corpse. Host slowness must fix the emulator, never the verdict.
            warn "  $name: guest not responding - cold-booting before the next attempt (result unchanged)."
            if [ "$i" -lt "$attempts" ] && [ "$RECOVERIES_DONE" -lt "$E2E_MAX_RECOVERIES" ]; then
                RECOVERIES_DONE=$((RECOVERIES_DONE + 1))
                restart_avd "$serial" "unresponsive guest during $name" || { warn "  recovery cold-boot failed; emulator likely unusable - stopping retries for $name."; break; }
            fi
            if [ "$i" -lt "$attempts" ]; then
                if [ "$RECOVERIES_DONE" -lt "$E2E_MAX_RECOVERIES" ]; then
                    # Count the recovery ATTEMPT against the budget (success or not) so repeated failed
                    # cold-boots can't loop unbounded. If the cold-boot itself fails, the emulator is
                    # unusable: stop retrying and keep anr_infra=1 so the flow is recorded INFRA, not FAIL.
                    RECOVERIES_DONE=$((RECOVERIES_DONE + 1))
                    restart_avd "$serial" "ANR during $name" || { warn "  recovery cold-boot failed; emulator likely unusable - stopping retries for $name."; break; }
                else
                    warn "  ANR during $name but recovery budget exhausted (E2E_MAX_RECOVERIES=$E2E_MAX_RECOVERIES) - stopping retries."
                    break
                fi
            fi
        fi
    done
    [ "$retried" -eq 1 ] && RETRIED+=("$name")

    local mark=""
    case "$rc" in 124|137|143) mark=" (timed out after ${FLOW_TIMEOUT}s)" ;; esac
    [ "$retried" -eq 1 ] && mark="$mark (retried)"

    if [ -n "$EXPECT_FAIL" ] && [ "$EXPECT_FAIL" = "$name" ]; then
        # Mutation mode: the feature is deliberately broken, so we WANT this flow to fail - but only a
        # genuine assertion failure counts. A TIMEOUT (infra) is NOT proof the flow caught the break.
        # Always run a clean baseline pass FIRST and verify the failure reason in the artifacts.
        local timed_out=0 mut_step
        case "$rc" in 124|137|143) timed_out=1 ;; esac
        mut_step="$(hh_maestro_failing_step "$logf")"
        # For restore/roundtrip, the on-disk byte verifier is part of "did the flow pass" - consult it
        # so a mutation that corrupts data (UI still shows "Restore Complete") counts as MUT-OK, not
        # MUT-LEAK. Only when the UI itself passed (rc=0) and it wasn't an infra timeout.
        if [ "$rc" -eq 0 ] && [ "$timed_out" -eq 0 ]; then
            case "$cat" in
                roundtrip) "$SCRIPT_DIR/verify_roundtrip.sh" "$serial" >/dev/null 2>&1 || rc=1 ;;
                restore)   "$SCRIPT_DIR/verify_restore.sh" "$serial" "$(restore_min_files "$name")" >/dev/null 2>&1 || rc=1 ;;
                backup)
                    # shellcheck disable=SC2046 # deliberate word splitting of the per-flow arguments
                    if [ -n "$(backup_verify_args "$name")" ]; then
                        "$SCRIPT_DIR/verify_backup.sh" "$serial" $(backup_verify_args "$name") >/dev/null 2>&1 || rc=1
                    fi
                    ;;
            esac
        fi
        if [ "$timed_out" -eq 1 ]; then
            RESULTS+=("$name|MUT-INCONCLUSIVE|timed out$mark - not proof the flow detected the break; rerun")
            capture_artifacts "$serial" "$name"
            warn "MUT-INCONCLUSIVE $name - timed out; confirm a clean baseline first, then rerun."
        elif [ "$rc" -ne 0 ] && ! hh_flow_failed_deterministically "$logf" \
             && [ "$cat" != "restore" ] && [ "$cat" != "roundtrip" ] && [ "$cat" != "backup" ]; then
            # It failed, but maestro never named a step -- an ANR or a crash, not the flow catching
            # the break. Claiming MUT-OK here would certify a flow as regression-proof on evidence
            # it never produced. (The byte-verifier categories are exempt: there rc can be set by
            # the verifier above, with the UI having passed and named nothing.)
            RESULTS+=("$name|MUT-INCONCLUSIVE|failed with no failing step recorded$mark - ANR/crash, not proof the flow detected the break; rerun")
            capture_artifacts "$serial" "$name"
            warn "MUT-INCONCLUSIVE $name - failed without naming a step; rerun on a healthy emulator."
        elif [ "$rc" -ne 0 ]; then
            RESULTS+=("$name|MUT-OK|failed on broken build$mark${mut_step:+ at: $mut_step} - verify reason in artifacts")
            capture_artifacts "$serial" "$name"
            log "MUT-OK $name - flow FAILED on the broken build${mut_step:+ at: $mut_step} (confirm the failure reason in artifacts)."
        else
            RESULTS+=("$name|MUT-LEAK|passed on broken build (FALSE PASS!)")
            capture_artifacts "$serial" "$name"
            warn "MUT-LEAK $name - flow PASSED on a broken build; it cannot detect this regression."
        fi
        return
    fi

    if [ "$rc" -eq 0 ]; then
        # Restore flows: a UI "Restore Complete" is not enough — verify the restored bytes match a
        # Go-kopia reference restore of the same snapshot (verify_restore.sh: count + per-file md5).
        if [ "$cat" = "restore" ] || [ "$cat" = "roundtrip" ] || { [ "$cat" = "backup" ] && [ -n "$(backup_verify_args "$name")" ]; }; then
            local vout vrc=0 vlast
            if [ "$cat" = "roundtrip" ]; then
                vout="$("$SCRIPT_DIR/verify_roundtrip.sh" "$serial" 2>&1)" || vrc=$?
            elif [ "$cat" = "backup" ]; then
                # shellcheck disable=SC2046 # deliberate word splitting of the per-flow arguments
                vout="$("$SCRIPT_DIR/verify_backup.sh" "$serial" $(backup_verify_args "$name") 2>&1)" || vrc=$?
            else
                vout="$("$SCRIPT_DIR/verify_restore.sh" "$serial" "$(restore_min_files "$name")" 2>&1)" || vrc=$?
            fi
            vlast="$(printf '%s' "$vout" | tail -1)"
            if [ "$vrc" -ne 0 ]; then
                printf '%s\n' "$vout" > "$ARTIFACT_DIR/$name.restore-verify.log"
                RESULTS+=("$name|FAIL|integrity: $vlast$mark")
                capture_artifacts "$serial" "$name"
                warn "FAIL $name - restore integrity check failed: $vlast (see $ARTIFACT_DIR/$name.restore-verify.log)"
                return
            fi
            RESULTS+=("$name|PASS|$vlast${mark:+;$mark}")
            log "PASS $name - $vlast$mark"
            return
        fi
        RESULTS+=("$name|PASS|${mark# }")
        log "PASS $name$mark"
    elif [ "$anr_infra" -eq 1 ]; then
        # The guest was wedged (ANR), not the feature broken - record INFRA so degradation never
        # masquerades as a code regression. Recovery was attempted; see health.log + the maestro log.
        # The failing step is printed even here so an INFRA row can be told apart from a real failure
        # without opening the log by hand; if maestro named a step, this row would not be INFRA.
        local infra_step; infra_step="$(hh_maestro_failing_step "$logf")"
        RESULTS+=("$name|INFRA|emulator ANR/degradation (recovered=$RECOVERIES_DONE), no failing step recorded$mark; ${infra_step:+failed at: $infra_step; }see $logf")
        capture_artifacts "$serial" "$name"
        warn "INFRA $name - emulator wedged (ANR) and no failing step was recorded$mark - re-run before trusting this - log: $logf"
    else
        local failed_step; failed_step="$(hh_maestro_failing_step "$logf")"
        RESULTS+=("$name|FAIL|${failed_step:+failed at: $failed_step; }rc=$rc$mark; see $logf")
        capture_artifacts "$serial" "$name"
        warn "FAIL $name (rc=$rc)$mark${failed_step:+ - failed at: $failed_step} - log: $logf"
    fi
}

# --------------------------------------------------------------------------- #
#  Summary
# --------------------------------------------------------------------------- #
print_summary() {
    local r name status note pass=0 fail=0 skip=0 infra=0 other=0
    echo ""
    echo "==================== E2E SUMMARY ===================="
    printf '%-44s %-9s %s\n' "FLOW" "STATUS" "NOTE"
    for r in "${RESULTS[@]:-}"; do
        [ -n "$r" ] || continue
        name="${r%%|*}"; status="$(echo "$r" | cut -d'|' -f2)"; note="$(echo "$r" | cut -d'|' -f3-)"
        printf '%-44s %-9s %s\n' "$name" "$status" "$note"
        case "$status" in
            PASS|MUT-OK) pass=$((pass+1)) ;;
            FAIL|MUT-LEAK|MUT-INCONCLUSIVE) fail=$((fail+1)) ;;
            SKIP) skip=$((skip+1)) ;;
            INFRA) infra=$((infra+1)) ;;
            *) other=$((other+1)) ;;
        esac
    done
    echo "----------------------------------------------------"
    echo "pass=$pass  fail=$fail  skip=$skip  infra=$infra  other=$other"
    [ "${#RETRIED[@]}" -gt 0 ] && echo "retried (flaky): ${RETRIED[*]}"
    # Deliberately not phrased as "NOT a code failure" any more. INFRA now means only that maestro
    # never named a failing step; it is a reason to re-run, not evidence the code is fine. Each row
    # carries the last step reached so the two can be told apart without opening the log.
    [ "$infra" -gt 0 ] && warn "$infra flow(s) ended INFRA - no failing step was recorded, so this looks like emulator ANR/degradation rather than a code failure. Re-run before trusting it (task-19; see ${HEALTH_LOG:-health.log})."
    echo "artifacts: $ARTIFACT_DIR"
    echo "===================================================="
    [ "$fail" -eq 0 ] && [ "$infra" -eq 0 ] || return 1
    # If literally everything was skipped, that's not a real green.
    if [ "$pass" -eq 0 ] && [ "$skip" -gt 0 ]; then
        warn "every requested flow was SKIPPED - nothing actually ran."
        return 2
    fi
    return 0
}

# --------------------------------------------------------------------------- #
#  Shard mode (fast, mutually-exclusive with the per-flow runner)
# --------------------------------------------------------------------------- #
# Runs the whole manifest natively sharded across up to 2 devices via maestro's own --shard-split.
# This is a SPEED escape hatch: it does NOT do per-flow reset, retry, artifacts, or visible-skip -
# for trustworthy results use the per-flow runner (the default).
run_shard_mode() {
    require_maestro
    validate_manifest
    case "$SHARD_SERIALS" in
        ,*|*,|*,,*) die "--shard-split: malformed serial list '$SHARD_SERIALS' (no leading/trailing/double commas)" ;;
    esac
    local serials
    IFS=',' read -r -a serials <<< "$SHARD_SERIALS"
    [ "${#serials[@]}" -ge 1 ] || die "--shard-split needs at least one serial"
    [ "${#serials[@]}" -le 2 ] || die "--shard-split: at most 2 serials (the AVD cap)"
    local s
    for s in "${serials[@]}"; do
        [ "$(adb -s "$s" get-state 2>/dev/null)" = "device" ] || die "device $s is not online (start it via manage_avds.sh)."
    done

    log "SHARD mode across ${#serials[@]} device(s): $SHARD_SERIALS"
    warn "FAST mode - NO per-flow reset / retry / artifacts / visible-skip. Use the per-flow runner for trustworthy results."

    build_apk_if_stale
    for s in "${serials[@]}"; do ensure_device_configured "$s"; install_apk "$s"; ensure_test_repos "$s"; done
    ensure_remote_backends || warn "remote backends unavailable - remote flows will FAIL in shard mode (no per-flow skip)."

    # Exclude restore/roundtrip flows: shard mode has no per-flow hooks, so the round-trip repo would
    # not be set up (the flow would hit a missing repo) and restore byte-verification would be skipped
    # (a UI-only pass is a false pass). Those flows must go through the per-flow runner.
    local files=() entry skipped=()
    for entry in "${MANIFEST[@]}"; do
        case "${entry##*|}" in
            restore|roundtrip|backup) skipped+=("${entry%%|*}") ;;
            *) files+=("$FLOW_DIR/${entry%%|*}.yaml") ;;
        esac
    done
    [ "${#skipped[@]}" -eq 0 ] || warn "shard mode SKIPS restore/roundtrip/backup flows (need per-attempt setup + byte-verification; run them via the per-flow runner): ${skipped[*]}"

    log "Running ${#files[@]} flows sharded ${#serials[@]}-way..."
    maestro --device "$SHARD_SERIALS" test --shard-split "${#serials[@]}" "${files[@]}"
}

# --------------------------------------------------------------------------- #
#  Main
# --------------------------------------------------------------------------- #
NO_REMOTE=0
EXPECT_FAIL=""
SERIAL=""
SHARD_SERIALS=""
REQUESTED=()

# Parse args.
while [ $# -gt 0 ]; do
    case "$1" in
        --help|-h) sed -n '2,34p' "$0"; exit 0 ;;
        --list) print_manifest; exit 0 ;;
        --no-remote) NO_REMOTE=1 ;;
        --force) FORCE=1 ;;
        --expect-fail) shift; EXPECT_FAIL="${1:-}"
                       case "$EXPECT_FAIL" in ""|--*) die "--expect-fail needs a flow name" ;; esac
                       EXPECT_FAIL="${EXPECT_FAIL%.yaml}" ;;
        --shard-split) shift; SHARD_SERIALS="${1:-}"
                       case "$SHARD_SERIALS" in ""|--*) die "--shard-split needs comma-separated serials" ;; esac ;;
        --*) die "unknown flag: $1" ;;
        *)
            if [ -z "$SERIAL" ]; then SERIAL="$1"; else REQUESTED+=("${1%.yaml}"); fi
            ;;
    esac
    shift
done

# Shard mode is a separate, mutually-exclusive fast path (its own validation + execution).
if [ -n "$SHARD_SERIALS" ]; then
    [ -z "$EXPECT_FAIL" ] || die "--shard-split is mutually exclusive with --expect-fail"
    [ "${#REQUESTED[@]}" -eq 0 ] || die "--shard-split runs the whole manifest; don't also name flows"
    [ -z "$SERIAL" ] || die "--shard-split takes no positional serial/flow; pass serials to --shard-split"
    [ "$NO_REMOTE" -eq 0 ] || die "--no-remote is not supported in --shard-split mode"
    run_shard_mode
    exit $?
fi

[ -n "$SERIAL" ] || die "missing <serial> (e.g. emulator-5554). See --help."

# Validate explicitly named flows first (cheap; a typo must be a LOUD error, not a silent SKIP
# that could still exit 0) - before touching the device or building.
for name in ${REQUESTED[@]+"${REQUESTED[@]}"}; do
    [ -f "$FLOW_DIR/$name.yaml" ] || die "no such flow: $name (see --list)"
done
[ -n "$EXPECT_FAIL" ] && { [ -f "$FLOW_DIR/$EXPECT_FAIL.yaml" ] || die "no such flow: $EXPECT_FAIL (see --list)"; }

require_maestro
validate_manifest
[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" = "device" ] || die "device $SERIAL is not online (start it via manage_avds.sh)."

# Pre-flight host/emulator health gate (task-19): swap/compressor (NOT the misleading "free %") drive
# the call. HARD aborts unless --force; WARN proceeds (a mid-run ANR is then auto-recovered).
hh_preflight "$SERIAL"; pf_rc=$?
if [ "$pf_rc" -eq 2 ] && [ "$FORCE" -ne 1 ]; then
    die "host memory too low for a reliable emulator run (see [health] HARD above). Free RAM (quit containers/browsers), or re-run with --force."
fi

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
HEALTH_LOG="$ARTIFACT_DIR/health.log"
echo "# host/emulator health log (task-19): time  tag  metrics" > "$HEALTH_LOG"

log "serial=$SERIAL  flows=${#QUEUE[@]}  artifacts=$ARTIFACT_DIR"

# Global prerequisites (once per run).
ensure_device_configured "$SERIAL"
ensure_fresh_apk "$SERIAL"
ensure_test_repos "$SERIAL"

# Per-flow work queue. Optionally cold-boot the AVD every N flows (E2E_RESTART_EVERY) to keep memory
# churn down on long runs; the reactive ANR recovery in run_one is the safety net regardless.
flow_idx=0
for name in ${QUEUE[@]+"${QUEUE[@]}"}; do
    if [ "$E2E_RESTART_EVERY" -gt 0 ] && [ "$flow_idx" -gt 0 ] && [ $(( flow_idx % E2E_RESTART_EVERY )) -eq 0 ]; then
        log "Proactive AVD cold-boot after $flow_idx flows (E2E_RESTART_EVERY=$E2E_RESTART_EVERY)."
        restart_avd "$SERIAL" "proactive/${E2E_RESTART_EVERY}" || warn "proactive restart did not fully succeed; continuing."
    fi
    run_one "$SERIAL" "$name"
    flow_idx=$((flow_idx + 1))
done

print_summary
exit $?

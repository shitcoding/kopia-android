#!/bin/bash
# Dynamic AVD management for parallel Maestro E2E tests.
# Creates, starts, sets up, and tears down emulators programmatically.
#
# AVDs are created by writing config files directly (like Android Studio),
# avoiding avdmanager CLI issues with system image resolution.
#
# Usage:
#   manage_avds.sh create <count>   - Create N AVDs (e2e_avd_1, e2e_avd_2, ...)
#   manage_avds.sh start <count>    - Start N AVDs with visible windows
#   manage_avds.sh setup <count>    - Install APK + push test data to running AVDs
#   manage_avds.sh list             - List running e2e AVDs with serials
#   manage_avds.sh stop             - Stop all running e2e AVDs
#   manage_avds.sh delete           - Delete all e2e AVDs

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Tools
EMULATOR="$HOME/Library/Android/sdk/emulator/emulator"
ADB="adb"

# AVD configuration
AVD_PREFIX="e2e_avd"
AVD_DIR="$HOME/.android/avd"
SYSTEM_IMAGE_DIR="system-images/android-36/google_apis/arm64-v8a/"
BASE_PORT=5554

# Boot timeout (seconds)
BOOT_TIMEOUT=120

# Hard cap on concurrent e2e AVDs. >2 AVDs + a hot Gradle build OOM Apple-Silicon
# machines (see the internal troubleshooting notes). Override deliberately with --force.
MAX_AVDS=2

# Set to 1 when --force is passed (parsed in main, below).
FORCE=0

# --------------------------------------------------------------------------- #
#  Helpers
# --------------------------------------------------------------------------- #

avd_name() {
    echo "${AVD_PREFIX}_${1}"
}

avd_port() {
    echo $(( BASE_PORT + ($1 - 1) * 2 ))
}

avd_serial() {
    echo "emulator-$(avd_port "$1")"
}

# Reject a non-numeric count, or a count above MAX_AVDS unless --force was given.
# Guards create/start/setup so a stray 3rd AVD can't be spun up by accident.
check_avd_cap() {
    local count="$1"
    # Reject non-numeric or absurdly long counts BEFORE any arithmetic: on bash 3.2 a value past the
    # 64-bit range makes `[ -gt ]` error out, which would make the guard fall open.
    if ! [[ "$count" =~ ^[1-9][0-9]*$ ]] || [ "${#count}" -gt 3 ]; then
        echo "ERROR: count must be a small positive integer, got: '$count'" >&2
        return 1
    fi
    if [ "$count" -gt "$MAX_AVDS" ] && [ "$FORCE" -ne 1 ]; then
        echo "ERROR: Refusing $count AVDs — the hard cap is $MAX_AVDS." >&2
        echo "  >$MAX_AVDS AVDs + a hot Gradle build OOM Apple-Silicon machines" >&2
        echo "  (see the internal troubleshooting notes). Re-run with --force to override at your own risk." >&2
        return 1
    fi
}

# Return list of running e2e emulator serials.
# Use awk (not grep) for the filter so a no-match exits 0 — grep's exit 1 would abort
# the `serials=$(...)` callers under `set -o pipefail` before they can handle "none running".
running_e2e_serials() {
    $ADB devices 2>/dev/null | awk '/^emulator-/ {print $1}' | while read -r serial; do
        # Check if this port belongs to one of our e2e AVDs
        local avd_name_on_device
        avd_name_on_device=$($ADB -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)
        if [[ "$avd_name_on_device" == ${AVD_PREFIX}_* ]]; then
            echo "$serial"
        fi
    done
}

wait_for_boot() {
    local serial="$1"
    local timeout="$2"
    local elapsed=0

    echo "  Waiting for $serial to boot (timeout: ${timeout}s)..."
    $ADB -s "$serial" wait-for-device

    while [ $elapsed -lt "$timeout" ]; do
        local booted
        booted=$($ADB -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
        if [ "$booted" = "1" ]; then
            echo "  $serial booted in ${elapsed}s"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done

    echo "  ERROR: $serial failed to boot within ${timeout}s"
    return 1
}

# --------------------------------------------------------------------------- #
#  Commands
# --------------------------------------------------------------------------- #

cmd_create() {
    local count="${1:?Usage: manage_avds.sh create <count>}"
    check_avd_cap "$count" || return 1

    # Verify system image exists
    local sysimg_path="$HOME/Library/Android/sdk/$SYSTEM_IMAGE_DIR"
    if [ ! -d "$sysimg_path" ]; then
        echo "ERROR: System image not found at $sysimg_path"
        echo "Install it: sdkmanager 'system-images;android-36;google_apis;arm64-v8a'"
        return 1
    fi

    echo "Creating $count AVD(s)..."

    for i in $(seq 1 "$count"); do
        local name
        name=$(avd_name "$i")
        local avd_path="$AVD_DIR/${name}.avd"
        local avd_ini="$AVD_DIR/${name}.ini"

        if [ -d "$avd_path" ]; then
            echo "  [$i/$count] $name already exists, skipping (use 'delete' first to recreate)"
            continue
        fi

        echo "  [$i/$count] Creating $name..."

        # Create AVD directory
        mkdir -p "$avd_path"

        # Write top-level .ini (tells emulator where the AVD lives)
        cat > "$avd_ini" << EOF
avd.ini.encoding=UTF-8
path=$avd_path
path.rel=avd/${name}.avd
target=android-36
EOF

        # Write full config.ini (hardware settings matching test_avd)
        cat > "$avd_path/config.ini" << EOF
AvdId=$name
PlayStore.enabled=false
abi.type=arm64-v8a
avd.ini.displayname=$name
avd.ini.encoding=UTF-8
disk.dataPartition.size=2G
fastboot.chosenSnapshotFile=
fastboot.forceChosenSnapshotBoot=no
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
hw.accelerometer=yes
hw.arc=false
hw.audioInput=yes
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=arm64
hw.cpu.ncore=2
hw.dPad=no
hw.device.hash2=MD5:dcecb1ba8ce173b79804663388815805
hw.device.manufacturer=User
hw.device.name=$name
hw.gps=no
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.gyroscope=yes
hw.initialOrientation=portrait
hw.keyboard=yes
hw.lcd.density=320
hw.lcd.height=1980
hw.lcd.width=882
hw.mainKeys=yes
hw.ramSize=2048
hw.sdCard=yes
hw.sensors.light=yes
hw.sensors.magnetic_field=yes
hw.sensors.orientation=yes
hw.sensors.pressure=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=$SYSTEM_IMAGE_DIR
runtime.network.latency=none
runtime.network.speed=full
sdcard.size=512M
showDeviceFrame=yes
skin.dynamic=yes
tag.display=Google APIs
tag.displaynames=Google APIs
tag.id=google_apis
tag.ids=google_apis
vm.heapSize=256
EOF

        echo "  [$i/$count] $name created"
    done

    echo ""
    echo "Done. AVDs created:"
    for i in $(seq 1 "$count"); do
        local name
        name=$(avd_name "$i")
        if [ -d "$AVD_DIR/${name}.avd" ]; then
            echo "  $name"
        fi
    done
}

cmd_start() {
    local count="${1:?Usage: manage_avds.sh start <count>}"
    check_avd_cap "$count" || return 1

    # Resource-safety: the cap is on AVDs left *running*, not just on the requested count. If e2e AVDs
    # outside the requested range are already up, refuse when the union would exceed MAX_AVDS.
    if [ "$FORCE" -ne 1 ]; then
        local after=()
        local i s
        for i in $(seq 1 "$count"); do after+=("$(avd_serial "$i")"); done
        while read -r s; do
            [ -n "$s" ] || continue
            case " ${after[*]} " in
                *" $s "*) ;;                # requested serial already counted
                *) after+=("$s") ;;         # extra running e2e AVD
            esac
        done < <(running_e2e_serials)
        if [ "${#after[@]}" -gt "$MAX_AVDS" ]; then
            echo "ERROR: starting $count AVD(s) would leave ${#after[@]} e2e AVDs running (cap $MAX_AVDS)." >&2
            echo "  Stop the extra AVD(s) first ('$(basename "$0") stop'), or re-run with --force." >&2
            return 1
        fi
    fi

    echo "Starting $count AVD(s) with visible windows..."
    echo "  Memory budget: ~1 GB RSS per AVD (swiftshader_indirect GPU + cold boot)."
    echo "  Do NOT run the full Gradle test suite while AVDs are hot. See the internal troubleshooting notes."

    local pids=()
    local started=0
    for i in $(seq 1 "$count"); do
        local name
        name=$(avd_name "$i")
        local port
        port=$(avd_port "$i")
        local serial
        serial=$(avd_serial "$i")

        # Verify AVD exists
        if [ ! -d "$AVD_DIR/${name}.avd" ]; then
            echo "  [$i/$count] ERROR: $name does not exist. Run 'create $count' first."
            continue
        fi

        # Check if already running
        if $ADB devices 2>/dev/null | grep -q "^${serial}"; then
            echo "  [$i/$count] $serial already running, skipping"
            started=$((started + 1))
            continue
        fi

        echo "  [$i/$count] Starting $name on port $port (serial: $serial)..."
        $EMULATOR \
            -avd "$name" \
            -port "$port" \
            -no-snapshot-load \
            -no-snapshot-save \
            -no-boot-anim \
            -no-audio \
            -gpu swiftshader_indirect &
        pids+=($!)
        started=$((started + 1))
    done

    # Wait for all to boot
    local failed=0
    for i in $(seq 1 "$count"); do
        local serial
        serial=$(avd_serial "$i")
        if ! wait_for_boot "$serial" "$BOOT_TIMEOUT"; then
            failed=$((failed + 1))
        fi
    done

    echo ""
    if [ $failed -gt 0 ]; then
        echo "WARNING: $failed AVD(s) failed to boot."
        echo "Running devices:"
        $ADB devices
        return 1
    fi

    echo "All $count AVD(s) booted successfully."
    echo "Running devices:"
    $ADB devices | grep "^emulator-" || true
}

cmd_setup() {
    local count="${1:?Usage: manage_avds.sh setup <count>}"
    check_avd_cap "$count" || return 1

    # Find APK
    local apk_path="$REPO_ROOT/app-android/build/outputs/apk/debug/app-android-debug.apk"
    if [ ! -f "$apk_path" ]; then
        echo "ERROR: Debug APK not found at $apk_path"
        echo "Build it first: cd $REPO_ROOT && ./gradlew :app-android:assembleDebug"
        return 1
    fi

    echo "Setting up $count AVD(s) in parallel..."
    echo "  APK: $apk_path"

    local pids=()
    for i in $(seq 1 "$count"); do
        local serial
        serial=$(avd_serial "$i")

        (
            echo "  [$i/$count] Setting up $serial..."

            # Configure device settings (animations, input, screen stability)
            "$SCRIPT_DIR/configure_avd.sh" "$serial"

            # Install APK
            echo "    Installing APK..."
            $ADB -s "$serial" install -r -g "$apk_path" 2>&1 | sed 's/^/    /'

            # Push test repos
            echo "    Pushing test repositories..."
            $ADB -s "$serial" shell "rm -rf /sdcard/testrepo /sdcard/v1repo /sdcard/Download/restore_dest /sdcard/Download/_kopia_restore /sdcard/KopiaTestRepo /sdcard/KopiaNegativeTestRepo" 2>/dev/null || true
            $ADB -s "$serial" push "$REPO_ROOT/core/src/test/resources/fixtures/edge_case_repos/edge_case_repo" /sdcard/testrepo 2>&1 | sed 's/^/    /'
            $ADB -s "$serial" push "$REPO_ROOT/core/src/test/resources/fixtures/edge_case_repos/v1_test_repo" /sdcard/v1repo 2>&1 | sed 's/^/    /'
            $ADB -s "$serial" shell "mkdir -p /sdcard/Download/restore_dest /sdcard/Download/_kopia_restore"

            # Grant permissions
            echo "    Granting permissions..."
            $ADB -s "$serial" shell appops set org.kopiaKt.app MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

            echo "  [$i/$count] $serial setup complete"
        ) &
        pids+=($!)
    done

    # Wait for all parallel setups
    local failed=0
    for pid in "${pids[@]}"; do
        if ! wait "$pid"; then
            failed=$((failed + 1))
        fi
    done

    echo ""
    if [ $failed -gt 0 ]; then
        echo "WARNING: $failed device(s) had setup errors."
        return 1
    fi
    echo "All $count AVD(s) set up successfully."
}

cmd_list() {
    local serials
    serials=$(running_e2e_serials)

    if [ -z "$serials" ]; then
        echo "No running e2e AVDs found."
        return 0
    fi

    echo "Running e2e AVDs:"
    echo "$serials" | while read -r serial; do
        local avd_name_on_device
        avd_name_on_device=$($ADB -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)
        echo "  $serial ($avd_name_on_device)"
    done
}

cmd_stop() {
    echo "Stopping all e2e AVDs..."

    local serials
    serials=$(running_e2e_serials)

    if [ -z "$serials" ]; then
        echo "  No running e2e AVDs found."
        return 0
    fi

    echo "$serials" | while read -r serial; do
        echo "  Stopping $serial..."
        $ADB -s "$serial" emu kill 2>/dev/null || true
    done

    # Wait a moment for emulators to shut down
    sleep 3
    echo "Done."
}

cmd_delete() {
    # Stop first if running
    cmd_stop 2>/dev/null || true

    echo "Deleting all e2e AVDs..."

    local found=0
    for avd_path in "$AVD_DIR/${AVD_PREFIX}_"*.avd; do
        [ -d "$avd_path" ] || continue
        local name
        name=$(basename "$avd_path" .avd)
        echo "  Deleting $name..."
        rm -rf "$avd_path"
        rm -f "$AVD_DIR/${name}.ini"
        found=$((found + 1))
    done

    if [ $found -eq 0 ]; then
        echo "  No e2e AVDs found to delete."
    else
        echo "Deleted $found AVD(s)."
    fi
}

cmd_help() {
    echo "Dynamic AVD management for parallel Maestro E2E tests."
    echo ""
    echo "Usage: $(basename "$0") <command> [args]"
    echo ""
    echo "Commands:"
    echo "  create <count>   Create N AVDs (${AVD_PREFIX}_1, ${AVD_PREFIX}_2, ...)"
    echo "  start <count>    Start N AVDs with visible windows (ports ${BASE_PORT}, $((BASE_PORT+2)), ...)"
    echo "  setup <count>    Install APK + push test data to N running AVDs"
    echo "  list             List running e2e AVDs with serials"
    echo "  stop             Stop all running e2e AVDs"
    echo "  delete           Delete all e2e AVDs (stops first if running)"
    echo "  help             Show this help message"
    echo ""
    echo "Flags:"
    echo "  --force          Override the ${MAX_AVDS}-AVD hard cap on create/start/setup (accept OOM risk)."
    echo ""
    echo "HARD CAP: ${MAX_AVDS} AVDs. More than ${MAX_AVDS} + a hot Gradle build OOMs Apple-Silicon"
    echo "machines (see the internal troubleshooting notes). create/start/setup refuse >${MAX_AVDS} without --force."
    echo ""
    echo "Examples:"
    echo "  $(basename "$0") create 2          # Create 2 AVDs"
    echo "  $(basename "$0") start 2           # Start both with visible windows"
    echo "  $(basename "$0") setup 2           # Configure + install APK + push repos"
    echo "  $(basename "$0") list              # Show running e2e AVDs"
    echo ""
    echo "  # Run Maestro with 2-way sharding:"
    echo "  maestro --device \"emulator-5554,emulator-5556\" test --shard-split=2 e2e/maestro/"
    echo ""
    echo "  $(basename "$0") stop              # Stop all e2e AVDs"
    echo "  $(basename "$0") delete            # Clean up all e2e AVDs"
}

# --------------------------------------------------------------------------- #
#  Main
# --------------------------------------------------------------------------- #

command="${1:-help}"
shift || true

# Parse an optional --force flag (overrides the AVD cap); keep remaining positional args.
positional=()
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        *) positional+=("$arg") ;;
    esac
done
set -- ${positional[@]+"${positional[@]}"}

case "$command" in
    create)  cmd_create "$@" ;;
    start)   cmd_start "$@" ;;
    setup)   cmd_setup "$@" ;;
    list)    cmd_list ;;
    stop)    cmd_stop ;;
    delete)  cmd_delete ;;
    help)    cmd_help ;;
    *)
        echo "Unknown command: $command"
        echo ""
        cmd_help
        exit 1
        ;;
esac

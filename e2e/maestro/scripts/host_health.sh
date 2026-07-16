#!/usr/bin/env bash
# host_health.sh - shared host/emulator health helpers for the E2E scripts (task-19).
#
# Sourced by run_e2e.sh and manage_avds.sh. Defines hh_* functions only; no top-level side effects,
# no `set -e` (must not change the caller's shell options).
#
# Why: the Android emulator degrades under sustained E2E load (qemu RSS growth) AMPLIFIED by host
# memory over-commitment, and `memory_pressure` "free %" is misleading (counts compressed/inactive as
# available) - the real signals are swap used/free, compressor occupancy, and qemu RSS. These helpers
# expose those, gate a run before it starts, detect a wedged (ANR'd) guest, and stop an emulator
# robustly. macOS-only metrics (sysctl/vm_stat/memory_pressure); they degrade to "n/a"/0 elsewhere.

# ----- host memory metrics (macOS) ----------------------------------------- #

# Swap used / free in whole MB (parses `sysctl -n vm.swapusage`; normalizes M/G). 0 if unavailable.
hh_swap_used_mb() {
    sysctl -n vm.swapusage 2>/dev/null | awk '{
        for (i=1;i<=NF;i++) if ($i=="used") v=$(i+2)
        if (v=="") { print 0; exit }
        g=(v ~ /G/); sub(/[MG]/,"",v); printf "%d", (g ? v*1024 : v)
    }' 2>/dev/null || echo 0
}
hh_swap_free_mb() {
    # macOS swap is DYNAMIC: total==0 means no swapfile has been allocated yet (zero pressure, the OS
    # grows swap on demand), NOT exhaustion - report the unconstrained sentinel, else free=0 would
    # false-trip the HARD gate on a healthy host.
    sysctl -n vm.swapusage 2>/dev/null | awk '{
        for (i=1;i<=NF;i++) { if ($i=="total") t=$(i+2); if ($i=="free") v=$(i+2) }
        if (v=="") { print 99999; exit }
        sub(/[MG]/,"",t); if (t+0 == 0) { print 99999; exit }
        g=(v ~ /G/); sub(/[MG]/,"",v); printf "%d", (g ? v*1024 : v)
    }' 2>/dev/null || echo 99999
}

# Compressor occupancy in whole MB (vm_stat "Pages occupied by compressor" * 16384). 0 if unavailable.
hh_compressor_mb() {
    vm_stat 2>/dev/null | awk '/occupied by compressor/ {
        gsub(/\./,"",$5); printf "%d", $5*16384/1048576; found=1
    } END { if (!found) print 0 }' 2>/dev/null || echo 0
}

# System-wide free percentage (the MISLEADING metric; logged for context, not used for hard gating).
hh_free_pct() {
    memory_pressure 2>/dev/null | grep -i "free percentage" | grep -oE '[0-9]+' | tail -1 || true
}

# qemu RSS in whole MB for the emulator on the given serial's port. 0 if not found/not running.
# NOTE: matches the emulator's `-port <n>` argv (validated for this setup). If a future emulator build
# uses `-android-ports <console>,<adb>` instead, this matcher would need to also accept that form.
hh_qemu_rss_mb() {
    local serial="$1" port pid
    port="${serial##*-}"
    pid="$(pgrep -f "qemu-system.*-port ${port}" 2>/dev/null | head -1)"
    if [ -n "$pid" ]; then
        ps -o rss= -p "$pid" 2>/dev/null | awk '{printf "%d", $1/1024}' || echo 0
    else
        echo 0
    fi
}

# Count of stray maestro CLI java processes, EXCLUDING the MCP server (which is session infra).
# `grep -c` always prints a single integer to stdout; `|| true` swallows its exit-1-on-zero-matches so
# this stays one clean number under set -e / pipefail.
hh_stale_maestro_count() {
    local n
    n="$(pgrep -fl "maestro.cli" 2>/dev/null | grep -v -e " mcp" -e "AppKt mcp" | grep -c . || true)"
    echo "${n:-0}"
}

# One-line health snapshot for logging. Arg: serial (optional, for qemu RSS).
hh_snapshot() {
    local serial="${1:-}" rss="n/a"
    [ -n "$serial" ] && rss="$(hh_qemu_rss_mb "$serial")MB"
    printf 'free=%s%% swap_used=%sMB swap_free=%sMB compressor=%sMB qemu_rss=%s stale_maestro=%s' \
        "$(hh_free_pct)" "$(hh_swap_used_mb)" "$(hh_swap_free_mb)" "$(hh_compressor_mb)" \
        "$rss" "$(hh_stale_maestro_count)"
}

# ----- pre-flight gate ----------------------------------------------------- #
# Thresholds (MB / %); env-overridable so they can be tuned from real run logs. Swap-USED is a LAGGING
# signal (stale swap persists after pressure eases), so it only WARNs; the HARD gate needs genuinely no
# room left (low free% AND almost no swap headroom). Returns: 0 ok, 1 warn, 2 hard.
HH_SWAP_FREE_HARD_MB="${HH_SWAP_FREE_HARD_MB:-256}"   # swap almost exhausted -> next alloc OOMs
HH_FREE_PCT_HARD="${HH_FREE_PCT_HARD:-10}"
HH_SWAP_FREE_HARD2_MB="${HH_SWAP_FREE_HARD2_MB:-1024}" # low free% only counts as HARD with low swap too
HH_SWAP_USED_WARN_MB="${HH_SWAP_USED_WARN_MB:-4096}"
HH_SWAP_FREE_WARN_MB="${HH_SWAP_FREE_WARN_MB:-2048}"
HH_COMPRESSOR_WARN_MB="${HH_COMPRESSOR_WARN_MB:-8192}"

hh_preflight() {
    local serial="${1:-}" free swap_used swap_free comp stale hard=0 warn=0
    free="$(hh_free_pct)"; swap_used="$(hh_swap_used_mb)"; swap_free="$(hh_swap_free_mb)"
    comp="$(hh_compressor_mb)"; stale="$(hh_stale_maestro_count)"
    echo "[health] $(hh_snapshot "$serial")"

    # HARD keys on swap HEADROOM (not the misleading free%): swap almost exhausted is genuinely
    # dangerous (the next big allocation OOMs the guest), independent of how much "free" RAM is reported.
    # A low-free + low-swap combination is also HARD; high swap with ample headroom is only a WARN.
    if [ "${swap_free:-99999}" -lt "$HH_SWAP_FREE_HARD_MB" ] || \
       { [ "${free:-100}" -lt "$HH_FREE_PCT_HARD" ] && [ "${swap_free:-99999}" -lt "$HH_SWAP_FREE_HARD2_MB" ]; }; then
        echo "[health] HARD: swap nearly exhausted (${swap_free}MB free) / low memory (${free}% free) - the emulator will likely OOM/ANR. Free host RAM first."
        hard=1
    fi
    if [ "${swap_used:-0}" -gt "$HH_SWAP_USED_WARN_MB" ]; then
        echo "[health] WARN: swap used ${swap_used}MB (>${HH_SWAP_USED_WARN_MB}MB) - host has been under memory pressure; the AVD may degrade mid-run. Quit OrbStack/browsers/spare helper processes, and/or restart the AVD periodically."
        warn=1
    fi
    if [ "${swap_free:-99999}" -lt "$HH_SWAP_FREE_WARN_MB" ]; then
        echo "[health] WARN: only ${swap_free}MB swap free (<${HH_SWAP_FREE_WARN_MB}MB headroom)."
        warn=1
    fi
    if [ "${comp:-0}" -gt "$HH_COMPRESSOR_WARN_MB" ]; then
        echo "[health] WARN: compressor ${comp}MB occupied (>${HH_COMPRESSOR_WARN_MB}MB)."
        warn=1
    fi
    if [ "${stale:-0}" -gt 0 ]; then
        echo "[health] WARN: ${stale} stale maestro.cli process(es) holding RAM/port 7001 - clear with: pkill -f maestro.cli (the MCP server is excluded from this count)."
        warn=1
    fi
    [ "$hard" -eq 1 ] && return 2
    [ "$warn" -eq 1 ] && return 1
    return 0
}

# ----- ANR / wedged-guest detection (multi-signal) ------------------------- #
# Returns 0 if the guest looks wedged (ANR), 1 otherwise. The PRIMARY per-flow signal is the runner's
# grep of THIS attempt's maestro log for "isn't responding"; this is the secondary live probe. EVERY adb
# call is bounded by $TIMEOUT_BIN because a wedged guest can hang logcat/dumpsys too - if no timeout
# binary is available we cannot probe safely, so we return 1 and let the caller's log-grep decide.
hh_detect_anr() {
    local serial="$1"
    [ -n "${TIMEOUT_BIN:-}" ] || return 1
    # 1. Recent, specific ANR markers (tight filter + small window to avoid stale/unrelated noise).
    "$TIMEOUT_BIN" 8 adb -s "$serial" logcat -d -b events -b system -t 200 2>/dev/null \
        | grep -qE 'am_anr|ANR in' && return 0
    # 2. A process explicitly reported not-responding right now.
    "$TIMEOUT_BIN" 8 adb -s "$serial" shell dumpsys activity processes 2>/dev/null \
        | grep -qi "not responding" && return 0
    # 3. Live responsiveness probe: wake, then a cheap activity query must answer within the timeout.
    # Both adb calls are time-boxed (TIMEOUT_BIN is guaranteed set here - see the early return above).
    "$TIMEOUT_BIN" 6 adb -s "$serial" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    "$TIMEOUT_BIN" 6 adb -s "$serial" shell cmd activity top >/dev/null 2>&1 || return 0
    return 1
}

# ----- robust stop --------------------------------------------------------- #
# Kill the emulator on $1 and WAIT until it leaves `adb devices` AND its qemu PID exits (bounded by
# $2 seconds, default 40). SIGKILLs a lingering qemu as a last resort. Returns 0 on clean exit, 1 if it
# could not be confirmed gone. Avoids the classic race where a cold boot starts against a dying qemu.
hh_robust_stop() {
    local serial="$1" timeout="${2:-40}" port elapsed=0 online qpid
    port="${serial##*-}"
    adb -s "$serial" emu kill >/dev/null 2>&1 || true
    while [ "$elapsed" -lt "$timeout" ]; do
        online="$(adb devices 2>/dev/null | awk -v s="$serial" '$1==s && $2=="device"{print "y"}')"
        qpid="$(pgrep -f "qemu-system.*-port ${port}" 2>/dev/null | head -1)"
        [ -z "$online" ] && [ -z "$qpid" ] && return 0
        sleep 1; elapsed=$((elapsed + 1))
    done
    qpid="$(pgrep -f "qemu-system.*-port ${port}" 2>/dev/null | head -1)"
    [ -n "$qpid" ] && kill -9 "$qpid" 2>/dev/null || true
    sleep 2
    pgrep -f "qemu-system.*-port ${port}" >/dev/null 2>&1 && return 1
    return 0
}

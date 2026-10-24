#!/usr/bin/env bash
# Reproducible on-device measurement helper for HaP.
#
# Every number this prints comes from the device itself (getprop, /proc, ps,
# dumpsys). Nothing is estimated. Run the same scenario before and after a
# change and diff the output to get a real before/after comparison.
#
# Device note (WSL2): WSL has no USB sysfs, so the Android device is only
# reachable through a Windows-side adb. Point ADB at it, e.g.
#   ADB=/mnt/c/Users/<you>/hap-tools/platform-tools/adb.exe tools/device-check.sh facts
#
# Usage:
#   tools/device-check.sh facts
#   tools/device-check.sh install <path-to.apk>
#   tools/device-check.sh control <op>      # debug builds only
#   tools/device-check.sh footprint         # RSS/CPU of every HaP process
#   tools/device-check.sh scenario          # start, wait, footprint, stop
#   tools/device-check.sh leak-check        # start/stop x3, then verify nothing remains

set -uo pipefail

ADB="${ADB:-adb}"
PKG="com.streamvault.plugin.hap"
DEBUG_ACTIVITY="$PKG/.debug.HapDebugControlActivity"

die() { echo "error: $*" >&2; exit 1; }

require_device() {
    command -v "$ADB" >/dev/null 2>&1 || die "adb not found (set ADB=/path/to/adb)"
    local state
    # Windows adb.exe emits CRLF; strip it before comparing.
    state="$("$ADB" get-state 2>/dev/null | tr -d '\r\n')"
    [[ "$state" == "device" ]] || die "no device in 'device' state (got: ${state:-none})"
}

sh_() { "$ADB" shell "$@" 2>/dev/null | tr -d '\r'; }

# Every HaP-owned process: the Java app plus the native AceServe/Python and
# kubo/IPFS children, which run as separate PIDs.
hap_pids() {
    sh_ "ps -A -o PID,NAME | grep -E 'com.streamvault.plugin.hap|aceserve|main_android|libipfs|ipfs' | grep -v grep" \
        | awk '{print $1}'
}

cmd_facts() {
    require_device
    echo "== build =="
    echo "release:      $(sh_ getprop ro.build.version.release)"
    echo "sdk:          $(sh_ getprop ro.build.version.sdk)"
    echo "model:        $(sh_ getprop ro.product.model)"
    echo "soc:          $(sh_ getprop ro.soc.model)"
    echo "abilist:      $(sh_ getprop ro.product.cpu.abilist)"
    echo "page_size:    $(sh_ getconf PAGE_SIZE)"
    echo "cores:        $(sh_ nproc)"
    echo
    echo "== memory =="
    sh_ "grep -E 'MemTotal|MemAvailable|SwapTotal' /proc/meminfo"
    echo
    echo "== storage =="
    sh_ "df -h /data | tail -1"
    echo
    echo "== installed HaP =="
    local code
    code="$(sh_ "dumpsys package $PKG | grep -m1 versionCode" | tr -d ' ')"
    echo "${code:-not installed}"
}

cmd_install() {
    require_device
    local apk="${1:-}"
    [[ -n "$apk" ]] || die "usage: $0 install <path-to.apk>"
    [[ -f "$apk" ]] || die "APK not found: $apk"
    # adb install needs a path the adb host can read. When adb is a Windows
    # binary driven from WSL, a /home path is invisible to it; stage the APK on
    # the Windows side and hand over a Windows path. The staging directory is
    # derived from where the adb binary itself lives (…/hap-tools/platform-tools/adb.exe
    # -> …/hap-tools), because the Linux $USER does not match the Windows one.
    local staged="$apk"
    case "$ADB" in
        *.exe|*.exe.*)
            local stage_dir="${HAP_STAGE_DIR:-}"
            if [[ -z "$stage_dir" ]]; then
                stage_dir="$(cd "$(dirname "$ADB")/../.." 2>/dev/null && pwd)" \
                    || die "cannot derive a Windows staging dir; set HAP_STAGE_DIR"
            fi
            mkdir -p "$stage_dir" || die "cannot create $stage_dir"
            cp -f "$apk" "$stage_dir/" || die "cannot stage APK into $stage_dir"
            staged="$(wslpath -w "$stage_dir/$(basename "$apk")")" || die "cannot convert path"
            ;;
    esac
    "$ADB" install -r -t "$staged" 2>&1 | tail -3
}

cmd_control() {
    require_device
    local op="${1:-}"
    [[ -n "$op" ]] || die "usage: $0 control <start|stop|restart|status|ipfs_on|ipfs_off|server_mode_on|server_mode_off|wait_ready>"
    "$ADB" logcat -c 2>/dev/null
    "$ADB" shell am start -W -n "$DEBUG_ACTIVITY" --es op "$op" >/dev/null 2>&1 \
        || die "debug control activity unavailable (is this a debug build?)"
    sleep 1
    "$ADB" logcat -d -s HapDebugControl 2>/dev/null | tr -d '\r' | grep "op=" | tail -1
}

cmd_footprint() {
    require_device
    local pids
    pids="$(hap_pids)"
    if [[ -z "$pids" ]]; then
        echo "no HaP processes running"
        return
    fi
    echo "== HaP processes (PID RSS_KB NAME) =="
    sh_ "ps -A -o PID,RSS,NAME" | awk 'NR==1 || /com.streamvault.plugin.hap|aceserve|main_android|libipfs|ipfs/'
    echo
    echo "== thread count per PID =="
    for pid in $pids; do
        local threads
        threads="$(sh_ "ls /proc/$pid/task | wc -l")"
        echo "pid $pid: ${threads:-?} threads"
    done
    echo
    echo "== total RSS (KB) =="
    local total=0
    for pid in $pids; do
        local rss
        rss="$(sh_ "awk '/VmRSS/{print \$2}' /proc/$pid/status")"
        [[ -n "$rss" ]] && total=$((total + rss))
    done
    echo "$total"
    echo
    echo "== TCP sockets owned by HaP processes =="
    for pid in $pids; do
        local n
        n="$(sh_ "ls -l /proc/$pid/fd 2>/dev/null | grep -c socket")"
        echo "pid $pid: ${n:-0} sockets"
    done
}

cmd_scenario() {
    require_device
    echo "### cold state"
    cmd_footprint
    echo
    echo "### start"
    local t0 t1
    t0="$(date +%s%3N)"
    cmd_control start >/dev/null
    cmd_control wait_ready
    t1="$(date +%s%3N)"
    echo "start->ready wall clock: $((t1 - t0)) ms (includes adb round trips)"
    echo
    echo "### steady state"
    cmd_footprint
    echo
    echo "### stop"
    cmd_control stop >/dev/null
    sleep 3
    cmd_footprint
}

cmd_leak_check() {
    require_device
    echo "### start/stop x3"
    for i in 1 2 3; do
        cmd_control start >/dev/null
        sleep 2
        cmd_control stop >/dev/null
        sleep 3
        echo "cycle $i residual processes:"
        local pids
        pids="$(hap_pids)"
        if [[ -z "$pids" ]]; then
            echo "  none"
        else
            sh_ "ps -A -o PID,RSS,NAME" | awk '/com.streamvault.plugin.hap|aceserve|main_android|libipfs|ipfs/' | sed 's/^/  /'
        fi
    done
    echo
    echo "### final state"
    cmd_footprint
}

case "${1:-}" in
    facts)      cmd_facts ;;
    install)    shift; cmd_install "$@" ;;
    control)    shift; cmd_control "$@" ;;
    footprint)  cmd_footprint ;;
    scenario)   cmd_scenario ;;
    leak-check) cmd_leak_check ;;
    *)
        sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
        exit 1
        ;;
esac

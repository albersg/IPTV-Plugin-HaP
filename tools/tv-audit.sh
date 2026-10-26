#!/usr/bin/env bash
# Walk every section of HaP with the D-pad and audit what a screen reader can name.
#
# Every section is entered from a cold start, inside one `adb shell` invocation: sending one
# `adb shell` per key event drops presses, which is how a walk ends up auditing a section it never
# opened. For each D-pad position the visible hierarchy is dumped (with retries, because
# `uiautomator dump` fails while the UI is animating and leaves the previous file in place) and
# checked with tools/a11y-audit.py, so a control is audited when it becomes visible, not only when a
# panel happens to be at the top.
#
# Usage: ADB=adb SERIAL=... tools/tv-audit.sh [serial]
#        STEPS=12 (default 8) to walk further down each section.
# Exits 1 when any focusable node has no name, so it can gate a release walk.

set -u

ADB=${ADB:-adb}
SERIAL=${1:-${ADB_SERIAL:-}}
OUT=${OUT:-/tmp/hap-tv-audit}
STEPS=${STEPS:-8}
PKG=com.streamvault.plugin.hap
ROOT=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$OUT"

shell() { $ADB ${SERIAL:+-s "$SERIAL"} shell "$@"; }

dump() { # dump <file>: retry until the file belongs to HaP
    local target=$1 attempt
    for attempt in 1 2 3 4 5; do
        shell "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
        $ADB ${SERIAL:+-s "$SERIAL"} exec-out cat /sdcard/ui.xml >"$target" 2>/dev/null
        if grep -q "package=\"$PKG\"" "$target"; then return 0; fi
        sleep 1.2
    done
    return 1
}

sections=(Inicio Canales Eventos Fuentes Conexiones Ajustes)
status=0
for index in "${!sections[@]}"; do
    name=${sections[$index]}
    shell am force-stop "$PKG" >/dev/null
    sleep 1
    shell am start -a "$PKG.CONFIGURE" -p "$PKG" >/dev/null
    sleep 6

    keys=""
    for _ in $(seq 1 "$index"); do keys+="input keyevent 20; sleep 0.5; "; done
    # ENTER selects the rail entry and leaves the focus on the rail by design; RIGHT moves into the
    # section's first control, which is what the walk has to audit.
    shell "${keys}input keyevent 23; sleep 1; input keyevent 22" >/dev/null
    sleep 2.5

    echo "== $name =="
    for step in $(seq 1 "$STEPS"); do
        if ! dump "$OUT/$name-$step.xml"; then
            echo "  step $step: no dump"
            status=1
            break
        fi
        if ! python3 "$ROOT/tools/a11y-audit.py" "$OUT/$name-$step.xml" | tail -1 | grep -q " 0 without a name"; then
            echo "  step $step:"
            python3 "$ROOT/tools/a11y-audit.py" "$OUT/$name-$step.xml" | sed 's/^/    /'
            status=1
        fi
        if [ "$step" = 1 ]; then
            $ADB ${SERIAL:+-s "$SERIAL"} exec-out screencap -p >"$OUT/$name.png" 2>/dev/null
        fi
        shell "input keyevent 20" >/dev/null
        sleep 1.2
    done
done

echo "audit files in $OUT"
exit $status

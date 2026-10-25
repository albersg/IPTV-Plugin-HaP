#!/usr/bin/env bash
# Runs HaP on a local Android emulator, with no TV box involved.
#
# Geometry is the box's: 1920x1080 at 320dpi, which is 960x540dp and therefore selects the
# w600dp television layouts. What you see here is the TV design, not the phone one.
#
# The APK must be the arm64 build. Measured: an android-34 google_apis x86_64 image refuses
# armeabi-v7a with INSTALL_FAILED_NO_MATCHING_ABIS, but accepts arm64-v8a through its ARM
# translation layer. `armv7` simply does not install.
#
# Usage:
#   tools/emulator.sh              # boot if needed, install the arm64 build, open HaP, screenshot
#   tools/emulator.sh --screenshot # only take a screenshot of whatever is on screen
#   tools/emulator.sh --stop       # shut the emulator down
#
# Requires the packages listed in docs/Emulator.md and a writable XDG_RUNTIME_DIR (see there).
set -euo pipefail

AVD="${HAP_AVD:-haptv}"
IMAGE="${HAP_SYSTEM_IMAGE:-system-images;android-34;google_apis;x86_64}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${HAP_APK:-$ROOT/app/build/outputs/apk/arm64/release/app-arm64-release-unsigned.apk}"
SHOTS="${HAP_SHOTS:-/tmp/hap-shots}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
# The emulator writes its run directory under XDG_RUNTIME_DIR. If a previous run created it as
# root, startup dies with "Failed to create jwk directory ... Permission denied", which looks
# like a Qt problem and is not.
export XDG_RUNTIME_DIR="${HAP_XDG_RUNTIME_DIR:-/tmp/xdg-$USER}"
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"

ADB="$ANDROID_HOME/platform-tools/adb"
mkdir -p "$SHOTS"

screenshot() {
    # exec-out, not `adb pull`: pull with a Windows path from WSL silently writes nothing.
    local target="$SHOTS/hap-$(date +%H%M%S).png"
    "$ADB" exec-out screencap -p > "$target"
    echo "screenshot: $target"
}

case "${1:-}" in
    --stop) "$ADB" emu kill || true; exit 0 ;;
    --screenshot) screenshot; exit 0 ;;
esac

if ! "$ADB" devices | grep -q "emulator-.*device"; then
    if ! "$ANDROID_HOME/emulator/emulator" -list-avds | grep -qx "$AVD"; then
        echo "creating AVD $AVD"
        echo "no" | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
            -n "$AVD" -k "$IMAGE" -d tv_1080p --force
        printf 'hw.lcd.width=1920\nhw.lcd.height=1080\nhw.lcd.density=320\nhw.keyboard=yes\n' \
            >> "$HOME/.android/avd/$AVD.avd/config.inargv"
        printf 'hw.lcd.width=1920\nhw.lcd.height=1080\nhw.lcd.density=320\nhw.keyboard=yes\n' \
            >> "$HOME/.android/avd/$AVD.avd/config.ini"
        rm -f "$HOME/.android/avd/$AVD.avd/config.inargv"
    fi
    echo "booting $AVD"
    # -no-window: the Qt xcb plugin cannot start under WSLg even with every library present, and
    # the screen is read through screencap anyway.
    nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window \
        -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim > /tmp/hap-emulator.log 2>&1 &
    "$ADB" wait-for-device
    for _ in $(seq 1 60); do
        [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
        sleep 5
    done
fi

[ -f "$APK" ] || { echo "missing $APK - run: ./gradlew :app:assembleArm64Release" >&2; exit 1; }
"$ADB" install -r "$APK"
"$ADB" shell am start -n com.streamvault.plugin.hap/.HapConfigActivity
sleep 3
screenshot

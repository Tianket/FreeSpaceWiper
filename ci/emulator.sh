#!/usr/bin/env bash
# Creates and boots a headless Android emulator on a CI runner.
# Usage: ci/emulator.sh <api level>
set -euo pipefail
API=$1
TOOLS=$(ls -d "$ANDROID_HOME"/cmdline-tools/*/bin | tail -1)
IMAGE="system-images;android-${API};default;x86_64"

export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"

yes | "$TOOLS/sdkmanager" --licenses > /dev/null 2>&1 || true
"$TOOLS/sdkmanager" --install "$IMAGE" "emulator" "platform-tools" > /dev/null
echo no | "$TOOLS/avdmanager" create avd --force -n test -k "$IMAGE" --device pixel_5 > /dev/null

CONFIG="$ANDROID_AVD_HOME/test.avd/config.ini"
ls "$ANDROID_AVD_HOME"
{
    echo "disk.dataPartition.size=4G"
    echo "hw.ramSize=3072"
    echo "hw.cpu.ncore=4"
    echo "hw.keyboard=yes"
} >> "$CONFIG"

nohup "$ANDROID_HOME/emulator/emulator" -avd test -no-window -no-audio -no-boot-anim -no-snapshot \
    -cores 4 -gpu swiftshader_indirect -camera-back none -camera-front none > emulator.log 2>&1 &

ADB="$ANDROID_HOME/platform-tools/adb"
"$ADB" wait-for-device
for _ in $(seq 1 150); do
    if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
        break
    fi
    sleep 2
done
[ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || { echo "emulator did not boot"; tail -50 emulator.log; exit 1; }
"$ADB" shell input keyevent 82 || true
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" shell settings put global "$scale" 0
done
echo "emulator booted: API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
